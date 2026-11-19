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
 * or none at all, is a compile error; above 90 % of it, a warning. A number, not an expression.
 */
#define DAMPF_BUDGET(cycles) __attribute__((dampf_budget(cycles)))

#else

/* one unsigned comparison: no "always true" warning for an unsigned v and lo 0 (-Wextra) */
#define DAMPF_RANGE_HOLDS(v, lo, hi) \
    ((unsigned long long)(v) - (unsigned long long)(lo) <= (unsigned long long)(hi) - (unsigned long long)(lo))
#if __STDC_HOSTED__
#include <assert.h>
#define DAMPF_RANGE(v, lo, hi) assert(DAMPF_RANGE_HOLDS(v, lo, hi) && "DAMPF_RANGE(" #v ", " #lo ", " #hi ")")
#elif defined(NDEBUG)
#define DAMPF_RANGE(v, lo, hi) ((void)0)
#else
/* without the host's headers (the desktop build of a game: -nostdinc -ffreestanding), no <assert.h>:
 * a wrong hint stops the program all the same */
#define DAMPF_RANGE(v, lo, hi) (DAMPF_RANGE_HOLDS(v, lo, hi) ? (void)0 : __builtin_trap())
#endif
/* checked by dampf at build time; the emulator tests measure it */
#define DAMPF_BUDGET(cycles)

#endif

#endif
