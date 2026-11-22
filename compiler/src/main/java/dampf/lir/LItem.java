package dampf.lir;

/**
 * One line of LIR: an instruction, a label, a directive or comment, or the header of a C line;
 * at the top level of a unit also a whole function ({@link LFunction}). See
 * ADR-6F3CJEQA.
 */
public sealed interface LItem permits LInstr, LLabel, LDirective, LSourceLine, LFunction, LMark, LCopy {
}
