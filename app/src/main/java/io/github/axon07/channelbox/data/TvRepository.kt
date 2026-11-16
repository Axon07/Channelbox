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

    /** Reloads the playlist and the TV guide. Returns true on success. */
    suspend fun refresh(): Boolean = mutex.withLock {
        try {
            val s = settings.current()
            _state.value = RefreshState.Running("Loading channels…")
            val playlist = when (s.sourceType) {
                SourceType.M3U -> http.download(s.m3uUrl) { M3uParser.parse(maybeGunzip(it).bufferedReader()) }
                SourceType.XTREAM -> XtreamClient(http).load(XtreamClient.Login(s.xtServer, s.xtUser, s.xtPass))
                SourceType.NONE -> {
                    _state.value = RefreshState.Idle
                    return@withLock false
                }
            }
            if (playlist.channels.isEmpty()) throw IOException("The playlist loaded but has no channels")

            val entities = toEntities(playlist.channels)
            db.channels().replaceAll(entities)

            var programmes = 0
            // Your own guide URLs first, then every guide the playlist lists.
            val epgUrls = (splitUrls(s.epgUrl) + playlist.epgUrls).distinct()
            if (epgUrls.isNotEmpty()) {
                val tvgNames = playlist.channels.associate { it.url to it.tvgName }
                programmes = importEpg(epgUrls, entities, tvgNames)
            }
            settings.setLastRefresh(System.currentTimeMillis())
            _state.value = RefreshState.Done(entities.size, programmes)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = RefreshState.Failed(e.message ?: e.javaClass.simpleName)
            false
        }
    }

    private fun toEntities(parsed: List<ParsedChannel>): List<ChannelEntity> {
        val used = HashSet<Int>()
        var next = 1
        return parsed.distinctBy { it.url }.map { p ->
            val number = p.number?.takeIf { used.add(it) } ?: run {
                while (next in used) next++
                used.add(next)
                next
            }
            ChannelEntity(
                id = p.url,
                number = number,
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
            )
        }
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
        return count
    }

    companion object {
        const val HOUR = 3_600_000L
        private const val BATCH = 2_000
        fun normalizeName(name: String): String = EpgMatcher.normalizeName(name)
    }
}
