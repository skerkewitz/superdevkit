package dampf.backend;

import dampf.lir.LOperand;

import dampf.mir.Block;
import dampf.mir.CountedLoops;
import dampf.mir.Instr;
import dampf.mir.MFunction;
import dampf.mir.MType;
import dampf.mir.Ranges;
import dampf.mir.Value;
import dampf.target.SnesTarget;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The analyses on MIR that decide how selection keeps values: results handed on in A, byte
 * masks, loop counters in X, indexes in Y.
 */
final class SelectionPlan {
    private final Selection sel;

    SelectionPlan(Selection sel) {
        this.sel = sel;
    }

    /**
     * Finds the results that need not be stored: a 1-word result whose only use is the next
     * instruction written in the same block, where that instruction starts by loading it into A
     * and reads it nowhere else. Only instructions whose code is known to begin that way qualify.
     */
    void findForwards(MFunction f) {
        Map<Instr, Integer> uses = new HashMap<>();
        Map<Instr, Instr> user = new HashMap<>();
        for (Block b : f.blocks()) {
            for (Instr i : b.instrs()) {
                for (Value v : i.operands()) {
                    if (v instanceof Instr d) {
                        uses.merge(d, 1, Integer::sum);
                        user.put(d, i);
                    }
                }
            }
        }
        for (Block b : f.blocks()) {
            List<Instr> written = new ArrayList<>();
            for (Instr i : b.instrs()) {
                if (!(i instanceof Instr.Phi) && !sel.fused.contains(i) && !sel.folded.contains(i)) {
                    written.add(i);
                }
            }
            for (int k = 0; k + 1 < written.size(); k++) {
                Instr i = written.get(k);
                Instr u = written.get(k + 1);
                if (!producesInA(i) || uses.getOrDefault(i, 0) != 1) {
                    continue;
                }
                // what u sees: i itself, or a cast without code of it (that only u reads)
                Instr seen = i;
                while (user.get(seen) != u && FrameLayout.transparent(user.get(seen))
                        && uses.getOrDefault(user.get(seen), 0) == 1) {
                    seen = user.get(seen);
                }
                if (Selection.words(seen.type()) == 1 && (user.get(seen) == u || u.isTerminator()) && readsFirst(u, seen, b)) {
                    sel.forward.add(i);
                }
            }
        }
    }

    /** i's code ends with A holding its 1-word result, stored with result(). */
    static boolean producesInA(Instr i) {
        if (i.type() == MType.VOID || Selection.words(i.type()) != 1 || FrameLayout.transparent(i)) {
            return false;
        }
        return i instanceof Instr.Bin || i instanceof Instr.Load || i instanceof Instr.Cast
                || i instanceof Instr.Call || i instanceof Instr.Cmp;
    }

    /** u's code starts by loading v into A, and reads v nowhere else. */
    boolean readsFirst(Instr u, Instr v, Block b) {
        if (u.isTerminator() && firstPhiCopyReads(b, v)) {
            return true;   // the first φ copy at the end of the block loads v
        }
        long n = u.operands().stream().filter(o -> o == v).count();
        if (n != 1) {
            return u instanceof Instr.Branch br && br.op(0) instanceof Instr.Cmp c && sel.fused.contains(c)
                    && n == 0 && fusedReadsFirst(c, v, b);
        }
        return switch (u) {
            case Instr.Bin x -> Selection.words(x.type()) == 1 && switch (x.op) {
                case ADD, AND, OR, XOR -> true;     // commutative: v is loaded first either way (bin swaps)
                case SUB -> x.op(0) == v;
                case SHL, LSHR, ASHR -> x.op(0) == v && ArithSelection.unrolled(x);
                default -> false;
            };
            case Instr.Store s -> s.op(1) == v && Selection.words(v.type()) == 1 && addressNeedsNoA(s.op(0));
            case Instr.Ret r -> true;
            case Instr.Cast c -> c.kind == Instr.CastKind.SEXT || c.kind == Instr.CastKind.TRUNC && Selection.words(c.type()) == 1;
            case Instr.Call c -> c.op(0) == v;
            case Instr.Cmp c -> cmpReadsFirst(c, v);
            // an i16 offset in A: the pointer is added to it
            // (not an indexed access: its loads read the offset into X from its place)
            case Instr.PtrAdd p -> p.op(1) == v && v.type() == MType.I16 && p.op(0) != v && !sel.layout.indexable(p);
            default -> false;
        };
    }

    /**
     * Whether the code at the end of b starts with a φ copy that loads v (and v is read nowhere
     * else there): then v is handed on in A and stored into the incoming place from it.
     */
    boolean firstPhiCopyReads(Block b, Instr v) {
        for (Block s : new LinkedHashSet<>(b.terminator().successors())) {
            for (Instr i : s.instrs()) {
                if (!(i instanceof Instr.Phi p)) {
                    break;
                }
                if (sel.xResident.containsKey(p)) {
                    return false;   // the copies end with LDX for it: keep it simple
                }
                Value op = p.op(p.from.indexOf(b));
                // (every copy is code or an LCopy, which leaves nothing known in A) v predicted
                // in the φ's own place is stored there (perhaps stepped in place): the copies coalesce
                return op == v && Selection.words(p.type()) == 1 && !java.util.Objects.equals(sel.place.get(v), sel.place.get(p));
            }
        }
        return false;
    }

    /** A fused comparison at the end of b whose code starts with "lda v" (and no φ copies come first). */
    boolean fusedReadsFirst(Instr.Cmp c, Value v, Block b) {
        for (Block s : b.terminator().successors()) {
            if (!s.instrs().isEmpty() && s.instrs().getFirst() instanceof Instr.Phi) {
                return false;
            }
        }
        return cmpReadsFirst(c, v);
    }

    /** The code of comparison c (with its value, or fused with the branch) starts by loading v. */
    static boolean cmpReadsFirst(Instr.Cmp c, Value v) {
        if (Selection.words(c.op(0).type()) != 1 || c.op(0) == c.op(1)) {
            return false;
        }
        return switch (c.pred) {
            case EQ, NE, ULT, UGE -> c.op(0) == v;
            case SLT, SGE -> c.op(0) == v && c.op(1) instanceof Value.Const;
            case UGT, ULE -> c.op(1) == v;
            case SGT, SLE -> c.op(1) == v && c.op(0) instanceof Value.Const;
        };
    }

    /** A load or store at this address needs A for nothing before the access itself. */
    boolean addressNeedsNoA(Value address) {
        FrameLayout.Address a = sel.memory.address(address);
        if (sel.memory.isFixed(a)) {
            return true;
        }
        Value p = a.base();
        while (p instanceof Instr i && FrameLayout.transparent(i)) {
            p = i.op(0);
        }
        return (p instanceof Value.Param || p instanceof Instr) && sel.place.containsKey(p) && SnesTarget.inDirectPage(sel.place.get(p), 4);
    }

    /**
     * Lazy byte masks. An i8 value in A or in its place may carry any high byte, unless one of its
     * readers needs it clean: comparisons, extensions (they need no code, their readers read the
     * place as 16 bits), right shifts and divisions, shift counts, calls, returns and switches
     * (parameters and results are clean by convention). A φ needs it if a reader of the φ does,
     * and passes that on to its operands. Clean by themselves: constants, parameters, call
     * results, an AND with a clean operand, an OR or XOR of clean ones, a right shift or unsigned
     * division (their operands are clean), a sum or product of clean operands the value ranges
     * keep below 256, a truth value extended to a byte.
     */
    void findMasks(MFunction f) {
        sel.masked.clear();
        Set<Value> need = sel.clean;
        need.clear();
        java.util.ArrayDeque<Value> work = new java.util.ArrayDeque<>();
        for (Block b : f.blocks()) {
            for (Instr u : b.instrs()) {
                for (int k = 0; k < u.operands().size(); k++) {
                    Value v = u.op(k);
                    if (v.type() == MType.I8 && (v instanceof Instr || v instanceof Value.Param) && needsClean(u, k) && need.add(v)) {
                        work.add(v);
                    }
                }
            }
        }
        while (!work.isEmpty()) {
            if (work.removeFirst() instanceof Instr.Phi p) {
                for (Value v : p.operands()) {
                    if (v.type() == MType.I8 && (v instanceof Instr || v instanceof Value.Param) && need.add(v)) {
                        work.add(v);
                    }
                }
            }
        }
        Map<Value, Boolean> natural = new HashMap<>();
        for (Value v : need) {
            if (v instanceof Instr i && !(i instanceof Instr.Phi) && !naturallyClean(i, need, natural)) {
                sel.masked.add(i);
            }
        }
    }

    static boolean needsClean(Instr u, int k) {
        return switch (u) {
            case Instr.Phi p -> false;   // passed on when the φ itself needs it
            case Instr.Store s -> k == 0;
            case Instr.Bin b -> switch (b.op) {
                case ADD, SUB, MUL, AND, OR, XOR -> false;
                case SHL -> k == 1;
                default -> true;
            };
            case Instr.Cast c -> c.kind != Instr.CastKind.TRUNC;
            default -> true;
        };
    }

    boolean clean(Value v, Set<Value> need, Map<Value, Boolean> natural) {
        if (!(v instanceof Instr i) || v.type() != MType.I8) {
            return true;   // constants, parameters, undefined values, truth values
        }
        return need.contains(v) || !(i instanceof Instr.Phi) && naturallyClean(i, need, natural);
    }

    boolean naturallyClean(Instr i, Set<Value> need, Map<Value, Boolean> natural) {
        Boolean known = natural.get(i);
        if (known != null) {
            return known;
        }
        natural.put(i, false);   // (a cycle runs through a φ, which is not looked into)
        boolean r = switch (i) {
            case Instr.Call c -> true;
            case Instr.Bin b -> switch (b.op) {
                case AND -> clean(b.op(0), need, natural) || clean(b.op(1), need, natural);
                case OR, XOR -> clean(b.op(0), need, natural) && clean(b.op(1), need, natural);
                case LSHR, UDIV, UREM -> true;
                case ADD, SUB, MUL, SHL -> sel.arith.staysByte(b) && clean(b.op(0), need, natural) && clean(b.op(1), need, natural);
                default -> false;
            };
            case Instr.Cast c -> c.kind == Instr.CastKind.ZEXT;
            default -> false;
        };
        natural.put(i, r);
        return r;
    }

    /**
     * Indexes that prefer Y: used in a loop where they do not change, while the loop also indexes
     * with something else (the bubble a of the pairs (a, b), whose b changes in the inner loop).
     * Kept in Y wherever they are used, they stay there across the inner loop's rounds (what Y
     * holds is known across blocks), and X is left to the index that changes.
     */
    void findYIndexes(MFunction f) {
        sel.yIndexes.clear();
        for (dampf.mir.Loops.Loop loop : dampf.mir.Loops.find(f)) {
            Set<Value> indexes = new HashSet<>();
            for (Block b : loop.blocks()) {
                for (Instr i : b.instrs()) {
                    if (i instanceof Instr.Load || i instanceof Instr.Store) {
                        FrameLayout.Address a = sel.memory.address(i.op(0));
                        if (a.index() != null) {
                            indexes.add(Selection.throughCopies(a.index()));
                        }
                    }
                }
            }
            if (indexes.size() < 2) {
                continue;
            }
            for (Value v : indexes) {
                boolean outside = !(v instanceof Instr d) || !loop.blocks().contains(d.block());
                if (outside && !sel.xResident.containsKey(v)) {
                    sel.yIndexes.add(v);
                }
            }
        }
    }

    /**
     * Chooses loop values to keep in X for the whole loop: a φ of the loop head that is only
     * compared with constants (EQ, NE, ULT, UGE: CPX), used as the index of the loop's array
     * accesses (no LDX), and stepped by a constant on the way back (INX, DEX, or TXA/ADC/TAX).
     * Nothing in the loop may need X otherwise (calls, the runtime's multiplication and
     * division, variable shifts, 32-bit sign extension, another index), the value is not used after
     * the loop, and an 8-bit counter must not wrap past 255. Loops are looked at innermost first;
     * a loop around one that has X cannot have it too.
     */
    void findXCounters(MFunction f) {
        sel.xResident.clear();
        sel.xSteps.clear();
        sel.xBlocks.clear();
        sel.xBounds.clear();
        Map<Value, List<Instr>> users = new HashMap<>();
        for (Block b : f.blocks()) {
            for (Instr i : b.instrs()) {
                for (Value v : i.operands()) {
                    users.computeIfAbsent(v, k -> new ArrayList<>()).add(i);
                }
            }
        }
        // where loops that have X load their start value, and the value
        Map<Block, Value> entries = new HashMap<>();
        List<CountedLoops.Loop> all = CountedLoops.find(f);
        for (CountedLoops.Loop counted : all) {
            dampf.mir.Loops.Loop loop = counted.loop();
            if (loop.blocks().stream().anyMatch(b -> sel.xBlocks.containsKey(b) || entries.containsKey(b)) || clobbersX(loop)) {
                continue;
            }
            // the start value goes into X at the end of the blocks that enter the loop: X must be
            // free there (two loops in a row: the first one's test block enters the second)
            List<Block> enters = loop.head().preds().stream().filter(p -> !loop.blocks().contains(p)).toList();
            if (enters.stream().anyMatch(sel.xBlocks::containsKey)) {
                continue;
            }
            Instr best = null;
            Instr bestStep = null;
            long bestC = 0;
            int bestUses = 0;
            for (Instr i : loop.head().instrs()) {
                if (!(i instanceof Instr.Phi p)) {
                    break;
                }
                CountedLoops.Counter counter = p.type() == MType.I8 || p.type() == MType.I16 ? counted.counter(p) : null;
                Instr step = counter == null ? null : counter.step();
                boolean ok = true;
                for (int k = 0; k < p.from.size() && ok; k++) {
                    // a start value that another loop keeps in X has no place to load it from
                    if (!loop.blocks().contains(p.from.get(k)) && p.op(k) instanceof Instr d
                            && (sel.xResident.containsKey(d) || sel.xSteps.containsKey(d))) {
                        ok = false;
                    }
                }
                // the step runs once a round (in no inner loop); from it to the way back X holds
                // the stepped value, before it the φ's
                Set<Block> after = step == null ? null : after(loop, step.block());
                if (!ok || step == null || after == null || inInnerLoop(step.block(), counted, all)) {
                    continue;
                }
                long c = counter.c();
                if (p.type() == MType.I8) {
                    Ranges.Range r = sel.ranges.atUnsigned(p, step.block());
                    if (r == null || r.lo() + c < 0 || r.hi() + c > 0xFF) {
                        continue;   // it would wrap, and X would not
                    }
                }
                int indexUses = otherIndex(loop, p, step) ? -1 : usesFitX(p, step, loop, users, after);
                if (indexUses < 0 || indexUses <= bestUses && best != null) {
                    continue;
                }
                best = p;
                bestStep = step;
                bestC = c;
                bestUses = indexUses;
            }
            // a block that enters two loops keeping X (one on each edge of its branch) sets X
            // once: both must start from the same value
            Instr.Phi chosen = best instanceof Instr.Phi bp ? bp : null;
            if (chosen != null && enters.stream().anyMatch(e -> entries.containsKey(e)
                    && !same(entries.get(e), chosen.op(chosen.from.indexOf(e))))) {
                best = null;
            }
            if (best != null && bestUses > 0) {
                usesFitX((Instr.Phi) best, bestStep, loop, users, after(loop, bestStep.block()));
                sel.xBounds.addAll(bounds);
                for (Block e : enters) {
                    entries.put(e, chosen.op(chosen.from.indexOf(e)));
                }
                sel.xResident.put(best, bestStep);
                sel.xSteps.put(bestStep, bestC);
                for (Block b : loop.blocks()) {
                    sel.xBlocks.put(b, best);
                }
            }
        }
    }

    /**
     * The blocks of the loop that a round reaches after block s without passing the head; null
     * if s itself is among them (s in a cycle of its own).
     */
    static Set<Block> after(dampf.mir.Loops.Loop loop, Block s) {
        Set<Block> out = new HashSet<>();
        List<Block> work = new ArrayList<>(s.terminator().successors());
        while (!work.isEmpty()) {
            Block b = work.removeLast();
            if (b == loop.head() || !loop.blocks().contains(b) || !out.add(b)) {
                continue;
            }
            work.addAll(b.terminator().successors());
        }
        return out.contains(s) ? null : out;
    }

    /** Whether b lies in a loop inside {@code loop}. */
    private static boolean inInnerLoop(Block b, CountedLoops.Loop loop, List<CountedLoops.Loop> all) {
        for (CountedLoops.Loop l : all) {
            if (l != loop && loop.blocks().contains(l.head()) && l.blocks().contains(b)) {
                return true;
            }
        }
        return false;
    }

    /** Whether two values are the same: one value, or equal constants. */
    private static boolean same(Value a, Value b) {
        return a == b || a instanceof Value.Const p && b instanceof Value.Const q && p.bits() == q.bits();
    }

    /** The bounds in a place the last {@link #usesFitX} let the counter be compared with. */
    private final List<Value> bounds = new ArrayList<>();

    /**
     * Whether a 16-bit counter in X can be compared with b where b is kept (CPX on its place): b
     * is a 16-bit value from outside the loop that has a place (not in X itself, and not only
     * handed on in A, which findForwards then leaves out).
     */
    private boolean placeBound(Value b, Instr x, dampf.mir.Loops.Loop loop) {
        if (x.type() != MType.I16 || b.type() != MType.I16 || b instanceof Value.Const) {
            return false;
        }
        if (b instanceof Instr.Cast z && FrameLayout.transparent(z) && z.kind == Instr.CastKind.ZEXT) {
            // a zero extension has no place: CPX reads its source's, which holds it zero-extended
            Value src = z.op(0);
            if (src instanceof Instr d && d.block() != null && !loop.blocks().contains(d.block()) && !sel.xResident.containsKey(d)
                    && !sel.xSteps.containsKey(d) && !sel.folded.contains(d) && !sel.fused.contains(d)) {
                bounds.add(src);
                return true;
            }
            return src instanceof Value.Param;
        }
        if (b instanceof Instr d) {
            return d.block() != null && !loop.blocks().contains(d.block()) && !sel.xResident.containsKey(d)
                    && !sel.xSteps.containsKey(d) && !sel.folded.contains(d) && !sel.fused.contains(d);
        }
        return b instanceof Value.Param;
    }

    /** Whether u runs after the step in a round: later in the step's block, or in a block after it. */
    private static boolean isAfter(Instr u, Instr step, Set<Block> after) {
        Block s = step.block();
        return u.block() == s ? s.instrs().indexOf(u) > s.instrs().indexOf(step) : after.contains(u.block());
    }

    /**
     * How often v or its stepped value (or a copy of either) indexes an array access in the loop,
     * or -1 if one of them has a use that needs it somewhere other than X: after the loop, v
     * after the step (X has the next value by then), or anything but a fused comparison with a
     * constant, an index, or the zero extension of a byte.
     */
    int usesFitX(Instr.Phi v, Instr step, dampf.mir.Loops.Loop loop, Map<Value, List<Instr>> users, Set<Block> after) {
        int indexes = 0;
        bounds.clear();
        for (Instr x : List.of(v, step)) {
            List<Instr> work = new ArrayList<>(users.getOrDefault(x, List.of()));
            for (int n = 0; n < work.size(); n++) { // NOPMD ForLoopCanBeForeach: work grows in the loop
                Instr u = work.get(n);
                if (!loop.blocks().contains(u.block())) {
                    return -1;
                }
                if (x == v ? u == step : u == v) {
                    continue;   // the step, and the stepped value on the way back
                }
                if (x == v && isAfter(u, step, after)) {
                    return -1;   // read after the step: X has the next value by then
                }
                switch (u) {
                    case Instr.Cast c when FrameLayout.transparent(c) && c.kind == Instr.CastKind.ZEXT && c.type() == MType.I16 ->
                            work.addAll(users.getOrDefault(c, List.of()));
                    case Instr.Cmp c when sel.fused.contains(c) && Selection.throughCopies(c.op(0)) == x && c.op(1) instanceof Value.Const k
                            && (c.pred == Instr.Pred.EQ || c.pred == Instr.Pred.NE || c.pred == Instr.Pred.ULT || c.pred == Instr.Pred.UGE
                            || (c.pred == Instr.Pred.SLT || c.pred == Instr.Pred.SGE) && x.type() == MType.I16 && k.bits() == 0) -> { }
                    case Instr.Cmp c when sel.fused.contains(c) && Selection.throughCopies(c.op(0)) == x && placeBound(c.op(1), x, loop)
                            && (c.pred == Instr.Pred.EQ || c.pred == Instr.Pred.NE || c.pred == Instr.Pred.ULT || c.pred == Instr.Pred.UGE) ->
                            bounds.add(c.op(1));
                    // x plus a constant that only indexes (a[i + 1]): the accesses add it in the operand
                    case Instr.Bin b when sel.folded.contains(b) && b.op == Instr.BinOp.ADD ->
                            work.addAll(users.getOrDefault(b, List.of()));
                    case Instr.PtrAdd q when sel.folded.contains(q) && sel.layout.indexable(q)
                            && Selection.throughCopies(sel.layout.indexOf(q)) == x && q.op(0) != x -> {
                        // the address is formed where it is used, with X: the φ's after the step
                        // would take the next element (an offset added to it, then a store
                        // after the step), and the stepped value's must stay in the round
                        if (x == v ? usedAfter(q, step, users, after) : leavesRound(q, users)) {
                            return -1;
                        }
                        indexes++;
                    }
                    default -> {
                        return -1;
                    }
                }
            }
        }
        return indexes;
    }

    /** Whether an address (or one formed from it by a further ptradd) is used after the step. */
    private static boolean usedAfter(Value address, Instr step, Map<Value, List<Instr>> users, Set<Block> after) {
        List<Instr> work = new ArrayList<>(users.getOrDefault(address, List.of()));
        for (int n = 0; n < work.size(); n++) { // NOPMD ForLoopCanBeForeach: work grows in the loop
            Instr u = work.get(n);
            if (isAfter(u, step, after)) {
                return true;
            }
            if (u instanceof Instr.PtrAdd || u instanceof Instr.Copy) {
                work.addAll(users.getOrDefault(u, List.of()));
            }
        }
        return false;
    }

    /** Whether an address (or one formed from it) goes into a φ, to be used in another round. */
    private static boolean leavesRound(Value address, Map<Value, List<Instr>> users) {
        List<Instr> work = new ArrayList<>(users.getOrDefault(address, List.of()));
        for (int n = 0; n < work.size(); n++) { // NOPMD ForLoopCanBeForeach: work grows in the loop
            Instr u = work.get(n);
            if (u instanceof Instr.Phi) {
                return true;
            }
            if (u instanceof Instr.PtrAdd || u instanceof Instr.Copy) {
                work.addAll(users.getOrDefault(u, List.of()));
            }
        }
        return false;
    }

    /**
     * Whether an array access in the loop is indexed by something other than v (or its step) and would load X
     * for it. With v in X, another index goes into Y (fixedAt), which works for LDA and STA at
     * an absolute address; STZ (a store of 0) and accesses in the direct page or at a long
     * address have no absolute,Y form.
     */
    boolean otherIndex(dampf.mir.Loops.Loop loop, Instr v, Instr step) {
        for (Block b : loop.blocks()) {
            for (Instr i : b.instrs()) {
                if (i instanceof Instr.Load || i instanceof Instr.Store) {
                    FrameLayout.Address a = sel.layout.address(i.op(0));
                    LOperand.Mem f = sel.fixed(a.base(), a.offset());
                    Value index = a.index() == null ? null : Selection.throughCopies(a.index());
                    if (index != null && f != null && index != v && index != step) {
                        boolean viaY = f.width() != LOperand.Width.DP && f.width() != LOperand.Width.LONG
                                && !(i instanceof Instr.Store s && s.op(1) instanceof Value.Const k && k.bits() == 0);
                        if (!viaY) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /** Whether code in the loop needs X for something else. */
    boolean clobbersX(dampf.mir.Loops.Loop loop) {
        for (Block b : loop.blocks()) {
            for (Instr i : b.instrs()) {
                boolean bad = switch (i) {
                    case Instr.Call c -> true;
                    case Instr.Bin x -> switch (x.op) {
                        case MUL -> Selection.words(x.type()) == 2 || !(x.op(0) instanceof Value.Const || x.op(1) instanceof Value.Const);
                        case UDIV, UREM, SDIV, SREM -> true;
                        case SHL, LSHR, ASHR -> Selection.words(x.type()) == 2 || !ArithSelection.unrolled(x);
                        default -> false;
                    };
                    case Instr.Cast c -> c.kind == Instr.CastKind.SEXT && Selection.words(c.type()) == 2;
                    case Instr.Ret r -> !r.operands().isEmpty() && Selection.words(r.op(0).type()) == 2;
                    default -> false;
                };
                if (bad) {
                    return true;
                }
            }
        }
        return false;
    }
}
