package dev.hivens.skinema.libav

/** The byte stream did not decode, or a libav call refused it. */
open class LibavException(message: String) : RuntimeException(message)

/**
 * The file carries nothing to show: no video stream at all, or only an
 * attached picture. Not a failure -- a player answers it by playing the
 * sound frameless -- which is exactly why it is its own type. Read off the
 * base type instead, every OTHER way an open can fail reads as "no video"
 * too: an undecodable codec, a truncated file with no dimensions, a refused
 * hardware-decode request. Those are failures, and turning them into silent
 * audio-only playback is the opposite of failing closed.
 */
class NoVideoStreamException(message: String) : LibavException(message)
