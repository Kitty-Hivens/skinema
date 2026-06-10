/*
 * Offline oracle for WebpAbi.kt -- compile against the libwebpdemux major
 * the pin uses and transcribe. Not part of the build.
 *
 *   cc tools/webp-oracle.c -o /tmp/webp-oracle && /tmp/webp-oracle
 */
#include <stdio.h>
#include <stddef.h>
#include <webp/demux.h>

#define P(expr) printf("%-44s = %lld\n", #expr, (long long)(expr))

int main(void) {
    P(WEBP_DEMUX_ABI_VERSION);
    P(MODE_RGBA);

    P(offsetof(WebPData, bytes));
    P(offsetof(WebPData, size));
    P(sizeof(WebPData));

    P(offsetof(WebPAnimDecoderOptions, color_mode));
    P(offsetof(WebPAnimDecoderOptions, use_threads));
    P(sizeof(WebPAnimDecoderOptions));

    P(offsetof(WebPAnimInfo, canvas_width));
    P(offsetof(WebPAnimInfo, canvas_height));
    P(offsetof(WebPAnimInfo, loop_count));
    P(offsetof(WebPAnimInfo, frame_count));
    P(sizeof(WebPAnimInfo));
    return 0;
}
