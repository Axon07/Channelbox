package io.github.axon07.channelbox.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DummyExoMediaDrm
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.UnsupportedDrmException
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ts.TsExtractor
import io.github.axon07.channelbox.data.StorageSettings
import io.github.axon07.channelbox.data.StreamSpec
import io.github.axon07.channelbox.data.StreamSpecs
import io.github.axon07.channelbox.data.UserAgent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.io.File
import java.util.Locale
import java.util.UUID

sealed interface PlayerStatus {
    data object Idle : PlayerStatus
    data object Buffering : PlayerStatus
    data object Playing : PlayerStatus
    data class Error(val message: String) : PlayerStatus
}

/**
 * Engine options that need a new player (applied when Channelbox starts).
 * [buffer]: none, small, medium, large, xlarge.
 */
data class PlayerOptions(
    val softwareAudio: Boolean = false,
    val softwareVideo: Boolean = false,
    val buffer: String = "medium",
    val passthrough: Boolean = true,
    /** Dolby Vision: "auto" (only when the TV shows it), "on" or "off" (played as HDR10). */
    val dolbyVision: String = "auto",
)

/** What's playing right now, for the info banner. */
data class StreamInfo(val video: String?, val audio: String?, val bitrate: String?)

/** An audio, video or subtitle track the viewer can pick. [group] null = "Off" / "Auto". */
data class TrackOption(val label: String, val group: Tracks.Group?, val index: Int, val selected: Boolean)

/** One of several channels ("programs") carried in a single MPEG-TS stream (MPTS). */
data class ProgramOption(val label: String, val number: Int, val selected: Boolean)

/**
 * One ExoPlayer for the whole app.
 *
 * Live channels: every failure (bad link, unsupported format, DRM error) becomes an
 * on-screen message and an automatic retry – never a crash. When the stream type isn't
 * known it tries plain/TS, then HLS, then DASH. Widevine channels that fail are retried
 * once at security level L3, which fixes many cheap boxes.
 *
 * Catch-up programmes and recordings play "on demand": seekable, no auto-reconnect.
 */
@OptIn(UnstableApi::class)
class TvPlayer(
    private val context: Context,
    private val http: OkHttpClient,
    private val options: PlayerOptions = PlayerOptions(),
    /**
     * Take Android's audio focus. Off for multi-view tiles: each tile asking for focus would pause
     * the others (only the newest tile kept playing).
     */
    private val handleAudioFocus: Boolean = true,
) {

    private val handler = Handler(Looper.getMainLooper())
    private val _status = MutableStateFlow<PlayerStatus>(PlayerStatus.Idle)
    val status: StateFlow<PlayerStatus> = _status.asStateFlow()

    /** Bumped whenever the available/selected audio & subtitle tracks change. */
    private val _tracksVersion = MutableStateFlow(0)
    val tracksVersion: StateFlow<Int> = _tracksVersion.asStateFlow()

    private var spec: StreamSpec? = null
    private var candidates: List<String?> = listOf(null)
    private var attempt = 0
    private var confirmed = false // the current type has played at least once
    private var retries = 0
    private var widevineL3 = false
    private var onDemand = false

    /**
     * The stream may carry several channels (MPTS: udp/rtp multicast, udpxy links, recordings of
     * those, or a playlist entry with a program). Then every program's tracks are read and the
     * player is kept on one program; elsewhere the first program is read, as before.
     */
    private var multiProgram = false
    /** Program being shown when there are several; null = not picked yet. */
    private var program: Int? = null
    /** Program the playlist asked for (#EXTVLCOPT:program=…). */
    private var wantedProgram: Int? = null
    /** Times the program was (re)applied since the stream opened – stops a loop if a program can't play. */
    private var programApplies = 0

    /** Wi-Fi drops multicast on many boxes unless an app holds this; only held while a udp/rtp channel plays. */
    private val multicastLock = runCatching {
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager)
            .createMulticastLock("channelbox").apply { setReferenceCounted(false) }
    }.getOrNull()

    private fun multicast(on: Boolean) {
        runCatching {
            val lock = multicastLock ?: return
            if (on) lock.acquire() else if (lock.isHeld) lock.release()
        }
    }

    /** False while a recording or catch-up programme is playing. */
    val isLive: Boolean get() = !onDemand

    /**
     * Connection speed. Starts from the speed measured last time (kept on the device), so Auto opens
     * a channel at the quality the connection can carry right away; on the very first run it starts
     * modest and steps up within seconds instead of stalling on a stream that's too big.
     */
    private val speedPrefs = context.getSharedPreferences("player", Context.MODE_PRIVATE)
    private val bandwidthMeter = DefaultBandwidthMeter.Builder(context)
        .setInitialBitrateEstimate(speedPrefs.getLong(KEY_SPEED, FIRST_RUN_SPEED).coerceIn(MIN_SPEED, MAX_SPEED))
        .build()
        .also { meter ->
            // Only the main player remembers the speed (multi-view tiles share the connection).
            if (handleAudioFocus) {
                var savedAt = 0L
                meter.addEventListener(handler) { _, _, _ ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - savedAt > 30_000) {
                        savedAt = now
                        speedPrefs.edit().putLong(KEY_SPEED, meter.bitrateEstimate).apply()
                    }
                }
            }
        }

    /**
     * Auto quality: steps up after a few seconds of good speed (the default waits 10 s), and only
     * steps down when the buffer is really running low.
     */
    private val trackSelector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(
        context,
        androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection.Factory(
            /* minDurationForQualityIncreaseMs = */ 3_000,
            /* maxDurationForQualityDecreaseMs = */ 15_000,
            /* minDurationToRetainAfterDiscardMs = */ 15_000,
            /* bandwidthFraction = */ 0.75f,
        ),
    )

    /** Login for playing recordings saved on an SMB share. */
    var storageSettings: () -> StorageSettings = { StorageSettings() }

    /** Settings → captions on by default. */
    var captionsByDefault: () -> Boolean = { false }

    /** Captions turned on / off with CC or the menu this session; carries over to the next channel. */
    private var captionsWanted: Boolean? = null

    /**
     * Captions start off unless asked for. Without this the player picks up the box's accessibility
     * caption setting and streams that flag a subtitle track as default, so CC came on by itself.
     */
    private fun applyCaptionDefault() {
        val on = captionsWanted ?: captionsByDefault()
        val b = exo.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !on)
        if (on) {
            b.setPreferredTextLanguage(java.util.Locale.getDefault().language)
                .setSelectUndeterminedTextLanguage(true)
        }
        exo.trackSelectionParameters = b.build()
    }

    /**
     * Whether Dolby Vision video goes to the box's Dolby Vision decoder. When it doesn't, Media3
     * falls back to the HEVC / AVC base layer, so most Dolby Vision streams still play as HDR10
     * (or SDR) instead of with the purple / green picture a DV decoder gives on a non-DV TV.
     */
    private val dolbyVisionAllowed: Boolean = when (options.dolbyVision) {
        "on" -> true
        "off" -> false
        else -> io.github.axon07.channelbox.system.Hdr.displaySupportsDolbyVision(context)
    }

    // FFmpeg (bundled) decodes AC3 / E-AC3 / DTS / TrueHD audio when the box can't.
    private val renderers = object : DefaultRenderersFactory(context) {
        // Passthrough off: decode everything to PCM, as if the TV had no Dolby / DTS receiver.
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): androidx.media3.exoplayer.audio.AudioSink? {
            if (options.passthrough) return super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
            @Suppress("DEPRECATION")
            return androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioCapabilities(androidx.media3.exoplayer.audio.AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                .build()
        }
    }
        .setExtensionRendererMode(
            if (options.softwareAudio) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
        )
        .setEnableDecoderFallback(true)
        .setMediaCodecSelector { mime, secure, tunneling ->
            if (mime == MimeTypes.VIDEO_DOLBY_VISION && !dolbyVisionAllowed) return@setMediaCodecSelector emptyList()
            val all = androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(mime, secure, tunneling)
            // HDR / Dolby Vision only come out of the hardware decoder, so it stays first for Dolby Vision.
            val software = if (mime.startsWith("video/")) options.softwareVideo && mime != MimeTypes.VIDEO_DOLBY_VISION
            else options.softwareAudio
            // Software first when chosen; hardware stays as a fallback (protected video has no software decoder).
            if (software) all.sortedBy { !it.softwareOnly } else all
        }

    val exo: ExoPlayer = ExoPlayer.Builder(context, renderers)
        .setBandwidthMeter(bandwidthMeter)
        .setTrackSelector(trackSelector)
        .setLoadControl(loadControl(options.buffer))
        .build()
        .apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                handleAudioFocus,
            )
            setWakeMode(C.WAKE_MODE_NETWORK)
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> {
                            retries = 0
                            refusals = 0
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

                override fun onTracksChanged(tracks: Tracks) {
                    keepProgram()
                    _tracksVersion.value++
                    updateFrameRate()
                }

                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) = updateFrameRate()
            })
        }

    private val _frameRate = MutableStateFlow(0f)

    /** Frame rate of the video playing (0 = unknown / nothing), for auto frame rate. */
    val frameRate: StateFlow<Float> = _frameRate.asStateFlow()

    private fun updateFrameRate() {
        val fps = exo.videoFormat?.frameRate ?: 0f
        _frameRate.value = if (fps > 0f) fps else 0f
    }

    /** Live channel. */
    fun play(stream: StreamSpec) = open(stream, onDemand = false)

    /** Catch-up programme: seekable, plays once. */
    fun playOnDemand(stream: StreamSpec, startAt: Long = 0) = open(stream, onDemand = true, startAt = startAt)

    /** Where an on-demand video starts (kept while a portal link is being fetched). */
    private var startPosition = 0L

    /**
     * Pause-live-TV buffer: plays [file] from the start while [growing] (the stream is still being
     * written to it). It can't be seeked – the length isn't known yet.
     */
    fun playTimeshift(file: File, growing: () -> Boolean) {
        startToken++
        handler.removeCallbacksAndMessages(null)
        applyCaptionDefault()
        onDemand = true
        spec = null
        programApplies = 0 // same program as live, if the channel has several
        val source = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(
            { GrowingFileDataSource(file, growing) },
            extractors(multiProgram),
        ).createMediaSource(MediaItem.fromUri(Uri.fromFile(file)))
        exo.setMediaSource(source)
        exo.prepare()
        exo.playWhenReady = true
        _status.value = PlayerStatus.Buffering
    }

    /**
     * Pause-live-TV buffer saved as a local HLS playlist (channels with separate audio): plays it
     * from the start; the player re-reads the playlist as new segments are added.
     */
    fun playTimeshiftPlaylist(index: File) {
        open(StreamSpec(Uri.fromFile(index).toString(), emptyMap(), null, StreamSpecs.MIME_HLS), onDemand = true)
        exo.seekTo(0L)
    }

    /** Recorded file. */
    fun playFile(path: String) =
        open(StreamSpec(Uri.fromFile(File(path)).toString(), emptyMap(), null, null), onDemand = true)

    private fun open(stream: StreamSpec, onDemand: Boolean, startAt: Long = 0) {
        startPosition = startAt.coerceAtLeast(0)
        handler.removeCallbacksAndMessages(null)
        // Let go of the previous stream first: on one-connection accounts the provider refuses the
        // new channel (HTTP 401/403) while the old connection is still open.
        exo.stop()
        applyCaptionDefault()
        this.onDemand = onDemand
        spec = stream
        multiProgram = wantsMultiProgram(stream)
        wantedProgram = stream.program
        program = null
        programApplies = 0
        val isHttp = stream.url.startsWith("http://", true) || stream.url.startsWith("https://", true) ||
            io.github.axon07.channelbox.data.StreamResolver.needsResolving(stream.url) // a portal link becomes http(s)
        candidates = when {
            stream.mime != null -> listOf(stream.mime)
            !isHttp -> listOf(null) // rtsp://, rtmp://, udp://, file:// – the player knows these
            // DRM-protected IPTV streams are almost always DASH, even without ".mpd" in the link.
            stream.drm != null -> listOf(StreamSpecs.MIME_DASH, null, StreamSpecs.MIME_HLS)
            else -> listOf(null, StreamSpecs.MIME_HLS, StreamSpecs.MIME_DASH)
        }
        attempt = 0
        confirmed = false
        retries = 0
        refusals = 0
        widevineL3 = false
        start()
    }

    /** Times in a row the provider refused this stream (connection limit), retried quietly. */
    private var refusals = 0

    private fun httpCode(error: PlaybackException): Int? {
        var cause: Throwable? = error
        while (cause != null && cause !is HttpDataSource.InvalidResponseCodeException) cause = cause.cause
        return (cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
    }

    /** Called when the app comes back to the foreground. */
    fun resume() {
        if (onDemand) exo.prepare() // keeps the position
        else if (spec != null) start()
    }

    fun stop() {
        startToken++
        handler.removeCallbacksAndMessages(null)
        exo.stop()
        multicast(false)
        _status.value = PlayerStatus.Idle
    }

    /**
     * Pause live TV: let go of the stream (many accounts allow only one connection, which the pause
     * buffer now needs) while the paused picture stays on screen.
     */
    fun hold() {
        startToken++
        handler.removeCallbacksAndMessages(null)
        exo.pause()
        exo.stop()
        multicast(false)
    }

    fun release() {
        startToken++
        resolver.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        exo.release()
        multicast(false)
    }

    /** Portal links ("stalker://") are turned into playable ones here, off the main thread. */
    private val resolver = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var startToken = 0

    private fun start() {
        val s = spec ?: return
        val mime = candidates.getOrNull(attempt)
        val token = ++startToken
        if (io.github.axon07.channelbox.data.StreamResolver.needsResolving(s.url)) {
            _status.value = PlayerStatus.Buffering
            resolver.execute {
                // A fresh link every time: portal links expire within seconds, so retries ask again.
                val result = runCatching { io.github.axon07.channelbox.data.StreamResolver.resolve(s) }
                handler.post {
                    if (token != startToken || spec !== s) return@post
                    result.fold(
                        onSuccess = { real -> prepare(real, real.mime ?: mime) },
                        onFailure = { e -> failed(e.message ?: "couldn't get a link from the portal") },
                    )
                }
            }
            return
        }
        prepare(s, mime)
    }

    private fun prepare(s: StreamSpec, mime: String?) {
        try {
            multicast(StreamSpecs.isUdp(s.url))
            val source = buildSource(s, mime)
            if (onDemand && startPosition > 0) exo.setMediaSource(source, startPosition) else exo.setMediaSource(source)
            exo.prepare()
            exo.playWhenReady = true
            _status.value = PlayerStatus.Buffering
        } catch (e: Exception) {
            // e.g. an unsupported stream type – try the next one instead of crashing.
            failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun buildSource(s: StreamSpec, mime: String?): MediaSource {
        val userAgent = s.header("User-Agent") ?: UserAgent.current
        val httpSource = OkHttpDataSource.Factory(http)
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(s.headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) })
        // DefaultDataSource adds rtmp://, file:// … and uses OkHttp for http(s).
        val dataSource = when {
            s.url.startsWith("smb://") -> SmbDataSource.Factory(storageSettings)
            StreamSpecs.isUdp(s.url) -> MulticastDataSource.Factory() // multicast, with RTP removed
            else -> DefaultDataSource.Factory(context, httpSource)
        }
        val sources = DefaultMediaSourceFactory(dataSource, extractors(multiProgram))
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
                    StreamSpecs.PLAYREADY -> if (supported(C.PLAYREADY_UUID)) C.PLAYREADY_UUID else C.WIDEVINE_UUID
                    else -> C.WIDEVINE_UUID
                }
                val callback = HttpMediaDrmCallback(drm.licenseUrl, httpSource).apply {
                    drm.licenseHeaders.forEach { (k, v) -> setKeyRequestProperty(k, v) }
                }
                val forceL3 = widevineL3 && scheme == C.WIDEVINE_UUID
                val manager = DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(scheme, drmProvider(forceL3))
                    .setMultiSession(scheme == C.CLEARKEY_UUID)
                    .setPlayClearSamplesWithoutKeys(true)
                    .build(callback)
                sources.setDrmSessionManagerProvider { manager }
            }
        }
        return sources.createMediaSource(item.build())
    }

    private fun drmProvider(forceL3: Boolean) = ExoMediaDrm.Provider { uuid: UUID ->
        try {
            FrameworkMediaDrm.newInstance(uuid).also { drm ->
                if (forceL3) runCatching { drm.setPropertyString("securityLevel", "L3") }
            }
        } catch (e: UnsupportedDrmException) {
            DummyExoMediaDrm()
        }
    }

    private fun supported(scheme: UUID): Boolean =
        runCatching { android.media.MediaDrm.isCryptoSchemeSupported(scheme) }.getOrDefault(false)

    private fun handleError(error: PlaybackException) {
        val s = spec
        // Widevine at L1 fails on many cheap boxes; L3 (software) usually works.
        val isDrmError = error.errorCode in 6000..6999
        if (isDrmError && !widevineL3 && s?.drm != null && s.drm.clearKeys.isEmpty() &&
            (s.drm.type == StreamSpecs.WIDEVINE || (s.drm.type == StreamSpecs.PLAYREADY && !supported(C.PLAYREADY_UUID)))
        ) {
            widevineL3 = true
            handler.post { start() }
            return
        }
        if (!isLive) {
            _status.value = PlayerStatus.Error("Can't play this (${describe(error)})")
            if (!confirmed && attempt < candidates.lastIndex) {
                attempt++
                handler.post { start() }
            }
            return
        }
        // Refused (401/403) or "too many connections": usually the previous channel's connection is
        // still being closed on a one-connection account. Not a format problem, so don't try other
        // formats (more connections) – wait a moment and ask again, without alarming messages.
        val code = httpCode(error)
        if (code != null && code in REFUSED && refusals < 4) {
            refusals++
            val current = spec
            _status.value = PlayerStatus.Buffering
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({ if (spec === current && current != null) start() }, 1_200L * refusals)
            return
        }
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            exo.seekToDefaultPosition()
            exo.prepare()
            return
        }
        failed(describe(error))
    }

    /** What went wrong, in words. For HTTP errors: the server's status code and what it usually means. */
    private fun describe(error: PlaybackException): String {
        var cause: Throwable? = error
        while (cause != null && cause !is HttpDataSource.InvalidResponseCodeException) cause = cause.cause
        val code = (cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
            ?: return error.errorCodeName
        val hint = when (code) {
            401, 403 -> "the provider refused it – usually the account is already playing on another device; " +
                "otherwise the account has ended or the provider blocks this app's user agent (Settings → Network)"
            404, 410 -> "the link no longer works – reload the playlist"
            429, 456, 458, 509 -> "too many connections on this account – stop it on other devices"
            in 500..599 -> "the provider's server has a problem"
            else -> null
        }
        return if (hint != null) "HTTP $code: $hint" else "HTTP $code"
    }

    private fun failed(reason: String) {
        if (spec == null) return
        if (!confirmed && attempt < candidates.lastIndex) {
            attempt++
            handler.post { start() }
            return
        }
        if (!isLive) {
            _status.value = PlayerStatus.Error("Can't play this ($reason)")
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

    // ---- Programs: several channels in one MPEG-TS stream (MPTS) ---------------------------------

    private fun wantsMultiProgram(s: StreamSpec): Boolean {
        if (s.program != null) return true
        val u = s.url.lowercase(Locale.ROOT)
        return StreamSpecs.isUdp(u) ||
            // udpxy / msd_lite / router multicast-to-HTTP gateways: http://router:4022/udp/239.1.1.1:1234
            "/udp/" in u || "/rtp/" in u
    }

    /**
     * Single-program mode plays the first program and starts as soon as its table arrives; it stays
     * the default so a stream whose list names a program that never shows up still starts.
     */
    private fun extractors(multi: Boolean): ExtractorsFactory = DefaultExtractorsFactory()
        .setTsExtractorMode(if (multi) TsExtractor.MODE_MULTI_PMT else TsExtractor.MODE_SINGLE_PMT)

    /** Program a track belongs to: MPEG-TS track ids are "program/PID". */
    private fun programOf(group: Tracks.Group): Int? {
        if (!multiProgram || group.length == 0) return null
        val id = group.getTrackFormat(0).id ?: return null
        val slash = id.indexOf('/')
        return if (slash > 0) id.substring(0, slash).toIntOrNull() else null
    }

    private fun inProgram(group: Tracks.Group): Boolean {
        val n = program ?: return true
        return programOf(group).let { it == null || it == n }
    }

    private fun programGroups(n: Int) = exo.currentTracks.groups.filter { programOf(it) == n }

    /** Programs with picture or sound, in stream order. */
    private fun programNumbers(): List<Int> = exo.currentTracks.groups
        .filter { it.type == C.TRACK_TYPE_VIDEO || it.type == C.TRACK_TYPE_AUDIO }
        .mapNotNull { programOf(it) }
        .distinct()

    private fun wholeGroup(g: Tracks.Group) = TrackSelectionOverride(g.mediaTrackGroup, (0 until g.length).toList())

    private fun rememberedProgram(): Int? =
        spec?.url?.let { speedPrefs.getInt(KEY_PROGRAM + it, -1) }?.takeIf { it >= 0 }

    /**
     * Keeps picture, sound and subtitles on one program. Without this the player picks the best
     * video and audio on their own, which can mix one channel's picture with another's sound.
     */
    private fun keepProgram() {
        val numbers = programNumbers()
        if (numbers.size < 2) return
        val target = program?.takeIf { it in numbers }
            ?: wantedProgram?.takeIf { it in numbers }
            ?: rememberedProgram()?.takeIf { it in numbers }
            ?: numbers.first()
        val mixed = exo.currentTracks.groups.any {
            it.isSelected && (it.type == C.TRACK_TYPE_VIDEO || it.type == C.TRACK_TYPE_AUDIO) && programOf(it) != target
        }
        if ((target != program || mixed) && programApplies < 5) {
            programApplies++
            applyProgram(target)
        }
    }

    private fun applyProgram(n: Int) {
        program = n
        val groups = programGroups(n)
        val lang = Locale.getDefault().language
        fun pick(list: List<Tracks.Group>) =
            list.firstOrNull { it.getTrackFormat(0).language?.startsWith(lang) == true } ?: list.firstOrNull()
        val b = exo.trackSelectionParameters.buildUpon()
        groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }?.let { b.setOverrideForType(wholeGroup(it)) }
        pick(groups.filter { it.type == C.TRACK_TYPE_AUDIO })?.let { b.setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, 0)) }
        // Subtitles: only this program's (when they're on).
        if (C.TRACK_TYPE_TEXT !in exo.trackSelectionParameters.disabledTrackTypes) {
            b.clearOverridesOfType(C.TRACK_TYPE_TEXT)
            exo.currentTracks.groups
                .filter { it.type == C.TRACK_TYPE_TEXT && programOf(it).let { p -> p != null && p != n } }
                .forEach { b.addOverride(TrackSelectionOverride(it.mediaTrackGroup, emptyList<Int>())) }
            pick(groups.filter { it.type == C.TRACK_TYPE_TEXT })?.let { b.addOverride(TrackSelectionOverride(it.mediaTrackGroup, 0)) }
        }
        exo.trackSelectionParameters = b.build()
    }

    /** The channels in this stream when it carries more than one (empty otherwise). */
    fun programs(): List<ProgramOption> {
        val numbers = programNumbers()
        if (numbers.size < 2) return emptyList()
        val current = program ?: numbers.first()
        return numbers.map { n ->
            val groups = programGroups(n)
            val video = groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }?.getTrackFormat(0)
            val languages = groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                .mapNotNull { g -> g.getTrackFormat(0).language?.takeIf { it.isNotBlank() && it != "und" } }
                .map { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()).ifBlank { it } }
                .distinct()
            val label = listOfNotNull(
                "Program $n",
                video?.height?.takeIf { it > 0 }?.let { "${it}p" } ?: if (video == null) "radio" else null,
                languages.takeIf { it.isNotEmpty() }?.joinToString(", "),
            ).joinToString(" · ")
            ProgramOption(label, n, n == current)
        }
    }

    /** Switches to another channel in the same stream; remembered for this link unless the playlist names one. */
    fun selectProgram(option: ProgramOption) {
        programApplies = 0
        applyProgram(option.number)
        val url = spec?.url
        if (wantedProgram == null && url != null) speedPrefs.edit().putInt(KEY_PROGRAM + url, option.number).apply()
        _tracksVersion.value++
    }

    // ---- Audio / subtitle tracks ---------------------------------------------------------------

    fun audioTracks(): List<TrackOption> = tracks(C.TRACK_TYPE_AUDIO)

    fun subtitleTracks(): List<TrackOption> {
        val list = tracks(C.TRACK_TYPE_TEXT)
        val disabled = C.TRACK_TYPE_TEXT in exo.trackSelectionParameters.disabledTrackTypes
        val anySelected = !disabled && list.any { it.selected }
        return listOf(TrackOption("Off", null, 0, !anySelected)) + if (disabled) list.map { it.copy(selected = false) } else list
    }

    private fun tracks(type: Int): List<TrackOption> {
        val out = ArrayList<TrackOption>()
        exo.currentTracks.groups.filter { it.type == type && inProgram(it) }.forEach { group ->
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                out += TrackOption(describe(group.getTrackFormat(i), out.size + 1), group, i, group.isTrackSelected(i))
            }
        }
        return out
    }

    /** Video qualities, "Auto" first. */
    fun videoTracks(): List<TrackOption> {
        val list = tracks(C.TRACK_TYPE_VIDEO)
        val overridden = exo.trackSelectionParameters.overrides.values.any {
            // With several programs the program's video is always set; a quality is "picked" only
            // when it narrows that down.
            it.type == C.TRACK_TYPE_VIDEO && (program == null || it.trackIndices.size < it.mediaTrackGroup.length)
        }
        val playing = exo.videoFormat?.height?.takeIf { it > 0 }?.let { " (now ${it}p)" }.orEmpty()
        return listOf(TrackOption("Auto$playing", null, 0, !overridden)) +
            list.sortedByDescending { it.group?.getTrackFormat(it.index)?.height ?: 0 }
                .map { it.copy(selected = overridden && it.selected) }
    }

    /**
     * Quality choice: [ADAPTIVE] (the default) = follow the connection speed; 0 = best available,
     * >0 = best up to that height, both held steady.
     */
    fun setMaxQuality(height: Int) {
        val b = exo.trackSelectionParameters.buildUpon()
        if (height <= 0) b.clearVideoSizeConstraints() else b.setMaxVideoSize(Int.MAX_VALUE, height)
        b.setForceHighestSupportedBitrate(height != ADAPTIVE)
        exo.trackSelectionParameters = b.build()
    }

    /** Languages of the subtitles / closed captions the stream offers. */
    fun captionLanguages(): List<String> = tracks(C.TRACK_TYPE_TEXT).map { it.label.substringBefore(" · ") }.distinct()

    val captionsOn: Boolean
        get() = C.TRACK_TYPE_TEXT !in exo.trackSelectionParameters.disabledTrackTypes &&
            tracks(C.TRACK_TYPE_TEXT).any { it.selected }

    /** CC button: turns captions off, or on in the box's language (else the first one). Returns the new state. */
    fun toggleCaptions(): Boolean {
        val text = tracks(C.TRACK_TYPE_TEXT)
        if (captionsOn || text.isEmpty()) {
            select(C.TRACK_TYPE_TEXT, TrackOption("Off", null, 0, false))
            return false
        }
        val lang = Locale.getDefault().language
        val pick = text.firstOrNull { it.group?.getTrackFormat(it.index)?.language?.startsWith(lang) == true } ?: text.first()
        select(C.TRACK_TYPE_TEXT, pick)
        return true
    }

    fun streamInfo(): StreamInfo {
        val v = exo.videoFormat
        val a = exo.audioFormat
        val video = v?.let { f ->
            listOfNotNull(
                if (f.height > 0) "${f.height}p" else null,
                f.frameRate.takeIf { it > 0 }?.let { "${Math.round(it)} fps" },
                codecName(f.sampleMimeType ?: f.codecs),
                dynamicRange(f),
            ).joinToString(" · ").ifEmpty { null }
        }
        val audio = a?.let { describe(it, 1).substringAfter("Track 1 · ", describe(it, 1)) }
        val bits = (v?.bitrate?.takeIf { it > 0 } ?: bandwidthMeter.bitrateEstimate.toInt().takeIf { it > 0 })
            ?.let { String.format(Locale.US, "%.1f Mbps", it / 1_000_000.0) }
        return StreamInfo(video, audio, bits)
    }

    /** "Dolby Vision", "HDR10" or "HLG" for HDR video; null for normal (SDR) video. */
    private fun dynamicRange(f: Format): String? {
        val dv = f.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION ||
            f.codecs?.let { c -> listOf("dvh1", "dvhe", "dva1", "dvav", "dav1").any { c.startsWith(it) } } == true
        val transfer = f.colorInfo?.colorTransfer
        return when {
            dv && dolbyVisionAllowed -> "Dolby Vision"
            dv -> "Dolby Vision as HDR10"
            transfer == C.COLOR_TRANSFER_ST2084 -> "HDR10"
            transfer == C.COLOR_TRANSFER_HLG -> "HLG"
            else -> null
        }
    }

    private fun codecName(mime: String?): String? = when {
        mime == null -> null
        mime == MimeTypes.VIDEO_DOLBY_VISION -> "H.265"
        mime.contains("avc") || mime == MimeTypes.VIDEO_H264 -> "H.264"
        mime.contains("hev") || mime.contains("hvc") || mime == MimeTypes.VIDEO_H265 -> "H.265"
        mime.contains("av01") || mime == MimeTypes.VIDEO_AV1 -> "AV1"
        mime == MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
        mime.contains("vp9") || mime == MimeTypes.VIDEO_VP9 -> "VP9"
        else -> null
    }

    fun select(type: Int, option: TrackOption) {
        val builder = exo.trackSelectionParameters.buildUpon()
        val group = option.group
        if (group == null && type == C.TRACK_TYPE_VIDEO) {
            val programVideo = program?.let { n -> programGroups(n).firstOrNull { it.type == C.TRACK_TYPE_VIDEO } }
            if (programVideo != null) builder.setOverrideForType(wholeGroup(programVideo))
            else builder.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        } else if (group == null) {
            builder.setTrackTypeDisabled(type, true)
            if (type == C.TRACK_TYPE_TEXT) captionsWanted = false
        } else {
            if (type == C.TRACK_TYPE_TEXT) captionsWanted = true
            builder.setTrackTypeDisabled(type, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.index))
        }
        exo.trackSelectionParameters = builder.build()
    }

    private fun describe(f: Format, number: Int): String {
        if (f.sampleMimeType?.startsWith("video/") == true || f.height > 0) {
            return listOfNotNull(
                if (f.height > 0) "${f.height}p" else "Video $number",
                f.frameRate.takeIf { it > 0 }?.let { "${Math.round(it)} fps" },
                f.bitrate.takeIf { it > 0 }?.let { String.format(Locale.US, "%.1f Mbps", it / 1_000_000.0) },
            ).joinToString(" · ")
        }
        val language = f.language?.takeIf { it.isNotBlank() && it != "und" }
            ?.let { Locale(it).displayLanguage.takeIf { name -> name.isNotBlank() } ?: it }
        val name = f.label?.takeIf { it.isNotBlank() } ?: language ?: "Track $number"
        val codec = when (f.sampleMimeType) {
            MimeTypes.AUDIO_AC3 -> "Dolby Digital"
            MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital+"
            MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD -> "DTS"
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L2 -> "MP2/MP3"
            MimeTypes.APPLICATION_CEA608, MimeTypes.APPLICATION_CEA708 -> "Closed captions"
            else -> null
        }
        val channels = when (f.channelCount) {
            1 -> "mono"
            2 -> "stereo"
            6 -> "5.1"
            8 -> "7.1"
            Format.NO_VALUE -> null
            else -> "${f.channelCount} ch"
        }
        return listOfNotNull(name, codec, channels).distinct().joinToString(" · ")
    }

    companion object {
        /** HTTP codes providers use for "refused" / "too many connections". */
        private val REFUSED = setOf(401, 403, 429, 456, 458, 509)
        const val DEFAULT_USER_AGENT = UserAgent.DEFAULT
        private const val KEY_SPEED = "bandwidth_estimate"
        /** + stream link: the program last picked in a multi-channel stream. */
        private const val KEY_PROGRAM = "program:"
        /** First run: about 720p on most providers, then up as soon as the speed is known. */
        private const val FIRST_RUN_SPEED = 4_000_000L
        private const val MIN_SPEED = 1_000_000L
        private const val MAX_SPEED = 100_000_000L
    }
}

/** [TvPlayer.setMaxQuality] value: let quality follow the connection speed. */
const val ADAPTIVE = -1

/**
 * Buffer sizes (how much video is loaded ahead). Bigger rides out a shaky connection but starts
 * channels a little slower; "none" starts fastest. Values: minimum / maximum buffered, and how
 * much is needed to start (and to restart after running out).
 */
private fun loadControl(size: String): androidx.media3.exoplayer.LoadControl {
    val (min, max, start, restart) = when (size) {
        "none" -> listOf(1_500, 4_000, 500, 1_000)
        "small" -> listOf(5_000, 15_000, 750, 2_000)
        "large" -> listOf(30_000, 60_000, 2_500, 6_000)
        "xlarge" -> listOf(60_000, 120_000, 4_000, 10_000)
        // Starts after 1 s of video (like a cable box: picture first), keeps up to 30 s ahead.
        else -> listOf(15_000, 30_000, 1_000, 3_000) // medium
    }
    return androidx.media3.exoplayer.DefaultLoadControl.Builder()
        .setBufferDurationsMs(min, max, start, restart)
        // Start as soon as the time is buffered, without also waiting for a byte target.
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()
}
