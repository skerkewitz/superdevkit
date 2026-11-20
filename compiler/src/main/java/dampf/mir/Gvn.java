package dampf.mir;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Common subexpressions and redundant loads (part of optimization H), along the dominator tree.
 *
 * <p>A pure instruction (arithmetic, comparison, cast, ptradd) that computes what a dominating one
 * already computed is replaced by it.
 *
 * <p>A load from an address that was already loaded or stored, with nothing in between that may
 * write there, is replaced by the known value. "In between" is followed through straight-line
 * code: into a block whose only predecessor is the block before it. A store kills the known
 * values it may overlap (two different globals or frame slots never overlap; a pointer of
 * unknown target may overlap anything); a copy kills all; a call kills all unless the called
 * function writes no memory at all ({@link #writesMemory}). Volatile loads and stores are never
 * reused or forwarded.
 */
public final class Gvn {
    private final MModule module;
    private final Map<MFunction, Boolean> writes;
    private final Map<Instr, Value> replaced = new HashMap<>();
    private final Map<Object, Value> exprs = new HashMap<>();
    private Map<Block, List<Block>> children;

    /** What a load key looks like: the address and the type. */
    private record LoadKey(Value address, MType type) {}

    private record ExprKey(String what, MType type, List<Value> operands) {}

    private Gvn(MModule module, Map<MFunction, Boolean> writes) {
        this.module = module;
        this.writes = writes;
    }

    /** Returns whether anything changed. */
    public static boolean run(MModule m, MFunction f, Map<MFunction, Boolean> writes) {
        Gvn g = new Gvn(m, writes);
        Map<Block, Block> idom = Cfg.idoms(f);
        g.children = Cfg.domChildren(f, idom);
        g.walk(f.entry(), new HashMap<>());
        DeadCode.replaceUses(f, g.replaced);
        return !g.replaced.isEmpty();
    }

    /** Which functions may write memory (store, copy, or call one that does); no recursion, so this ends. */
    public static Map<MFunction, Boolean> writesMemory(MModule m) {
        Map<MFunction, Boolean> result = new HashMap<>();
        for (MFunction f : m.functions()) {
            writes(m, f, result);
        }
        return result;
    }

    private static boolean writes(MModule m, MFunction f, Map<MFunction, Boolean> memo) {
        Boolean known = memo.get(f);
        if (known != null) {
            return known;
        }
        memo.put(f, true); // assumed while it is being looked at (there is no recursion anyway)
        boolean w = false;
        for (Block b : f.blocks()) {
            for (Instr i : b.instrs()) {
                w |= switch (i) {
                    case Instr.Store s -> true;
                    case Instr.Copy c -> true;
                    case Instr.Call c -> m.function(c.callee) == null || writes(m, m.function(c.callee), memo);
                    default -> false;
                };
            }
        }
        memo.put(f, w);
        return w;
    }

    private void walk(Block b, Map<LoadKey, Value> loads) {
        List<Object> added = new ArrayList<>();
        for (Instr i : List.copyOf(b.instrs())) {
            resolve(i);
            switch (i) {
                case Instr.Bin x -> pure(i, key("bin " + x.op, i, commutative(x.op)), added);
                case Instr.Cmp c -> pure(i, key("cmp " + c.pred, i, c.pred == Instr.Pred.EQ || c.pred == Instr.Pred.NE), added);
                case Instr.Cast c -> pure(i, key("cast " + c.kind, i, false), added);
                case Instr.PtrAdd p -> pure(i, key("ptradd", i, false), added);
                case Instr.Load l when !l.isVolatile -> {
                    LoadKey k = new LoadKey(l.op(0), l.type());
                    Value known = loads.get(k);
                    if (known != null) {
                        replaced.put(l, known);
                    } else {
                        loads.put(k, l);
                    }
                }
                case Instr.Store s -> {
                    kill(loads, s.op(0), s.op(1).type().size);
                    if (!s.isVolatile) {
                        loads.put(new LoadKey(s.op(0), s.op(1).type()), s.op(1));
                    }
                }
                case Instr.Copy c -> loads.clear();
                case Instr.Call c -> {
                    MFunction callee = module.function(c.callee);
                    if (callee == null || writes.getOrDefault(callee, true)) {
                        loads.clear();
                    }
                }
                default -> { }
            }
        }
        for (Block c : children.getOrDefault(b, List.of())) {
            // what is known about memory carries on only into straight-line code
            walk(c, c.preds().size() == 1 ? new HashMap<>(loads) : new HashMap<>());
        }
        for (Object k : added) {
            exprs.remove(k);
        }
    }

    private void resolve(Instr i) {
        for (int k = 0; k < i.operands().size(); k++) {
            Value v = i.op(k);
            while (v instanceof Instr d && replaced.containsKey(d)) {
                v = replaced.get(d);
            }
            if (v != i.op(k)) {
                i.setOp(k, v);
            }
        }
    }

    private static boolean commutative(Instr i) {
        return i instanceof Instr.Bin b && commutative(b.op)
                || i instanceof Instr.Cmp c && (c.pred == Instr.Pred.EQ || c.pred == Instr.Pred.NE);
    }

    private static boolean commutative(Instr.BinOp op) {
        return switch (op) {
            case ADD, MUL, AND, OR, XOR -> true;
            default -> false;
        };
    }

    private static ExprKey key(String what, Instr i, boolean commutative) {
        List<Value> ops = new ArrayList<>(i.operands());
        if (commutative && ops.get(0) instanceof Value.Const && !(ops.get(1) instanceof Value.Const)) {
            ops = List.of(ops.get(1), ops.get(0));
        }
        return new ExprKey(what, i.type(), ops);
    }

    private void pure(Instr i, ExprKey k, List<Object> added) {
        if (k.operands().stream().anyMatch(v -> v instanceof Value.Undef)) {
            return;   // two undefs need not be the same value
        }
        Value known = exprs.get(k);
        if (known == null && k.operands().size() == 2 && commutative(i)) {
            // a + b is b + a (the constant of one is on the right already, see key)
            known = exprs.get(new ExprKey(k.what(), k.type(), List.of(k.operands().get(1), k.operands().get(0))));
        }
        if (known != null) {
            replaced.put(i, known);
        } else {
            exprs.put(k, i);
            added.add(k);
        }
    }

    // ------------------------------------------------------------------ may two accesses overlap?

    /** An address as a base plus a constant offset. */
    private record Location(Value base, long offset) {
        boolean named() {
            return base instanceof Value.GlobalAddr || base instanceof Value.SlotAddr;
        }
    }

    private static Location locate(Value address) {
        long offset = 0;
        Value v = address;
        while (v instanceof Instr.PtrAdd p && p.op(1) instanceof Value.Const k) {
            offset += k.type().signed(k.bits());
            v = p.op(0);
        }
        return new Location(v, offset);
    }

    private static boolean mayOverlap(Value a, int sizeA, Value b, int sizeB) {
        Location la = locate(a);
        Location lb = locate(b);
        if (la.base().equals(lb.base())) {
            return la.offset() < lb.offset() + sizeB && lb.offset() < la.offset() + sizeA;
        }
        return !(la.named() && lb.named());
    }

    private static void kill(Map<LoadKey, Value> loads, Value address, int size) {
        loads.keySet().removeIf(k -> mayOverlap(k.address(), k.type().size, address, size));
    }
}
