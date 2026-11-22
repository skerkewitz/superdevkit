package dampf.lir;

/**
 * A word copied from one frame place to another (a φ copy, B2 of docs/archive/compiler-flaws.md):
 * {@code LDA from}, {@code STA to} in 16 bits, or no code at all if the colouring gave both places
 * the same address. The colouring reads it as a copy (the two may share); it is expanded when the
 * places get their addresses, before any LIR pass, and never printed.
 */
public record LCopy(LOperand.Mem to, LOperand.Mem from) implements LItem {}
