/* C in interrupt handlers (ADR-6FM9TXV4): an IRQ and an NMI handler run while the main code
 * computes, both sides with locals, pointers, the runtime's scratch registers, multiplications
 * (on the CPU's multiplier in the main code, in software in the handlers) and divisions. The NMI
 * also comes while the IRQ handler runs (the IRQ at line 215, the NMI at 225). The main results
 * must be the PC's; each handler writes out[k] = f(k) for its k-th call modulo the table's size,
 * so its results do not depend on how often it came, and the PC calls the handlers itself.
 * On the SNES each handler also checks the line it runs on: the vertical timer's line (100 and
 * 215 in turn, the next one set right away, so a write never makes the current line VTIME's,
 * which would fire at once), and 225 for the NMI; the same in dampf's emulator and in Mesen. */
#include <stdint.h>
#include <dampf.h>

#define IRQS 12
#define NMIS 4
#define IRQ_MIN 20
#define NMI_MIN 5
#define N 48

#define REG8(a) (*(volatile uint8_t *)(a))

uint16_t data[N] = { 1, 2, 3, 255, 256, 257, 1000, 4097, 12345, 32767, 32768, 40000, 65535, 65534, 7, 9,
                     0x5A5A, 0xA5A5, 0x00FF, 0xFF00, 0x1234, 0x4321, 0x8001, 0x7FFE, 100, 200, 300, 400,
                     11, 13, 17, 19, 23, 29, 31, 37, 0x0F0F, 0xF0F0, 0x3333, 0xCCCC, 5, 50, 500, 5000,
                     60000, 6000, 600, 60 };
uint32_t main_result[3];
uint16_t main_table[8];

uint32_t irq_out[IRQS];
uint16_t nmi_out[NMIS];
uint16_t irq_seq, nmi_seq, irq_total, nmi_total;
uint8_t irq_slot;
uint16_t irq_line_errors, nmi_line_errors;
uint8_t irqs_during_work, nmis_during_work;

#ifdef __DAMPF__
static const uint16_t irq_lines[2] = { 100, 215 };
#endif

/* ---- the main code: a leaf with locals in the direct page, a caller with a local array */

static uint32_t mix(const uint16_t *a, uint16_t n, uint16_t seed)
{
    uint32_t h = seed;
    uint16_t i;
    uint8_t j;
    for (i = 0; i < n; i++) {
        uint16_t x = a[i];
        for (j = 1; j < 6; j++) {
            uint16_t p = (uint16_t)((uint32_t)x * (uint16_t)(j * 997u + i));   /* dampf_mul16 */
            uint8_t b = (uint8_t)x;
            uint8_t c = (uint8_t)(p >> 8);
            uint16_t q = (uint16_t)((uint16_t)b * c);                         /* the multiplier, inline */
            h = h * (uint32_t)(p | 1u) + q;                                    /* dampf_mul32 */
            h ^= (h >> 7) + (uint16_t)(q / (uint16_t)(j + 1));                 /* dampf_udiv16 */
        }
    }
    return h;
}

static void fill(uint16_t *t, uint16_t k)
{
    uint8_t i;
    for (i = 0; i < 8; i++) {
        t[i] = (uint16_t)((uint32_t)data[(k + i) % N] * (uint16_t)(i + 3));
    }
}

static uint32_t main_work(uint16_t seed)
{
    uint16_t t[8];
    uint8_t i;
    uint32_t h;
    fill(t, seed);
    h = mix(data, N, seed);
    for (i = 0; i < 8; i++) {
        h += mix(t, 8, (uint16_t)(seed + i));
        main_table[i] = (uint16_t)(main_table[i] ^ t[i] ^ (uint16_t)h);
    }
    return h;
}

/* ---- the IRQ context: the handler's frame lies above its callees', beyond the direct page */

static uint32_t irq_work(const uint16_t *t, uint16_t k)
{
    uint32_t s = k;
    uint8_t i;
    for (i = 0; i < 2; i++) {
        uint8_t b = (uint8_t)(t[i] + k);
        uint8_t c = (uint8_t)(i + 5);
        s += (uint32_t)t[i] * (uint32_t)(k + 77u);   /* dampf_mul32_soft */
        s ^= (uint16_t)((uint16_t)b * c);             /* two bytes: dampf_mul16_soft, not the multiplier */
    }
    return s + (uint16_t)((uint32_t)t[2] * (uint16_t)(k + 3u)) / (uint16_t)(k + 2u);
}

DAMPF_INTERRUPT(irq) void on_irq(void)
{
    uint16_t t[96];   /* beyond the direct page: absolute addresses in the IRQ context's area */
    uint16_t k;
    uint8_t i;
#ifdef __DAMPF__
    uint16_t line;
    (void)REG8(0x2137);
    line = REG8(0x213D);
    line = (uint16_t)(line | ((REG8(0x213D) & 1) << 8));
    (void)REG8(0x213F);
    if (line != irq_lines[irq_slot]) {
        irq_line_errors++;
    }
    irq_slot = (uint8_t)(irq_slot ^ 1);
    REG8(0x4209) = (uint8_t)irq_lines[irq_slot];
    REG8(0x420A) = (uint8_t)(irq_lines[irq_slot] >> 8);
#endif
    k = irq_seq;
    irq_seq = (uint16_t)(k + 1 == IRQS ? 0 : k + 1);
    for (i = 0; i < 8; i++) {
        t[k * 4 + i] = (uint16_t)(k * 31u + i * 7u);
    }
    t[95] = (uint16_t)(k ^ 0x5A5Au);
    irq_out[k] = irq_work(&t[k * 4], k) + t[95];
    irq_total++;
}

/* ---- the NMI context */

static uint16_t nmi_work(const uint16_t *p, uint16_t k)
{
    return (uint16_t)((uint32_t)p[0] * p[1] + (uint32_t)p[2] * (uint16_t)(k + 1000u));
}

DAMPF_INTERRUPT(nmi) void on_nmi(void)
{
    uint16_t v[3];
    uint16_t k;
#ifdef __DAMPF__
    uint16_t line;
    (void)REG8(0x2137);
    line = REG8(0x213D);
    line = (uint16_t)(line | ((REG8(0x213D) & 1) << 8));
    (void)REG8(0x213F);
    if (line != 225) {
        nmi_line_errors++;
    }
#endif
    k = nmi_seq;
    nmi_seq = (uint16_t)(k + 1 == NMIS ? 0 : k + 1);
    v[0] = (uint16_t)(k + 3);
    v[1] = (uint16_t)(k * 1234u);
    v[2] = data[k];
    nmi_out[k] = nmi_work(v, k);
    nmi_total++;
}

/* ---- the main code reads the handlers' counters through a volatile pointer */

static uint16_t peek16(const uint16_t *p)
{
    return *(const volatile uint16_t *)p;
}

void test_main(void)
{
#ifdef __DAMPF__
    REG8(0x4209) = (uint8_t)irq_lines[0];
    REG8(0x420A) = 0;
    REG8(0x4200) = 0xA0;   /* NMI, and the IRQ at line VTIME */
#endif
    main_result[0] = main_work(1);
    main_result[1] = main_work(77);
    main_result[2] = main_work(40000);
#ifndef __DAMPF__
    {
        uint8_t n;
        for (n = 0; n < IRQ_MIN; n++) {
            on_irq();
        }
        for (n = 0; n < NMI_MIN; n++) {
            on_nmi();
        }
    }
#endif
    irqs_during_work = (uint8_t)(peek16(&irq_total) >= IRQ_MIN);
    nmis_during_work = (uint8_t)(peek16(&nmi_total) >= NMI_MIN);
#ifdef __DAMPF__
    while (peek16(&irq_total) < IRQ_MIN + IRQS || peek16(&nmi_total) < NMI_MIN + NMIS) {
    }
    REG8(0x4200) = 0;
#else
    while (irq_total < IRQ_MIN + IRQS) {
        on_irq();
    }
    while (nmi_total < NMI_MIN + NMIS) {
        on_nmi();
    }
#endif
    irq_seq = 0;
    nmi_seq = 0;
    irq_total = 0;
    nmi_total = 0;
    irq_slot = 0;
}
