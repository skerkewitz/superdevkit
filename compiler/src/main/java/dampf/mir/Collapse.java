package dampf.mir;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Two loops over the rows of a table that lie one after another become one loop over the table:
 * the outer loop steps an offset o by K from a constant S while {@code o < B}, the inner loop
 * steps x by c (a divisor of K: the size of an element) from o while {@code x < o + K} and goes
 * back to the outer head when it ends, where
 * o takes the next row's offset o + K, the value x ends with. The inner loop's bound becomes the
 * end of the last row, END = S + K * ceil((B - S) / K), and when it ends it goes to where the
 * outer loop went: the inner loop walks every row in one go, and the outer loop is no loop any
 * more (its test runs once, before the first row). What the outer loop's φs held after it, the
 * exit now takes from a φ of its own: the φ's value if no row ran, else the inner φ's paired with
 * it (x for o). The {@code table-2d} family of the spelling test is this.
 *
 * <p>Exact where every round of the inner loop sees only what the flat loop would: its code reads
 * nothing of the outer loop but x and the values it carries round (a sum: an outer φ that starts
 * the inner φ of the row and takes its value when the row ends), the outer loop has no code of its
 * own but o + K and its test, both loops are left only at their tests, the outer loop's exit has
 * no other way in, only the outer φs are read after the loop, and END fits the type in the
 * comparisons' reading (then no x on the way wraps either). With S not below B the outer test
 * fails at once, as before.
 */
public final class Collapse {
    private Collapse() {}

    /** Returns whether a nest was collapsed. */
    public static boolean run(MFunction f) {
        List<CountedLoops.Loop> loops = CountedLoops.find(f);
        for (CountedLoops.Loop outer : loops) {
            for (CountedLoops.Loop inner : loops) {
                if (inner != outer && outer.blocks().containsAll(inner.blocks()) && collapse(f, outer, inner)) {
                    return true;   // one at a time: the loops are found again
                }
            }
        }
        return false;
    }

    private static boolean collapse(MFunction f, CountedLoops.Loop outer, CountedLoops.Loop inner) {
        Block oh = outer.head();
        Block ih = inner.head();
        // both loops are left only at their head's test, the inner one back to the outer head
        if (outer.exitEdges().size() != 1 || outer.exitEdges().getFirst().from() != oh || outer.exits().size() != 1
                || inner.exitEdges().size() != 1 || inner.exitEdges().getFirst().from() != ih
                || inner.exitEdges().getFirst().to() != oh || inner.exits().size() != 1 || inner.preheader() == null) {
            return false;
        }
        CountedLoops.Exit oe = outer.exits().getFirst();
        CountedLoops.Exit ie = inner.exits().getFirst();
        if (oe.block() != oh || ie.block() != ih || oe.stepped() || oe.widened() || ie.stepped() || ie.widened()) {
            return false;
        }
        boolean signed;
        switch (oe.stay()) {
            case ULT -> signed = false;
            case SLT -> signed = true;
            default -> {
                return false;
            }
        }
        if (ie.stay() != oe.stay()) {
            return false;
        }
        // o: from the constant S by K; x: from o by 1, while x < E = o + K
        Instr.Phi o = oe.counter().phi();
        Instr.Phi x = ie.counter().phi();
        if (!(ie.bound() instanceof Instr.Bin e) || e.op != Instr.BinOp.ADD || e.op(0) != o
                || !(e.op(1) instanceof Value.Const kc) || !(oe.bound() instanceof Value.Const bc)
                || o.type() != x.type()) {
            return false;
        }
        MType t = o.type();
        long k = signed ? t.signed(kc.bits()) : kc.bits();
        long c = ie.counter().c();
        if (k <= 0 || c <= 0 || k % c != 0 || oe.counter().c() != k || o.from.size() != 2 || x.from.size() != 2) {
            return false;
        }
        Block pre = inner.preheader();
        Value s = null;
        for (int n = 0; n < o.from.size(); n++) {
            Block from = o.from.get(n);
            if (outer.blocks().contains(from)) {
                if (from != ih || o.op(n) != e) {
                    return false;   // o takes E when a row ends
                }
            } else {
                s = o.op(n);
            }
        }
        if (!(s instanceof Value.Const sc) || x.op(x.from.indexOf(pre)) != o) {
            return false;
        }
        // the outer loop: its head, and at most one block between it and the inner loop, with
        // nothing in them but the φs, E, the test and the jumps
        Set<Block> own = new HashSet<>(outer.blocks());
        own.removeAll(inner.blocks());
        if (own.size() > 2 || own.size() == 2 && !own.contains(pre)) {
            return false;
        }
        Instr.Cmp outerTest = oe.cmp();
        for (Block b : own) {
            for (Instr i : b.instrs()) {
                boolean allowed = i instanceof Instr.Phi && b == oh || i == e || i == outerTest || i.isTerminator();
                if (!allowed) {
                    return false;
                }
            }
        }
        // the outer φs besides o: each a value the rows carry round, paired with an inner φ
        Map<Instr.Phi, Instr.Phi> pairs = new HashMap<>();
        for (Instr i : oh.instrs()) {
            if (!(i instanceof Instr.Phi p)) {
                break;
            }
            if (p == o) {
                continue;
            }
            if (p.from.size() != 2) {
                return false;
            }
            int back = p.from.indexOf(ih);
            if (back < 0 || !(p.op(back) instanceof Instr.Phi q) || q.block() != ih || q.from.size() != 2
                    || q.op(q.from.indexOf(pre)) != p) {
                return false;
            }
            pairs.put(q, p);
        }
        for (Instr i : ih.instrs()) {
            if (!(i instanceof Instr.Phi q)) {
                break;
            }
            if (q != x && !pairs.containsKey(q)) {
                return false;   // an inner φ that starts from something else every row
            }
        }
        // the inner loop reads nothing of the outer loop but E in its test (and its φs' starts)
        Set<Instr> outerValues = new HashSet<>();
        for (Block b : own) {
            outerValues.addAll(b.instrs());
        }
        for (Block b : inner.blocks()) {
            for (Instr i : b.instrs()) {
                for (int n = 0; n < i.operands().size(); n++) {
                    Value v = i.op(n);
                    if (!(v instanceof Instr d) || !outerValues.contains(d)) {
                        continue;
                    }
                    boolean start = i instanceof Instr.Phi q && q.block() == ih && q.from.get(n) == pre;
                    boolean bound = i == ie.cmp() && d == e;
                    if (!start && !bound) {
                        return false;
                    }
                }
            }
        }
        // after the loop: one way in, and nothing of the outer loop read there but its φs
        Block exit = outer.exitEdges().getFirst().to();
        if (exit.preds().size() != 1 || outer.blocks().contains(exit)) {
            return false;
        }
        Map<Instr.Phi, Instr.Phi> atEnd = new HashMap<>();   // outer φ → its value after the last row
        atEnd.put(o, x);
        pairs.forEach((q, p) -> atEnd.put(p, q));
        for (Block b : f.blocks()) {
            if (outer.blocks().contains(b)) {
                continue;
            }
            for (Instr i : b.instrs()) {
                for (Value v : i.operands()) {
                    if (v instanceof Instr d && outerValues.contains(d) && !atEnd.containsKey(d)) {
                        return false;
                    }
                }
            }
        }
        // END: the end of the last row, in the comparisons' reading; it must fit the type
        long start = signed ? t.signed(sc.bits()) : sc.bits();
        long limit = signed ? t.signed(bc.bits()) : bc.bits();
        long end = start < limit ? start + k * ((limit - start + k - 1) / k) : start;
        long max = signed ? (1L << (t.bits() - 1)) - 1 : (1L << t.bits()) - 1;
        if (end > max) {
            return false;
        }
        ie.cmp().setOp(ie.cmp().operands().indexOf(e), new Value.Const(t, t.wrap(end)));
        // the uses after the loop read a φ of the exit: the outer φ if no row ran, else the inner one
        Map<Instr, Instr.Phi> after = new HashMap<>();
        for (Map.Entry<Instr.Phi, Instr.Phi> en : atEnd.entrySet()) {
            Instr.Phi p = en.getKey();
            Instr.Phi np = exit.add(new Instr.Phi(p.type(), p.pos()));
            np.add(oh, p);
            np.add(ih, en.getValue());
            after.put(p, np);
        }
        for (Block b : f.blocks()) {
            if (outer.blocks().contains(b)) {
                continue;
            }
            for (Instr i : b.instrs()) {
                if (after.containsValue(i)) {
                    continue;
                }
                for (int n = 0; n < i.operands().size(); n++) {
                    if (i.op(n) instanceof Instr d && after.containsKey(d)) {
                        i.setOp(n, after.get(d));
                    }
                }
            }
        }
        // the inner loop ends where the outer one did; the outer head keeps only its entry
        Cfg.retarget(ih, oh, exit);
        for (Instr i : List.copyOf(oh.instrs())) {
            if (i instanceof Instr.Phi p) {
                int j = p.from.indexOf(ih);
                p.from.remove(j);
                p.operands().remove(j);
            }
        }
        o.known = null;
        x.known = null;
        return true;
    }
}
