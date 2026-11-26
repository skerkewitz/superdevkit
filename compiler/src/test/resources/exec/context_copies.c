/* Copies per context (ADR-6FQ4NSHE): the main code,
 * the IRQ handler and the NMI handler call the same helpers, which dampf compiles once per
 * context, each copy with frames of its own. The chain work -> mix2 -> scale is reached from all
 * three: scale multiplies (on the CPU's multiplier in the main code, in software in the copies of
 * the interrupts), mix2 reads a constant table that all copies share, and count's static local is
 * one variable for all copies, so it counts the calls of every context. While the interrupts
 * come, the main code runs mix2 and scale itself, so a copy that shared the main code's frames
 * would change its results. Each handler writes out[k] = work(k + base) for its k-th call, up to a
 * fixed number of calls, so the results do not depend on how often it came; the PC calls the
 * handlers itself. The main code calls work, and so count, only while no interrupt is on: an
 * interrupt between count's read and its write would lose a call. */
#include <stdint.h>
#include <dampf.h>

#define IRQS 8
#define NMIS 4
#define N 16

#define REG8(a) (*(volatile uint8_t *)(a))

uint16_t data[N] = { 1, 255, 256, 1000, 4097, 12345, 32767, 32768, 40000, 65535, 7, 0x5A5A, 0xA5A5, 0x1234, 60000, 600 };
uint32_t main_out[3];
uint32_t irq_out[IRQS];
uint32_t nmi_out[NMIS];
uint16_t irq_count, nmi_count;
uint16_t calls;
uint8_t irqs_during_work, nmis_during_work;

/* ---- the helpers every context calls */

static uint16_t count(uint16_t add)
{
    static uint16_t n;
    n = (uint16_t)(n + add);
    return n;
}

static uint32_t scale(uint16_t a, uint16_t b)
{
    return (uint32_t)a * b + (uint16_t)((uint16_t)(uint8_t)a * (uint8_t)b);
}

static uint32_t mix2(const uint16_t *p, uint16_t k)
{
    static const uint8_t shifts[4] = { 1, 3, 5, 7 };
    uint32_t h = scale(p[0], k);
    return (h << shifts[k & 3]) ^ scale(p[1], (uint16_t)(k + 7u));
}

static uint32_t work(uint16_t k)
{
    uint16_t t[2];
    t[0] = data[k % N];
    t[1] = data[(uint16_t)(k + 3u) % N];
    (void)count(1);
    return mix2(t, k) + k;
}

/* ---- the handlers */

DAMPF_INTERRUPT(irq) void on_irq(void)
{
    if (irq_count < IRQS) {
        irq_out[irq_count] = work((uint16_t)(irq_count + 100u));
        irq_count++;
    }
}

DAMPF_INTERRUPT(nmi) void on_nmi(void)
{
    if (nmi_count < NMIS) {
        nmi_out[nmi_count] = work((uint16_t)(nmi_count + 200u));
        nmi_count++;
    }
}

/* ---- the main code */

static uint16_t peek16(const uint16_t *p)
{
    return *(const volatile uint16_t *)p;
}

void test_main(void)
{
    uint32_t h = 0;
    uint16_t i;
    main_out[0] = work(1);
#ifdef __DAMPF__
    REG8(0x4209) = 100;
    REG8(0x420A) = 0;
    REG8(0x4200) = 0xA0;   /* the NMI, and the IRQ at line 100 */
#endif
    for (i = 0; i < 600; i++) {
        h += mix2(&data[i % (N - 1)], i);
    }
    main_out[1] = h;
#ifndef __DAMPF__
    on_irq();
    on_nmi();
#endif
    irqs_during_work = (uint8_t)(peek16(&irq_count) > 0);
    nmis_during_work = (uint8_t)(peek16(&nmi_count) > 0);
#ifdef __DAMPF__
    while (peek16(&irq_count) < IRQS || peek16(&nmi_count) < NMIS) {
    }
    REG8(0x4200) = 0;
#else
    while (irq_count < IRQS) {
        on_irq();
    }
    while (nmi_count < NMIS) {
        on_nmi();
    }
#endif
    main_out[2] = work(2);
    calls = count(0);
}
