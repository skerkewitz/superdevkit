/* The game's IRQ on Rachael (sdevkit/irq.h, ADR-6FRX1GYC): the row it runs before; render.c calls
 * it while it draws the picture, as the SNES's timer does. Its register writes count as written
 * while the picture is drawn until it has called sdevkit_irq_wait_hblank (ppu.c), so a handler
 * that forgets the wait fails the desktop check. */
#include "rachael.h"
#include "sdevkit/irq.h"

void (*sdevkit__irq_handler)(void);

void sdevkit_irq_line(uint8_t row)
{
    sdevkit__rachael.irq_on = (uint8_t)(row < SDEVKIT_IRQ_ROWS);
    sdevkit__rachael.irq_row = row;
}

void sdevkit_irq_off(void)
{
    sdevkit__rachael.irq_on = 0;
}

/* The SNES waits for the horizontal blank; Rachael has no time, she notes that the handler waited. */
void sdevkit_irq_wait_hblank(void)
{
    sdevkit__rachael.irq_waited = 1;
}

/* The SNES's NMI runs the vertical blank of a game that publishes (sdevkit/publish.h, declared
 * there; that header needs <dampf.h>, which Rachael is compiled without). On Rachael that game's
 * game_vblank does it itself and never calls this. */
void sdevkit_publish_nmi_on(void);
void sdevkit_publish_nmi_on(void)
{
}
