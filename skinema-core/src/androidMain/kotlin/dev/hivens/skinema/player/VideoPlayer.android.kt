package dev.hivens.skinema.player

import dev.hivens.skinema.audio.ChannelPreference
import dev.hivens.skinema.audio.PcmFormat
import dev.hivens.skinema.audio.PcmSink
import dev.hivens.skinema.core.MediaClock
import dev.hivens.skinema.libav.AudioTrack
import dev.hivens.skinema.libav.Chapter
import dev.hivens.skinema.libav.HwAccel
import dev.hivens.skinema.libav.SubtitleTrack
import dev.hivens.skinema.subtitles.SubtitleOverlay
import java.nio.file.Path

/** Not implemented on Android yet: every player settles [State.Failed] at once. */
actual class VideoPlayer actual constructor(
    path: Path,
    loop: Boolean,
    audio: Boolean,
    explicitClock: MediaClock?,
    sink: PcmSink?,
    readAheadFrames: Int,
    audioTrack: Int?,
    hardware: HwAccel,
    unwatched: WhenUnwatched,
    startPaused: Boolean,
    volume: Float,
    audioChannels: ChannelPreference,
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

    @Volatile
    private var stateField: State = State.Failed(UnsupportedOperationException("VideoPlayer is not implemented on Android yet"))

    actual val state: State get() = stateField
    actual var durationNanos: Long? = null
        private set
    actual val audioTracks: List<AudioTrack> get() = emptyList()
    actual val activeAudioTrack: Int? get() = null
    actual val activeAudioFormat: PcmFormat? get() = null
    actual var tags: Map<String, String> = emptyMap()
        private set
    actual var chapters: List<Chapter> = emptyList()
        private set
    actual var coverArt: ByteArray? = null
        private set
    actual var rotationDegrees: Int = 0
        private set
    actual var hardwareActive: Boolean = false
        private set
    actual var subtitleTracks: List<SubtitleTrack> = emptyList()
        private set

    actual fun acquireFrame(): FrameSlot? = null
    actual val changeCount: Long get() = 0L
    @Throws(InterruptedException::class)
    actual fun awaitChange(since: Long, timeoutNanos: Long): Long = 0L
    actual fun setPresenting(presenting: Boolean) {}
    actual fun reportVisible(visible: Boolean?) {}
    actual fun pause() {}
    actual fun resume() {}
    actual fun seek(ptsNanos: Long, exact: Boolean) {}
    actual fun seekBy(deltaNanos: Long, exact: Boolean) {}
    actual fun stepForward() {}
    actual fun stepBackward() {}
    actual fun setVolume(volume: Float) {}
    actual var rate: Float = 1f
        private set
    actual fun setRate(rate: Float) {}
    actual var loop: Boolean = loop
    actual var source: Path = path
        private set
    actual var sourceFailure: Throwable? = null
        private set
    actual fun setSource(path: Path) {}
    actual fun selectAudioTrack(streamIndex: Int) {}
    actual fun addExternalSubtitles(file: Path): List<SubtitleTrack> = emptyList()
    actual fun selectSubtitleTrack(id: Int?) {}
    actual val activeSubtitleTrack: Int? get() = null
    actual fun acquireSubtitles(): SubtitleOverlay? = null
    actual fun setSubtitleCanvasSize(width: Int, height: Int) {}
    actual fun positionNanos(): Long = 0L
    actual fun closeAsync() {
        stateField = State.Closed
    }

    actual override fun close() = closeAsync()
}
