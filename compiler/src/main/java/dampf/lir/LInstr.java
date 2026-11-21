package dampf.lir;

import dampf.target.Cycles65816;

/**
 * A 65816 instruction: mnemonic, operand as ca65 writes it (null if none), an optional comment,
 * and the processor state it runs in (the accumulator width; index registers are 16 bit in
 * dampf's code). The opcode, and with it the addressing mode, length and cycles, come from
 * {@link Cycles65816}; -1 if the table does not know the instruction.
 */
public final class LInstr implements LItem {
    private final String mnemonic;
    private final String operand;
    private final LOperand op;
    private final String comment;
    private final boolean m16;
    private final boolean x16;
    private final int opcode;
    private final boolean relaxable;

    public LInstr(String mnemonic, String operand, String comment, boolean m16) {
        this(mnemonic, operand, comment, m16, true, false);
    }

    /**
     * {@code relaxable}: a jump or branch whose form {@link BranchRelax} may choose (the code
     * generator writes the long form).
     */
    public LInstr(String mnemonic, String operand, String comment, boolean m16, boolean x16, boolean relaxable) {
        this(mnemonic, LOperand.of(mnemonic, operand), comment, m16, x16, relaxable);
    }

    /** An instruction with a typed operand (its owner kept); null for none. */
    public LInstr(String mnemonic, LOperand op, String comment, boolean m16, boolean x16, boolean relaxable) {
        this.mnemonic = mnemonic;
        this.operand = op == null ? null : op.text();
        this.op = op;
        this.comment = comment;
        this.m16 = m16;
        this.x16 = x16;
        this.relaxable = relaxable;
        this.opcode = Cycles65816.opcode(mnemonic, Cycles65816.mode(mnemonic, operand));
    }

    public String mnemonic() {
        return mnemonic;
    }

    public String operand() {
        return operand;
    }

    /** The operand, typed (ADR-6F8NBFAQ); null if none. */
    public LOperand op() {
        return op;
    }

    public String comment() {
        return comment;
    }

    /** The accumulator is 16 bit when this instruction runs (M = 0). */
    public boolean m16() {
        return m16;
    }

    public boolean x16() {
        return x16;
    }

    /** Whether {@link BranchRelax} may give this jump or branch a shorter form. */
    public boolean relaxable() {
        return relaxable;
    }

    public int opcode() {
        return opcode;
    }

    public boolean known() {
        return opcode >= 0;
    }

    /** The addressing mode as the cycle table names it ("abs", "dp", "#imm", "[dp],y", "" for none). */
    public String mode() {
        return Cycles65816.mode(mnemonic, operand);
    }

    /** CPU cycles; for a conditional branch the cycles when it is not taken. 0 if unknown. */
    public int cycles() {
        return known() ? Cycles65816.cycles(opcode, m16, x16, false) : 0;
    }

    /** CPU cycles of a conditional branch that is taken (the same as {@link #cycles} for any other). */
    public int cyclesTaken() {
        return known() ? Cycles65816.cycles(opcode, m16, x16, isConditionalBranch()) : 0;
    }

    /** Bytes, 0 if unknown. */
    public int length() {
        return known() ? Cycles65816.length(opcode, m16, x16) : 0;
    }

    public boolean isConditionalBranch() {
        return known() && Cycles65816.isConditionalBranch(opcode);
    }

    /** BRA, BRL, JMP or JML: goes elsewhere for sure. */
    public boolean isJump() {
        return switch (mnemonic) {
            case "bra", "brl", "jmp", "jml" -> true;
            default -> false;
        };
    }

    public boolean isReturn() {
        return switch (mnemonic) {
            case "rtl", "rts", "rti" -> true;
            default -> false;
        };
    }

    public boolean isCall() {
        return mnemonic.equals("jsl") || mnemonic.equals("jsr");
    }

    /** A short branch: a conditional branch or BRA (reaches -128..127 bytes from the next instruction). */
    public boolean isShortBranch() {
        return isConditionalBranch() || mnemonic.equals("bra");
    }

    /** Ends a basic block: a branch, a jump or a return. */
    public boolean endsBlock() {
        return isConditionalBranch() || isJump() || isReturn();
    }

    /** Whether execution can go on with the next instruction. */
    public boolean fallsThrough() {
        return !isJump() && !isReturn();
    }

    /** The label a branch or jump goes to, or null (a return, an indirect jump, anything else). */
    public String target() {
        if (operand == null || !(isConditionalBranch() || isJump()) || operand.startsWith("(") || operand.startsWith("[")) {
            return null;
        }
        return operand;
    }

    /** The same instruction with another operand. */
    public LInstr withOperand(String newOperand) {
        return new LInstr(mnemonic, newOperand, comment, m16, x16, relaxable);
    }

    /** The same instruction with another typed operand. */
    public LInstr withOperand(LOperand newOperand) {
        return new LInstr(mnemonic, newOperand, comment, m16, x16, relaxable);
    }

    /** The same instruction with another mnemonic (BRA for JMP, ...). */
    public LInstr withMnemonic(String newMnemonic) {
        return new LInstr(newMnemonic, operand, comment, m16, x16, relaxable);
    }

    @Override
    public String toString() {
        return mnemonic + (operand == null ? "" : " " + operand);
    }
}
