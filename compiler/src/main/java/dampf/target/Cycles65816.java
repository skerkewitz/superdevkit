package dampf.target;

/**
 * CPU cycles per instruction of the 65816 in native mode, from the WDC W65C816S data sheet
 * (table 5-7). Cycles are CPU cycles; what one costs in master cycles depends on the memory each
 * cycle touches (6 for FastROM and internal operations, 8 for SlowROM and WRAM; see
 * docs/timing.md). This table is the first part of dampf's cost model.
 *
 * <p>Each entry is the cost with 8-bit A and index registers, plus what the processor state adds:
 * <ul>
 * <li>{@code m}: +1 with a 16-bit accumulator (M=0) for loads, stores, arithmetic and immediates;</li>
 * <li>{@code rmw}: +2 with a 16-bit accumulator for read-modify-write instructions on memory;</li>
 * <li>{@code x}: +1 with 16-bit index registers (X=0) for loads, stores, compares, pushes and pulls of X and Y;</li>
 * <li>{@code idx}: +1 for an indexed read if the index crosses a page, and always with 16-bit index
 *     registers (dampf's code runs with X=0, so it is always paid);</li>
 * <li>branches: +1 if taken.</li>
 * </ul>
 * The extra cycle for a direct page register whose low byte is not zero is not counted: dampf
 * keeps D's low byte at zero. Emulation mode is not modelled.
 */
public final class Cycles65816 {
    private static final int M = 1;     // +1 with 16-bit A
    private static final int RMW = 2;   // +2 with 16-bit A (read-modify-write)
    private static final int X = 4;     // +1 with 16-bit X/Y
    private static final int IDX = 8;   // +1 page crossing / 16-bit index (indexed reads)
    private static final int BR = 16;   // conditional branch: +1 if taken

    private static final int[] BASE = new int[256];
    private static final int[] FLAGS = new int[256];
    private static final String[] NAME = new String[256];

    private Cycles65816() {}

    private static void op(int code, String name, int cycles, int flags) {
        if (NAME[code] != null) {
            throw new IllegalStateException("opcode " + Integer.toHexString(code) + " defined twice");
        }
        BASE[code] = cycles;
        FLAGS[code] = flags;
        NAME[code] = name;
    }

    /** The 15 addressing modes of the "group 1" instructions (ORA AND EOR ADC STA LDA CMP SBC), low 5 bits of the opcode. */
    private static void group1(int high, String name, boolean store) {
        op(high | 0x01, name + " (dp,x)", 6, M);
        op(high | 0x03, name + " sr,s", 4, M);
        op(high | 0x05, name + " dp", 3, M);
        op(high | 0x07, name + " [dp]", 6, M);
        if (!store) {
            op(high | 0x09, name + " #imm", 2, M);
        }
        op(high | 0x0D, name + " abs", 4, M);
        op(high | 0x0F, name + " long", 5, M);
        op(high | 0x11, name + " (dp),y", store ? 6 : 5, store ? M : M | IDX);
        op(high | 0x12, name + " (dp)", 5, M);
        op(high | 0x13, name + " (sr,s),y", 7, M);
        op(high | 0x15, name + " dp,x", 4, M);
        op(high | 0x17, name + " [dp],y", 6, M);
        op(high | 0x19, name + " abs,y", store ? 5 : 4, store ? M : M | IDX);
        op(high | 0x1D, name + " abs,x", store ? 5 : 4, store ? M : M | IDX);
        op(high | 0x1F, name + " long,x", 5, M);
    }

    /** ASL ROL LSR ROR on A and memory; INC/DEC are listed separately. */
    private static void shift(int high, String name) {
        op(high | 0x0A, name + " a", 2, 0);
        op(high | 0x06, name + " dp", 5, RMW);
        op(high | 0x0E, name + " abs", 6, RMW);
        op(high | 0x16, name + " dp,x", 6, RMW);
        op(high | 0x1E, name + " abs,x", 7, RMW);
    }

    static {
        group1(0x00, "ora", false);
        group1(0x20, "and", false);
        group1(0x40, "eor", false);
        group1(0x60, "adc", false);
        group1(0x80, "sta", true);
        group1(0xA0, "lda", false);
        group1(0xC0, "cmp", false);
        group1(0xE0, "sbc", false);
        shift(0x00, "asl");
        shift(0x20, "rol");
        shift(0x40, "lsr");
        shift(0x60, "ror");

        op(0x1A, "inc a", 2, 0);
        op(0xE6, "inc dp", 5, RMW);
        op(0xEE, "inc abs", 6, RMW);
        op(0xF6, "inc dp,x", 6, RMW);
        op(0xFE, "inc abs,x", 7, RMW);
        op(0x3A, "dec a", 2, 0);
        op(0xC6, "dec dp", 5, RMW);
        op(0xCE, "dec abs", 6, RMW);
        op(0xD6, "dec dp,x", 6, RMW);
        op(0xDE, "dec abs,x", 7, RMW);
        op(0x04, "tsb dp", 5, RMW);
        op(0x0C, "tsb abs", 6, RMW);
        op(0x14, "trb dp", 5, RMW);
        op(0x1C, "trb abs", 6, RMW);

        op(0x24, "bit dp", 3, M);
        op(0x2C, "bit abs", 4, M);
        op(0x34, "bit dp,x", 4, M);
        op(0x3C, "bit abs,x", 4, M | IDX);
        op(0x89, "bit #imm", 2, M);

        op(0x64, "stz dp", 3, M);
        op(0x74, "stz dp,x", 4, M);
        op(0x9C, "stz abs", 4, M);
        op(0x9E, "stz abs,x", 5, M);

        op(0xA2, "ldx #imm", 2, X);
        op(0xA6, "ldx dp", 3, X);
        op(0xAE, "ldx abs", 4, X);
        op(0xB6, "ldx dp,y", 4, X);
        op(0xBE, "ldx abs,y", 4, X | IDX);
        op(0xA0, "ldy #imm", 2, X);
        op(0xA4, "ldy dp", 3, X);
        op(0xAC, "ldy abs", 4, X);
        op(0xB4, "ldy dp,x", 4, X);
        op(0xBC, "ldy abs,x", 4, X | IDX);
        op(0x86, "stx dp", 3, X);
        op(0x8E, "stx abs", 4, X);
        op(0x96, "stx dp,y", 4, X);
        op(0x84, "sty dp", 3, X);
        op(0x8C, "sty abs", 4, X);
        op(0x94, "sty dp,x", 4, X);
        op(0xE0, "cpx #imm", 2, X);
        op(0xE4, "cpx dp", 3, X);
        op(0xEC, "cpx abs", 4, X);
        op(0xC0, "cpy #imm", 2, X);
        op(0xC4, "cpy dp", 3, X);
        op(0xCC, "cpy abs", 4, X);

        op(0x10, "bpl", 2, BR);
        op(0x30, "bmi", 2, BR);
        op(0x50, "bvc", 2, BR);
        op(0x70, "bvs", 2, BR);
        op(0x90, "bcc", 2, BR);
        op(0xB0, "bcs", 2, BR);
        op(0xD0, "bne", 2, BR);
        op(0xF0, "beq", 2, BR);
        op(0x80, "bra", 3, 0);
        op(0x82, "brl", 4, 0);

        op(0x4C, "jmp abs", 3, 0);
        op(0x5C, "jml long", 4, 0);
        op(0x6C, "jmp (abs)", 5, 0);
        op(0x7C, "jmp (abs,x)", 6, 0);
        op(0xDC, "jml [abs]", 6, 0);
        op(0x20, "jsr abs", 6, 0);
        op(0xFC, "jsr (abs,x)", 8, 0);
        op(0x22, "jsl long", 8, 0);
        op(0x60, "rts", 6, 0);
        op(0x6B, "rtl", 6, 0);
        op(0x40, "rti", 7, 0);
        op(0x00, "brk", 8, 0);
        op(0x02, "cop", 8, 0);

        op(0x18, "clc", 2, 0);
        op(0x38, "sec", 2, 0);
        op(0x58, "cli", 2, 0);
        op(0x78, "sei", 2, 0);
        op(0xB8, "clv", 2, 0);
        op(0xD8, "cld", 2, 0);
        op(0xF8, "sed", 2, 0);
        op(0xC2, "rep #imm", 3, 0);
        op(0xE2, "sep #imm", 3, 0);
        op(0xFB, "xce", 2, 0);

        op(0xCA, "dex", 2, 0);
        op(0x88, "dey", 2, 0);
        op(0xE8, "inx", 2, 0);
        op(0xC8, "iny", 2, 0);
        op(0xAA, "tax", 2, 0);
        op(0xA8, "tay", 2, 0);
        op(0x8A, "txa", 2, 0);
        op(0x98, "tya", 2, 0);
        op(0x9A, "txs", 2, 0);
        op(0xBA, "tsx", 2, 0);
        op(0x9B, "txy", 2, 0);
        op(0xBB, "tyx", 2, 0);
        op(0x5B, "tcd", 2, 0);
        op(0x7B, "tdc", 2, 0);
        op(0x1B, "tcs", 2, 0);
        op(0x3B, "tsc", 2, 0);
        op(0xEB, "xba", 3, 0);

        op(0x48, "pha", 3, M);
        op(0x68, "pla", 4, M);
        op(0xDA, "phx", 3, X);
        op(0xFA, "plx", 4, X);
        op(0x5A, "phy", 3, X);
        op(0x7A, "ply", 4, X);
        op(0x08, "php", 3, 0);
        op(0x28, "plp", 4, 0);
        op(0x8B, "phb", 3, 0);
        op(0xAB, "plb", 4, 0);
        op(0x0B, "phd", 4, 0);
        op(0x2B, "pld", 5, 0);
        op(0x4B, "phk", 3, 0);
        op(0xF4, "pea", 5, 0);
        op(0xD4, "pei (dp)", 6, 0);
        op(0x62, "per", 6, 0);

        op(0x54, "mvn", 7, 0); // per byte moved
        op(0x44, "mvp", 7, 0); // per byte moved
        op(0xEA, "nop", 2, 0);
        op(0x42, "wdm", 2, 0);
        op(0xDB, "stp", 3, 0);
        op(0xCB, "wai", 3, 0);

        for (int i = 0; i < 256; i++) {
            if (NAME[i] == null) {
                throw new IllegalStateException("opcode " + Integer.toHexString(i) + " missing");
            }
        }
    }

    /**
     * CPU cycles of one execution.
     *
     * @param m16   16-bit accumulator (M=0)
     * @param x16   16-bit index registers (X=0)
     * @param taken for a conditional branch: whether it is taken
     */
    public static int cycles(int opcode, boolean m16, boolean x16, boolean taken) {
        int f = FLAGS[opcode];
        int c = BASE[opcode];
        if (m16 && (f & M) != 0) {
            c++;
        }
        if (m16 && (f & RMW) != 0) {
            c += 2;
        }
        if (x16 && (f & X) != 0) {
            c++;
        }
        if (x16 && (f & IDX) != 0) {
            c++; // with 8-bit index only on a page crossing; not modelled
        }
        if (taken && (f & BR) != 0) {
            c++;
        }
        return c;
    }

    private static final java.util.Map<String, Integer> BY_NAME = new java.util.HashMap<>();

    static {
        for (int i = 0; i < 256; i++) {
            BY_NAME.put(NAME[i], i);
        }
    }

    /**
     * The opcode for a mnemonic and an addressing mode as named in this table ("lda abs",
     * "sta [dp],y", "rtl", "bne"), or -1.
     */
    public static int opcode(String mnemonic, String mode) {
        Integer op = BY_NAME.get(mode.isEmpty() ? mnemonic : mnemonic + " " + mode);
        return op == null ? -1 : op;
    }

    /**
     * The addressing mode of a ca65 operand as dampf writes it: {@code #$0001} immediate,
     * {@code f:x} long, {@code z:x} direct page, {@code [dampf_ptr],y}, {@code x,x}, a plain
     * symbol absolute; branches, JSL and JMP are recognised by their mnemonic.
     */
    public static String mode(String mnemonic, String operand) {
        if (operand == null) {
            return "";
        }
        String o = operand.strip();
        if (o.equals("a")) {
            return "a";
        }
        if (o.startsWith("#")) {
            return "#imm";
        }
        if (mnemonic.equals("jsl") || mnemonic.equals("jml")) {
            return "long";
        }
        if (o.startsWith("f:")) {
            return o.endsWith(",x") ? "long,x" : "long";
        }
        if (o.startsWith("[")) {
            return o.endsWith(",y") ? "[dp],y" : "[dp]";
        }
        if ((mnemonic.equals("jmp") || mnemonic.equals("jsr")) && o.startsWith("(")) {
            return o.endsWith(",x)") ? "(abs,x)" : "(abs)";   // a jump table: JMP (table,X)
        }
        boolean dp = o.startsWith("z:");
        if (o.endsWith(",x")) {
            return dp ? "dp,x" : "abs,x";
        }
        if (o.endsWith(",y")) {
            return dp ? "dp,y" : "abs,y";
        }
        if (BY_NAME.containsKey(mnemonic) && (FLAGS[BY_NAME.get(mnemonic)] & BR) != 0 || mnemonic.equals("bra") || mnemonic.equals("brl")) {
            return "";
        }
        return dp ? "dp" : "abs";
    }

    public static boolean isConditionalBranch(int opcode) {
        return (FLAGS[opcode] & BR) != 0;
    }

    /** Mnemonic and addressing mode, for reports. */
    public static String name(int opcode) {
        return NAME[opcode];
    }

    /**
     * Instruction length in bytes. Immediates depend on the processor state: 2 or 3 bytes for A
     * immediates (M), for X/Y immediates (X); REP/SEP/BRK/COP always 2.
     */
    public static int length(int opcode, boolean m16, boolean x16) {
        String n = NAME[opcode];
        if (n.endsWith("#imm")) {
            if (n.startsWith("rep") || n.startsWith("sep")) {
                return 2;
            }
            boolean index = n.startsWith("ldx") || n.startsWith("ldy") || n.startsWith("cpx") || n.startsWith("cpy");
            return (index ? x16 : m16) ? 3 : 2;
        }
        if (n.endsWith(" long") || n.endsWith(" long,x") || n.startsWith("jsl")) {
            return 4;
        }
        if (n.contains("abs") || n.startsWith("pea") || n.startsWith("per") || n.startsWith("brl")) {
            return 3;
        }
        if (n.contains("dp") || n.contains("sr,s") || n.startsWith("b") && (FLAGS[opcode] & BR) != 0 || n.equals("bra")
                || n.equals("brk") || n.equals("cop") || n.equals("mvn") || n.equals("mvp") || n.equals("wdm")) {
            return n.equals("mvn") || n.equals("mvp") ? 3 : 2;
        }
        return 1;
    }
}
