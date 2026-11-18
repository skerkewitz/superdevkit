package dampf.backend;

import dampf.mir.Block;
import dampf.mir.Instr;
import dampf.mir.MFunction;
import dampf.mir.MType;
import dampf.mir.Ranges;
import dampf.mir.Value;
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
                case SHL, LSHR, ASHR -> x.op(0) == v && x.op(1) instanceof Value.Const k && k.bits() <= ArithSelection.UNROLL_SHIFT
                        && k.bits() < x.type().bits();
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
     * else there): the first copy that is not left out because the operand shares the φ's place.
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
                int to = sel.direct.contains(s) ? sel.place.get(p) : sel.incoming.get(p);
                if (sel.operand(op, 0).equals(Selection.at(to)) && (Selection.words(p.type()) == 1 || sel.operand(op, 1).equals(Selection.at(to + 2)))) {
                    if (op == v) {
                        return false;   // v's place is the φ's: it must be stored
                    }
                    continue;           // no code
                }
                return op == v && Selection.words(p.type()) == 1;
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
        return (p instanceof Value.Param || p instanceof Instr) && sel.place.containsKey(p) && Selection.FRAME_START + sel.place.get(p) + 3 < 0x100;
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
        Map<Value, List<Instr>> users = new HashMap<>();
        for (Block b : f.blocks()) {
            for (Instr i : b.instrs()) {
                for (Value v : i.operands()) {
                    users.computeIfAbsent(v, k -> new ArrayList<>()).add(i);
                }
            }
        }
        Set<Block> entries = new HashSet<>();   // where loops that have X load their start value
        for (dampf.mir.Loops.Loop loop : dampf.mir.Loops.find(f)) {
            if (loop.blocks().stream().anyMatch(b -> sel.xBlocks.containsKey(b) || entries.contains(b)) || clobbersX(loop)) {
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
                if (p.type() != MType.I8 && p.type() != MType.I16) {
                    continue;
                }
                Instr step = null;
                boolean ok = true;
                for (int k = 0; k < p.from.size() && ok; k++) {
                    if (loop.blocks().contains(p.from.get(k))) {
                        if (!(p.op(k) instanceof Instr.Bin s) || s.op != Instr.BinOp.ADD || s.op(0) != p
                                || !(s.op(1) instanceof Value.Const) || step != null && step != s) {
                            ok = false;
                        } else {
                            step = s;
                        }
                    } else if (p.op(k) instanceof Instr d && (sel.xResident.containsKey(d) || sel.xSteps.containsKey(d))) {
                        ok = false;
                    }
                }
                // the step must be the last thing before the jump back (else later code in the
                // loop would see the next value in X already)
                if (!ok || step == null || !loop.latches().contains(step.block())
                        || !(step.block().terminator() instanceof Instr.Jump)
                        || users.getOrDefault(step, List.of()).size() != 1) {
                    continue;
                }
                long c = MType.I16.signed(((Value.Const) step.op(1)).bits());
                if (p.type() == MType.I8) {
                    c = MType.I8.signed(((Value.Const) step.op(1)).bits());
                    Ranges.Range r = Ranges.unsigned(sel.ranges.at(p, step.block()), MType.I8);
                    if (r == null || r.lo() + c < 0 || r.hi() + c > 0xFF) {
                        continue;   // it would wrap, and X would not
                    }
                }
                int indexUses = otherIndex(loop, p) ? -1 : usesFitX(p, step, loop, users);
                if (indexUses < 0 || indexUses <= bestUses && best != null) {
                    continue;
                }
                best = p;
                bestStep = step;
                bestC = c;
                bestUses = indexUses;
            }
            if (best != null && bestUses > 0) {
                entries.addAll(enters);
                sel.xResident.put(best, bestStep);
                sel.xSteps.put(bestStep, bestC);
                for (Block b : loop.blocks()) {
                    sel.xBlocks.put(b, best);
                }
            }
        }
    }

    /**
     * How often v (or a copy of it) indexes an array access in the loop, or -1 if v has a use
     * that needs it somewhere other than X (or after the loop, or after the step).
     */
    int usesFitX(Instr.Phi v, Instr step, dampf.mir.Loops.Loop loop, Map<Value, List<Instr>> users) {
        int indexes = 0;
        List<Instr> work = new ArrayList<>(users.getOrDefault(v, List.of()));
        for (int n = 0; n < work.size(); n++) { // NOPMD ForLoopCanBeForeach: work grows in the loop
            Instr u = work.get(n);
            if (!loop.blocks().contains(u.block())) {
                return -1;
            }
            if (u == step) {
                continue;
            }
            if (u.block() == step.block() && u.block().instrs().indexOf(u) > u.block().instrs().indexOf(step)) {
                return -1;   // read after the step: X has the next value by then
            }
            switch (u) {
                case Instr.Cast c when FrameLayout.transparent(c) && c.kind == Instr.CastKind.ZEXT && c.type() == MType.I16 ->
                        work.addAll(users.getOrDefault(c, List.of()));
                case Instr.Cmp c when sel.fused.contains(c) && Selection.throughCopies(c.op(0)) == v && c.op(1) instanceof Value.Const
                        && (c.pred == Instr.Pred.EQ || c.pred == Instr.Pred.NE || c.pred == Instr.Pred.ULT
                        || c.pred == Instr.Pred.UGE) -> { }
                case Instr.PtrAdd q when sel.folded.contains(q) && sel.layout.indexable(q) && Selection.throughCopies(q.op(1)) == v
                        && q.op(0) != v -> {
                    // the address is formed where it is used, with X: a use after the step would
                    // take the next element (an offset added to it, then a store after the step)
                    if (usedAfter(q, step, users)) {
                        return -1;
                    }
                    indexes++;
                }
                default -> {
                    return -1;
                }
            }
        }
        return indexes;
    }

    /** Whether an address (or one formed from it by a further ptradd) is used after the step, in the step's block. */
    private static boolean usedAfter(Value address, Instr step, Map<Value, List<Instr>> users) {
        List<Instr> work = new ArrayList<>(users.getOrDefault(address, List.of()));
        for (int n = 0; n < work.size(); n++) { // NOPMD ForLoopCanBeForeach: work grows in the loop
            Instr u = work.get(n);
            if (u.block() == step.block() && u.block().instrs().indexOf(u) > u.block().instrs().indexOf(step)) {
                return true;
            }
            if (u instanceof Instr.PtrAdd || u instanceof Instr.Copy) {
                work.addAll(users.getOrDefault(u, List.of()));
            }
        }
        return false;
    }

    /**
     * Whether an array access in the loop is indexed by something other than v and would load X
     * for it. With v in X, another index goes into Y (fixedAt), which works for LDA and STA at
     * an absolute address; STZ (a store of 0) and accesses in the direct page or at a long
     * address have no absolute,Y form.
     */
    boolean otherIndex(dampf.mir.Loops.Loop loop, Instr v) {
        for (Block b : loop.blocks()) {
            for (Instr i : b.instrs()) {
                if (i instanceof Instr.Load || i instanceof Instr.Store) {
                    FrameLayout.Address a = sel.layout.address(i.op(0));
                    String f = sel.fixed(a.base(), a.offset());
                    if (a.index() != null && f != null && Selection.throughCopies(a.index()) != v) {
                        boolean viaY = !f.startsWith("z:") && !f.startsWith("f:")
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
                        case SHL, LSHR, ASHR -> Selection.words(x.type()) == 2 || !(x.op(1) instanceof Value.Const k)
                                || k.bits() > ArithSelection.UNROLL_SHIFT || k.bits() >= x.type().bits();
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
