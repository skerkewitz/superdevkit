/* Casts from pointers to integers. The number itself is the SNES address (bank << 16 | address)
 * on the SNES and something else on the PC, so this only checks what both agree on: distances
 * within one object, through 32- and 16-bit casts, for RAM, ROM, locals and parameters. */
#include <stdint.h>

#ifndef __DAMPF__
#pragma GCC diagnostic ignored "-Wpointer-to-int-cast"
#endif

uint8_t ram[40];
static const uint16_t rom[12] = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
struct pair { uint8_t a; uint8_t c; uint16_t b; } pairs[3];   /* no padding: the same layout on both */

uint32_t d_ram;
uint32_t d_rom;
uint16_t d16_ram;
uint16_t d16_rom;
uint8_t d8;
uint32_t d_param;
uint16_t d_member;
uint8_t same;
uint16_t d_local;

static uint32_t dist(const uint8_t *from, const uint8_t *to)
{
    return (uint32_t)to - (uint32_t)from;
}

static uint16_t dist16(const void *from, const void *to)
{
    return (uint16_t)((uint16_t)to - (uint16_t)from);
}

void test_main(void)
{
    uint8_t local[6];
    const uint16_t *r = &rom[2];
    uint8_t i;
    d_ram = (uint32_t)&ram[37] - (uint32_t)ram;
    d_rom = (uint32_t)(r + 7) - (uint32_t)r;
    d16_ram = (uint16_t)((uint16_t)&ram[20] - (uint16_t)&ram[3]);
    d16_rom = dist16(&rom[1], &rom[11]);
    d8 = (uint8_t)((uint8_t)(&ram[9]) - (uint8_t)(&ram[2]));
    d_param = 0;
    for (i = 0; i < 4; i++) {
        d_param += dist(ram, &ram[i * 3]);
    }
    d_member = dist16(&pairs[0], &pairs[2].b);
    same = (uint32_t)&ram[5] == (uint32_t)(ram + 5);
    d_local = (uint16_t)((uint32_t)&local[5] - (uint32_t)&local[1]);
    local[0] = 0;
}
