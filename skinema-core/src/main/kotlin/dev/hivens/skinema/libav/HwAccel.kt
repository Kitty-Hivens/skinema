package dev.hivens.skinema.libav

/**
 * Hardware-decode policy for a [VideoDecoder] / player.
 *
 * [OFF] is pure software decode -- the historical behaviour and the only
 * CI-tested path (a headless runner has no GPU). [AUTO] tries the
 * platform's GPU decoder (VAAPI/NVDEC on Linux, D3D11VA/DXVA2 on Windows,
 * VideoToolbox on macOS) and falls back to software per file when no
 * device or codec support is present. That fallback is decided at OPEN
 * time: once decoding is on the GPU, a hardware error mid-stream (a frame
 * that cannot be downloaded off the device) ends playback as
 * [dev.hivens.skinema.player.VideoPlayer.State.Failed] like any other
 * decode error -- it is not a silent switch back to software (a mid-stream
 * software re-open is a future capability). [REQUIRE] turns the open-time
 * fallback into a hard failure too: a file that cannot decode on the GPU
 * surfaces as Failed.
 *
 * Hardware frames are downloaded to a software format and run through the
 * existing swscale chokepoint, so the RGBA8888 output contract is
 * unchanged whichever path a frame took.
 */
enum class HwAccel { OFF, AUTO, REQUIRE }
