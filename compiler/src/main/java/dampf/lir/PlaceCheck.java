package dampf.lir;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks the frame places of a function's LIR against what its code reads and writes (B2 of
 * docs/current-compiler-flaws.md, ADR record 47), by the owners of the operands: no byte of a
 * place is read on a path where it was not written, and no write to a place lands on a byte
 * that another owner's live value is in (unless it writes back what is there already: a copy
 * between two values that share the place). The places assigned before the code existed made
 * two miscompilations no verifier saw (b33eeba, 02fa308); this sees them.
 *
 * <p>The bytes followed are those of the owners in {@code tracked}: the function's values, its
 * φs' incoming places and its parameters (written by the caller: written on entry). Frame slots
 * are memory and may be reached through pointers; a callee's parameters are written for the
 * callee: neither is followed. Run right after the places are assigned, while every operand
 * still names its owner.
 */
public final class PlaceCheck {
    private PlaceCheck() {}

    /** A byte of an owner's place. */
    private record Byte(LOperand.Owner owner, int at) {}

    /** What an instruction does to followed bytes: reads, writes, and the frame offset of each. */
    private record Access(List<Integer> reads, List<Integer> writes) {}

    /**
     * The problems, empty if none. {@code tracked}: whether an owner is followed;
     * {@code entry}: whether its place is written when the function starts.
     */
    public static List<String> check(LFunction f, java.util.function.Predicate<LOperand.Owner> tracked,
                                     java.util.function.Predicate<LOperand.Owner> entry) {
        Map<Byte, Integer> index = new HashMap<>();
        List<Byte> bytes = new ArrayList<>();
        List<Integer> offsetOf = new ArrayList<>();
        Map<LInstr, Access> access = new IdentityHashMap<>();
        marks.clear();
        BitSet atEntry = new BitSet();
        for (LBlock b : f.blocks()) {
            for (LItem it : b.items()) {
                if (it instanceof LInstr i) {
                    access.put(i, access(i, tracked, index, bytes, offsetOf));
                } else if (it instanceof LMark m) {
                    marks.put(m, mark(m, tracked, index, bytes, offsetOf));
                }
            }
        }
        for (int k = 0; k < bytes.size(); k++) {
            if (entry.test(bytes.get(k).owner())) {
                atEntry.set(k);
            }
        }
        List<String> errors = new ArrayList<>();
        written(f, access, atEntry, bytes.size(), bytes, errors);
        overlaps(f, access, bytes, offsetOf, errors);
        return errors;
    }

    /** The copies of the marks: read from, write to (static: one function at a time). */
    private static final Map<LMark, Access> marks = new IdentityHashMap<>();

    private static Access mark(LMark m, java.util.function.Predicate<LOperand.Owner> tracked, Map<Byte, Integer> index,
                               List<Byte> bytes, List<Integer> offsetOf) {
        List<Integer> reads = new ArrayList<>();
        List<Integer> writes = new ArrayList<>();
        for (LMark.Copy c : m.copies()) {
            for (int k = 0; k < c.bytes(); k++) {
                if (tracked.test(c.from())) {
                    reads.add(byteOf(c.from(), k, -1, index, bytes, offsetOf));
                }
                if (tracked.test(c.to())) {
                    writes.add(byteOf(c.to(), k, -1, index, bytes, offsetOf));
                }
            }
        }
        return new Access(reads, writes);
    }

    private static int byteOf(LOperand.Owner o, int k, int offset, Map<Byte, Integer> index, List<Byte> bytes,
                              List<Integer> offsetOf) {
        Byte by = new Byte(new LOperand.Owner(o.key(), o.incoming(), 0), o.word() + k);
        Integer x = index.get(by);
        if (x == null) {
            x = bytes.size();
            index.put(by, x);
            bytes.add(by);
            offsetOf.add(offset);
        } else if (offset >= 0 && offsetOf.get(x) < 0) {
            offsetOf.set(x, offset);
        }
        return x;
    }

    private static Access access(LInstr i, java.util.function.Predicate<LOperand.Owner> tracked, Map<Byte, Integer> index,
                                 List<Byte> bytes, List<Integer> offsetOf) {
        if (!(i.op() instanceof LOperand.Mem m) || !m.frame() || m.owner() == null || !tracked.test(m.owner())
                || m.index() != LOperand.Index.NONE && !m.indirect()) {
            return null;
        }
        int width = m.indirect() ? 3 : switch (i.mnemonic()) {
            case "ldx", "ldy", "stx", "sty", "cpx", "cpy" -> i.x16() ? 2 : 1;
            default -> i.m16() ? 2 : 1;
        };
        List<Integer> at = new ArrayList<>();
        for (int k = 0; k < width; k++) {
            at.add(byteOf(m.owner(), k, m.offset() + k, index, bytes, offsetOf));
        }
        if (m.indirect()) {
            return new Access(at, List.of());   // the pointer is read, whatever goes through it
        }
        return switch (i.mnemonic()) {
            case "sta", "stx", "sty", "stz" -> new Access(List.of(), at);
            case "inc", "dec", "asl", "lsr", "rol", "ror", "tsb", "trb" -> new Access(at, at);
            default -> new Access(at, List.of());
        };
    }

    /** Check 1: every byte read was written on every way to the read. */
    private static void written(LFunction f, Map<LInstr, Access> access, BitSet atEntry, int n, List<Byte> bytes,
                                List<String> errors) {
        if (f.blocks().isEmpty()) {
            return;
        }
        Map<LBlock, BitSet> in = new IdentityHashMap<>();
        BitSet all = new BitSet();
        all.set(0, n);
        for (LBlock b : f.blocks()) {
            in.put(b, (BitSet) all.clone());   // optimistic: what every way in brings
        }
        in.put(f.blocks().getFirst(), (BitSet) atEntry.clone());
        ArrayDeque<LBlock> work = new ArrayDeque<>(f.blocks());
        while (!work.isEmpty()) {
            LBlock b = work.removeFirst();
            BitSet s = (BitSet) in.get(b).clone();
            for (Access a : accesses(b, access)) {
                if (a != null) {
                    a.writes().forEach(s::set);
                }
            }
            for (LBlock t : b.successors()) {
                BitSet was = in.get(t);
                BitSet now = (BitSet) was.clone();
                now.and(s);
                if (t != f.blocks().getFirst() && !now.equals(was)) {
                    in.put(t, now);
                    if (!work.contains(t)) {
                        work.add(t);
                    }
                }
            }
        }
        for (LBlock b : f.blocks()) {
            BitSet s = (BitSet) in.get(b).clone();
            for (LItem it : b.items()) {
                Access a = it instanceof LInstr i ? access.get(i) : it instanceof LMark m ? marks.get(m) : null;
                if (a == null) {
                    continue;
                }
                for (int r : a.reads()) {
                    if (!s.get(r)) {
                        errors.add(f.name() + ": " + it + " reads byte " + bytes.get(r).at() + " of " + owner(bytes.get(r))
                                + " where it was not written on every way");
                    }
                }
                a.writes().forEach(s::set);
            }
        }
    }

    /**
     * Check 2: a write to a byte whose address another owner's live byte has (live after the
     * write), unless the write stores what A was loaded from at that very address.
     */
    private static void overlaps(LFunction f, Map<LInstr, Access> access, List<Byte> bytes, List<Integer> offsetOf,
                                 List<String> errors) {
        Map<LBlock, BitSet> liveIn = new IdentityHashMap<>();
        Map<LBlock, List<LBlock>> preds = new IdentityHashMap<>();
        for (LBlock b : f.blocks()) {
            liveIn.put(b, new BitSet());
            for (LBlock s : b.successors()) {
                preds.computeIfAbsent(s, k -> new ArrayList<>()).add(b);
            }
        }
        ArrayDeque<LBlock> work = new ArrayDeque<>(f.blocks().reversed());
        while (!work.isEmpty()) {
            LBlock b = work.removeFirst();
            BitSet live = liveOut(b, liveIn);
            List<Access> code = accesses(b, access);
            for (int k = code.size() - 1; k >= 0; k--) {
                transfer(code.get(k), live);
            }
            if (!live.equals(liveIn.get(b))) {
                liveIn.put(b, live);
                for (LBlock p : preds.getOrDefault(b, List.of())) {
                    if (!work.contains(p)) {
                        work.add(p);
                    }
                }
            }
        }
        RegContents contents = new RegContents();
        Map<LBlock, RegContents.State> holds = contents.entries(f);
        for (LBlock b : f.blocks()) {
            // A's contents forwards, liveness backwards: the states before each instruction first
            List<LItem> items = b.items();
            List<RegContents.State> before = new ArrayList<>();
            RegContents.State st = holds.getOrDefault(b, RegContents.State.NONE);
            for (LItem it : items) {
                before.add(st);
                if (it instanceof LInstr i) {
                    st = contents.after(i, st);
                }
            }
            BitSet live = liveOut(b, liveIn);
            for (int k = items.size() - 1; k >= 0; k--) {
                LItem it = items.get(k);
                Access a = it instanceof LInstr i ? access.get(i) : it instanceof LMark m ? marks.get(m) : null;
                boolean copy = it instanceof LMark
                        || it instanceof LInstr i && i.mnemonic().equals("sta") && before.get(k).a().contains(i.op());
                if (a != null && !a.writes().isEmpty() && !copy) {
                    for (int w : a.writes()) {
                        for (int x = live.nextSetBit(0); x >= 0; x = live.nextSetBit(x + 1)) {
                            if (!bytes.get(x).owner().equals(bytes.get(w).owner()) && offsetOf.get(x).equals(offsetOf.get(w))
                                    && offsetOf.get(x) >= 0) {
                                errors.add(f.name() + ": " + it + " writes byte " + bytes.get(w).at() + " of " + owner(bytes.get(w))
                                        + " over byte " + bytes.get(x).at() + " of " + owner(bytes.get(x)) + ", which is live");
                            }
                        }
                    }
                }
                transfer(a, live);
            }
        }
    }

    /** The accesses of a block's instructions and marks, in order (null for none). */
    private static List<Access> accesses(LBlock b, Map<LInstr, Access> access) {
        List<Access> out = new ArrayList<>();
        for (LItem it : b.items()) {
            if (it instanceof LInstr i) {
                out.add(access.get(i));
            } else if (it instanceof LMark m) {
                out.add(marks.get(m));
            }
        }
        return out;
    }

    private static BitSet liveOut(LBlock b, Map<LBlock, BitSet> liveIn) {
        BitSet live = new BitSet();
        for (LBlock s : b.successors()) {
            live.or(liveIn.get(s));
        }
        return live;
    }

    private static void transfer(Access a, BitSet live) {
        if (a == null) {
            return;
        }
        a.writes().forEach(live::clear);
        a.reads().forEach(live::set);
    }

    private static String owner(Byte b) {
        LOperand.Owner o = b.owner();
        String name = o.key() instanceof dampf.mir.Instr in ? dampf.mir.Printer.instr(in) : String.valueOf(o.key());
        return (o.incoming() ? "the incoming place of " : "") + name;
    }

    /** For tests: the set of owners a predicate accepts. */
    static java.util.function.Predicate<LOperand.Owner> of(Set<Object> keys) {
        return o -> keys.contains(o.key());
    }
}
