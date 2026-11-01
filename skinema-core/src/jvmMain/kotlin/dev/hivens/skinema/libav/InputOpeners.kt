package dev.hivens.skinema.libav

/** The picture of this input, as a frame source. */
internal actual fun ResolvedInput.openFrames(hardware: HwAccel): FrameSource = when (this) {
    is ResolvedInput.File -> FrameSources.open(path, hardware)
    is ResolvedInput.Seekable -> VideoDecoder.open(shared.cursor(), hardware)
    is ResolvedInput.Live -> VideoDecoder.openLane(demux(), hardware)
}

/** The sound of this input, or null when it has none; same contract as [AudioDecoder.openOrNull]. */
internal actual fun ResolvedInput.openSound(streamIndex: Int?): AudioDecoder? = when (this) {
    is ResolvedInput.File -> AudioDecoder.openOrNull(path, streamIndex)
    is ResolvedInput.Seekable -> AudioDecoder.openOrNull(shared.cursor(), streamIndex)
    is ResolvedInput.Live -> AudioDecoder.openLane(demux(), streamIndex)
}

/** The shared demuxer over a forward-only stream, opened by whichever side asks first. */
internal fun ResolvedInput.Live.demux(): SharedDemux = attach { SharedDemux.open(it) }

/**
 * Tells a forward-only stream's demuxer that every side that is going to read
 * it has taken its lane, so the lanes it made ready for nobody can go.
 */
internal actual fun ResolvedInput.settle() {
    ((this as? ResolvedInput.Live)?.attachedOrNull() as? SharedDemux)?.settle()
}
