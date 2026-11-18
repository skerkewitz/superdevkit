/*
 * <string.h> for dampf: memset and memcpy, the two a game needs to clear and copy memory.
 *
 * They are static functions in the header, so every file that includes it has its own copy and
 * none of them clashes with another; dampf compiles only what is called. The sizes are 16 bits
 * and the value a byte, since dampf has no plain int and no object is larger than 32 KB (ADR
 * record 10); a call written for the standard header (memset(buf, 0, sizeof buf)) works the same
 * way. On the PC the host's <string.h> comes first (gcc -idirafter), and does the same.
 *
 * Written in C for now; a copy with MVN or DMA comes with the fine tuning (roadmap M12).
 */
#ifndef DAMPF_STRING_H
#define DAMPF_STRING_H

#include <stdint.h>

static void *memset(void *s, uint8_t c, uint16_t n)
{
    uint8_t *p = (uint8_t *)s;
    uint16_t i;
    for (i = 0; i < n; i++) {
        p[i] = c;
    }
    return s;
}

static void *memcpy(void *d, const void *s, uint16_t n)
{
    uint8_t *to = (uint8_t *)d;
    const uint8_t *from = (const uint8_t *)s;
    uint16_t i;
    for (i = 0; i < n; i++) {
        to[i] = from[i];
    }
    return d;
}

#endif
