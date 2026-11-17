/*
 * dampf's own <stdint.h>. dampf preprocesses with -nostdinc, so host headers never leak in.
 *
 * The typedefs use the same underlying types as gcc and clang on the PC (char 8, short 16,
 * int 32 bit). dampf gives these types exactly their PC meaning, which is what makes the
 * integer promotions and overflow behave bit-identically on both sides. Writing plain `int`
 * etc. in game code is still rejected by the subset checker.
 */
#ifndef DAMPF_STDINT_H
#define DAMPF_STDINT_H

typedef signed char        int8_t;
typedef unsigned char      uint8_t;
typedef short              int16_t;
typedef unsigned short     uint16_t;
typedef int                int32_t;
typedef unsigned int       uint32_t;

#define INT8_MIN   (-128)
#define INT8_MAX   127
#define UINT8_MAX  255
#define INT16_MIN  (-32767 - 1)
#define INT16_MAX  32767
#define UINT16_MAX 65535
#define INT32_MIN  (-2147483647 - 1)
#define INT32_MAX  2147483647
#define UINT32_MAX 4294967295u

#define INT8_C(v)   (v)
#define UINT8_C(v)  (v)
#define INT16_C(v)  (v)
#define UINT16_C(v) (v)
#define INT32_C(v)  (v)
#define UINT32_C(v) (v ## u)

#endif
