package io.github.axon07.channelbox.data

import android.database.sqlite.SQLiteFullException
import android.util.Xml
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException

sealed interface RefreshState {
    data object Idle : RefreshState
    /** [background]: channels are ready and only the guide is still loading – the app can be used. */
    data class Running(val message: String, val background: Boolean = false) : RefreshState
    data class Failed(val message: String) : RefreshState
    data class Done(val channels: Int, val programmes: Int) : RefreshState
}

class TvRepository(
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val http: OkHttpClient,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<RefreshState>(RefreshState.Idle)
    val state: StateFlow<RefreshState> = _state.asStateFlow()

    /** Result of the last load of each playlist: "" = loaded, otherwise the error. */
    private val _playlistErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val playlistErrors: StateFlow<Map<String, String>> = _playlistErrors.asStateFlow()

    val channels: Flow<List<ChannelEntity>> = db.channels().observeAll()
    val favorites: Flow<Set<String>> = db.channels().observeFavorites().map { it.toSet() }

    fun programs(from: Long, to: Long): Flow<List<ProgramEntity>> = db.programs().observeWindow(from, to)

    suspend fun allChannels(): List<ChannelEntity> = db.channels().getAll()

    suspend fun searchPrograms(query: String, from: Long): List<ProgramEntity> = db.programs().search(query, from)

    // ---- Your changes to channels, groups and categories ----------------------------------------

    val overrides: Flow<List<OverrideEntity>> = db.overrides().observeAll()

    suspend fun putOverrides(items: List<OverrideEntity>) {
        val (empty, keep) = items.partition { it.isEmpty }
        if (keep.isNotEmpty()) db.overrides().put(keep)
        empty.forEach { db.overrides().delete(it.id) }
    }

    suspend fun resetEdits() {
        db.overrides().resetEdits()
        db.overrides().deleteEmpty()
    }

    // ---- Movies & TV shows --------------------------------------------------------------------

    val vod: VodDao get() = db.vod()

    /** Goes up when the movies & shows or watch positions change; the library re-reads its lists then. */
    private val _vodVersion = MutableStateFlow(0)
    val vodVersion: StateFlow<Int> = _vodVersion.asStateFlow()
    fun vodChanged() = _vodVersion.update { it + 1 }

    suspend fun saveProgress(p: VodProgressEntity) {
        db.vod().saveProgress(p)
        vodChanged()
    }

    suspend fun clearProgress(id: String) {
        db.vod().clearProgress(id)
        vodChanged()
    }

    /** Episodes of a show: from the playlist (M3U) or fetched from the provider (Xtream). */
    suspend fun episodes(show: VodEntity): List<VodEntity> {
        val key = show.seriesKey ?: return emptyList()
        if (show.id.startsWith("xseries:")) {
            val p = settings.current().playlists.firstOrNull { it.id == show.playlistId } ?: return emptyList()
            return XtreamClient(http).episodes(XtreamClient.Login(p.xtServer, p.xtUser, p.xtPass), p.id, key)
        }
        return db.vod().episodes(key)
    }

    suspend fun setFavorite(channelId: String, favorite: Boolean) {
        if (favorite) db.channels().addFavorite(FavoriteEntity(channelId))
        else db.channels().removeFavorite(channelId)
    }

    /**
     * Deletes everything a removed playlist stored on the device (channels, movies & shows, watch
     * positions) and gives the space back. Favourites and recordings are kept.
     */
    suspend fun forgetPlaylist(playlistId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            db.withTransaction {
                db.channels().deleteForPlaylist(playlistId)
                db.vod().deleteProgressForPlaylist(playlistId)
                db.vod().deleteForPlaylist(playlistId)
            }
            _playlistErrors.value = _playlistErrors.value - playlistId
            vodChanged()
            compact(force = true)
        }
    }

    /**
     * Deletes every saved channel, movie & show and guide entry so the next reload starts from
     * scratch. Favourites, recordings, watch positions and your own names/numbers are kept.
     */
    suspend fun clearSavedData() = withContext(Dispatchers.IO) {
        mutex.withLock {
            db.withTransaction {
                db.channels().clear()
                db.programs().clear()
                db.vod().deleteOtherPlaylists(emptyList())
            }
            _playlistErrors.value = emptyMap()
            settings.setLastRefresh(0L)
            vodChanged()
            compact(force = true)
        }
    }

    /**
     * SQLite keeps the file at its biggest size after rows are deleted, so a huge playlist that was
     * replaced or removed would keep holding the space. This rewrites the file to its real size when
     * there's a lot to gain (or always, with [force]). Needs free space while it runs; skipped if short.
     */
    private fun compact(force: Boolean = false) {
        try {
            val sql = db.openHelper.writableDatabase
            fun pragma(name: String): Long = sql.query("PRAGMA $name").use { if (it.moveToFirst()) it.getLong(0) else 0L }
            val free = pragma("freelist_count") * pragma("page_size")
            if (free < COMPACT_MIN_BYTES && !(force && free > 0)) return
            _state.value = RefreshState.Running("Freeing up storage…")
            sql.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            sql.execSQL("VACUUM")
            sql.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Busy or not enough room to rewrite – the space is reused by the next load anyway.
        } finally {
            if (_state.value is RefreshState.Running) _state.value = RefreshState.Idle
        }
    }

    /** Reloads every enabled playlist and the TV guide. Returns true when at least one playlist loaded. */
    /**
     * Reloads every playlist, then the guide. [onChannels] runs as soon as the channels (and movies &
     * shows) are saved, so the app can go to the guide while the TV guide keeps loading behind it.
     */
    suspend fun refresh(onChannels: suspend () -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                refreshLocked(onChannels)
            } finally {
                vodChanged()
            }
        }
    }

    // Runs on an IO thread: Room's blocking calls (movies & shows are written while streaming) aren't
    // allowed on the main thread.
    private suspend fun refreshLocked(onChannels: suspend () -> Unit): Boolean {
        return try {
            val s = settings.current()
            val sources = s.playlists.filter { it.enabled }
            if (sources.isEmpty()) {
                db.channels().clear()
                db.programs().clear()
                db.vod().deleteOtherPlaylists(emptyList())
                _state.value = RefreshState.Idle
                compact()
                return false
            }

            // Anything still stored for a playlist that's gone or off is dropped first, so it can't
            // show up under the remaining playlists, even if this load fails or is cut short.
            val keepIds = sources.map { it.id }
            db.channels().deleteOtherPlaylists(keepIds)
            db.vod().deleteOtherPlaylists(keepIds)

            val lineup = Lineup() // live channels, numbered as they arrive
            val kept = ArrayList<ChannelEntity>() // last good channels of playlists that failed this time
            val epgUrls = ArrayList<String>()
            val failures = ArrayList<String>()
            val errors = HashMap<String, String>()
            sources.forEachIndexed { index, p ->
                val label = if (sources.size > 1) "${p.displayName} (${index + 1} of ${sources.size})" else "channels"
                _state.value = RefreshState.Running("Loading $label…")
                val start = lineup.size
                try {
                    val writer = VodWriter(db, p.id)
                    var live = 0
                    var entries = 0
                    val guides: List<String>
                    if (p.type == SourceType.XTREAM) {
                        val login = XtreamClient.Login(p.xtServer, p.xtUser, p.xtPass)
                        val playlist = XtreamClient(http).load(login)
                        playlist.channels.forEach { lineup.add(p.id, it) }
                        live = playlist.channels.size
                        guides = playlist.epgUrls
                        if (p.includeVod) {
                            _state.value = RefreshState.Running("Loading movies & shows from ${p.displayName}…")
                            XtreamClient(http).loadVod(login, p.id, writer) { n ->
                                _state.value = RefreshState.Running("Loading movies & shows from ${p.displayName}… $n")
                            }
                        }
                    } else {
                        // Streamed: live channels are kept, files (movies, episodes) are saved to
                        // Movies & TV Shows as they arrive, so huge lists don't have to fit in memory.
                        val builder = VodBuilder(p.id)
                        // An Xtream link underneath? Take movies & shows from the provider's API, like a
                        // login would: real posters, plots and ratings instead of episode stills.
                        var filesFromApi = false
                        val vodFromApi: suspend (XtreamClient.Login) -> Unit = { login ->
                            _state.value = RefreshState.Running("Loading movies & shows from ${p.displayName}…")
                            XtreamClient(http).loadVod(login, p.id, writer) { n ->
                                _state.value = RefreshState.Running("Loading movies & shows from ${p.displayName}… $n")
                            }
                            filesFromApi = writer.count > 0
                        }
                        // 1) The playlist link itself is an Xtream get.php link.
                        val linkLogin = if (p.includeVod) XtreamClient.Login.fromM3uUrl(p.m3uUrl) else null
                        if (linkLogin != null) {
                            vodFromApi(linkLogin)
                            _state.value = RefreshState.Running("Loading $label…")
                        }
                        // 2) Otherwise the movie/episode links may carry the login (…/series/USER/PASS/1.mp4):
                        //    skip those entries and ask the API after the download.
                        var entryLogin: XtreamClient.Login? = null
                        var skippedFiles = 0
                        guides = http.download(p.m3uUrl) { input ->
                            M3uParser.parse(maybeGunzip(input).bufferedReader()) { ch ->
                                val kind = VodClassifier.kind(ch.url, ch.name)
                                if (kind == VodClassifier.Kind.LIVE) {
                                    lineup.add(p.id, ch)
                                    live++
                                } else if (p.includeVod && !filesFromApi) {
                                    if (linkLogin == null && entryLogin == null && skippedFiles == 0 && builder.isEmpty) {
                                        entryLogin = XtreamClient.Login.fromStreamUrl(ch.url)
                                    }
                                    if (entryLogin != null) skippedFiles++
                                    else builder.add(ch, kind).forEach(writer::add)
                                }
                                if (++entries % 5_000 == 0) _state.value = RefreshState.Running("Loading $label… $entries entries")
                            }
                        }
                        val login = entryLogin
                        if (login != null) {
                            vodFromApi(login)
                            if (!filesFromApi && skippedFiles > 0) {
                                // The API gave nothing: read the playlist's own movie & show entries after all.
                                _state.value = RefreshState.Running("Loading movies & shows from ${p.displayName}…")
                                val again = VodBuilder(p.id)
                                http.download(p.m3uUrl) { input ->
                                    M3uParser.parse(maybeGunzip(input).bufferedReader()) { ch ->
                                        val kind = VodClassifier.kind(ch.url, ch.name)
                                        if (kind != VodClassifier.Kind.LIVE) again.add(ch, kind).forEach(writer::add)
                                    }
                                }
                            }
                        }
                    }
                    writer.finish()
                    if (live == 0 && writer.count == 0) {
                        throw IOException(
                            if (entries > 0 && !p.includeVod) "only movies & shows – turn on \"Include movies & shows\""
                            else "no channels in the playlist"
                        )
                    }
                    // Your own guide URLs first, then every guide the playlist lists.
                    epgUrls += splitUrls(p.epgUrl) + guides
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Also catches running out of memory, so one huge playlist can't crash the app.
                    lineup.rollback(start)
                    val why = when (e) {
                        is OutOfMemoryError -> "too big for this device's memory"
                        is SQLiteFullException -> {
                            // Don't leave a half-saved list filling the device.
                            runCatching { db.vod().deleteForPlaylist(p.id) }
                            "not enough storage on this device – turn off \"Include movies & shows\" for this playlist"
                        }
                        else -> e.message ?: e.javaClass.simpleName
                    }
                    failures += "${p.displayName}: $why"
                    errors[p.id] = why
                    kept += db.channels().forPlaylist(p.id).map { it.copy(playlistId = p.id) }
                    epgUrls += splitUrls(p.epgUrl)
                }
            }
            _playlistErrors.value = errors
            if (failures.size == sources.size && kept.isEmpty()) throw IOException(failures.joinToString("\n"))

            val entities = lineup.finish(kept)
            db.channels().replaceAll(entities)

            // Movies & shows of playlists that were removed or turned off.
            db.vod().deleteOtherPlaylists(sources.map { it.id })
            // Channels and movies & shows are in: usable now, even if the guide takes a while (or the
            // app is closed before it finishes – it won't redo everything on the next start).
            settings.setLastRefresh(System.currentTimeMillis())
            vodChanged()
            runCatching { onChannels() }

            var programmes = 0
            val guides = epgUrls.distinct()
            if (guides.isNotEmpty()) {
                programmes = importEpg(guides, entities, lineup.tvgNames)
            }
            settings.setLastRefresh(System.currentTimeMillis())
            compact() // only does work when a much smaller list replaced a big one
            _state.value = if (failures.isEmpty()) RefreshState.Done(entities.size, programmes)
            else RefreshState.Failed(
                "Couldn't load " + failures.joinToString("; ") +
                    if (kept.isNotEmpty()) " – showing its channels from last time" else ""
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _state.value = RefreshState.Failed(
                if (e is OutOfMemoryError) "Ran out of memory – try fewer days of TV guide" else e.message ?: e.javaClass.simpleName
            )
            false
        }
    }

    /**
     * Merges the playlists' live channels in order, converting each as it arrives so a big list is
     * held only once. A channel keeps its own number when it's free; otherwise it gets the next free
     * one, so a second playlist continues after the first. The same link is only listed once.
     */
    private class Lineup {
        private val channels = ArrayList<ChannelEntity>()
        private val used = HashSet<Int>()
        private val seen = HashSet<String>()
        private var next = 1
        /** tvg-name where it differs from the shown name (helps guide matching). */
        val tvgNames = HashMap<String, String>()
        val size get() = channels.size

        private fun number(wanted: Int?): Int = wanted?.takeIf { used.add(it) } ?: run {
            while (next in used) next++
            used.add(next)
            next
        }

        fun add(playlistId: String, p: ParsedChannel) {
            if (!seen.add(p.url)) return
            channels += ChannelEntity(
                id = p.url,
                number = number(p.number),
                name = p.name,
                groupTitle = p.group,
                logoUrl = p.logo,
                streamUrl = p.url,
                epgId = p.epgId?.lowercase(),
                catchupDays = p.catchupDays,
                headers = StreamSpecs.encodeHeaders(p.headers),
                drmType = p.drmType,
                drmKey = p.drmKey,
                mimeHint = p.manifestType,
                catchupType = p.catchupType,
                catchupSource = p.catchupSource,
                playlistId = playlistId,
            )
            p.tvgName?.takeIf { it != p.name }?.let { tvgNames[p.url] = it }
        }

        /** Drops what a playlist added before it failed. */
        fun rollback(toSize: Int) {
            while (channels.size > toSize) {
                val c = channels.removeAt(channels.size - 1)
                seen.remove(c.id)
                used.remove(c.number)
                tvgNames.remove(c.id)
            }
            next = 1
        }

        /** Adds the last good channels of playlists that failed this time. */
        fun finish(kept: List<ChannelEntity>): List<ChannelEntity> {
            kept.forEach { k -> if (seen.add(k.id)) channels += k.copy(number = number(k.number)) }
            return channels
        }
    }

    /** Loads every guide source; a source that fails is skipped. Returns the number of programmes stored. */
    /** Highest programme row from before the current guide load; older rows are the old guide. */
    private var oldProgramsUpTo = 0L
    /** Guide ids whose old programmes were already swapped for new ones in this load. */
    private val replaced = HashSet<String>()

    private suspend fun importEpg(
        urls: List<String>,
        channels: List<ChannelEntity>,
        tvgNames: Map<String, String>,
    ): Int {
        val matcher = EpgMatcher(
            channels.map { ch -> EpgMatcher.Candidate(ch.id, ch.epgId, listOfNotNull(tvgNames[ch.id], ch.name)) }
        )
        val now = System.currentTimeMillis()
        val keepFrom = now - 6 * HOUR
        val keepTo = now + settings.current().guideDays.coerceIn(1, 14) * 24 * HOUR
        val filled = HashSet<String>() // guide ids that already have programmes from an earlier source

        // The old guide stays on screen while the new one loads: each channel's old programmes are
        // swapped out when its new ones arrive, and leftovers are removed at the end.
        oldProgramsUpTo = db.openHelper.readableDatabase.query("SELECT IFNULL(MAX(rowId), 0) FROM programs")
            .use { if (it.moveToFirst()) it.getLong(0) else 0L }
        replaced.clear()
        var count = 0
        urls.forEachIndexed { index, url ->
            _state.value = RefreshState.Running(background = true, message = 
                if (urls.size > 1) "Loading TV guide ${index + 1} of ${urls.size}…" else "Loading TV guide…"
            )
            try {
                count += importOne(url, matcher, filled, keepFrom, keepTo)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // One broken guide shouldn't stop the others.
            }
        }

        // Something loaded: drop what's left of the old guide. Nothing loaded: keep the old one.
        if (count > 0) {
            db.openHelper.writableDatabase.execSQL("DELETE FROM programs WHERE rowId <= ?", arrayOf(oldProgramsUpTo))
        }
        val changed = channels.filter { ch -> matcher.assigned[ch.id]?.let { it != ch.epgId } == true }
        if (changed.isNotEmpty()) {
            db.withTransaction { changed.forEach { db.channels().setEpgId(it.id, matcher.assigned.getValue(it.id)) } }
        }
        return count
    }

    private suspend fun importOne(
        url: String,
        matcher: EpgMatcher,
        filled: MutableSet<String>,
        keepFrom: Long,
        keepTo: Long,
    ): Int {
        var count = 0
        val fromThisSource = HashSet<String>()
        val batch = ArrayList<ProgramEntity>(BATCH)

        fun flush() {
            if (batch.isEmpty()) return
            db.runInTransaction {
                val sql = db.openHelper.writableDatabase
                batch.asSequence().map { it.epgId }.distinct().filter { replaced.add(it) }.forEach { id ->
                    sql.execSQL("DELETE FROM programs WHERE epgId = ? AND rowId <= ?", arrayOf(id, oldProgramsUpTo))
                }
                db.programs().insertBlocking(batch)
            }
            count += batch.size
            batch.clear()
        }

        http.download(url) { input ->
            val parser = Xml.newPullParser()
            parser.setInput(maybeGunzip(input), null)
            XmlTvParser(parser).parse(
                onChannel = { id, names -> matcher.onChannel(id, names) },
                onProgramme = { channelId, start, stop, title, desc ->
                    val key = channelId.trim().lowercase()
                    if (stop > keepFrom && start < keepTo && key !in filled && matcher.wants(key)) {
                        fromThisSource += key
                        batch += ProgramEntity(epgId = key, start = start, stop = stop, title = title, description = desc)
                        if (batch.size >= BATCH) flush()
                    }
                },
            )
            flush()
        }
        matcher.finish()
        filled += fromThisSource
        matcher.releaseEmpty(filled)
        return count
    }

    companion object {
        const val HOUR = 3_600_000L
        private const val BATCH = 2_000
        /** Free space inside the database worth rewriting the file for. */
        private const val COMPACT_MIN_BYTES = 64L * 1024 * 1024
        fun normalizeName(name: String): String = EpgMatcher.normalizeName(name)
    }
}
