package dampf.mir;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Inlining: a call of a small function is replaced by a copy of its body. On the 65816 a call
 * costs about 30 cycles besides the work (JSL and RTL, the arguments copied into the callee's
 * frame, the result copied back), and after inlining the other passes see both sides: a
 * comparison the callee returns as a truth value becomes the caller's branch, loads of the same
 * field are shared, and so on.
 *
 * <p>Inlined are functions of at most {@link #SMALL} instructions, and functions with a single
 * call site of at most {@link #SINGLE} instructions, or {@link #SINGLE_IN_LOOP} where that call
 * lies in a loop of the caller and the callee has no loop of its own (the call is paid in every
 * round), as long as the caller stays below {@link #CALLER_LIMIT}. Callees are inlined bottom-up: the call graph has no cycles, so the
 * leaves first, then the functions that called them.
 */
public final class Inline {
    static final int SMALL = 60;
    static final int SINGLE = 150;
    /**
     * A single call site in a loop, of a callee without loops (K4 of
     * docs/current-compiler-flaws.md: Steinschlag's {@code fall}, called for every cell; a callee
     * with a loop of its own made Mandelbrot slower when inlined so).
     */
    static final int SINGLE_IN_LOOP = 600;
    static final int CALLER_LIMIT = 1500;

    private Inline() {}

    /** Returns whether anything was inlined. */
    public static boolean run(MModule m) {
        return run(m, f -> { });
    }

    /**
     * {@code simplify} runs on a function after something was inlined into it, before it is
     * considered for inlining into its callers: its size is then that of the simplified body (as
     * LLVM's inliner simplifies a function before its callers see it).
     */
    public static boolean run(MModule m, java.util.function.Consumer<MFunction> simplify) {
        return run(m, simplify, dampf.util.PassLimit.none());
    }

    /** As {@link #run(MModule, java.util.function.Consumer)}; each inlined call is one application of the limit. */
    public static boolean run(MModule m, java.util.function.Consumer<MFunction> simplify, dampf.util.PassLimit limit) {
        counter = 0;
        Map<String, Integer> sites = new HashMap<>();
        for (MFunction f : m.functions()) {
            for (Block b : f.blocks()) {
                for (Instr i : b.instrs()) {
                    if (i instanceof Instr.Call c) {
                        sites.merge(c.callee, 1, Integer::sum);
                    }
                }
            }
        }
        boolean changed = false;
        for (MFunction f : order(m)) {
            boolean into = false;
            boolean again = true;
            while (again) {
                again = false;
                for (Block b : List.copyOf(f.blocks())) {
                    for (Instr i : List.copyOf(b.instrs())) {
                        if (i instanceof Instr.Call c && i.block() == b) {
                            MFunction callee = m.function(c.callee);
                            int n = size(callee);
                            boolean single = sites.getOrDefault(c.callee, 0) == 1
                                    && (n <= SINGLE || n <= SINGLE_IN_LOOP && inLoop(f, b) && Loops.find(callee).isEmpty());
                            boolean small = n <= SMALL || single;
                            if (callee != f && small && size(f) + n <= CALLER_LIMIT && limit.allows()) {
                                inline(f, c, callee);
                                limit.count("Inline " + callee.name(), f.name());
                                changed = true;
                                into = true;
                                again = true;
                                break;
                            }
                        }
                    }
                    if (again) {
                        break;
                    }
                }
            }
            if (into) {
                simplify.accept(f);
            }
        }
        return changed;
    }

    /** Whether block b lies in a loop of f. */
    private static boolean inLoop(MFunction f, Block b) {
        for (Loops.Loop l : Loops.find(f)) {
            if (l.blocks().contains(b)) {
                return true;
            }
        }
        return false;
    }

    static int size(MFunction f) {
        int n = 0;
        for (Block b : f.blocks()) {
            n += b.instrs().size();
        }
        return n;
    }

    /** The functions, callees before their callers. */
    private static List<MFunction> order(MModule m) {
        List<MFunction> out = new ArrayList<>();
        Map<MFunction, Boolean> seen = new IdentityHashMap<>();
        for (MFunction f : m.functions()) {
            visit(m, f, seen, out);
        }
        return out;
    }

    private static void visit(MModule m, MFunction f, Map<MFunction, Boolean> seen, List<MFunction> out) {
        if (seen.containsKey(f)) {
            return;
        }
        seen.put(f, true);
        for (Block b : f.blocks()) {
            for (Instr i : b.instrs()) {
                if (i instanceof Instr.Call c && m.function(c.callee) != null) {
                    visit(m, m.function(c.callee), seen, out);
                }
            }
        }
        out.add(f);
    }

    private static int counter;

    /** Replaces the call c in f by a copy of callee's body. */
    static void inline(MFunction f, Instr.Call c, MFunction callee) {
        Block b = c.block();
        int at = b.instrs().indexOf(c);
        String tag = callee.name().replaceFirst("^_", "") + (counter++);

        // the code after the call moves to a new block; b's successors now come from there
        Block cont = f.newBlock("after_" + tag);
        List<Instr> after = new ArrayList<>(b.instrs().subList(at + 1, b.instrs().size()));
        b.instrs().subList(at, b.instrs().size()).clear();
        c.block = null;
        for (Instr i : after) {
            i.block = cont;
            cont.instrs().add(i);
        }
        for (Block s : cont.terminator().successors()) {
            java.util.Collections.replaceAll(s.preds(), b, cont);
            for (Instr i : s.instrs()) {
                if (i instanceof Instr.Phi p) {
                    java.util.Collections.replaceAll(p.from, b, cont);
                }
            }
        }

        // the callee's values, slots and blocks, copied
        Map<Value, Value> map = new IdentityHashMap<>();
        for (int k = 0; k < callee.params().size(); k++) {
            map.put(callee.params().get(k), c.op(k));
        }
        Map<Value.Slot, Value.Slot> slots = new HashMap<>();
        for (Value.Slot s : callee.slots()) {
            Value.Slot n = new Value.Slot(s.name() + "_" + tag, s.size());
            f.slots().add(n);
            slots.put(s, n);
        }
        Map<Block, Block> blocks = new IdentityHashMap<>();
        for (Block cb : callee.blocks()) {
            blocks.put(cb, f.newBlock(tag + "_" + cb.name().replaceFirst("^b\\d+_?", "")));
        }
        Instr.Phi result = c.type() == MType.VOID ? null : new Instr.Phi(c.type(), c.pos());
        List<Instr.Phi> phis = new ArrayList<>();
        for (Block cb : callee.blocks()) {
            Block nb = blocks.get(cb);
            for (Instr i : cb.instrs()) {
                Instr n = copy(i, blocks, cont);
                if (n instanceof Instr.Phi p) {
                    phis.add(p);
                }
                map.put(i, n);
                nb.add(n);
                if (i instanceof Instr.Ret r && result != null) {
                    result.add(nb, r.operands().isEmpty() ? new Value.Undef(c.type()) : r.op(0));
                }
            }
        }
        // operands refer to the copies (φ operands may come from later blocks, so this is a second pass)
        for (Block cb : callee.blocks()) {
            for (Instr i : blocks.get(cb).instrs()) {
                for (int k = 0; k < i.operands().size(); k++) {
                    i.setOp(k, resolve(i.op(k), map, slots));
                }
            }
        }
        b.add(new Instr.Jump(blocks.get(callee.entry()), c.pos()));
        Value value = null;
        if (result != null) {
            for (int k = 0; k < result.operands().size(); k++) {
                result.setOp(k, resolve(result.op(k), map, slots));
            }
            if (result.operands().isEmpty()) {
                value = new Value.Undef(c.type());   // the callee never returns
            } else {
                cont.add(result);
                value = result;
            }
        }
        if (value != null) {
            DeadCode.replaceUses(f, Map.of(c, value));
        }
    }

    private static Value resolve(Value v, Map<Value, Value> map, Map<Value.Slot, Value.Slot> slots) {
        if (v instanceof Value.SlotAddr s) {
            return new Value.SlotAddr(slots.get(s.slot()));
        }
        Value r = map.get(v);
        return r != null ? r : v;
    }

    /** A copy of i with its blocks mapped; its operands are fixed afterwards. */
    private static Instr copy(Instr i, Map<Block, Block> blocks, Block cont) {
        return switch (i) {
            case Instr.Bin x -> new Instr.Bin(x.op, x.op(0), x.op(1), x.pos());
            case Instr.Cmp x -> new Instr.Cmp(x.pred, x.op(0), x.op(1), x.pos());
            case Instr.Cast x -> new Instr.Cast(x.kind, x.op(0), x.type(), x.pos());
            case Instr.PtrAdd x -> new Instr.PtrAdd(x.op(0), x.op(1), x.pos());
            case Instr.Load x -> new Instr.Load(x.type(), x.op(0), x.isVolatile, x.pos());
            case Instr.Store x -> new Instr.Store(x.op(0), x.op(1), x.isVolatile, x.pos());
            case Instr.Copy x -> new Instr.Copy(x.op(0), x.op(1), x.size, x.pos());
            case Instr.Call x -> new Instr.Call(x.callee, x.type(), x.operands(), x.pos());
            case Instr.Phi x -> {
                Instr.Phi p = new Instr.Phi(x.type(), x.pos());
                // the copy takes the same values, so what a pass knew of them still holds (an
                // induction variable's range: without it, the inlined loop indexes with a 24-bit
                // pointer); a range that depends on a parameter's holds for every call site
                p.known = x.known;
                for (int k = 0; k < x.from.size(); k++) {
                    p.add(blocks.get(x.from.get(k)), x.op(k));
                }
                yield p;
            }
            case Instr.Jump x -> new Instr.Jump(blocks.get(x.target), x.pos());
            case Instr.Branch x -> new Instr.Branch(x.op(0), blocks.get(x.ifTrue), blocks.get(x.ifFalse), x.pos());
            case Instr.Switch x -> {
                Instr.Switch s = new Instr.Switch(x.op(0), blocks.get(x.otherwise), x.pos());
                s.cases.addAll(x.cases);
                for (Block t : x.targets) {
                    s.targets.add(blocks.get(t));
                }
                yield s;
            }
            case Instr.Ret x -> new Instr.Jump(cont, x.pos());
            case Instr.Unreachable x -> new Instr.Unreachable(x.pos());
        };
    }
}
