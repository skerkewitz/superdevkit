package dampf.lir;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * What A, X and Y hold for sure, as a data flow analysis over a function's blocks: for each
 * register the operands it equals (places in the frame and immediates; after {@code LDA x / STA y}
 * both, after {@code TAX} what A equals). A place is forgotten when something writes it or a byte
 * next to it, a register when an instruction changes it, everything at a call; a store through a
 * pointer may write any place. Where ways meet, what all of them bring holds. Only places of the
 * frame and immediates are followed, and X and Y only at 16 bits. The operands are typed
 * ({@link LOperand}, ADR-6F8NBFAQ).
 */
public final class RegContents {
    /** What the registers hold: the operands A, X and Y each equal. */
    public record State(Set<LOperand> a, Set<LOperand> x, Set<LOperand> y) {
        public static final State NONE = new State(Set.of(), Set.of(), Set.of());

        /** What both hold. */
        public State meet(State o) {
            return new State(both(a, o.a), both(x, o.x), both(y, o.y));
        }

        private static Set<LOperand> both(Set<LOperand> p, Set<LOperand> q) {
            Set<LOperand> out = new HashSet<>(p);
            out.retainAll(q);
            return Set.copyOf(out);
        }

        /** Whether everything this says holds in o too. */
        public boolean within(State o) {
            return o.a.containsAll(a) && o.x.containsAll(x) && o.y.containsAll(y);
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
        boolean x8 = !i.x16();
        Set<LOperand> a = new HashSet<>(s.a());
        Set<LOperand> x = new HashSet<>(s.x());
        Set<LOperand> y = new HashSet<>(s.y());
        boolean writesMemory = switch (m) {
            case "sta", "stx", "sty", "stz", "inc", "dec", "asl", "lsr", "rol", "ror", "tsb", "trb" ->
                    op != null && !(op instanceof LOperand.Acc);
            default -> false;
        };
        if (writesMemory) {
            forgetPlace(a, op);
            forgetPlace(x, op);
            forgetPlace(y, op);
        }
        switch (m) {
            case "ldx" -> x = known(op, x8);
            case "ldy" -> y = known(op, x8);
            case "stx" -> {
                if (!x8 && !writesAnyPlace(op) && trackable(op)) {
                    x.add(op);
                }
            }
            case "sty" -> {
                if (!x8 && !writesAnyPlace(op) && trackable(op)) {
                    y.add(op);
                }
            }
            // (TAX copies all 16 bits of A; A's operands are known at 16 bits only)
            case "tax" -> x = m8 || x8 ? new HashSet<>() : new HashSet<>(a);
            case "tay" -> y = m8 || x8 ? new HashSet<>() : new HashSet<>(a);
            case "txy" -> y = x8 ? new HashSet<>() : new HashSet<>(x);
            case "tyx" -> x = x8 ? new HashSet<>() : new HashSet<>(y);
            case "inx", "dex", "tsx", "plx" -> x.clear();
            case "iny", "dey", "ply" -> y.clear();
            case "mvn", "mvp", "jsl", "jsr" -> {
                a.clear();
                x.clear();
                y.clear();
            }
            default -> { }
        }
        if (x8) {
            x.clear();
            y.clear();
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
            case "txa" -> a = m8 || x8 ? new HashSet<>() : new HashSet<>(s.x());
            case "tya" -> a = m8 || x8 ? new HashSet<>() : new HashSet<>(s.y());
            case "stx", "sty", "stz", "cmp", "cpx", "cpy", "ldx", "ldy", "inx", "dex", "iny", "dey",
                 "tax", "tay", "txy", "tyx", "phx", "phy", "pha", "php",
                 "bcc", "bcs", "beq", "bne", "bmi", "bpl", "bvc", "bvs", "bra", "jmp", "clc", "sec" -> { }
            case "asl", "lsr", "rol", "ror", "inc", "dec" -> {
                if (op == null || op instanceof LOperand.Acc) {
                    a.clear();
                }
            }
            default -> a.clear();
        }
        return new State(Set.copyOf(a), Set.copyOf(x), Set.copyOf(y));
    }

    /** What an index register loaded with op holds. */
    private static Set<LOperand> known(LOperand op, boolean x8) {
        Set<LOperand> out = new HashSet<>();
        if (!x8 && trackable(op)) {
            out.add(op);
        }
        return out;
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
