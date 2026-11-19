package dampf.backend;

import dampf.lir.BranchRelax;
import dampf.lir.DeadStores;
import dampf.mir.Block;
import dampf.mir.Instr;
import dampf.mir.MFunction;
import dampf.mir.MType;
import dampf.mir.Printer;
import dampf.mir.Ranges;
import dampf.mir.Value;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What instruction selection from MIR shares: the places of the values, the code being written
 * and what the registers hold while it is written, and the helpers that write instructions,
 * operands and results. The selection of each kind of instruction lives in its own class
 * ({@link ArithSelection}, {@link BranchSelection}, {@link MemorySelection}, {@link CallSelection});
 * {@link SelectionPlan} decides beforehand, from the MIR, how values are kept. See {@link MirCodeGen}.
 */
final class Selection {
    /** The code generator (its frames and module). */
    final MirCodeGen gen;
    final SelectionPlan plan = new SelectionPlan(this);
    final ArithSelection arith = new ArithSelection(this);
    final BranchSelection branch = new BranchSelection(this);
    final MemorySelection memory = new MemorySelection(this);
    final CallSelection calls = new CallSelection(this);

    Selection(MirCodeGen gen) {
        this.gen = gen;
    }

    static final String P = "z:dampf_ptr";
    static final String Q = "z:dampf_ptr+4";
    static final String VIA_P = "[dampf_ptr],y";
    static final String VIA_Q = "[dampf_ptr+4],y";
    static final String FRAME = "dampf_frame";
    /** Where the frames start: the low RAM after the runtime's direct page registers (docs/memory-map.md). */
    static final int FRAME_START = 0x20;
    final Set<String> rom = new HashSet<>();

    /** Position of every value, frame slot and φ "incoming" place in dampf_frame. */
    final Map<Object, Integer> place = new IdentityHashMap<>();
    final Map<Instr.Phi, Integer> incoming = new IdentityHashMap<>();

    AsmWriter out;
    FrameLayout layout;
    final Set<Instr> fused = new HashSet<>();
    /** Results that stay in A for the next instruction, their only reader, and are never stored. */
    final Set<Instr> forward = new HashSet<>();
    Ranges ranges;
    final Set<Instr> folded = new HashSet<>();
    int labels;

    /** The last instruction written goes elsewhere for sure (JMP, BRA, RTL): nothing falls through. */
    boolean lastTransfer;
    /** N and Z describe the value in A (set by the instruction that computed or loaded it). */
    boolean nzFromA;

    static int words(MType t) {
        return t.size <= 2 ? 1 : 2;
    }

    final Set<Block> direct = new HashSet<>();

    /** Every function's value ranges, computed with the checks of the hints (see {@link dampf.mir.Hints}). */

    void layout() {
        for (MFunction f : gen.module.functions()) {
            dampf.mir.ExitEdges.split(f);
            dampf.mir.Returns.duplicate(f);
            dampf.mir.PointerRemat.run(f);
        }
        Map<MFunction, Ranges> hintRanges = dampf.mir.Hints.strip(gen.module);
        for (MFunction f : gen.module.functions()) {
            for (Block b : f.blocks()) {
                for (Instr i : b.instrs()) {
                    if (i instanceof Instr.Call c) {
                        List<MFunction> l = gen.callees.computeIfAbsent(f.name(), k -> new ArrayList<>());
                        MFunction callee = gen.module.function(c.callee);
                        if (callee != null && !l.contains(callee)) {
                            l.add(callee);
                        }
                    }
                }
            }
        }
        for (MFunction f : gen.module.functions()) {
            FrameLayout l = new FrameLayout(f, hintRanges.get(f));
            gen.layouts.put(f, l);
            gen.frameSize.put(f, l.size);
            direct.addAll(l.direct);
        }
        for (MFunction f : gen.module.functions()) {
            FrameLayout l = gen.layouts.get(f);
            int b = gen.base(f, new LinkedHashSet<>());
            l.offset.forEach((k, v) -> place.put(k, b + v));
            l.incoming.forEach((k, v) -> incoming.put(k, b + v));
            gen.frameTotal = Math.max(gen.frameTotal, b + l.size);
        }
        gen.argumentsInPlace();
        if (gen.frameTotal > MirCodeGen.MAX_FRAME) {
            throw new IllegalStateException("the frames need " + gen.frameTotal + " bytes, more than " + MirCodeGen.MAX_FRAME);
        }
    }

    static String at(int offset) {
        // a word that lies in the direct page is reached with direct page addressing (D = 0)
        // (ca65 takes dampf_frame for a zero page symbol, so the others need "a:")
        return (FRAME_START + offset + 1 < 0x100 ? "z:" : "a:") + FRAME + "+" + offset;
    }

    /** Word w (0 low, 1 high) of a value, as an operand of lda, adc, cmp, ... */
    String operand(Value v, int w) {
        if (v instanceof Instr i && (xResident.containsKey(i) || xSteps.containsKey(i))) {
            throw new IllegalStateException(Printer.instr(i) + " lives in X, it has no place to read");
        }
        return switch (v) {
            case Value.Const k -> imm(k.bits() >> (16 * w));
            case Value.Undef u -> imm(0);
            case Value.GlobalAddr g -> rom.contains(g.symbol())
                    ? (w == 0 ? "#.loword(" + g.symbol() + ")" : "#^" + g.symbol())
                    : (w == 0 ? "#.loword(" + g.symbol() + ")" : imm(0x7E));
            case Value.SlotAddr s -> w == 0 ? "#.loword(" + FRAME + "+" + place.get(s.slot()) + ")" : imm(0x7E);
            case Value.Param p -> w >= words(p.type()) ? imm(0) : at(place.get(p) + 2 * w);
            case Instr i when FrameLayout.transparent(i) -> w >= words(i.type()) ? imm(0) : operand(i.op(0), w);
            case Instr i -> w >= words(i.type()) ? imm(0) : at(place.get(i) + 2 * w);
        };
    }

    /**
     * Stores word w of i's result from A into its place, unless the next instruction is the only
     * one that reads it and takes it from A ({@link #forward}): then A is noted as holding it.
     */
    void result(Instr i, int w) {
        if (w == 0 && forward.contains(i)) {
            aHolds.clear();
            aHolds.add(dest(i, 0));
            return;
        }
        ins("sta", dest(i, w));   // (DeadStores takes it out if no path reads it)
    }

    String dest(Instr i, int w) {
        return at(place.get(i) + 2 * w);
    }

    /**
     * Masks the result of i, if it is an i1 (always) or an i8 that must be clean ({@link #masked}).
     * An i8 that only byte-wise code reads (byte stores, adds, ...) keeps whatever high byte it got.
     */
    void maskResult(Instr i) {
        if (i.type() == MType.I8) {
            if (masked.contains(i)) {
                ins("and", imm(0xFF));
            }
        } else {
            mask(i.type());
        }
    }

    /** The i8 results the code must mask: one of their readers needs a clean high byte, and they have none of their own. */
    final Set<Instr> masked = new HashSet<>();
    /** The i8 values whose high byte is 0 in their place: those masked, clean by themselves, and φs of such. */
    final Set<Value> clean = new HashSet<>();

    void mask(MType t) {
        if (t == MType.I8) {
            ins("and", imm(0xFF));
        } else if (t == MType.I1) {
            ins("and", imm(1));
        }
    }

    /** A memory operand for byte {@code k} of the object at a fixed address, or null if the address is not fixed. */
    String fixed(Value address, int k) {
        return switch (address) {
            case Value.GlobalAddr g -> (rom.contains(g.symbol()) ? "f:" : "") + g.symbol() + (k == 0 ? "" : "+" + k);
            case Value.SlotAddr s -> at(place.get(s.slot()) + k);
            // a number made into a pointer (a hardware register): long addressing, since the data
            // bank is $7E and an absolute $210B would reach WRAM
            case Value.Const c -> String.format("f:$%06X", (c.bits() + k) & 0xFFFFFF);
            default -> null;
        };
    }

    /** Copies a pointer into the direct page for [dp],y. */
    void pointer(Value address, String dp) {
        ins("lda", operand(address, 0));
        ins("sta", dp);
        ins("lda", operand(address, 1));
        ins("sta", dp + "+2");
    }

    /** Indexes that prefer Y (see findYIndexes). */
    final Set<Value> yIndexes = new HashSet<>();

    /**
     * What A holds for sure: the operand strings (frame places and an immediate) equal to it, so
     * a load of any of them can be left out (after LDA x / STA y both are). Forgotten at labels,
     * calls and stores through pointers, and when an instruction changes A; a place is dropped
     * when something else writes it or a neighbouring byte.
     */
    final Set<String> aHolds = new HashSet<>();
    /** The pointers in dampf_ptr and dampf_ptr+4 (forgotten like aHolds), and what Y holds. */
    Value ptrP;
    Value ptrQ;
    String yHolds;
    /** What X holds (a frame place or a constant), like aHolds. */
    String xHolds;
    /** The accumulator is 8 bit (between SEP #$20 and REP #$20). */
    boolean m8;

    /**
     * A REP #$20 and an AND #$00FF held back for one instruction: if SEP #$20 comes next (after
     * at most an LDY), all of them go, and the code stays 8 bit between a byte load and a byte
     * store. (A value is masked to 8 bits only for code that reads all 16; a byte store does not,
     * and after SEP nothing assumes what A holds.)
     */
    boolean heldRep;
    boolean heldAnd;

    void flushHeld() {
        if (heldRep || heldAnd) {
            lastTransfer = false;
        }
        if (heldRep) {
            out.ins("rep", "#$20", null);
        }
        if (heldAnd) {
            out.ins("and", imm(0xFF), null);
        }
        heldRep = false;
        heldAnd = false;
    }

    void ins(String mnemonic, String operand) {
        ins(mnemonic, operand, false);
    }

    /** {@code relax}: a jump or branch {@link BranchRelax} may shorten. */
    void ins(String mnemonic, String operand, boolean relax) {
        if (xTaken != null && changesX(mnemonic)) {
            throw new IllegalStateException(mnemonic + " in a loop whose " + Printer.instr(xTaken) + " lives in X");
        }
        if (mnemonic.equals("rep") && "#$20".equals(operand)) {
            flushHeld();
            heldRep = true;
            track(mnemonic, operand);
            return;
        }
        if (mnemonic.equals("and") && imm(0xFF).equals(operand) && !m8) {
            heldAnd = true;
            track(mnemonic, operand);
            return;
        }
        if (mnemonic.equals("and") && heldAnd && !heldRep && operand != null && operand.startsWith("#$")
                && Integer.parseInt(operand.substring(2), 16) <= 0xFF) {
            heldAnd = false;   // a narrower mask right after: the held one adds nothing
        }
        if (mnemonic.equals("sep") && "#$20".equals(operand) && (heldRep || heldAnd)) {
            boolean both = heldRep;
            heldRep = false;
            heldAnd = false;
            if (!both) {
                out.ins(mnemonic, operand, null);
            }
            track(mnemonic, operand);
            return;
        }
        if (mnemonic.equals("lda") && !m8 && operand != null && aHolds.contains(operand)) {
            return;   // A has it already (the flags are not set again: see loadAlways())
        }
        if (!mnemonic.equals("ldy")) {
            flushHeld();
        }
        if (mnemonic.equals("ldy") && operand != null && operand.equals(yHolds)) {
            return;
        }
        if (mnemonic.equals("ldx") && operand != null && operand.equals(xHolds)) {
            return;
        }
        out.ins(mnemonic, operand, null, relax);
        track(mnemonic, operand);
    }

    /**
     * N and Z for a value: CMP #0 if A holds it already (perhaps never stored, see forward), else
     * a load (which sets them).
     */
    void flagsOf(String operand) {
        if (!m8 && aHolds.contains(operand)) {
            flushHeld();
            if (nzFromA) {
                return;   // the instruction that left the value in A set N and Z for it already
            }
            out.ins("cmp", imm(0), null);
            track("cmp", imm(0));
        } else {
            loadAlways(operand);
        }
    }

    static boolean changesX(String mnemonic) {
        return switch (mnemonic) {
            case "ldx", "inx", "dex", "tax", "tsx", "plx", "jsl" -> true;
            default -> false;
        };
    }

    /** An instruction on the loop value in X (allowed where X is taken). */
    void xOp(String mnemonic, String operand) {
        Instr taken = xTaken;
        xTaken = null;
        ins(mnemonic, operand);
        xTaken = taken;
    }

    /** Loop φs kept in X (to their step instruction), the steps (to their constant), and per block the φ that has X. */
    final Map<Instr, Instr> xResident = new HashMap<>();
    final Map<Instr, Long> xSteps = new HashMap<>();
    final Map<Block, Instr> xBlocks = new HashMap<>();
    Instr xTaken;

    static Value throughCopies(Value v) {
        while (v instanceof Instr i && FrameLayout.transparent(i)) {
            v = i.op(0);
        }
        return v;
    }

    /** A load that always happens, for code that branches on the flags it sets. */
    void loadAlways(String operand) {
        flushHeld();
        out.ins("lda", operand, null);
        track("lda", operand);
    }

    static boolean trackable(String operand) {
        return operand != null && (operand.contains(FRAME) && !operand.startsWith("[") || operand.startsWith("#"));
    }

    void track(String mnemonic, String operand) {
        lastTransfer = switch (mnemonic) {
            case "jmp", "bra", "brl", "jml", "rtl", "rts" -> true;
            default -> false;
        };
        nzFromA = switch (mnemonic) {
            // N and Z from A's (new) 16-bit value
            case "lda", "and", "ora", "eor", "adc", "sbc", "txa", "tya", "pla", "tax", "tay", "tcd" -> !m8;
            case "asl", "lsr", "rol", "ror", "inc", "dec" -> !m8 && "a".equals(operand);
            // N and Z untouched (REP #$20 changes only M)
            case "sta", "stx", "sty", "stz", "clc", "sec", "jmp", "bra", "brl", "pha", "phx", "phy",
                 "bcc", "bcs", "beq", "bne", "bmi", "bpl", "bvc", "bvs" -> nzFromA;
            case "rep" -> nzFromA && "#$20".equals(operand);
            default -> false;   // ldx, inx, cmp, bit, memory INC/ASL ..., calls, SEP, MVN: something else
        };
        boolean writesMemory = switch (mnemonic) {
            case "sta", "stx", "sty", "stz", "inc", "dec", "asl", "lsr", "rol", "ror" -> operand != null && !operand.equals("a");
            default -> false;
        };
        if (writesMemory && (operand.startsWith("[") || operand.equals(xHolds))) {
            xHolds = null;
        }
        if (writesMemory && (operand.startsWith("[") || operand.equals(yHolds))) {
            yHolds = null;
        }
        switch (mnemonic) {
            case "ldy" -> yHolds = trackable(operand) ? operand : null;
            case "ldx" -> xHolds = trackable(operand) ? operand : null;
            case "inx", "dex", "tax", "tsx", "plx" -> xHolds = null;
            case "iny", "dey", "tay", "ply" -> yHolds = null;
            case "jsl" -> {
                aHolds.clear();
                yHolds = null;
                xHolds = null;
                ptrP = null;   // the called function uses dampf_ptr too
                ptrQ = null;
            }
            default -> { }
        }
        if (writesMemory) {
            forgetPlace(operand);
        }
        switch (mnemonic) {
            case "sep" -> {
                if ("#$20".equals(operand)) {
                    m8 = true;
                    aHolds.clear();
                }
            }
            case "rep" -> {
                if ("#$20".equals(operand)) {
                    m8 = false;
                }
            }
            case "lda" -> {
                aHolds.clear();
                if (!m8 && trackable(operand)) {
                    aHolds.add(operand);
                }
            }
            case "sta" -> {
                if (operand != null && operand.startsWith("[")) {
                    aHolds.clear();   // may write any frame place
                } else if (!m8 && trackable(operand)) {
                    aHolds.add(operand);   // (forgetPlace dropped it and its neighbours first)
                }
            }
            case "stx", "sty", "stz", "cmp", "cpx", "cpy", "ldx", "ldy", "inx", "dex", "iny", "dey",
                 "bcc", "bcs", "beq", "bne", "bmi", "bpl", "bvc", "bvs", "bra", "jmp", "clc", "sec" -> { }
            case "asl", "lsr", "rol", "ror", "inc", "dec" -> {
                if ("a".equals(operand) || operand == null) {
                    aHolds.clear();
                }
            }
            default -> aHolds.clear();   // adc, and, jsl, tax, rtl, ...: A (or everything) changes
        }
    }

    /** A write to a place: A no longer equals it, nor a place that shares a byte with it. */
    void forgetPlace(String operand) {
        if (operand.startsWith("[")) {
            aHolds.removeIf(h -> !h.startsWith("#"));
            return;
        }
        int at = frameOffset(operand);
        aHolds.removeIf(h -> h.equals(operand) || at >= 0 && frameOffset(h) >= 0 && Math.abs(frameOffset(h) - at) <= 1);
    }

    /** The offset of a direct frame place ("z:dampf_frame+6"), -1 for anything else. */
    static int frameOffset(String operand) {
        int k = operand.indexOf(FRAME);
        if (k < 0 || operand.indexOf(',') >= 0) {
            return -1;
        }
        String rest = operand.substring(k + FRAME.length());
        if (rest.isEmpty()) {
            return 0;
        }
        if (!rest.startsWith("+")) {
            return -1;
        }
        try {
            return Integer.parseInt(rest.substring(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    void place(String label) {
        flushHeld();
        out.label(label);
        // something may jump here: nothing is known about the registers (the LIR passes find
        // what every way in brings)
        aHolds.clear();
        xHolds = null;
        yHolds = null;
        ptrP = null;
        ptrQ = null;
        m8 = false;
        lastTransfer = false;
        nzFromA = false;
    }

    String label() {
        return "@L" + (labels++);
    }

    static String imm(long v) {
        return "#$" + hex(v & 0xFFFF, 4);
    }

    static String hex(long v, int digits) {
        return String.format("%0" + digits + "X", v);
    }

    static String pad(String s, int width) {
        return s.length() >= width ? s + " " : s + " ".repeat(width - s.length());
    }
}
