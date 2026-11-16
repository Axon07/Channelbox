package io.github.axon07.channelbox.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import io.github.axon07.channelbox.data.StreamSpec
import io.github.axon07.channelbox.data.StreamSpecs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.io.File

sealed interface PlayerStatus {
    data object Idle : PlayerStatus
    data object Buffering : PlayerStatus
    data object Playing : PlayerStatus
    data class Error(val message: String) : PlayerStatus
}

/**
 * One ExoPlayer for the whole app.
 *
 * Live channels: every failure (bad link, unsupported format, DRM error) becomes an
 * on-screen message and an automatic retry – never a crash. When the stream type
 * isn't known it tries plain/TS, then HLS, then DASH.
 */
@OptIn(UnstableApi::class)
class TvPlayer(private val context: Context, private val http: OkHttpClient) {

    private val handler = Handler(Looper.getMainLooper())
    private val _status = MutableStateFlow<PlayerStatus>(PlayerStatus.Idle)
    val status: StateFlow<PlayerStatus> = _status.asStateFlow()

    private var spec: StreamSpec? = null
    private var candidates: List<String?> = listOf(null)
    private var attempt = 0
    private var confirmed = false // the current type has played at least once
    private var retries = 0
    private var filePath: String? = null

    /** False while a recording is playing. */
    val isLive: Boolean get() = filePath == null

    val exo: ExoPlayer = ExoPlayer.Builder(context)
        .build()
        .apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            setWakeMode(C.WAKE_MODE_NETWORK)
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> {
                            retries = 0
                            confirmed = true
                            _status.value = PlayerStatus.Playing
                        }
                        Player.STATE_BUFFERING -> _status.value = PlayerStatus.Buffering
                        // A live stream "ending" means the server closed the connection: reconnect.
                        Player.STATE_ENDED -> {
                            if (isLive) scheduleRetry("Reconnecting…") else _status.value = PlayerStatus.Idle
                        }
                        Player.STATE_IDLE -> Unit
                    }
                }

                override fun onPlayerError(error: PlaybackException) = handleError(error)
            })
        }

    fun play(stream: StreamSpec) {
        handler.removeCallbacksAndMessages(null)
        filePath = null
        spec = stream
        candidates = when {
            stream.mime != null -> listOf(stream.mime)
            // DRM-protected IPTV streams are almost always DASH, even without ".mpd" in the link.
            stream.drm != null -> listOf(StreamSpecs.MIME_DASH, null, StreamSpecs.MIME_HLS)
            else -> listOf(null, StreamSpecs.MIME_HLS, StreamSpecs.MIME_DASH)
        }
        attempt = 0
        confirmed = false
        retries = 0
        start()
    }

    /** Plays a recorded file (seekable, no auto-reconnect). */
    fun playFile(path: String) {
        handler.removeCallbacksAndMessages(null)
        filePath = path
        try {
            exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
            exo.prepare()
            exo.playWhenReady = true
            _status.value = PlayerStatus.Buffering
        } catch (e: Exception) {
            _status.value = PlayerStatus.Error("Can't play this recording: ${e.message}")
        }
    }

    /** Called when the app comes back to the foreground. */
    fun resume() {
        if (filePath != null) {
            exo.prepare() // keeps the position
        } else if (spec != null) {
            start()
        }
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        exo.stop()
        _status.value = PlayerStatus.Idle
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        exo.release()
    }

    private fun start() {
        val s = spec ?: return
        val mime = candidates.getOrNull(attempt)
        try {
            exo.setMediaSource(buildSource(s, mime))
            exo.prepare()
            exo.playWhenReady = true
            _status.value = PlayerStatus.Buffering
        } catch (e: Exception) {
            // e.g. an unsupported stream type – try the next one instead of crashing.
            failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun buildSource(s: StreamSpec, mime: String?): MediaSource {
        val userAgent = s.header("User-Agent") ?: DEFAULT_USER_AGENT
        val dataSource = OkHttpDataSource.Factory(http)
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(s.headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) })
        val sources = DefaultMediaSourceFactory(context).setDataSourceFactory(dataSource)
        val item = MediaItem.Builder().setUri(s.url)
        if (mime != null) item.setMimeType(mime)

        val drm = s.drm
        if (drm != null) {
            if (drm.clearKeys.isNotEmpty()) {
                // ClearKey with the keys in the playlist: answer license requests locally.
                val manager = ClearKeyDrmSessionManager(drm.clearKeys)
                sources.setDrmSessionManagerProvider { manager }
            } else {
                val scheme = when (drm.type) {
                    StreamSpecs.CLEARKEY -> C.CLEARKEY_UUID
                    // Many Android TV boxes have no PlayReady; most license servers also speak Widevine.
                    StreamSpecs.PLAYREADY ->
                        if (supported(C.PLAYREADY_UUID)) C.PLAYREADY_UUID else C.WIDEVINE_UUID
                    else -> C.WIDEVINE_UUID
                }
                item.setDrmConfiguration(
                    MediaItem.DrmConfiguration.Builder(scheme)
                        .setLicenseUri(drm.licenseUrl)
                        .setLicenseRequestHeaders(drm.licenseHeaders)
                        .setMultiSession(scheme == C.CLEARKEY_UUID)
                        .setPlayClearContentWithoutKey(true)
                        .build()
                )
                sources.setDrmSessionManagerProvider(
                    DefaultDrmSessionManagerProvider().apply { setDrmHttpDataSourceFactory(dataSource) }
                )
            }
        }
        return sources.createMediaSource(item.build())
    }

    private fun supported(scheme: java.util.UUID): Boolean =
        runCatching { android.media.MediaDrm.isCryptoSchemeSupported(scheme) }.getOrDefault(false)

    private fun handleError(error: PlaybackException) {
        if (!isLive) {
            _status.value = PlayerStatus.Error("Can't play this recording (${error.errorCodeName})")
            return
        }
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            exo.seekToDefaultPosition()
            exo.prepare()
            return
        }
        failed(error.errorCodeName)
    }

    private fun failed(reason: String) {
        if (spec == null) return
        if (!confirmed && attempt < candidates.lastIndex) {
            attempt++
            handler.post { start() }
            return
        }
        scheduleRetry("Channel unavailable – retrying… ($reason)")
    }

    private fun scheduleRetry(message: String) {
        val current = spec ?: return
        retries++
        _status.value = PlayerStatus.Error(message)
        val delayMs = (retries * 3_000L).coerceAtMost(30_000L)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (spec === current && isLive) {
                if (!confirmed) attempt = 0
                start()
            }
        }, delayMs)
    }

    companion object {
        const val DEFAULT_USER_AGENT = "Channelbox/1.0 (Linux; Android) ExoPlayer"
    }
}
