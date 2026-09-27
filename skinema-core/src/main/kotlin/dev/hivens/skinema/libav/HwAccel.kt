package dev.hivens.skinema.libav

/**
 * Hardware-decode policy for a [VideoDecoder] / player.
 *
 * [OFF] is pure software decode -- the historical behaviour and the only
 * CI-tested path (a headless runner has no GPU). [AUTO] tries the
 * platform's GPU decoder (VAAPI/NVDEC on Linux, D3D11VA/DXVA2 on Windows,
 * VideoToolbox on macOS) and falls back to software per file when no
 * device or codec support is present. That fallback is decided at the open
 * or, at the latest, before the first frame: a device can accept a stream
 * and its hwaccel still fail to initialise for it, and a decoder with no
 * software path of its own (FFmpeg's AV1) is then replaced by the software
 * one before anything was shown. Once a frame has come off the GPU, a
 * hardware error mid-stream (a frame that cannot be downloaded off the
 * device) ends playback as
 * [dev.hivens.skinema.player.VideoPlayer.State.Failed] like any other decode
 * error. It is not a silent switch back to software: a mid-stream software
 * re-open is a future capability. [REQUIRE] turns the fallback into a hard
 * failure, at either moment: a file that cannot decode on the GPU surfaces
 * as Failed, or, for a player's [dev.hivens.skinema.player.VideoPlayer.setSource],
 * as a refused switch. The second moment is the one that matters in
 * practice, since it is past every check the open could make.
 *
 * Hardware frames are downloaded to a software format and run through the
 * existing swscale chokepoint, so the RGBA8888 output contract is
 * unchanged whichever path a frame took.
 *
 * The device may decode with a different decoder than software would:
 * libdav1d (AV1) and libvpx (8-bit VP8/VP9) have no hardware path, so the
 * device is offered FFmpeg's own decoder for those codecs. A VP8/VP9 stream
 * carrying webm alpha stays on libvpx, because the GPU would drop the alpha:
 * it decodes in software under [AUTO] and fails under [REQUIRE]. A build
 * without libvpx reads that alpha nowhere, so there the stream is offered to
 * the device like any other.
 */
enum class HwAccel { OFF, AUTO, REQUIRE }
