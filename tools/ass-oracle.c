/*
 * Offline oracle for AssAbi.kt -- compile against the libass major the
 * pin uses (soname 9) and transcribe. Not part of the build.
 *
 *   cc tools/ass-oracle.c -o /tmp/ass-oracle && /tmp/ass-oracle
 */
#include <stdio.h>
#include <stddef.h>
#include <ass/ass.h>

#define P(expr) printf("%-44s = %lld\n", #expr, (long long)(expr))

int main(void) {
    P(LIBASS_VERSION);

    P(offsetof(ASS_Image, w));
    P(offsetof(ASS_Image, h));
    P(offsetof(ASS_Image, stride));
    P(offsetof(ASS_Image, bitmap));
    P(offsetof(ASS_Image, color));
    P(offsetof(ASS_Image, dst_x));
    P(offsetof(ASS_Image, dst_y));
    P(offsetof(ASS_Image, next));
    P(sizeof(ASS_Image));

    P(ASS_FONTPROVIDER_AUTODETECT);
    return 0;
}
