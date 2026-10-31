package dev.hivens.skinema.encode

/**
 * Video encode parameters. [codecName] is an FFmpeg encoder name
 * ("libx264", "libx265", "libsvtav1", "libvpx-vp9", ...); [options] are
 * its private options ("crf", "preset", ...). The container muxer is
 * inferred from the output file's extension.
 *
 * A hardware encoder ("h264_vaapi", "hevc_vaapi", ...) is detected from
 * its codec descriptor and driven on the GPU: [MediaWriter] opens a
 * hardware device, builds a surface pool, and uploads each frame before
 * encoding. [device] names the device to open (a VAAPI render node such as
 * "/dev/dri/renderD128"); null lets the driver pick its default. It is
 * ignored for a software encoder. Hardware encode is fail-closed -- if the
 * device or an upload is refused the writer throws, it does not silently
 * fall back to software.
 */
class VideoEncodeConfig(
    val codecName: String,
    val width: Int,
    val height: Int,
    /** Nominal frame rate -- the encoder's rate-control hint, not a hard cadence. */
    val fps: Int,
    /** Target bitrate in bits/s; 0 leaves the encoder on its own quality default (crf). */
    val bitRate: Long = 0,
    val options: Map<String, String> = emptyMap(),
    /** Hardware device to open for a GPU encoder (e.g. a VAAPI render node); null = driver default. */
    val device: String? = null,
)

/**
 * Audio encode parameters. [codecName] is an FFmpeg encoder name ("aac",
 * "libopus", "flac", ...). Input is interleaved S16LE STEREO at
 * [sampleRate] -- the [dev.hivens.skinema.libav.AudioDecoder] output shape
 * -- encoded at the same rate; no resampling, only a conversion into the
 * sample format and channel layout the encoder advertises. Stereo is kept
 * wherever the encoder takes it, so the ordinary case converts the format
 * alone; an encoder that takes only mono (or only surround) is given what it
 * takes, mixed by swresample. [options] are the encoder's private options.
 */
class AudioEncodeConfig(
    val codecName: String,
    val sampleRate: Int,
    /** Target bitrate in bits/s; 0 leaves the encoder on its own default. */
    val bitRate: Long = 0,
    val options: Map<String, String> = emptyMap(),
)
