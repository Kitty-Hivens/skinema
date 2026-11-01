package dev.hivens.skinema.libav

/** The picture of this input, as a frame source, on whichever reader the platform has for its kind. */
internal expect fun ResolvedInput.openFrames(hardware: HwAccel): FrameSource

/** The sound of this input, or null when it has none; same contract as [AudioDecoder.openOrNull]. */
internal expect fun ResolvedInput.openSound(streamIndex: Int?): AudioDecoder?

/**
 * Says every side that is going to read a forward-only stream has opened, so
 * the platform's shared reader can let go of what it kept ready for nobody.
 */
internal expect fun ResolvedInput.settle()
