package dampf.backend;

import dampf.lir.LOperand;

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
 * ({@link ArithSelection}, {@link BranchSelection}, {@link MemorySelection}, {@link CallSelection},
 * {@link SwitchSelection});
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
    final SwitchSelection switches = new SwitchSelection(this);

    Selection(MirCodeGen gen) {
        this.gen = gen;
    }

    static final LOperand.Mem P = new LOperand.Mem(LOperand.Width.DP, "dampf_ptr", 0, LOperand.Index.NONE, false);
    static final LOperand.Mem Q = new LOperand.Mem(LOperand.Width.DP, "dampf_ptr", 4, LOperand.Index.NONE, false);
    static final LOperand.Mem VIA_P = new LOperand.Mem(LOperand.Width.NONE, "dampf_ptr", 0, LOperand.Index.Y, true);
    static final LOperand.Mem VIA_Q = new LOperand.Mem(LOperand.Width.NONE, "dampf_ptr", 4, LOperand.Index.Y, true);
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

    /**
     * The symbol of the current function's absolute frame addresses ({@link MirCodeGen#frameSymbol}):
     * {@code dampf_frame} in the main code, {@code dampf_frame_irq} in the IRQ context.
     */
    String absFrame = FRAME;
    /** The current function runs in an interrupt context: no hardware multiplier (ADR-6FM9TXV4). */
    boolean interrupt;

    LOperand.Mem at(int offset) {
        // a word that lies in the direct page is reached with direct page addressing, relative to
        // the context's direct page (D = 0 in the main code); the others absolutely, in the
        // context's area (ca65 takes dampf_frame for a zero page symbol, so they need "a:")
        boolean dp = FRAME_START + offset + 1 < 0x100;
        return new LOperand.Mem(dp ? LOperand.Width.DP : LOperand.Width.ABS, dp ? FRAME : absFrame, offset,
                LOperand.Index.NONE, false);
    }

    /** Word w of a value's place (a value of this function, or a callee's parameter), owned by it. */
    LOperand.Mem placeOf(Value v, int w) {
        return at(place.get(v) + 2 * w).owned(new LOperand.Owner(v, false, 2 * w));
    }

    /** Word w of a φ's incoming place, owned by it. */
    LOperand.Mem incomingOf(Instr.Phi p, int w) {
        return at(incoming.get(p) + 2 * w).owned(new LOperand.Owner(p, true, 2 * w));
    }

    /** Word w (0 low, 1 high) of a value, as an operand of lda, adc, cmp, ... */
    LOperand operand(Value v, int w) {
        if (v instanceof Instr i && (xResident.containsKey(i) || xSteps.containsKey(i))) {
            throw new IllegalStateException(Printer.instr(i) + " lives in X, it has no place to read");
        }
        return switch (v) {
            case Value.Const k -> imm(k.bits() >> (16 * w));
            case Value.Undef u -> imm(0);
            case Value.GlobalAddr g -> rom.contains(g.symbol())
                    ? (w == 0 ? new LOperand.Imm(".loword(" + g.symbol() + ")") : new LOperand.Imm("^" + g.symbol()))
                    : (w == 0 ? new LOperand.Imm(".loword(" + g.symbol() + ")") : imm(0x7E));
            case Value.SlotAddr s -> w == 0 ? new LOperand.Imm(".loword(" + absFrame + "+" + place.get(s.slot()) + ")",
                    new LOperand.Owner(s.slot(), false, 0)) : imm(0x7E);
            case Value.Param p -> w >= words(p.type()) ? imm(0) : placeOf(p, w);
            case Instr i when FrameLayout.transparent(i) -> w >= words(i.type()) ? imm(0) : operand(i.op(0), w);
            case Instr i -> w >= words(i.type()) ? imm(0) : placeOf(i, w);
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

    LOperand dest(Instr i, int w) {
        return placeOf(i, w);
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
    LOperand.Mem fixed(Value address, int k) {
        return switch (address) {
            case Value.GlobalAddr g -> new LOperand.Mem(rom.contains(g.symbol()) ? LOperand.Width.LONG : LOperand.Width.NONE,
                    g.symbol(), k, LOperand.Index.NONE, false);
            case Value.SlotAddr s -> at(place.get(s.slot()) + k).owned(new LOperand.Owner(s.slot(), false, k));
            // a number made into a pointer (a hardware register): long addressing, since the data
            // bank is $7E and an absolute $210B would reach WRAM
            case Value.Const c -> new LOperand.Mem(LOperand.Width.LONG, String.format("$%06X", (c.bits() + k) & 0xFFFFFF), 0,
                    LOperand.Index.NONE, false);
            default -> null;
        };
    }

    /** Copies a pointer into the direct page for [dp],y. */
    void pointer(Value address, LOperand.Mem dp) {
        ins("lda", operand(address, 0));
        ins("sta", dp);
        ins("lda", operand(address, 1));
        // (Q's high word was written dampf_ptr+4+2 when operands were text: the same address)
        ins("sta", dp == Q ? new LOperand.Raw("z:dampf_ptr+4+2") : dp.plus(2));
    }

    /** Indexes that prefer Y (see findYIndexes). */
    final Set<Value> yIndexes = new HashSet<>();

    /**
     * What A holds for sure: the operands (frame places and an immediate) equal to it, so a load
     * of any of them can be left out (after LDA x / STA y both are). Forgotten at labels, calls
     * and stores through pointers, and when an instruction changes A; a place is dropped when
     * something else writes it or a neighbouring byte. A frame place counts by its owner, not by
     * its address: A holds that value wherever its place ends up (B2, ADR-6F8NBFAQ).
     */
    final Holds aHolds = new Holds();

    /**
     * A word copy between two frame places ({@link dampf.lir.LCopy}): it may become no code, so A
     * holds nothing known afterwards.
     */
    void copy(LOperand.Mem to, LOperand.Mem from) {
        flushHeld();
        if (m8) {
            ins("lda", from);
            ins("sta", to);
            return;
        }
        out.copy(to, from);
        aHolds.clear();
        nzFromA = false;
        lastTransfer = false;
    }

    /** Operands, a frame place matched by address and owner ({@link #same}). */
    static final class Holds {
        private final List<LOperand> ops = new ArrayList<>();

        boolean contains(LOperand o) {
            for (LOperand h : ops) {
                if (same(h, o)) {
                    return true;
                }
            }
            return false;
        }

        void add(LOperand o) {
            if (!contains(o)) {
                ops.add(o);
            }
        }

        void clear() {
            ops.clear();
        }

        void removeIf(java.util.function.Predicate<LOperand> p) {
            ops.removeIf(p);
        }
    }

    /** The same operand: equal, and a frame place of the same owner. */
    static boolean same(LOperand a, LOperand b) {
        if (a == null || b == null || !a.equals(b)) {
            return false;
        }
        return !(a instanceof LOperand.Mem m && b instanceof LOperand.Mem n) || java.util.Objects.equals(m.owner(), n.owner());
    }
    /** The pointers in dampf_ptr and dampf_ptr+4 (forgotten like aHolds), and what Y holds. */
    Value ptrP;
    Value ptrQ;
    LOperand yHolds;
    /** What X holds (a frame place or a constant), like aHolds. */
    LOperand xHolds;
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
            out.insOp("rep", WIDTH, null);
        }
        if (heldAnd) {
            out.insOp("and", imm(0xFF), null);
        }
        heldRep = false;
        heldAnd = false;
    }

    /** The accumulator's width bit of REP and SEP. */
    static final LOperand.Imm WIDTH = new LOperand.Imm("$20");
    static final LOperand.Acc A = new LOperand.Acc();

    void ins(String mnemonic, LOperand operand) {
        ins(mnemonic, operand, false);
    }

    /** An instruction without an operand. */
    void ins(String mnemonic) {
        ins(mnemonic, (LOperand) null, false);
    }

    /** An instruction whose operand is a label (or none). */
    void ins(String mnemonic, String label) {
        ins(mnemonic, label == null ? null : LOperand.of(mnemonic, label), false);
    }

    void ins(String mnemonic, String label, boolean relax) {
        ins(mnemonic, label == null ? null : LOperand.of(mnemonic, label), relax);
    }

    /** {@code relax}: a jump or branch {@link BranchRelax} may shorten. */
    void ins(String mnemonic, LOperand operand, boolean relax) {
        if (xTaken != null && changesX(mnemonic)) {
            throw new IllegalStateException(mnemonic + " in a loop whose " + Printer.instr(xTaken) + " lives in X");
        }
        if (mnemonic.equals("rep") && WIDTH.equals(operand)) {
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
        if (mnemonic.equals("and") && heldAnd && !heldRep && operand instanceof LOperand.Imm k && k.value() != null
                && k.value() <= 0xFF) {
            heldAnd = false;   // a narrower mask right after: the held one adds nothing
        }
        if (mnemonic.equals("sep") && WIDTH.equals(operand) && (heldRep || heldAnd)) {
            boolean both = heldRep;
            heldRep = false;
            heldAnd = false;
            if (!both) {
                out.insOp(mnemonic, operand, null);
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
        if (mnemonic.equals("ldy") && same(operand, yHolds)) {
            return;
        }
        if (mnemonic.equals("ldx") && same(operand, xHolds)) {
            return;
        }
        out.insOp(mnemonic, operand, null, relax);
        track(mnemonic, operand);
    }

    /**
     * N and Z for a value: CMP #0 if A holds it already (perhaps never stored, see forward), else
     * a load (which sets them).
     */
    void flagsOf(LOperand operand) {
        if (!m8 && aHolds.contains(operand)) {
            flushHeld();
            if (nzFromA) {
                return;   // the instruction that left the value in A set N and Z for it already
            }
            out.insOp("cmp", imm(0), null);
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
    void xOp(String mnemonic, LOperand operand) {
        Instr taken = xTaken;
        xTaken = null;
        ins(mnemonic, operand);
        xTaken = taken;
    }

    /** Loop φs kept in X (to their step instruction), the steps (to their constant), and per block the φ that has X. */
    final Map<Instr, Instr> xResident = new HashMap<>();
    final Map<Instr, Long> xSteps = new HashMap<>();
    final Map<Block, Instr> xBlocks = new HashMap<>();
    /** Values an X counter is compared with in their place (CPX): they must be stored. */
    final java.util.Set<Value> xBounds = new java.util.HashSet<>();
    Instr xTaken;

    static Value throughCopies(Value v) {
        while (v instanceof Instr i && FrameLayout.transparent(i)) {
            v = i.op(0);
        }
        return v;
    }

    /** A load that always happens, for code that branches on the flags it sets. */
    void loadAlways(LOperand operand) {
        flushHeld();
        out.insOp("lda", operand, null);
        track("lda", operand);
    }

    /**
     * A place whose contents a register can hold: a frame place or an immediate. Not an indexed
     * one ({@code z:dampf_frame+0,x}, an element of a local array): the same operand names another
     * byte when X or Y changes.
     */
    static boolean trackable(LOperand op) {
        return op instanceof LOperand.Imm || op instanceof LOperand.Mem m && m.frame() && m.index() == LOperand.Index.NONE
                && !m.indirect();
    }

    /** A store through a pointer or an index: it may write any frame place. */
    static boolean writesAnyPlace(LOperand op) {
        return op instanceof LOperand.Mem m && (m.indirect() || m.frame() && m.index() != LOperand.Index.NONE);
    }

    void track(String mnemonic, LOperand operand) {
        lastTransfer = switch (mnemonic) {
            case "jmp", "bra", "brl", "jml", "rtl", "rts" -> true;
            default -> false;
        };
        nzFromA = switch (mnemonic) {
            // N and Z from A's (new) 16-bit value
            case "lda", "and", "ora", "eor", "adc", "sbc", "txa", "tya", "pla", "tax", "tay", "tcd" -> !m8;
            case "asl", "lsr", "rol", "ror", "inc", "dec" -> !m8 && operand instanceof LOperand.Acc;
            // N and Z untouched (REP #$20 changes only M)
            case "sta", "stx", "sty", "stz", "clc", "sec", "jmp", "bra", "brl", "pha", "phx", "phy",
                 "bcc", "bcs", "beq", "bne", "bmi", "bpl", "bvc", "bvs" -> nzFromA;
            case "rep" -> nzFromA && WIDTH.equals(operand);
            default -> false;   // ldx, inx, cmp, bit, memory INC/ASL ..., calls, SEP, MVN: something else
        };
        boolean writesMemory = switch (mnemonic) {
            case "sta", "stx", "sty", "stz", "inc", "dec", "asl", "lsr", "rol", "ror" -> operand != null && !(operand instanceof LOperand.Acc);
            default -> false;
        };
        if (writesMemory && (writesAnyPlace(operand) || operand.equals(xHolds))) {
            xHolds = null;
        }
        if (writesMemory && (writesAnyPlace(operand) || operand.equals(yHolds))) {
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
                if (WIDTH.equals(operand)) {
                    m8 = true;
                    aHolds.clear();
                }
            }
            case "rep" -> {
                if (WIDTH.equals(operand)) {
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
                if (operand != null && writesAnyPlace(operand)) {
                    aHolds.clear();   // may write any frame place
                } else if (!m8 && trackable(operand)) {
                    aHolds.add(operand);   // (forgetPlace dropped it and its neighbours first)
                }
            }
            case "stx", "sty", "stz", "cmp", "cpx", "cpy", "ldx", "ldy", "inx", "dex", "iny", "dey",
                 "bcc", "bcs", "beq", "bne", "bmi", "bpl", "bvc", "bvs", "bra", "jmp", "clc", "sec" -> { }
            case "asl", "lsr", "rol", "ror", "inc", "dec" -> {
                if (operand instanceof LOperand.Acc || operand == null) {
                    aHolds.clear();
                }
            }
            default -> aHolds.clear();   // adc, and, jsl, tax, rtl, ...: A (or everything) changes
        }
    }

    /** A write to a place: A no longer equals it, nor a place that shares a byte with it. */
    void forgetPlace(LOperand operand) {
        if (writesAnyPlace(operand)) {
            aHolds.removeIf(h -> !(h instanceof LOperand.Imm));
            return;
        }
        int at = frameOffset(operand);
        aHolds.removeIf(h -> h.equals(operand) || at >= 0 && frameOffset(h) >= 0 && Math.abs(frameOffset(h) - at) <= 1);
    }

    /** The offset of a direct frame place ({@code z:dampf_frame+6}), -1 for anything else. */
    static int frameOffset(LOperand op) {
        return op instanceof LOperand.Mem m ? m.frameOffset() : -1;
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

    /** A register of the runtime in the direct page ({@code z:R0}). */
    static LOperand.Mem reg(String name) {
        return new LOperand.Mem(LOperand.Width.DP, name, 0, LOperand.Index.NONE, false);
    }

    static LOperand.Imm imm(long v) {
        return new LOperand.Imm("$" + hex(v & 0xFFFF, 4));
    }

    static String hex(long v, int digits) {
        return String.format("%0" + digits + "X", v);
    }

    static String pad(String s, int width) {
        return s.length() >= width ? s + " " : s + " ".repeat(width - s.length());
    }
}
