package dev.hivens.skinema.libav

import dev.hivens.skinema.android.ForwardTape
import dev.hivens.skinema.android.Input

internal actual fun ResolvedInput.openFrames(hardware: HwAccel): FrameSource = when (this) {
    is ResolvedInput.File -> VideoDecoder.open(path, hardware)
    is ResolvedInput.Seekable -> VideoDecoder.open(shared.cursor(), hardware)
    is ResolvedInput.Live -> VideoDecoder.openFrom(Input.Stream(attach { ForwardTape(it) }, owns = false), hardware)
}

/**
 * A forward-only stream feeds one decoder on Android: there is no shared
 * demuxer here, and two readers over one tape part ways as soon as one of
 * them runs past what it keeps. So such a stream gives its picture, and its
 * sound is refused by name.
 */
internal actual fun ResolvedInput.openSound(streamIndex: Int?): AudioDecoder? = when (this) {
    is ResolvedInput.File -> AudioDecoder.openOrNull(path, streamIndex)
    is ResolvedInput.Seekable -> AudioDecoder.openOrNull(shared.cursor(), streamIndex)
    is ResolvedInput.Live -> throw LibavException(
        "on Android a stream that reads forward only feeds one decoder, and its picture already has it",
    )
}

internal actual fun ResolvedInput.settle() {}
