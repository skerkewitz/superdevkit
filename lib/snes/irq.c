/* The game's IRQ on the SNES (sdevkit/irq.h, ADR-6FRX1GYC): the CPU's timer, which the kit owns.
 *
 * The timer's IRQ comes at dot HTIME of scanline VTIME (NMITIMEN bits 4 and 5). Picture row r is
 * scanline r + 1, so VTIME is r; HTIME puts the handler's first instruction early enough in that
 * scanline for it to read its data and decide before the horizontal blank that ends it, which
 * sdevkit_irq_wait_hblank then waits for (measured in Mesen, see irq.h). NMITIMEN is the kit's:
 * bit 0 keeps the joypads' automatic read. */
#include "sdevkit/irq.h"
#include "hw.h"

#define IRQ_HTIME 50
#define NMITIMEN_PADS 0x01
#define NMITIMEN_HV_IRQ 0x30
#define HVBJOY_HBLANK 0x0040   /* bit 6 of $4212, read with $4213 (RDIO, no side effect) in one load */

void sdevkit_irq_line(uint8_t row)
{
    if (row >= SDEVKIT_IRQ_ROWS) {
        NMITIMEN = NMITIMEN_PADS;
        return;
    }
    VTIME = row;
    HTIME = IRQ_HTIME;
    NMITIMEN = NMITIMEN_PADS | NMITIMEN_HV_IRQ;
}

void sdevkit_irq_off(void)
{
    NMITIMEN = NMITIMEN_PADS;
}

void sdevkit_irq_wait_hblank(void)
{
    while (!(REG16(0x4212) & HVBJOY_HBLANK)) {
    }
}
