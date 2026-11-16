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

    val channels: Flow<List<ChannelEntity>> = db.channels().observeAll()
    val favorites: Flow<Set<String>> = db.channels().observeFavorites().map { it.toSet() }

    fun programs(from: Long, to: Long): Flow<List<ProgramEntity>> = db.programs().observeWindow(from, to)

    suspend fun allChannels(): List<ChannelEntity> = db.channels().getAll()

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
                _state.value = RefreshState.Idle
                return@withLock false
            }

            val parsed = ArrayList<Pair<String, ParsedChannel>>()
            val kept = ArrayList<ChannelEntity>() // last good channels of playlists that failed this time
            val epgUrls = ArrayList<String>()
            val failures = ArrayList<String>()
            sources.forEachIndexed { index, p ->
                _state.value = RefreshState.Running(
                    if (sources.size > 1) "Loading ${p.displayName} (${index + 1} of ${sources.size})…" else "Loading channels…"
                )
                try {
                    val playlist = loadPlaylist(p)
                    if (playlist.channels.isEmpty()) throw IOException("no channels in the playlist")
                    playlist.channels.forEach { parsed += p.id to it }
                    // Your own guide URLs first, then every guide the playlist lists.
                    epgUrls += splitUrls(p.epgUrl) + playlist.epgUrls
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures += "${p.displayName}: ${e.message ?: e.javaClass.simpleName}"
                    kept += db.channels().forPlaylist(p.id).map { it.copy(playlistId = p.id) }
                    epgUrls += splitUrls(p.epgUrl)
                }
            }
            if (parsed.isEmpty() && kept.isEmpty()) throw IOException(failures.joinToString("\n"))

            val entities = toEntities(parsed, kept)
            db.channels().replaceAll(entities)

            var programmes = 0
            val guides = epgUrls.distinct()
            if (guides.isNotEmpty()) {
                val tvgNames = parsed.associate { it.second.url to it.second.tvgName }
                programmes = importEpg(guides, entities, tvgNames)
            }
            settings.setLastRefresh(System.currentTimeMillis())
            _state.value = if (failures.isEmpty()) RefreshState.Done(entities.size, programmes)
            else RefreshState.Failed("Couldn't load " + failures.joinToString("; ") + " – showing its last channels")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = RefreshState.Failed(e.message ?: e.javaClass.simpleName)
            false
        }
    }

    private suspend fun loadPlaylist(p: Playlist): ParsedPlaylist = when (p.type) {
        SourceType.XTREAM -> XtreamClient(http).load(XtreamClient.Login(p.xtServer, p.xtUser, p.xtPass))
        else -> http.download(p.m3uUrl) { M3uParser.parse(maybeGunzip(it).bufferedReader()) }
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
        val keepTo = now + 8 * 24 * HOUR
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
            } catch (e: Exception) {
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
