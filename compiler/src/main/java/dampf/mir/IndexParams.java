package dampf.mir;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Scaled index parameters across calls: a byte parameter that the function uses only as an index
 * into an array of structs ({@code zext} then {@code mul} by the struct's size C) becomes the
 * 16-bit product, and the callers multiply instead. In a loop over the array the caller has the
 * product already (the offset an induction variable steps), so the multiplication goes from every
 * call: {@code pair(i, j)} over a 23-byte struct array multiplied both indexes by 23 again in
 * every call (the game's finding 9, exec/pair_loops.c).
 *
 * <p>The parameter may also be compared, unsigned, with a constant or with another parameter
 * scaled by the same C: with C &gt; 0 and 255 * C within 16 bits, the products are in the same order
 * as the indexes, and equal exactly when they are, so the comparison takes the products (and the
 * constant times C). Any other use keeps the parameter as it is. Like {@link PointerParams} it
 * needs every call: a function without one (the entry) is left alone.
 */
public final class IndexParams {
    private IndexParams() {}

    /** What a parameter's uses are, when it can be scaled. */
    private record Plan(Value.Param param, long scale, List<Instr.Bin> products, List<Instr.Cmp> compares) {}

    /** Returns whether anything changed. */
    public static boolean run(MModule m) {
        Map<String, List<Instr.Call>> calls = new HashMap<>();
        for (MFunction f : m.functions()) {
            for (Block b : f.blocks()) {
                for (Instr i : b.instrs()) {
                    if (i instanceof Instr.Call c) {
                        calls.computeIfAbsent(c.callee, k -> new ArrayList<>()).add(c);
                    }
                }
            }
        }
        boolean changed = false;
        for (MFunction f : m.functions()) {
            List<Instr.Call> sites = calls.getOrDefault(f.name(), List.of());
            if (sites.isEmpty()) {
                continue;
            }
            Map<Value.Param, Plan> plans = new java.util.LinkedHashMap<>();   // in the order of the parameters: the output must not depend on hash codes
            for (Value.Param p : f.params()) {
                Plan plan = plan(f, p);
                if (plan != null) {
                    plans.put(p, plan);
                }
            }
            // a comparison between two parameters needs both scaled by the same C
            boolean dropped = true;
            while (dropped) {
                dropped = false;
                for (Plan plan : new ArrayList<>(plans.values())) {
                    for (Instr.Cmp c : plan.compares()) {
                        Value other = c.op(0) == plan.param() ? c.op(1) : c.op(0);
                        if (other instanceof Value.Param q && (plans.get(q) == null || plans.get(q).scale() != plan.scale())) {
                            plans.remove(plan.param());
                            dropped = true;
                            break;
                        }
                    }
                }
            }
            if (plans.isEmpty()) {
                continue;
            }
            Map<Value.Param, Value.Param> scaled = new HashMap<>();
            for (Plan plan : plans.values()) {
                Value.Param s = new Value.Param(plan.param().name(), MType.I16);
                s.known = new Ranges.Range(0, 255 * plan.scale());
                scaled.put(plan.param(), s);
            }
            for (Plan plan : plans.values()) {
                Value.Param s = scaled.get(plan.param());
                Map<Instr, Value> replaced = new HashMap<>();
                for (Instr.Bin mul : plan.products()) {
                    replaced.put(mul, s);
                }
                DeadCode.replaceUses(f, replaced);
                for (Instr.Cmp c : plan.compares()) {
                    for (int k = 0; k < 2; k++) {
                        Value v = c.op(k);
                        if (v instanceof Value.Param q && scaled.containsKey(q)) {
                            c.setOp(k, scaled.get(q));
                        } else if (v instanceof Value.Const k8) {
                            c.setOp(k, new Value.Const(MType.I16, k8.bits() * plan.scale()));
                        }
                    }
                }
                int index = f.params().indexOf(plan.param());
                f.params().set(index, s);
                for (Instr.Call call : sites) {
                    Value arg = call.op(index);
                    Value product;
                    if (arg instanceof Value.Const a) {
                        product = new Value.Const(MType.I16, a.bits() * plan.scale());
                    } else {
                        Instr z = new Instr.Cast(Instr.CastKind.ZEXT, arg, MType.I16, call.pos());
                        call.block().insertBefore(call, z);
                        Instr p = new Instr.Bin(Instr.BinOp.MUL, z, new Value.Const(MType.I16, plan.scale()), call.pos());
                        call.block().insertBefore(call, p);
                        product = p;
                    }
                    call.setOp(index, product);
                }
            }
            DeadCode.run(f);
            changed = true;
        }
        return changed;
    }

    /** The plan for a byte parameter used only as zext-then-mul by one constant, and in unsigned comparisons. */
    private static Plan plan(MFunction f, Value.Param p) {
        if (p.type() != MType.I8) {
            return null;
        }
        long scale = 0;
        List<Instr.Bin> products = new ArrayList<>();
        List<Instr.Cmp> compares = new ArrayList<>();
        for (Instr use : uses(f, p)) {
            if (use instanceof Instr.Cast z && z.kind == Instr.CastKind.ZEXT && z.type() == MType.I16) {
                for (Instr u : uses(f, z)) {
                    Long c = u instanceof Instr.Bin b && b.op == Instr.BinOp.MUL ? factor(b, z) : null;
                    if (c == null || c < 1 || 255 * c > 0xFFFF || scale != 0 && scale != c) {
                        return null;
                    }
                    scale = c;
                    products.add((Instr.Bin) u);
                }
            } else if (use instanceof Instr.Cmp c && unsigned(c.pred)
                    && (c.op(0) instanceof Value.Param || c.op(0) instanceof Value.Const)
                    && (c.op(1) instanceof Value.Param || c.op(1) instanceof Value.Const)) {
                compares.add(c);
            } else {
                return null;
            }
        }
        return scale == 0 ? null : new Plan(p, scale, products, compares);
    }

    /** The constant a multiplication multiplies {@code z} by, or null. */
    private static Long factor(Instr.Bin b, Instr z) {
        if (b.op(0) == z && b.op(1) instanceof Value.Const c) {
            return c.bits();
        }
        if (b.op(1) == z && b.op(0) instanceof Value.Const c) {
            return c.bits();
        }
        return null;
    }

    private static boolean unsigned(Instr.Pred p) {
        return switch (p) {
            case EQ, NE, ULT, ULE, UGT, UGE -> true;
            default -> false;
        };
    }

    private static List<Instr> uses(MFunction f, Value v) {
        List<Instr> out = new ArrayList<>();
        for (Block b : f.blocks()) {
            for (Instr i : b.instrs()) {
                if (i.operands().contains(v)) {
                    out.add(i);
                }
            }
        }
        return out;
    }
}
