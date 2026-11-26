/*
 * dampf's hints (docs/hints.md): what the programmer knows and the C code does not say.
 *
 * The same source builds with dampf and on the PC. dampf defines __DAMPF__, and the hints become
 * built-ins it understands. On the PC a hint that states a fact is a checked assertion (assert,
 * so NDEBUG switches it off), so a wrong hint is caught there before it makes wrong SNES code.
 *
 * On the PC, make this header reachable without dampf's <stdint.h> taking the place of the
 * host's: gcc -idirafter <dampf>/include (searched after the system directories).
 */
#ifndef DAMPF_H
#define DAMPF_H

#ifdef __DAMPF__

/*
 * DAMPF_RANGE(v, lo, hi): the local variable or parameter v lies in lo..hi here (both constant,
 * both included). dampf uses it from here on wherever this point is passed on the way: for
 * narrower code and for loop bounds in the timing analysis. A statement of its own.
 */
#define DAMPF_RANGE(v, lo, hi) __dampf_range(v, lo, hi)

/*
 * DAMPF_BUDGET(cycles), in front of a function definition: the function must finish within this
 * many CPU cycles, calls included (its worst case in the timing report). A worst case above it,
 * or none at all, is a compile error (a warning with --opt-limit); above 90 % of it, a warning. A number, not an expression.
 */
#define DAMPF_BUDGET(cycles) __attribute__((dampf_budget(cycles)))

/*
 * DAMPF_INTERRUPT(nmi) or DAMPF_INTERRUPT(irq), in front of a function definition: the function is
 * the handler of that interrupt, 'void name(void)', called by the hardware through the runtime's
 * entry stub and by no C code. It and every function it calls run in a context of their own, with
 * their own frames, direct page and scratch registers and no hardware multiplier; a function
 * called from two contexts is compiled once for each (docs/subset.md, "Interrupt handlers"). Built in the
 * compiler, not yet for games: the kit's NMI and the game's IRQ come later (ADR-6FKTVDE8).
 */
#define DAMPF_INTERRUPT(kind) __attribute__((dampf_interrupt(kind)))

/*
 * DAMPF_ASM("file.s"), after the parameter list of a function definition:
 *     uint16_t random16(uint16_t s) DAMPF_ASM("asm/random16.s") { ... }
 * dampf links the hand-written assembly in the file (relative to this C file) instead of
 * compiling the body; the body stays what the PC compiles and what dampf checks the assembly
 * against. The convention and the checks: docs/hints.md, "DAMPF_ASM".
 */
#define DAMPF_ASM(file) __attribute__((dampf_asm(file)))

#else

/* one unsigned comparison: no "always true" warning for an unsigned v and lo 0 (-Wextra) */
#define DAMPF_RANGE_HOLDS(v, lo, hi) \
    ((unsigned long long)(v) - (unsigned long long)(lo) <= (unsigned long long)(hi) - (unsigned long long)(lo))
#if __STDC_HOSTED__ && !defined(_MSC_VER)
#include <assert.h>
#define DAMPF_RANGE(v, lo, hi) assert(DAMPF_RANGE_HOLDS(v, lo, hi) && "DAMPF_RANGE(" #v ", " #lo ", " #hi ")")
#elif defined(NDEBUG)
#define DAMPF_RANGE(v, lo, hi) ((void)0)
#else
/* without the host's headers (the desktop build of a game: -nostdinc -ffreestanding, or MSVC's
 * /X, which still calls itself hosted), no <assert.h>: a wrong hint stops the program all the same */
#ifdef _MSC_VER
#define DAMPF_RANGE(v, lo, hi) (DAMPF_RANGE_HOLDS(v, lo, hi) ? (void)0 : __debugbreak())
#else
#define DAMPF_RANGE(v, lo, hi) (DAMPF_RANGE_HOLDS(v, lo, hi) ? (void)0 : __builtin_trap())
#endif
#endif
/* checked by dampf at build time; the emulator tests measure it */
#define DAMPF_BUDGET(cycles)
/* on the PC an ordinary function, which nothing calls unless the program does */
#define DAMPF_INTERRUPT(kind)
/* the PC compiles the C body; dampf checks the assembly against it */
#define DAMPF_ASM(file)

#endif

#endif
