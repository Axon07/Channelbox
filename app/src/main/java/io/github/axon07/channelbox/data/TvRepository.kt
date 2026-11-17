package io.github.axon07.channelbox.data

import android.util.Xml
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.IOException

sealed interface RefreshState {
    data object Idle : RefreshState
    data class Running(val message: String) : RefreshState
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

    // ---- Movies & TV shows --------------------------------------------------------------------

    val vod: VodDao get() = db.vod()

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

    /** Reloads every enabled playlist and the TV guide. Returns true when at least one playlist loaded. */
    suspend fun refresh(): Boolean = mutex.withLock {
        try {
            val s = settings.current()
            val sources = s.playlists.filter { it.enabled }
            if (sources.isEmpty()) {
                db.channels().clear()
                db.programs().clear()
                db.vod().deleteOtherPlaylists(emptyList())
                _state.value = RefreshState.Idle
                return@withLock false
            }

            val parsed = ArrayList<Pair<String, ParsedChannel>>()
            val kept = ArrayList<ChannelEntity>() // last good channels of playlists that failed this time
            val epgUrls = ArrayList<String>()
            val failures = ArrayList<String>()
            val errors = HashMap<String, String>()
            sources.forEachIndexed { index, p ->
                val label = if (sources.size > 1) "${p.displayName} (${index + 1} of ${sources.size})" else "channels"
                _state.value = RefreshState.Running("Loading $label…")
                val start = parsed.size
                try {
                    val writer = VodWriter(db, p.id)
                    var live = 0
                    var entries = 0
                    val guides: List<String>
                    if (p.type == SourceType.XTREAM) {
                        val login = XtreamClient.Login(p.xtServer, p.xtUser, p.xtPass)
                        val playlist = XtreamClient(http).load(login)
                        playlist.channels.forEach { parsed += p.id to it }
                        live = playlist.channels.size
                        guides = playlist.epgUrls
                        _state.value = RefreshState.Running("Loading movies & shows from ${p.displayName}…")
                        XtreamClient(http).loadVod(login, p.id, writer) { n ->
                            _state.value = RefreshState.Running("Loading movies & shows from ${p.displayName}… $n")
                        }
                    } else {
                        // Streamed: live channels are kept, files (movies, episodes) are saved to
                        // Movies & TV Shows as they arrive, so huge lists don't have to fit in memory.
                        val builder = VodBuilder(p.id)
                        guides = http.download(p.m3uUrl) { input ->
                            M3uParser.parse(maybeGunzip(input).bufferedReader()) { ch ->
                                val kind = VodClassifier.kind(ch.url, ch.name)
                                if (kind == VodClassifier.Kind.LIVE) {
                                    parsed += p.id to ch
                                    live++
                                } else {
                                    builder.add(ch, kind).forEach(writer::add)
                                }
                                if (++entries % 5_000 == 0) _state.value = RefreshState.Running("Loading $label… $entries entries")
                            }
                        }
                    }
                    writer.finish()
                    if (live == 0 && writer.count == 0) throw IOException("no channels in the playlist")
                    // Your own guide URLs first, then every guide the playlist lists.
                    epgUrls += splitUrls(p.epgUrl) + guides
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Also catches running out of memory, so one huge playlist can't crash the app.
                    while (parsed.size > start) parsed.removeAt(parsed.size - 1)
                    val why = if (e is OutOfMemoryError) "too big for this device's memory"
                    else e.message ?: e.javaClass.simpleName
                    failures += "${p.displayName}: $why"
                    errors[p.id] = why
                    kept += db.channels().forPlaylist(p.id).map { it.copy(playlistId = p.id) }
                    epgUrls += splitUrls(p.epgUrl)
                }
            }
            _playlistErrors.value = errors
            if (failures.size == sources.size && kept.isEmpty()) throw IOException(failures.joinToString("\n"))

            val entities = toEntities(parsed, kept)
            db.channels().replaceAll(entities)

            // Movies & shows of playlists that were removed or turned off.
            db.vod().deleteOtherPlaylists(sources.map { it.id })

            var programmes = 0
            val guides = epgUrls.distinct()
            if (guides.isNotEmpty()) {
                val tvgNames = parsed.associate { it.second.url to it.second.tvgName }
                programmes = importEpg(guides, entities, tvgNames)
            }
            settings.setLastRefresh(System.currentTimeMillis())
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
     * Merges the playlists in order. A channel keeps its own number when it's free;
     * otherwise it gets the next free one, so a second playlist continues after the first.
     */
    private fun toEntities(parsed: List<Pair<String, ParsedChannel>>, kept: List<ChannelEntity>): List<ChannelEntity> {
        val used = HashSet<Int>()
        var next = 1
        fun number(wanted: Int?): Int = wanted?.takeIf { used.add(it) } ?: run {
            while (next in used) next++
            used.add(next)
            next
        }
        val seen = HashSet<String>()
        val fresh = parsed.filter { seen.add(it.second.url) }.map { (playlistId, p) ->
            ChannelEntity(
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
        }
        val old = kept.filter { seen.add(it.id) }.map { it.copy(number = number(it.number)) }
        return fresh + old
    }

    /** Loads every guide source; a source that fails is skipped. Returns the number of programmes stored. */
    private suspend fun importEpg(
        urls: List<String>,
        channels: List<ChannelEntity>,
        tvgNames: Map<String, String?>,
    ): Int {
        val matcher = EpgMatcher(
            channels.map { ch -> EpgMatcher.Candidate(ch.id, ch.epgId, listOfNotNull(tvgNames[ch.id], ch.name)) }
        )
        val now = System.currentTimeMillis()
        val keepFrom = now - 6 * HOUR
        val keepTo = now + settings.current().guideDays.coerceIn(1, 14) * 24 * HOUR
        val filled = HashSet<String>() // guide ids that already have programmes from an earlier source

        db.programs().clear()
        var count = 0
        urls.forEachIndexed { index, url ->
            _state.value = RefreshState.Running(
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
            db.runInTransaction { db.programs().insertBlocking(batch) }
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
        fun normalizeName(name: String): String = EpgMatcher.normalizeName(name)
    }
}
