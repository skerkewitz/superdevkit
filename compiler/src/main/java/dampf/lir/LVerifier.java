package dampf.lir;

import dampf.target.Cycles65816;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks LIR: every label is defined once (in its function, or at the top level), every branch
 * and jump goes to a label that exists, every instruction is one the opcode table knows with the
 * addressing mode it is written with, no code runs off the end of its function, and the
 * accumulator width each instruction was written for is the width it runs in on every path to
 * it ({@code REP}/{@code SEP #$20} followed across blocks; a function starts with 16 bits, a call
 * returns with them).
 */
public final class LVerifier {
    private LVerifier() {}

    /** The problems found, empty if none. */
    public static List<String> verify(LUnit unit) {
        List<String> errors = new ArrayList<>();
        Set<String> top = new HashSet<>();
        for (LItem i : unit.items()) {
            String name = switch (i) {
                case LLabel l when !l.unnamed() -> l.name();
                case LDirective d when d.label() != null && !d.label().isEmpty() -> d.label();
                case LFunction f -> f.name();
                default -> null;
            };
            if (name != null && !top.add(name)) {
                errors.add("'" + name + "' is defined twice");
            }
        }
        for (LFunction f : unit.functions()) {
            function(f, errors);
        }
        return errors;
    }

    /** Throws if there is a problem. */
    public static void check(LUnit unit) {
        List<String> errors = verify(unit);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("LIR: " + String.join("; ", errors.subList(0, Math.min(10, errors.size())))
                    + (errors.size() > 10 ? " (and " + (errors.size() - 10) + " more)" : ""));
        }
    }

    private static void function(LFunction f, List<String> errors) {
        String in = " in " + f.name();
        Set<String> labels = new HashSet<>();
        for (LBlock b : f.blocks()) {
            for (LItem i : b.items()) {
                if (i instanceof LLabel l && !l.unnamed() && !labels.add(l.name())) {
                    errors.add("label " + l.name() + " is defined twice" + in);
                }
            }
        }
        if (f.endproc() == null) {
            errors.add("no .endproc" + in);
        }
        for (int k = 0; k < f.blocks().size(); k++) {
            LBlock b = f.blocks().get(k);
            for (LInstr i : b.instrs()) {
                if (!i.known()) {
                    errors.add("unknown instruction '" + i + "' (" + i.mnemonic() + " " + i.mode() + ")" + in);
                }
            }
            LInstr last = b.last();
            if (last != null && last.target() != null && b.target() == null
                    && (last.isConditionalBranch() || last.mnemonic().equals("bra") || last.mnemonic().equals("brl"))) {
                errors.add("'" + last + "' goes to a label that is not in the function" + in);
            }
            if (k == f.blocks().size() - 1 && b.hasCode() && (last == null || last.fallsThrough())) {
                errors.add("the code runs off the end" + in);
            }
        }
        widths(f, errors);
    }

    private static final int UNKNOWN = 0;

    /** The accumulator width along every path: what each block starts with must be what its code was written for. */
    private static void widths(LFunction f, List<String> errors) {
        if (f.blocks().isEmpty()) {
            return;
        }
        // per block start: 16, 8, or UNKNOWN where the ways in disagree
        Map<LBlock, Integer> entry = new IdentityHashMap<>();
        ArrayDeque<LBlock> work = new ArrayDeque<>();
        entry.put(f.blocks().getFirst(), 16);
        work.add(f.blocks().getFirst());
        while (!work.isEmpty()) {
            LBlock b = work.removeFirst();
            int width = entry.get(b);
            for (LInstr i : b.instrs()) {
                width = after(i, width);
            }
            for (LBlock s : b.successors()) {
                Integer was = entry.get(s);
                int now = was == null ? width : was == width ? was : UNKNOWN;
                if (was == null || now != was) {
                    entry.put(s, now);
                    work.add(s);
                }
            }
        }
        for (LBlock b : f.blocks()) {
            Integer start = entry.get(b);
            if (start == null) {
                continue;   // never reached
            }
            int width = start;
            for (LInstr i : b.instrs()) {
                if (dependsOnWidth(i)) {
                    if (width == UNKNOWN) {
                        errors.add("'" + i + "' after " + b + " runs with an accumulator width that depends on the way in, in " + f.name());
                    } else if ((width == 16) != i.m16()) {
                        errors.add("'" + i + "' after " + b + " was written for " + (i.m16() ? 16 : 8) + "-bit A but runs with "
                                + width + ", in " + f.name());
                    }
                }
                width = after(i, width);
            }
        }
    }

    private static int after(LInstr i, int width) {
        boolean widthBit = i.op() instanceof LOperand.Imm k && k.expr().equals("$20");
        if (widthBit && i.mnemonic().equals("rep")) {
            return 16;
        }
        if (widthBit && i.mnemonic().equals("sep")) {
            return 8;
        }
        return width;
    }

    /** Whether the accumulator width changes what an instruction does (its length or its cycles). */
    static boolean dependsOnWidth(LInstr i) {
        if (!i.known()) {
            return false;
        }
        int op = i.opcode();
        return Cycles65816.length(op, true, true) != Cycles65816.length(op, false, true)
                || Cycles65816.cycles(op, true, true, false) != Cycles65816.cycles(op, false, true, false);
    }
}
