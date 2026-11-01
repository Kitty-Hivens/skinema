package dev.hivens.skinema.encode

/**
 * Video encode parameters. [codecName] is an encoder name or a family name.
 *
 * A family name ("h264", "hevc", "av1", "vp9", "vp8") means the same on both
 * platforms and lets the platform pick the encoder: on the desktop the
 * hardware one first where it opens and the bundle's software one otherwise,
 * on Android the device's encoder for that codec. It is what shared code
 * should name. An encoder name ("libx264", "h264_vaapi", "libsvtav1", ...)
 * pins one FFmpeg encoder on the desktop and is mapped onto its codec on
 * Android. [options] are the encoder's private options ("crf", "preset", ...)
 * and apply on the desktop only. The container is inferred from the output
 * file's extension.
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
 * Audio encode parameters. [codecName] is an encoder name or a family name
 * ("aac", "opus", "flac"), with the meaning [VideoEncodeConfig] gives the two;
 * a .wav file takes "pcm_s16le". Input is interleaved S16LE STEREO at
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

/** The same config under another codec name: how a family name is tried against each encoder it stands for. */
internal fun VideoEncodeConfig.withCodec(name: String) = VideoEncodeConfig(name, width, height, fps, bitRate, options, device)

internal fun AudioEncodeConfig.withCodec(name: String) = AudioEncodeConfig(name, sampleRate, bitRate, options)
