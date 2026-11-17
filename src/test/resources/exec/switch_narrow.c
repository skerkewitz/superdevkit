/*
 * A switch on a uint8_t, int8_t or uint16_t compares 16 bits when every case fits the value's
 * type (C widens it to int first). Cases at the ends of the ranges, and switches with a case
 * outside the range (not narrowed: that case never matches), give the same results as on the PC.
 */
#include <stdint.h>

uint8_t ubytes[6] = {0, 1, 7, 128, 255, 9};
int8_t sbytes[6] = {0, -1, -128, 127, 5, -7};
uint16_t words[5] = {0, 300, 65535, 32768, 4};
uint16_t r_u8;
uint16_t r_s8;
uint16_t r_u16;
uint16_t r_wide;
uint16_t r_wide_s;

static uint16_t kind_u8(uint8_t k)
{
    switch (k) {
    case 0: return 3;
    case 1: return 5;
    case 7: return 11;
    case 128: return 17;
    case 255: return 23;
    default: return 1;
    }
}

static uint16_t kind_s8(int8_t k)
{
    switch (k) {
    case -128: return 3;
    case -1: return 5;
    case 0: return 7;
    case 127: return 11;
    default: return 2;
    }
}

static uint16_t kind_u16(uint16_t k)
{
    switch (k) {
    case 300: return 13;
    case 65535: return 19;
    case 32768: return 29;
    default: return 4;
    }
}

static uint16_t kind_wide(uint8_t k)
{
    switch (k) {
    case 7: return 31;
    case 263: return 37;   /* 7 + 256: never matches */
    case -1: return 41;    /* never matches */
    case 65545: return 43; /* 9 + 65536: never matches, but 9 in 16 bits */
    default: return 6;
    }
}

static uint16_t kind_wide_s(int8_t k)
{
    switch (k) {
    case 65535: return 47; /* never matches, but -1 in 16 bits */
    case 5: return 53;
    default: return 8;
    }
}

void test_main(void)
{
    uint8_t i;
    for (i = 0; i < 6; i++) {
        r_u8 = (uint16_t)(r_u8 * 7u + kind_u8(ubytes[i]));
        r_s8 = (uint16_t)(r_s8 * 7u + kind_s8(sbytes[i]));
        r_wide = (uint16_t)(r_wide * 7u + kind_wide(ubytes[i]));
        r_wide_s = (uint16_t)(r_wide_s * 7u + kind_wide_s(sbytes[i]));
    }
    for (i = 0; i < 5; i++) {
        r_u16 = (uint16_t)(r_u16 * 7u + kind_u16(words[i]));
    }
}
