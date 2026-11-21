package dampf.lir;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * What A, X and Y hold for sure, as a data flow analysis over a function's blocks: for A the
 * operands it equals (places in the frame and immediates; after {@code LDA x / STA y} both), for
 * X and Y one each. A place is forgotten when something writes it or a byte next to it, A when an
 * instruction changes it, everything at a call; a store through a pointer may write any place.
 * Where ways meet, what all of them bring holds. Only places of the frame and immediates are
 * followed. The operands are typed ({@link LOperand}, ADR record 47).
 */
public final class RegContents {
    /** What the registers hold: the operands A equals, and what X and Y equal (null: unknown). */
    public record State(Set<LOperand> a, LOperand x, LOperand y) {
        public static final State NONE = new State(Set.of(), null, null);

        /** What both hold. */
        public State meet(State o) {
            Set<LOperand> both = new HashSet<>(a);
            both.retainAll(o.a);
            return new State(Set.copyOf(both), x != null && x.equals(o.x) ? x : null, y != null && y.equals(o.y) ? y : null);
        }

        /** Whether everything this says holds in o too. */
        public boolean within(State o) {
            return o.a.containsAll(a) && (x == null || x.equals(o.x)) && (y == null || y.equals(o.y));
        }
    }

    public RegContents() {
    }

    /** What holds where each reachable block starts (the first block: nothing). */
    public Map<LBlock, State> entries(LFunction f) {
        Map<LBlock, State> in = new IdentityHashMap<>();
        if (f.blocks().isEmpty()) {
            return in;
        }
        ArrayDeque<LBlock> work = new ArrayDeque<>();
        in.put(f.blocks().getFirst(), State.NONE);
        work.add(f.blocks().getFirst());
        while (!work.isEmpty()) {
            LBlock b = work.removeFirst();
            State s = in.get(b);
            for (LInstr i : b.instrs()) {
                s = after(i, s);
            }
            for (LBlock t : b.successors()) {
                State was = in.get(t);
                State now = was == null ? s : was.meet(s);
                if (!now.equals(was)) {
                    in.put(t, now);
                    if (!work.contains(t)) {
                        work.add(t);
                    }
                }
            }
        }
        return in;
    }

    /** What holds after instruction i, from what held before it. */
    public State after(LInstr i, State s) {
        String m = i.mnemonic();
        LOperand op = i.op();
        boolean m8 = !i.m16();
        Set<LOperand> a = new HashSet<>(s.a());
        LOperand x = s.x();
        LOperand y = s.y();
        boolean writesMemory = switch (m) {
            case "sta", "stx", "sty", "stz", "inc", "dec", "asl", "lsr", "rol", "ror" -> op != null && !(op instanceof LOperand.Acc);
            default -> false;
        };
        if (writesMemory && (writesAnyPlace(op) || op.equals(x))) {
            x = null;
        }
        if (writesMemory && (writesAnyPlace(op) || op.equals(y))) {
            y = null;
        }
        switch (m) {
            case "ldy" -> y = trackable(op) ? op : null;
            case "ldx" -> x = trackable(op) ? op : null;
            case "inx", "dex", "tax", "tsx", "plx" -> x = null;
            case "iny", "dey", "tay", "ply" -> y = null;
            case "txy" -> y = x;
            case "tyx" -> x = y;
            case "mvn", "mvp" -> {
                x = null;
                y = null;
            }
            case "jsl", "jsr" -> {
                a.clear();
                x = null;
                y = null;
            }
            default -> { }
        }
        if (writesMemory) {
            forgetPlace(a, op);
        }
        switch (m) {
            case "sep" -> {
                if (isRegisterBits(op)) {
                    a.clear();
                }
            }
            case "rep" -> { }
            case "lda" -> {
                a.clear();
                if (!m8 && trackable(op)) {
                    a.add(op);
                }
            }
            case "sta" -> {
                if (op != null && writesAnyPlace(op)) {
                    a.clear();   // may write any frame place
                } else if (!m8 && trackable(op)) {
                    a.add(op);
                }
            }
            case "stx", "sty", "stz", "cmp", "cpx", "cpy", "ldx", "ldy", "inx", "dex", "iny", "dey",
                 "tax", "tay", "txy", "tyx",
                 "bcc", "bcs", "beq", "bne", "bmi", "bpl", "bvc", "bvs", "bra", "jmp", "clc", "sec" -> { }
            case "asl", "lsr", "rol", "ror", "inc", "dec" -> {
                if (op == null || op instanceof LOperand.Acc) {
                    a.clear();
                }
            }
            default -> a.clear();
        }
        return new State(Set.copyOf(a), x, y);
    }

    /** {@code #$20}: the accumulator's width bit of REP and SEP. */
    private static boolean isRegisterBits(LOperand op) {
        return op instanceof LOperand.Imm k && k.expr().equals("$20");
    }

    /**
     * A place whose contents a register can hold: a frame place or an immediate. Not an indexed
     * one ({@code z:dampf_frame+0,x}, an element of a local array): the same operand names another
     * byte when X or Y changes.
     */
    private static boolean trackable(LOperand op) {
        return op instanceof LOperand.Imm || op instanceof LOperand.Mem mem && mem.frame() && mem.index() == LOperand.Index.NONE
                && !mem.indirect();
    }

    /** A store through a pointer or an index: it may write any frame place. */
    private static boolean writesAnyPlace(LOperand op) {
        return op instanceof LOperand.Mem mem && (mem.indirect() || mem.frame() && mem.index() != LOperand.Index.NONE);
    }

    /** A write to a place: A no longer equals it, nor a place that shares a byte with it. */
    private static void forgetPlace(Set<LOperand> a, LOperand op) {
        if (writesAnyPlace(op)) {
            a.removeIf(h -> !(h instanceof LOperand.Imm));
            return;
        }
        int at = frameOffset(op);
        a.removeIf(h -> h.equals(op) || at >= 0 && frameOffset(h) >= 0 && Math.abs(frameOffset(h) - at) <= 1);
    }

    /** The offset of a direct frame place ({@code z:dampf_frame+6}), -1 for anything else. */
    public static int frameOffset(LOperand op) {
        return op instanceof LOperand.Mem mem ? mem.frameOffset() : -1;
    }
}
