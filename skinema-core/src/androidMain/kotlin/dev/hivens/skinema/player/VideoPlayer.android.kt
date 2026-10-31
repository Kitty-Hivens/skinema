@file:OptIn(UnstableApi::class)

package dev.hivens.skinema.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.media.Image
import android.media.MediaCodecList
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.hivens.skinema.Debug
import dev.hivens.skinema.android.AppContext
import dev.hivens.skinema.android.CueRasterizer
import dev.hivens.skinema.android.ContainerInfo
import dev.hivens.skinema.android.FrameBridge
import dev.hivens.skinema.android.ImageFrameSource
import dev.hivens.skinema.android.ImageFrames
import dev.hivens.skinema.android.Input
import dev.hivens.skinema.android.PcmSinkAudioSink
import dev.hivens.skinema.android.layoutFor
import dev.hivens.skinema.audio.ChannelPreference
import dev.hivens.skinema.audio.PcmEncoding
import dev.hivens.skinema.audio.PcmFormat
import dev.hivens.skinema.audio.PcmSink
import dev.hivens.skinema.core.MediaClock
import dev.hivens.skinema.libav.AudioDecoder
import dev.hivens.skinema.libav.AudioTrack
import dev.hivens.skinema.libav.Chapter
import dev.hivens.skinema.libav.HwAccel
import dev.hivens.skinema.libav.LibavException
import dev.hivens.skinema.libav.SubtitleTrack
import dev.hivens.skinema.libav.VideoDecoder
import dev.hivens.skinema.subtitles.SubtitleOverlay
import java.nio.file.Files
import java.util.Locale
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import android.media.AudioFormat as PlatformAudioFormat

/**
 * One media file played through Media3 on Android.
 *
 * ExoPlayer decodes on the platform's codecs, paces against the audio device
 * and releases each frame at its own time; the frame is drawn on the GPU into
 * an image this player owns ([FrameBridge]) and published into a one-slot
 * mailbox, which is [acquireFrame]'s. Every call is safe from any thread: the
 * work is posted to the player's own thread, where ExoPlayer requires it.
 *
 * Where the contract differs from the desktop's, it is written on the member.
 * In general: frames arrive already turned for display, so [rotationDegrees]
 * is zero; `readAheadFrames` is not a knob here, since ExoPlayer buffers by
 * its own policy; and `explicitClock` is refused, since ExoPlayer takes no
 * external media clock to pace video against.
 *
 * An image file (a still, or an animated GIF, APNG or WebP) does not go
 * through ExoPlayer, which plays none of them as video. The player paces its
 * frames itself, from [ImageFrameSource], against a clock of its own, and
 * draws each one into the same bridge, so the mailbox, [acquireFrameBitmap]
 * and everything downstream see one kind of frame. A [setSource] moves
 * between the two freely.
 */
actual class VideoPlayer actual constructor(
    path: Path,
    loop: Boolean,
    private val audio: Boolean,
    explicitClock: MediaClock?,
    private val sink: PcmSink?,
    @Suppress("UNUSED_PARAMETER") readAheadFrames: Int,
    audioTrack: Int?,
    private val hardware: HwAccel,
    private val unwatched: WhenUnwatched,
    startPaused: Boolean,
    volume: Float,
    private val audioChannels: ChannelPreference,
) : AutoCloseable {

    actual sealed interface State {
        actual data object Opening : State
        actual data object Playing : State
        actual data object Paused : State
        actual data object Ended : State
        actual data object Seeking : State
        actual data class Failed actual constructor(actual val cause: Throwable) : State
        actual data object Closed : State
    }

    actual class FrameSlot internal constructor() {
        actual var width: Int = 0
            internal set
        actual var height: Int = 0
            internal set
        actual var ptsNanos: Long = 0L
            internal set
        actual var rgba: ByteArray = ByteArray(0)
            internal set
    }

    private val thread = HandlerThread("skinema-player").apply { start() }
    private val handler = Handler(thread.looper)

    // -- State and change count ------------------------------------------------

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val changeMonitor = Object()

    @Volatile
    private var changes = 0L

    @Volatile
    private var stateField: State = State.Opening

    actual val state: State get() = stateField

    /** The desktop's rule: Failed is terminal, and the check and the write are one step. */
    private fun publishState(next: State) {
        synchronized(changeMonitor) {
            if (stateField is State.Failed && next !is State.Failed) return
            if (stateField == State.Closed) return
            if (stateField == next) return
            stateField = next
            changes++
            changeMonitor.notifyAll()
        }
    }

    private fun noteChange() {
        synchronized(changeMonitor) {
            changes++
            changeMonitor.notifyAll()
        }
    }

    actual val changeCount: Long get() = changes

    @Throws(InterruptedException::class)
    actual fun awaitChange(since: Long, timeoutNanos: Long): Long {
        synchronized(changeMonitor) {
            if (changes != since || timeoutNanos <= 0L) return changes
            changeMonitor.wait(timeoutNanos / 1_000_000L, (timeoutNanos % 1_000_000L).toInt())
            return changes
        }
    }

    // -- Metadata ----------------------------------------------------------------

    @Volatile
    actual var durationNanos: Long? = null
        private set

    @Volatile
    private var audioTrackList: List<AudioTrack> = emptyList()

    actual val audioTracks: List<AudioTrack> get() = audioTrackList

    @Volatile
    private var selectedAudioTrack: Int? = null

    actual val activeAudioTrack: Int? get() = if (audioTrackList.isEmpty()) null else selectedAudioTrack

    @Volatile
    private var audioFormat: PcmFormat? = null

    /** What the platform audio track was opened with, read off its initialisation. */
    actual val activeAudioFormat: PcmFormat? get() = audioFormat

    @Volatile
    actual var tags: Map<String, String> = emptyMap()
        private set

    /** Empty: the platform does not read chapters. */
    @Volatile
    actual var chapters: List<Chapter> = emptyList()
        private set

    @Volatile
    actual var coverArt: ByteArray? = null
        private set

    /**
     * Zero, always. The platform applies a file's orientation as it renders,
     * so frames arrive already turned for display, which is what this
     * property's contract asks a consumer to do itself on the desktop.
     */
    actual var rotationDegrees: Int = 0
        private set

    @Volatile
    actual var hardwareActive: Boolean = false
        private set

    @Volatile
    actual var subtitleTracks: List<SubtitleTrack> = emptyList()
        private set

    // -- Settings ----------------------------------------------------------------

    @Volatile
    actual var rate: Float = 1f
        private set

    @Volatile
    private var volumeField: Float = if (volume.isNaN()) 1f else volume.coerceIn(0f, 1f)

    @Volatile
    private var loopField: Boolean = loop

    actual var loop: Boolean
        get() = loopField
        set(value) {
            loopField = value
            post { player?.repeatMode = if (value) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF }
        }

    @Volatile
    actual var source: Path = path
        private set

    @Volatile
    actual var sourceFailure: Throwable? = null
        private set

    // -- Player-thread state -----------------------------------------------------

    private var player: ExoPlayer? = null

    @Volatile
    private var bridge: FrameBridge? = null
    private var everReady = false
    private var seekPending = false
    private var intendedNanos = 0L
    @Volatile
    private var hasVideo = false
    private var userPaused = startPaused
    private var autoPaused = false
    private var stopMode: StopMode? = null

    // The picture is off while nobody takes it: the video track disabled for
    // ExoPlayer, no frames composed for an image.
    private var videoTrackDisabled = false

    // The image file playing, when it is one. Its frames, its lap and whether
    // the lap ran out without looping: the image side's whole state.
    private var clip: ImageFrameSource? = null
    private var clipEnded = false
    private val clipAdvance = Runnable { advanceClip() }

    // Read from ExoPlayer's playback thread, where a frame released late by a
    // file just switched away from must not move the image's clock.
    @Volatile
    private var clipActive = false

    private enum class StopMode { FROZE, KEPT }

    @Volatile
    private var closing = false

    // -- Clock ---------------------------------------------------------------------

    // Sampled on the player thread and by the frame metadata callback, read
    // from anywhere: ExoPlayer may be asked for its position on its own thread
    // only, and positionNanos() is called from render loops.
    @Volatile
    private var clockBase = 0L

    @Volatile
    private var clockAt = 0L

    @Volatile
    private var clockRunning = false

    private fun sampleClock(positionNanos: Long, atNanos: Long, running: Boolean) {
        clockBase = positionNanos
        clockAt = atNanos
        clockRunning = running
    }

    actual fun positionNanos(): Long {
        val base = clockBase
        if (!clockRunning) return base
        return base + ((System.nanoTime() - clockAt) * rate).toLong()
    }

    // -- Frames ----------------------------------------------------------------------

    private val mailbox = Any()
    private var latest: Image? = null
    private var latestPts = 0L
    private var latestPremultiplied = false
    private var fresh = false

    // Images handed out by acquireFrameBitmap and not yet given back. A
    // superseded image is closed (returned to the reader) only once nothing
    // borrows it, so a frame being drawn is never recycled under the draw.
    private val borrowed = HashSet<Image>()

    @Volatile
    private var lastPublishedPts = 0L

    // Release time -> media time, filled as ExoPlayer releases each frame and
    // read when the frame comes out of the bridge carrying its release time.
    private val releaseToPts = ConcurrentHashMap<Long, Long>()

    // Timestamps of the images that carry a composed picture rather than a
    // decoded one. Those come out of the GPU premultiplied, as Android draws
    // every bitmap, and acquireFrame owes straight alpha.
    private val premultipliedTags: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var lastAcquireNanos = 0L

    @Volatile
    private var publishedSinceRead = false

    private var slotBytes = ByteArray(0)

    private fun onImage(image: Image) {
        if (closing) {
            release(image)
            return
        }
        val pts = releaseToPts.remove(image.timestamp) ?: lastPublishedPts
        val premultiplied = premultipliedTags.remove(image.timestamp)
        if (releaseToPts.size > RELEASE_MAP_LIMIT) {
            releaseToPts.keys.sorted().take(releaseToPts.size - RELEASE_MAP_LIMIT / 2).forEach(releaseToPts::remove)
        }
        if (premultipliedTags.size > RELEASE_MAP_LIMIT) {
            premultipliedTags.sorted().take(premultipliedTags.size - RELEASE_MAP_LIMIT / 2).forEach(premultipliedTags::remove)
        }
        synchronized(mailbox) {
            val old = latest
            latest = image
            latestPts = pts
            latestPremultiplied = premultiplied
            fresh = true
            if (old != null && old !in borrowed) release(old)
        }
        lastPublishedPts = pts
        publishedSinceRead = true
        noteChange()
    }

    actual fun acquireFrame(): FrameSlot? {
        lastAcquireNanos = System.nanoTime()
        publishedSinceRead = false
        if (!presentingSaid && !visibilityReported) post { mailboxRead() }
        synchronized(mailbox) {
            if (!fresh) return null
            val image = latest ?: return null
            fresh = false
            val width = image.width
            val height = image.height
            val plane = image.planes[0]
            val rowBytes = width * 4
            val bytes = rowBytes * height
            if (slotBytes.size != bytes) slotBytes = ByteArray(bytes)
            val buffer = plane.buffer.duplicate()
            for (row in 0 until height) {
                buffer.position(row * plane.rowStride)
                buffer.get(slotBytes, row * rowBytes, rowBytes)
            }
            if (latestPremultiplied) unpremultiply(slotBytes)
            return FrameSlot().also {
                it.width = width
                it.height = height
                it.ptsNanos = latestPts
                it.rgba = slotBytes
            }
        }
    }

    /**
     * The freshest frame as a hardware [android.graphics.Bitmap], with no copy:
     * the image the GPU drew is wrapped as it is. Null when nothing newer
     * arrived since the last call, as [acquireFrame].
     *
     * The frame is borrowed. Its image stays out of the player's rotation until
     * [FrameBitmap.close], so a consumer that keeps one past the next frame
     * holds a buffer the player cannot reuse, and one that never closes them
     * stops the picture once the reader runs out. The Compose surface closes
     * each one as soon as the draw that used it has ended. Android only: the
     * desktop has no hardware frame to hand out.
     *
     * Shares the mailbox with [acquireFrame]: one reader per player, either way.
     */
    fun acquireFrameBitmap(): FrameBitmap? {
        lastAcquireNanos = System.nanoTime()
        publishedSinceRead = false
        if (!presentingSaid && !visibilityReported) post { mailboxRead() }
        synchronized(mailbox) {
            if (!fresh) return null
            val image = latest ?: return null
            fresh = false
            val buffer = image.hardwareBuffer ?: return null
            val bitmap = buffer.use { Bitmap.wrapHardwareBuffer(it, ColorSpace.get(ColorSpace.Named.SRGB)) } ?: return null
            borrowed += image
            return FrameBitmap(image, bitmap, latestPts, ::giveBack)
        }
    }

    private fun giveBack(image: Image) {
        synchronized(mailbox) {
            borrowed -= image
            if (image !== latest) release(image)
        }
    }

    private fun release(image: Image) {
        val frames = bridge
        if (frames != null) frames.release(image) else image.close()
    }

    // -- Watching ----------------------------------------------------------------------

    @Volatile
    private var presentingSaid = false

    @Volatile
    private var visibilityReported = false

    private var presenting = true
    private var reportedVisible: Boolean? = null

    actual fun setPresenting(presenting: Boolean) {
        presentingSaid = true
        post {
            this.presenting = presenting
            applyWatch(presenting)
        }
    }

    actual fun reportVisible(visible: Boolean?) {
        if (presentingSaid) return
        visibilityReported = visible != null
        post {
            if (presentingSaid) return@post
            reportedVisible = visible
            applyWatch(visible ?: true)
        }
    }

    private fun mailboxRead() {
        if (presentingSaid || visibilityReported) return
        applyWatch(true)
    }

    /** The mailbox notice: a mailbox that was being read and has gone unread for [UNREAD_NANOS]. */
    private fun checkMailbox() {
        if (presentingSaid || visibilityReported || stopMode != null) return
        val read = lastAcquireNanos
        if (read != 0L && publishedSinceRead && System.nanoTime() - read > UNREAD_NANOS) applyWatch(false)
    }

    /** What [WhenUnwatched] says to do with the picture being taken or not. On the player thread. */
    private fun applyWatch(watched: Boolean) {
        if (!hasVideo) return
        if (clip != null) {
            applyClipWatch(watched)
            return
        }
        val p = player ?: return
        if (!watched && stopMode == null) {
            val mode = when (unwatched) {
                WhenUnwatched.Freeze -> StopMode.FROZE
                WhenUnwatched.KeepTime -> StopMode.KEPT
                WhenUnwatched.FollowSound -> if (audible()) StopMode.KEPT else StopMode.FROZE
            }
            if (mode == StopMode.FROZE && p.playWhenReady) {
                autoPaused = true
                p.pause()
            } else {
                setVideoDisabled(true)
            }
            stopMode = mode
        } else if (watched && stopMode != null) {
            if (autoPaused && !userPaused) p.play()
            autoPaused = false
            setVideoDisabled(false)
            stopMode = null
        }
        recomputeState()
    }

    private fun audible(): Boolean {
        val p = player ?: return false
        return audio && selectedAudioTrack != null && volumeField > 0f && p.playbackState != Player.STATE_ENDED
    }

    private fun setVideoDisabled(disabled: Boolean) {
        val p = player ?: return
        if (videoTrackDisabled == disabled) return
        videoTrackDisabled = disabled
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, disabled).build()
    }

    // -- Commands ----------------------------------------------------------------------

    private fun post(block: () -> Unit) {
        if (closing) return
        handler.post {
            if (closing) return@post
            try {
                block()
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    actual fun pause() = post {
        userPaused = true
        if (clip != null) freezeClip() else player?.pause()
        recomputeState()
    }

    actual fun resume() = post {
        userPaused = false
        if (clip != null) {
            resumeClip()
            return@post
        }
        val p = player ?: return@post
        // Not the way back from a stop the player made for a hidden picture:
        // it plays, and the picture stays off until it is being taken again.
        if (stopMode == StopMode.FROZE) {
            autoPaused = false
            setVideoDisabled(true)
            stopMode = StopMode.KEPT
        }
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
        p.play()
        recomputeState()
    }

    actual fun seek(ptsNanos: Long, exact: Boolean) = post { seekTo(ptsNanos.coerceAtLeast(0), exact) }

    actual fun seekBy(deltaNanos: Long, exact: Boolean) = post {
        val base = when {
            clip != null -> positionNanos()
            seekPending -> intendedNanos
            hasVideo -> lastPublishedPts
            else -> positionNanos()
        }
        seekTo((base + deltaNanos).coerceAtLeast(0), exact)
    }

    private fun seekTo(target: Long, exact: Boolean) {
        if (clip != null) {
            // Every frame of an image can be reached, so exact or not lands
            // on the frame showing at the target.
            landClip(target)
            recomputeState()
            return
        }
        val p = player ?: return
        intendedNanos = target
        seekPending = true
        p.setSeekParameters(if (exact) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
        p.seekTo(target / NANOS_PER_MILLI)
        sampleClock(target, System.nanoTime(), false)
        recomputeState()
    }

    actual fun stepForward() = post {
        clip?.let { c ->
            val next = c.shown + 1
            stepClip(if (next < c.frameCount) next else if (loopField) 0 else c.shown)
            return@post
        }
        val p = player ?: return@post
        userPaused = true
        p.pause()
        // The first frame at or after a millisecond past the one on screen is
        // the next one: frames are never closer than that.
        seekTo((lastPublishedPts / NANOS_PER_MILLI + 1) * NANOS_PER_MILLI, exact = true)
    }

    actual fun stepBackward() = post {
        clip?.let { c ->
            stepClip((c.shown - 1).coerceAtLeast(0))
            return@post
        }
        val p = player ?: return@post
        userPaused = true
        p.pause()
        val shown = lastPublishedPts
        val file = source
        // Which frame precedes the one on screen is only knowable by decoding
        // toward it, as on the desktop: run the platform decoder over the
        // stretch before it, off the player thread, and land an exact seek on
        // the answer.
        decodeWork.execute {
            val previous = runCatching { previousFramePts(file, shown) }
                .onFailure { Debug.trace("step backward discovery", it) }
                .getOrNull()
            post { seekTo(previous ?: (shown - NANOS_PER_MILLI).coerceAtLeast(0), exact = true) }
        }
    }

    private fun previousFramePts(file: Path, shown: Long): Long? {
        if (shown <= 0L) return 0L
        VideoDecoder.open(file, hardware).use { d ->
            d.seekBefore(shown)
            var previous: Long? = null
            while (true) {
                val frame = d.nextFrame(convert = false) ?: break
                if (frame.ptsNanos >= shown) break
                previous = frame.ptsNanos
            }
            return previous
        }
    }

    actual fun setVolume(volume: Float) {
        if (volume.isNaN()) return
        volumeField = volume.coerceIn(0f, 1f)
        post { if (audio) player?.volume = volumeField }
    }

    actual fun setRate(rate: Float) {
        if (rate.isNaN()) return
        val clamped = rate.coerceIn(0.5f, 4f)
        post {
            // Re-anchored at the old rate first, or the time since the last
            // anchor would be counted at the new one.
            if (clip != null) sampleClock(positionNanos(), System.nanoTime(), clockRunning)
            this.rate = clamped
            player?.playbackParameters = PlaybackParameters(clamped)
            scheduleClip()
        }
    }

    actual fun setSource(path: Path) {
        if (closing) return
        val picture = hasVideo
        decodeWork.execute {
            // Opened and decoded to a first frame before the player touches
            // it, so a file that will not play is refused and the one playing
            // carries on, as on the desktop.
            val refusal = runCatching {
                if (picture) {
                    VideoDecoder.open(path, hardware).use { it.nextFrame(convert = false) ?: error("$path gives no picture") }
                } else {
                    (AudioDecoder.openOrNull(path) ?: error("$path carries no sound")).close()
                }
            }.exceptionOrNull()
            if (refusal != null) {
                sourceFailure = refusal
                noteChange()
                return@execute
            }
            val image = picture && isImageFile(path)
            val info = if (image) null else ContainerInfo.read(Input.File(path))
            post {
                // A player that had ended plays the next file, as on the desktop.
                val ended = if (clip != null) clipEnded else player?.playbackState == Player.STATE_ENDED
                if (ended) userPaused = false
                sourceFailure = null
                source = path
                everReady = false
                seekPending = false
                lastPublishedPts = 0L
                // Subtitles go off with the file they belonged to, as on the desktop.
                externals.clear()
                selectedSubtitleId = null
                pendingSubtitleId = null
                currentCues = emptyList()
                publishState(State.Opening)
                if (info == null) {
                    player?.let {
                        it.stop()
                        it.clearMediaItems()
                    }
                    publishCues()
                    startClip(path)
                    return@post
                }
                closeClip()
                bridge?.acceptDecoderFrames(true)
                val p = player ?: buildPlayer()
                tags = info.tags
                coverArt = info.coverArt
                durationNanos = null
                sampleClock(0L, System.nanoTime(), false)
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                publishCues()
                p.setMediaItem(mediaItem(path))
                p.prepare()
                if (!userPaused) p.play()
            }
        }
    }

    actual fun selectAudioTrack(streamIndex: Int) = post {
        val p = player ?: return@post
        val group = p.currentTracks.groups.getOrNull(streamIndex) ?: return@post
        if (group.type != C.TRACK_TYPE_AUDIO) return@post
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0)).build()
    }

    // -- Subtitles ----------------------------------------------------------------------

    private class External(val path: Path, val mime: String, val track: SubtitleTrack)

    private val externals = CopyOnWriteArrayList<External>()
    private val externalIds = AtomicInteger(0)
    private var embeddedSubtitles: List<SubtitleTrack> = emptyList()

    @Volatile
    private var selectedSubtitleId: Int? = null
    private var pendingSubtitleId: Int? = null
    private var currentCues: List<Cue> = emptyList()

    @Volatile
    private var canvasWidth = 0

    @Volatile
    private var canvasHeight = 0

    @Volatile
    private var videoWidth = 0

    @Volatile
    private var videoHeight = 0

    private val overlayLock = Any()
    private var overlay: SubtitleOverlay? = null
    private var overlayFresh = false
    private var overlayGeneration = 0L

    /**
     * Appends [file]'s track under a negative id, as on the desktop. The file
     * is side-loaded into the playing item, which ExoPlayer can only do by
     * preparing it again at the current position: the picture holds while it
     * does, and the sound pauses for as long as that takes. Formats are told
     * by extension (.srt, .ass, .ssa, .vtt, .ttml); an unreadable file or one
     * of another kind adds nothing, and so does an image file playing: its
     * frames are drawn by this player, not by ExoPlayer, which is what lays
     * out the cues.
     */
    actual fun addExternalSubtitles(file: Path): List<SubtitleTrack> {
        if (closing || !hasVideo || clipActive) return emptyList()
        val mime = subtitleMimeFor(file) ?: return emptyList()
        if (!Files.isReadable(file)) return emptyList()
        val id = externalIds.decrementAndGet()
        val track = SubtitleTrack(
            id = id, streamIndex = 0, language = null, title = file.fileName?.toString(),
            codecName = mime, isText = true, isDefault = false, isForced = false, externalPath = file,
        )
        externals += External(file, mime, track)
        subtitleTracks = embeddedSubtitles + externals.map { it.track }
        post {
            val p = player ?: return@post
            val position = p.currentPosition
            val playing = p.playWhenReady
            p.setMediaItem(mediaItem(source), position)
            p.prepare()
            p.playWhenReady = playing
        }
        return listOf(track)
    }

    actual fun selectSubtitleTrack(id: Int?) = post {
        val p = player ?: return@post
        if (id == null) {
            selectedSubtitleId = null
            pendingSubtitleId = null
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            currentCues = emptyList()
            publishCues()
            return@post
        }
        val group = subtitleGroup(id, p.currentTracks)
        if (group == null) {
            // A side-loaded file whose preparation has not landed yet.
            if (subtitleTracks.any { it.id == id }) pendingSubtitleId = id
            return@post
        }
        selectSubtitles(group, id)
    }

    private fun selectSubtitles(group: Tracks.Group, id: Int) {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0)).build()
        selectedSubtitleId = id
        noteChange()
    }

    /** The text group a skinema id names: a group index, or a side-loaded file's tagged id. */
    private fun subtitleGroup(id: Int, tracks: Tracks): Tracks.Group? {
        val groups = tracks.groups
        if (id >= 0) return groups.getOrNull(id)?.takeIf { it.type == C.TRACK_TYPE_TEXT }
        val tag = EXTERNAL_PREFIX + (-id)
        return groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.getTrackFormat(0).id?.endsWith(tag) == true }
    }

    actual val activeSubtitleTrack: Int? get() = selectedSubtitleId

    actual fun acquireSubtitles(): SubtitleOverlay? = synchronized(overlayLock) {
        if (!overlayFresh) return null
        overlayFresh = false
        overlay
    }

    actual fun setSubtitleCanvasSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (width == canvasWidth && height == canvasHeight) return
        canvasWidth = width
        canvasHeight = height
        post { if (selectedSubtitleId != null) publishCues() }
    }

    /** The cues on screen, drawn at the announced canvas size or, before one, the video's own. */
    private fun publishCues() {
        val width = canvasWidth.takeIf { it > 0 } ?: videoWidth
        val height = canvasHeight.takeIf { it > 0 } ?: videoHeight
        val next = CueRasterizer.rasterize(currentCues, width, height)
        synchronized(overlayLock) {
            next.generation = ++overlayGeneration
            overlay = next
            overlayFresh = true
        }
        noteChange()
    }

    // -- Lifecycle --------------------------------------------------------------------

    private val decodeWork = Executors.newSingleThreadExecutor { r -> Thread(r, "skinema-player-work").apply { isDaemon = true } }

    init {
        handler.post { open(path, explicitClock, audioTrack, startPaused) }
    }

    /** The file, with every side-loaded subtitle file attached under its tagged id. */
    private fun mediaItem(path: Path): MediaItem = MediaItem.Builder()
        .setUri(Uri.fromFile(path.toFile()))
        .setSubtitleConfigurations(
            externals.map { e ->
                MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(e.path.toFile()))
                    .setMimeType(e.mime)
                    .setId(EXTERNAL_PREFIX + (-e.track.id))
                    .setLabel(e.track.title)
                    .build()
            },
        )
        .build()

    private fun open(path: Path, explicitClock: MediaClock?, audioTrack: Int?, startPaused: Boolean) {
        if (closing) return
        try {
            require(explicitClock == null) { "explicitClock is not supported on Android: ExoPlayer takes no external media clock" }
            bridge = FrameBridge(::onImage)
            if (isImageFile(path)) {
                startClip(path)
            } else {
                val info = ContainerInfo.read(Input.File(path))
                tags = info.tags
                coverArt = info.coverArt
                val p = buildPlayer()
                pendingAudioTrack = audioTrack
                p.setMediaItem(mediaItem(path))
                p.playWhenReady = !startPaused
                p.prepare()
            }
            handler.postDelayed(tick, TICK_MILLIS)
        } catch (t: Throwable) {
            fail(t)
        }
    }

    /**
     * ExoPlayer, wired to the bridge and carrying every setting made so far.
     * Built when the first file that is not an image arrives, so a player
     * that only ever shows images never starts one.
     */
    private fun buildPlayer(): ExoPlayer {
        val context = AppContext.get()
        val frames = checkNotNull(bridge)
        val renderers = object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink? = when {
                    // Ignored without sound, as on the desktop: nothing opens it.
                    sink != null && audio -> PcmSinkAudioSink(sink, audioChannels) { audioFormat = it }
                    audioChannels == ChannelPreference.Stereo -> DefaultAudioSink.Builder(context)
                        .setAudioProcessors(arrayOf(stereoFold()))
                        .build()
                    else -> super.buildAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParams)
                }
        }.setMediaCodecSelector(codecSelector(hardware)).setEnableDecoderFallback(hardware == HwAccel.AUTO)
        val p = ExoPlayer.Builder(context, renderers)
            .setLooper(thread.looper)
            .setMediaSourceFactory(DefaultMediaSourceFactory(FileDataSource.Factory()))
            .build()
        player = p
        p.setVideoSurface(frames.inputSurface)
        p.addListener(listener)
        p.addAnalyticsListener(analytics)
        p.setVideoFrameMetadataListener { presentationTimeUs, releaseTimeNs, _, _ ->
            if (clipActive) return@setVideoFrameMetadataListener
            val pts = presentationTimeUs * NANOS_PER_MICRO
            releaseToPts[releaseTimeNs] = pts
            sampleClock(pts, releaseTimeNs, clockRunning)
        }
        p.repeatMode = if (loopField) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        p.volume = if (audio) volumeField else 0f
        p.playbackParameters = PlaybackParameters(rate)
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !audio)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        return p
    }

    // -- Image files --------------------------------------------------------------

    private fun isImageFile(path: Path): Boolean =
        runCatching { Input.File(path).use { ImageFrames.isImage(it.head(16)) } }.getOrDefault(false)

    /** Puts the image at [path] on screen in place of whatever played, on its first frame. */
    private fun startClip(path: Path) {
        if (hardware == HwAccel.REQUIRE) throw LibavException("no hardware decoder takes an image (HwAccel.REQUIRE)")
        val image = ImageFrames.read(Files.readAllBytes(path))
        closeClip()
        val c = ImageFrameSource(image)
        clip = c
        clipActive = true
        hasVideo = true
        hardwareActive = false
        durationNanos = c.durationNanos()
        tags = emptyMap()
        coverArt = null
        audioTrackList = emptyList()
        selectedAudioTrack = null
        audioFormat = null
        embeddedSubtitles = emptyList()
        subtitleTracks = emptyList()
        videoWidth = image.width
        videoHeight = image.height
        bridge?.acceptDecoderFrames(false)
        bridge?.resize(image.width, image.height, redraw = false)
        landClip(0L)
        recomputeState()
    }

    private fun closeClip() {
        handler.removeCallbacks(clipAdvance)
        clip?.close()
        clip = null
        clipActive = false
        clipEnded = false
    }

    /** Whether the image's clock runs: an animation, not ended, and paused by nobody. */
    private fun clipRuns(): Boolean = clip?.animated == true && !clipEnded && !userPaused && !autoPaused

    /**
     * Shows the frame at [target], clamped to the lap, and anchors the clock
     * there. A still has one frame and no time: it reads Ended unless it
     * loops, as the desktop's does once its single frame has played, and the
     * clock never runs, where the desktop's turns a 40 ms lap over and over.
     */
    private fun landClip(target: Long) {
        val c = clip ?: return
        val at = if (c.animated) target.coerceIn(0L, c.lapNanos) else 0L
        c.show(c.frameAt(at))
        presentClip()
        clipEnded = !c.animated && !loopField
        sampleClock(at, System.nanoTime(), clipRuns())
        scheduleClip()
    }

    private fun stepClip(index: Int) {
        val c = clip ?: return
        userPaused = true
        freezeClip()
        landClip(c.ptsOf(index))
        recomputeState()
    }

    private fun freezeClip() {
        handler.removeCallbacks(clipAdvance)
        sampleClock(positionNanos(), System.nanoTime(), false)
    }

    private fun resumeClip() {
        // Not the way back from a stop the player made for a hidden picture,
        // as with a video: the clock runs and the frames stay off.
        if (stopMode == StopMode.FROZE) {
            autoPaused = false
            videoTrackDisabled = true
            stopMode = StopMode.KEPT
        }
        if (clipEnded) landClip(0L)
        if (clipRuns()) sampleClock(positionNanos(), System.nanoTime(), true)
        scheduleClip()
        recomputeState()
    }

    /** Draws the composed frame into the bridge, stamped so it comes out carrying its pts. */
    private fun presentClip() {
        val c = clip ?: return
        val frames = bridge ?: return
        val tag = maxOf(System.nanoTime(), lastClipTag + 1)
        lastClipTag = tag
        releaseToPts[tag] = c.ptsOf(c.shown)
        premultipliedTags += tag
        // A copy, since the next frame is composed onto the same canvas while
        // the bridge may still be uploading this one.
        frames.present(c.canvasBitmap.copy(Bitmap.Config.ARGB_8888, false), tag)
    }

    private var lastClipTag = 0L

    /** Wakes at the next frame's time, or at the end of the lap while the frames are off. */
    private fun scheduleClip() {
        handler.removeCallbacks(clipAdvance)
        val c = clip ?: return
        if (!clockRunning) return
        val next = c.shown + 1
        val event = if (!videoTrackDisabled && next < c.frameCount) c.ptsOf(next) else c.lapNanos
        val wallNanos = ((event - positionNanos()) / rate).toLong()
        // Rounded up, or the wake lands just short of the frame and spins.
        handler.postDelayed(clipAdvance, ((wallNanos + NANOS_PER_MILLI - 1) / NANOS_PER_MILLI).coerceAtLeast(0L))
    }

    private fun advanceClip() {
        val c = clip ?: return
        if (!clockRunning) return
        var position = positionNanos()
        val lap = c.lapNanos
        if (position >= lap) {
            if (!loopField) {
                sampleClock(lap, System.nanoTime(), false)
                clipEnded = true
                recomputeState()
                return
            }
            position %= lap
            sampleClock(position, System.nanoTime(), true)
        }
        if (!videoTrackDisabled) {
            val index = c.frameAt(position)
            if (index != c.shown) {
                c.show(index)
                presentClip()
            }
        }
        scheduleClip()
    }

    /** [applyWatch] for an image. Nothing is ever heard, so FollowSound stops time with the picture. */
    private fun applyClipWatch(watched: Boolean) {
        val c = clip ?: return
        if (!watched && stopMode == null) {
            val mode = if (unwatched == WhenUnwatched.KeepTime) StopMode.KEPT else StopMode.FROZE
            if (mode == StopMode.FROZE && clockRunning) {
                autoPaused = true
                freezeClip()
            } else {
                videoTrackDisabled = true
                scheduleClip()
            }
            stopMode = mode
        } else if (watched && stopMode != null) {
            val lift = autoPaused && !userPaused
            autoPaused = false
            if (videoTrackDisabled) {
                videoTrackDisabled = false
                // Catches up with the clock. An ended one keeps the frame it
                // had, as on the desktop: nothing decoded the end.
                val index = c.frameAt(positionNanos().coerceAtMost(c.lapNanos))
                if (!clipEnded && index != c.shown) {
                    c.show(index)
                    presentClip()
                }
            }
            if (lift) sampleClock(positionNanos(), System.nanoTime(), true)
            scheduleClip()
            stopMode = null
        }
        recomputeState()
    }

    private var pendingAudioTrack: Int? = null

    private val tick = object : Runnable {
        override fun run() {
            if (closing) return
            if (clip != null) {
                checkMailbox()
                handler.postDelayed(this, TICK_MILLIS)
                return
            }
            val p = player ?: return
            if (!hasVideo || stopMode != null) {
                sampleClock(p.currentPosition * NANOS_PER_MILLI, System.nanoTime(), p.isPlaying)
            } else {
                clockRunning = p.isPlaying
            }
            checkMailbox()
            handler.postDelayed(this, TICK_MILLIS)
        }
    }

    private fun recomputeState() {
        if (clip != null) {
            publishState(
                when {
                    clipEnded -> State.Ended
                    userPaused || autoPaused -> State.Paused
                    else -> State.Playing
                },
            )
            return
        }
        val p = player ?: return
        val next = when {
            p.playbackState == Player.STATE_IDLE || !everReady -> State.Opening
            seekPending -> State.Seeking
            p.playbackState == Player.STATE_ENDED -> State.Ended
            p.playbackState == Player.STATE_BUFFERING -> State.Seeking
            p.playWhenReady -> State.Playing
            else -> State.Paused
        }
        publishState(next)
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (clip != null) return
            val p = player ?: return
            if (playbackState == Player.STATE_READY) {
                everReady = true
                if (seekPending) {
                    seekPending = false
                    intendedNanos = p.currentPosition * NANOS_PER_MILLI
                }
                sampleClock(p.currentPosition * NANOS_PER_MILLI, System.nanoTime(), p.isPlaying)
            }
            if (playbackState == Player.STATE_ENDED) sampleClock(p.currentPosition * NANOS_PER_MILLI, System.nanoTime(), false)
            recomputeState()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (clip != null) return
            val p = player ?: return
            sampleClock(p.currentPosition * NANOS_PER_MILLI, System.nanoTime(), isPlaying)
            recomputeState()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = recomputeState()

        override fun onPlayerError(error: PlaybackException) {
            // ExoPlayer stands idle while an image plays; a late word from
            // the file it was stopped on is not this player's failure.
            if (clip == null) fail(error)
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (clip != null) return
            val duration = player?.duration ?: return
            durationNanos = if (duration == C.TIME_UNSET || duration < 0) null else duration * NANOS_PER_MILLI
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (clip != null) return
            if (videoSize.width > 0 && videoSize.height > 0) {
                videoWidth = videoSize.width
                videoHeight = videoSize.height
            }
            bridge?.resize(videoSize.width, videoSize.height)
        }

        override fun onCues(cueGroup: CueGroup) {
            if (clip != null) return
            if (selectedSubtitleId == null) return
            currentCues = cueGroup.cues
            publishCues()
        }

        override fun onTracksChanged(tracks: Tracks) {
            if (clip != null) return
            val groups = tracks.groups
            if (groups.any { it.type == C.TRACK_TYPE_VIDEO }) hasVideo = true
            audioTrackList = groups.mapIndexedNotNull { i, g ->
                if (g.type != C.TRACK_TYPE_AUDIO) return@mapIndexedNotNull null
                val f = g.getTrackFormat(0)
                AudioTrack(
                    streamIndex = i,
                    language = threeLetterLanguage(f.language),
                    title = f.label,
                    channels = f.channelCount.coerceAtLeast(0),
                    sampleRate = f.sampleRate.coerceAtLeast(0),
                    isDefault = f.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
                )
            }.takeIf { audio } ?: emptyList()
            selectedAudioTrack = groups.indices.firstOrNull { groups[it].type == C.TRACK_TYPE_AUDIO && groups[it].isSelected }
            pendingAudioTrack?.let { wanted ->
                pendingAudioTrack = null
                if (wanted != selectedAudioTrack) selectAudioTrack(wanted)
            }
            embeddedSubtitles = groups.mapIndexedNotNull { i, g ->
                if (g.type != C.TRACK_TYPE_TEXT) return@mapIndexedNotNull null
                val f = g.getTrackFormat(0)
                if (f.id?.contains(EXTERNAL_PREFIX) == true) return@mapIndexedNotNull null
                val mime = f.sampleMimeType ?: f.codecs ?: "text"
                SubtitleTrack(
                    id = i, streamIndex = i, language = threeLetterLanguage(f.language), title = f.label,
                    codecName = mime, isText = mime !in BITMAP_SUBTITLES,
                    isDefault = f.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
                    isForced = f.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
                )
            }
            // A frameless player takes no subtitles, as on the desktop: there is no picture to lay text over.
            subtitleTracks = if (hasVideo) embeddedSubtitles + externals.map { it.track } else emptyList()
            pendingSubtitleId?.let { wanted ->
                subtitleGroup(wanted, tracks)?.let { group ->
                    pendingSubtitleId = null
                    selectSubtitles(group, wanted)
                }
            }
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (clip != null) return
            sampleClock(newPosition.positionMs * NANOS_PER_MILLI, System.nanoTime(), player?.isPlaying == true)
        }
    }

    private val analytics = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            hardwareActive = runCatching {
                MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull { it.name == decoderName }?.isHardwareAccelerated
            }.getOrNull() == true
        }

        override fun onVideoDisabled(eventTime: AnalyticsListener.EventTime, decoderCounters: androidx.media3.exoplayer.DecoderCounters) {
            hardwareActive = false
        }

        override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
            val channels = Integer.bitCount(audioTrackConfig.channelConfig).coerceAtLeast(1)
            val (encoding, bits) = when (audioTrackConfig.encoding) {
                PlatformAudioFormat.ENCODING_PCM_8BIT -> PcmEncoding.U8 to 8
                PlatformAudioFormat.ENCODING_PCM_FLOAT -> PcmEncoding.F32LE to 32
                ENCODING_PCM_32BIT -> PcmEncoding.S32LE to 32
                ENCODING_PCM_24BIT_PACKED -> PcmEncoding.S32LE to 24
                else -> PcmEncoding.S16LE to 16
            }
            audioFormat = PcmFormat(audioTrackConfig.sampleRate, channels, layoutFor(channels), encoding, bits)
        }
    }

    private fun fail(cause: Throwable) {
        publishState(State.Failed(cause))
        handler.post {
            runCatching { player?.release() }
            player = null
            closeClip()
        }
    }

    actual fun closeAsync() {
        if (closing) return
        closing = true
        handler.post {
            runCatching { player?.release() }
            player = null
            closeClip()
            runCatching { bridge?.close() }
            bridge = null
            synchronized(mailbox) {
                latest?.close()
                latest = null
                borrowed.forEach(Image::close)
                borrowed.clear()
            }
            decodeWork.shutdownNow()
            synchronized(changeMonitor) {
                stateField = State.Closed
                changes++
                changeMonitor.notifyAll()
            }
            thread.quitSafely()
        }
    }

    actual override fun close() {
        closeAsync()
        thread.join(CLOSE_BUDGET_MILLIS)
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val NANOS_PER_MICRO = 1_000L
        const val TICK_MILLIS = 100L
        const val UNREAD_NANOS = 2_000_000_000L
        const val RELEASE_MAP_LIMIT = 64

        /** Tags a side-loaded subtitle file's format id, so its group can be found again. */
        const val EXTERNAL_PREFIX = "skinema-external-"

        /** The subtitle types Media3 decodes to pictures rather than text. */
        val BITMAP_SUBTITLES = setOf(MimeTypes.APPLICATION_PGS, MimeTypes.APPLICATION_DVBSUBS, MimeTypes.APPLICATION_VOBSUB)

        /**
         * Media3 normalises a track's language to a BCP 47 tag ("en"), where
         * the desktop reports the container's own three-letter ISO 639 tag
         * ("eng"). Turned back into three letters so shared code reads one
         * form on both. The platform gives the terminology code, so a file
         * tagged with a bibliographic one ("ger") reads "deu" here.
         */
        fun threeLetterLanguage(tag: String?): String? {
            if (tag.isNullOrBlank() || tag == "und") return null
            return runCatching { Locale.forLanguageTag(tag).isO3Language }.getOrNull()?.takeIf { it.isNotEmpty() } ?: tag
        }

        fun subtitleMimeFor(file: Path): String? = when (file.fileName?.toString()?.substringAfterLast('.')?.lowercase()) {
            "srt" -> MimeTypes.APPLICATION_SUBRIP
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "vtt" -> MimeTypes.TEXT_VTT
            "ttml", "dfxp" -> MimeTypes.APPLICATION_TTML
            else -> null
        }
        const val CLOSE_BUDGET_MILLIS = 3_000L

        /** Premultiplied RGBA back to straight, in place. Opaque and empty pixels are left as they are. */
        fun unpremultiply(rgba: ByteArray) {
            var i = 0
            while (i < rgba.size) {
                val a = rgba[i + 3].toInt() and 0xFF
                if (a in 1..254) {
                    for (c in 0..2) {
                        val v = rgba[i + c].toInt() and 0xFF
                        rgba[i + c] = ((v * 255 + a / 2) / a).coerceAtMost(255).toByte()
                    }
                }
                i += 4
            }
        }

        // AudioFormat constants that arrive at API 31, named for the floor of 29.
        const val ENCODING_PCM_24BIT_PACKED = 21
        const val ENCODING_PCM_32BIT = 22

        /** Folds any layout to two channels before the device is asked, which is what Stereo means. */
        fun stereoFold() = ChannelMixingAudioProcessor().apply {
            for (channels in 1..8) putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(channels, 2))
        }

        /**
         * The platform decoders a policy allows for video, the desktop's three
         * words: OFF software only, REQUIRE hardware only, AUTO the platform's
         * own order. Audio decoders are left alone: the policy is about video.
         */
        fun codecSelector(hardware: HwAccel) = MediaCodecSelector { mimeType, secure, tunneling ->
            val all = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
            if (!mimeType.startsWith("video/")) return@MediaCodecSelector all
            when (hardware) {
                HwAccel.OFF -> all.filter(MediaCodecInfo::softwareOnly)
                HwAccel.REQUIRE -> all.filter(MediaCodecInfo::hardwareAccelerated)
                HwAccel.AUTO -> all
            }
        }
    }
}
