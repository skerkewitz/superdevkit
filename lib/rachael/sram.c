/*
 * Save RAM on Rachael (ADR-6F3CK03Z): the cartridge's battery-backed RAM as a block of memory of
 * SDEVKIT_SRAM_SIZE bytes, the size the build gives (-DSDEVKIT_SRAM_SIZE, as dampf --sram defines
 * it for the SNES). Power on (sdevkit__reset) leaves it as it is, as the battery does; the frontend
 * fills it from a file or a recording before the game starts and saves it when it changed.
 */
#include "rachael.h"

#if SDEVKIT_SRAM_SIZE > 0
static uint8_t sram[SDEVKIT_SRAM_SIZE];
#endif

uint8_t *sdevkit_sram(void)
{
#if SDEVKIT_SRAM_SIZE > 0
    return sram;
#else
    return 0;
#endif
}
