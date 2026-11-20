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
 * Where ways meet, what all of them bring holds. Only places of the frame (operands that name
 * {@code frame}) and immediates are followed.
 */
public final class RegContents {
    /** What the registers hold: the operands A equals, and what X and Y equal (null: unknown). */
    public record State(Set<String> a, String x, String y) {
        public static final State NONE = new State(Set.of(), null, null);

        /** What both hold. */
        public State meet(State o) {
            Set<String> both = new HashSet<>(a);
            both.retainAll(o.a);
            return new State(Set.copyOf(both), x != null && x.equals(o.x) ? x : null, y != null && y.equals(o.y) ? y : null);
        }

        /** Whether everything this says holds in o too. */
        public boolean within(State o) {
            return o.a.containsAll(a) && (x == null || x.equals(o.x)) && (y == null || y.equals(o.y));
        }
    }

    private final String frame;

    /** {@code frame}: the symbol the frame places are written with ("dampf_frame"). */
    public RegContents(String frame) {
        this.frame = frame;
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
        String op = i.operand();
        boolean m8 = !i.m16();
        Set<String> a = new HashSet<>(s.a());
        String x = s.x();
        String y = s.y();
        boolean writesMemory = switch (m) {
            case "sta", "stx", "sty", "stz", "inc", "dec", "asl", "lsr", "rol", "ror" -> op != null && !op.equals("a");
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
            case "jsl" -> {
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
                if ("#$20".equals(op)) {
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
                 "bcc", "bcs", "beq", "bne", "bmi", "bpl", "bvc", "bvs", "bra", "jmp", "clc", "sec" -> { }
            case "asl", "lsr", "rol", "ror", "inc", "dec" -> {
                if ("a".equals(op) || op == null) {
                    a.clear();
                }
            }
            default -> a.clear();
        }
        return new State(Set.copyOf(a), x, y);
    }

    /**
     * A place whose contents a register can hold: a frame place or an immediate. Not an indexed
     * one ({@code z:dampf_frame+0,x}, an element of a local array): the same text names another
     * byte when X or Y changes.
     */
    private boolean trackable(String operand) {
        return operand != null && operand.indexOf(',') < 0
                && (operand.contains(frame) && !operand.startsWith("[") || operand.startsWith("#"));
    }

    /** A store through a pointer or an index: it may write any frame place. */
    private boolean writesAnyPlace(String operand) {
        return operand.startsWith("[") || operand.indexOf(',') >= 0 && operand.contains(frame);
    }

    /** A write to a place: A no longer equals it, nor a place that shares a byte with it. */
    private void forgetPlace(Set<String> a, String operand) {
        if (writesAnyPlace(operand)) {
            a.removeIf(h -> !h.startsWith("#"));
            return;
        }
        int at = frameOffset(operand);
        a.removeIf(h -> h.equals(operand) || at >= 0 && frameOffset(h) >= 0 && Math.abs(frameOffset(h) - at) <= 1);
    }

    /** The offset of a direct frame place ("z:dampf_frame+6"), -1 for anything else. */
    public int frameOffset(String operand) {
        int k = operand.indexOf(frame);
        if (k < 0 || operand.indexOf(',') >= 0) {
            return -1;
        }
        String rest = operand.substring(k + frame.length());
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
}
