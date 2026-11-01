package dev.hivens.skinema.libav

/** The picture of this input, as a frame source. */
internal fun ResolvedInput.openFrames(hardware: HwAccel): FrameSource = when (this) {
    is ResolvedInput.File -> FrameSources.open(path, hardware)
    is ResolvedInput.Seekable -> VideoDecoder.open(shared.cursor(), hardware)
    is ResolvedInput.Live -> throw LibavException(LIVE_NOT_YET)
}

/** The sound of this input, or null when it has none; same contract as [AudioDecoder.openOrNull]. */
internal fun ResolvedInput.openSound(streamIndex: Int?): AudioDecoder? = when (this) {
    is ResolvedInput.File -> AudioDecoder.openOrNull(path, streamIndex)
    is ResolvedInput.Seekable -> AudioDecoder.openOrNull(shared.cursor(), streamIndex)
    is ResolvedInput.Live -> throw LibavException(LIVE_NOT_YET)
}

internal const val LIVE_NOT_YET = "a stream that reads forward only does not play yet"
