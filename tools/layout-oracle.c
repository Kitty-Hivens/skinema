/*
 * Offline oracle for the per-major struct offset tables in skinema-core.
 * Compile against the pinned FFmpeg major's headers and transcribe the
 * output into LibavOffsets.kt. Not part of the build; re-run on every
 * major bump.
 *
 *   cc tools/layout-oracle.c -o /tmp/layout-oracle && /tmp/layout-oracle
 */
#include <stdio.h>
#include <stddef.h>
#include <errno.h>
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/frame.h>
#include <libavutil/log.h>
#include <libavutil/pixfmt.h>
#include <libswscale/swscale.h>
#include <libswresample/swresample.h>

#define P(expr) printf("%-44s = %lld\n", #expr, (long long)(expr))

int main(void) {
    P(LIBAVUTIL_VERSION_MAJOR);
    P(LIBSWRESAMPLE_VERSION_MAJOR);
    P(LIBSWSCALE_VERSION_MAJOR);
    P(LIBAVCODEC_VERSION_MAJOR);
    P(LIBAVFORMAT_VERSION_MAJOR);

    P(offsetof(AVFormatContext, nb_streams));
    P(offsetof(AVFormatContext, streams));
    P(offsetof(AVFormatContext, duration));
    P(sizeof(AVFormatContext));

    P(offsetof(AVStream, time_base));
    P(offsetof(AVStream, codecpar));
    P(offsetof(AVStream, duration));
    P(sizeof(AVStream));

    P(offsetof(AVPacket, stream_index));
    P(sizeof(AVPacket));

    P(offsetof(AVCodecParameters, codec_id));
    P(sizeof(AVCodecParameters));

    P(offsetof(AVFrame, data));
    P(offsetof(AVFrame, linesize));
    P(offsetof(AVFrame, width));
    P(offsetof(AVFrame, height));
    P(offsetof(AVFrame, format));
    P(offsetof(AVFrame, pts));
    P(offsetof(AVFrame, best_effort_timestamp));
    P(offsetof(AVFrame, nb_samples));
    P(offsetof(AVFrame, sample_rate));
    P(offsetof(AVFrame, ch_layout));
    P(offsetof(AVChannelLayout, nb_channels));
    P(sizeof(AVFrame));

    P(AVMEDIA_TYPE_VIDEO);
    P(AVMEDIA_TYPE_AUDIO);
    P(AV_SAMPLE_FMT_S16);
    P(AV_PIX_FMT_RGBA);
    P(SWS_BILINEAR);
    P(AVSEEK_FLAG_BACKWARD);
    P(AV_CODEC_ID_VP8);
    P(AV_CODEC_ID_VP9);
    P(AV_LOG_QUIET);
    P(AVERROR(EAGAIN));
    P(AVERROR_EOF);
    P(AVERROR_INVALIDDATA);
    P(AV_NOPTS_VALUE);
    return 0;
}
