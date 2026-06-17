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
#include <libavfilter/avfilter.h>
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
    P(LIBAVFILTER_VERSION_MAJOR);

    P(offsetof(AVFormatContext, nb_streams));
    P(offsetof(AVFormatContext, streams));
    P(offsetof(AVFormatContext, nb_chapters));
    P(offsetof(AVFormatContext, chapters));
    P(offsetof(AVFormatContext, start_time));
    P(offsetof(AVFormatContext, duration));
    P(offsetof(AVFormatContext, metadata));
    P(sizeof(AVFormatContext));

    P(offsetof(AVStream, time_base));
    P(offsetof(AVStream, codecpar));
    P(offsetof(AVStream, duration));
    P(offsetof(AVStream, disposition));
    P(offsetof(AVStream, metadata));
    P(offsetof(AVStream, attached_pic));
    P(sizeof(AVStream));

    P(offsetof(AVChapter, time_base));
    P(offsetof(AVChapter, start));
    P(offsetof(AVChapter, end));
    P(offsetof(AVChapter, metadata));
    P(sizeof(AVChapter));

    P(offsetof(AVPacket, data));
    P(offsetof(AVPacket, size));
    P(offsetof(AVPacket, stream_index));
    P(offsetof(AVPacket, pts));
    P(offsetof(AVPacket, duration));
    P(sizeof(AVPacket));

    P(offsetof(AVSubtitle, format));
    P(offsetof(AVSubtitle, start_display_time));
    P(offsetof(AVSubtitle, end_display_time));
    P(offsetof(AVSubtitle, num_rects));
    P(offsetof(AVSubtitle, rects));
    P(sizeof(AVSubtitle));

    P(offsetof(AVSubtitleRect, x));
    P(offsetof(AVSubtitleRect, y));
    P(offsetof(AVSubtitleRect, w));
    P(offsetof(AVSubtitleRect, h));
    P(offsetof(AVSubtitleRect, nb_colors));
    P(offsetof(AVSubtitleRect, data));
    P(offsetof(AVSubtitleRect, linesize));
    P(offsetof(AVSubtitleRect, type));
    P(offsetof(AVSubtitleRect, text));
    P(offsetof(AVSubtitleRect, ass));
    P(sizeof(AVSubtitleRect));

    P(offsetof(AVCodecContext, subtitle_header));
    P(offsetof(AVCodecContext, subtitle_header_size));
    P(sizeof(AVCodecContext));

    P(AVMEDIA_TYPE_SUBTITLE);
    P(AVMEDIA_TYPE_ATTACHMENT);
    P(AV_DISPOSITION_FORCED);
    P(SUBTITLE_BITMAP);
    P(SUBTITLE_TEXT);
    P(SUBTITLE_ASS);
    P(AV_CODEC_ID_ASS);
    P(AV_CODEC_ID_SSA);
    P(AV_CODEC_ID_SUBRIP);
    P(AV_CODEC_ID_MOV_TEXT);
    P(AV_CODEC_ID_WEBVTT);
    P(AV_CODEC_ID_HDMV_PGS_SUBTITLE);
    P(AV_CODEC_ID_DVD_SUBTITLE);

    P(AV_DISPOSITION_ATTACHED_PIC);
    P(AV_DICT_IGNORE_SUFFIX);

    P(offsetof(AVCodecParameters, codec_type));
    P(offsetof(AVCodecParameters, codec_id));
    P(offsetof(AVCodecParameters, ch_layout));
    P(offsetof(AVCodecParameters, sample_rate));
    P(offsetof(AVCodecParameters, coded_side_data));
    P(offsetof(AVCodecParameters, nb_coded_side_data));
    P(offsetof(AVCodecParameters, extradata));
    P(offsetof(AVCodecParameters, extradata_size));
    P(offsetof(AVCodecParameters, width));
    P(offsetof(AVCodecParameters, height));
    P(sizeof(AVCodecParameters));

    P(offsetof(AVPacketSideData, data));
    P(offsetof(AVPacketSideData, size));
    P(offsetof(AVPacketSideData, type));
    P(sizeof(AVPacketSideData));
    P(AV_PKT_DATA_DISPLAYMATRIX);

    P(offsetof(AVDictionaryEntry, key));
    P(offsetof(AVDictionaryEntry, value));

    P(AV_DISPOSITION_DEFAULT);

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
    P(offsetof(AVFrame, color_range));
    P(offsetof(AVFrame, color_primaries));
    P(offsetof(AVFrame, color_trc));
    P(offsetof(AVFrame, colorspace));
    P(offsetof(AVChannelLayout, nb_channels));
    P(sizeof(AVFrame));

    P(AVCOL_SPC_BT709);
    P(AVCOL_SPC_UNSPECIFIED);
    P(AVCOL_SPC_FCC);
    P(AVCOL_SPC_BT470BG);
    P(AVCOL_SPC_SMPTE170M);
    P(AVCOL_SPC_SMPTE240M);
    P(AVCOL_SPC_BT2020_NCL);
    P(AVCOL_SPC_BT2020_CL);
    P(AVCOL_RANGE_JPEG);
    P(SWS_CS_ITU709);
    P(SWS_CS_FCC);
    P(SWS_CS_ITU601);
    P(SWS_CS_SMPTE240M);
    P(SWS_CS_BT2020);

    P(AVMEDIA_TYPE_VIDEO);
    P(AVMEDIA_TYPE_AUDIO);
    P(AV_SAMPLE_FMT_S16);
    P(AV_PIX_FMT_RGBA);
    P(AV_PIX_FMT_RGBA64LE);
    P(AVCOL_TRC_BT709);
    P(AVCOL_TRC_UNSPECIFIED);
    P(AVCOL_TRC_BT2020_10);
    P(AVCOL_TRC_BT2020_12);
    P(AVCOL_TRC_SMPTE2084);
    P(AVCOL_TRC_ARIB_STD_B67);
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
