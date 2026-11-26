/* The game's IRQ on the SNES (sdevkit/irq.h, ADR-6FRX1GYC), and the switch for the kit's NMI
 * (sdevkit/publish.h, ADR-6FS0T4WB): NMITIMEN, which the kit owns.
 *
 * The timer's IRQ comes at dot HTIME of scanline VTIME (NMITIMEN bits 4 and 5). Picture row r is
 * scanline r + 1, so VTIME is r; HTIME puts the handler's first instruction early enough in that
 * scanline for it to read its data and decide before the horizontal blank that ends it, which
 * sdevkit_irq_wait_hblank then waits for (measured in Mesen, see irq.h). NMITIMEN is write-only:
 * every write keeps bit 0, the joypads' automatic read, and what the kit switched on so far, the
 * timer's bits and the NMI's bit 7, which two bytes keep. The NMI's is written once, in the main
 * code during a vertical blank, where no IRQ comes (its rows end at 223); the timer's in any
 * context, each write complete. */
#include "sdevkit/irq.h"
#include "hw.h"

#define IRQ_HTIME 50
#define NMITIMEN_PADS 0x01
#define NMITIMEN_HV_IRQ 0x30
#define HVBJOY_HBLANK 0x0040   /* bit 6 of $4212, read with $4213 (RDIO, no side effect) in one load */

#define NMITIMEN_NMI 0x80

static uint8_t timer_bits;      /* 0 or NMITIMEN_HV_IRQ */
static uint8_t nmi_bit;         /* 0 or NMITIMEN_NMI, once sdevkit_publish_nmi_on ran */

void sdevkit_irq_line(uint8_t row)
{
    if (row >= SDEVKIT_IRQ_ROWS) {
        timer_bits = 0;
        NMITIMEN = (uint8_t)(NMITIMEN_PADS | nmi_bit);
        return;
    }
    VTIME = row;
    HTIME = IRQ_HTIME;
    timer_bits = NMITIMEN_HV_IRQ;
    NMITIMEN = (uint8_t)(NMITIMEN_PADS | NMITIMEN_HV_IRQ | nmi_bit);
}

void sdevkit_irq_off(void)
{
    timer_bits = 0;
    NMITIMEN = (uint8_t)(NMITIMEN_PADS | nmi_bit);
}

/* In the vertical blank (the game_vblank of SDEVKIT_PUBLISH): RDNMI's flag is set since its
 * start and nothing has read it, so the NMI comes right after this write and serves this
 * vertical blank. Declared in sdevkit/publish.h, which is not included here: it declares the
 * published data, which only a game that publishes defines. */
void sdevkit_publish_nmi_on(void);
void sdevkit_publish_nmi_on(void)
{
    if (nmi_bit) {
        return;
    }
    nmi_bit = NMITIMEN_NMI;
    NMITIMEN = (uint8_t)(NMITIMEN_PADS | NMITIMEN_NMI | timer_bits);
}

void sdevkit_irq_wait_hblank(void)
{
    while (!(REG16(0x4212) & HVBJOY_HBLANK)) {
    }
}
