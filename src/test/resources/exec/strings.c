/* Strings: adjacent literals are joined (also through a macro, as the game's version text is). */
#include <stdint.h>

#define VERSION "1.4.0"

static const char *const version = "VERSION " VERSION;
static const char joined[] = "AB" "CD"
                             "EF";
static const char escapes[] = "a\tb\"c\\d\x41";

char out[16];
uint8_t version_length;
uint8_t joined_size;
uint8_t escape_sum;

static uint8_t length(const char *s)
{
    uint8_t n = 0;
    while (s[n]) {
        n++;
    }
    return n;
}

void test_main(void)
{
    uint8_t i;

    for (i = 0; version[i]; i++) {
        out[i] = version[i];
    }
    version_length = length(version);
    joined_size = (uint8_t)sizeof joined;
    for (i = 0; i < sizeof escapes - 1; i++) {
        escape_sum = (uint8_t)(escape_sum + (uint8_t)escapes[i]);
    }
}
