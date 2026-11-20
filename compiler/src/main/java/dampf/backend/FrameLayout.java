package dampf.backend;

import dampf.mir.Block;
import dampf.mir.Instr;
import dampf.mir.MFunction;
import dampf.mir.MType;
import dampf.mir.Ranges;
import dampf.mir.Value;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where the values of one function live in its frame, and which instructions the code generator
 * folds into others. Values whose lifetimes do not overlap share a place: liveness over the
 * blocks, an interference graph, and greedy colouring by byte ranges.
 *
 * <p>What the code generator does that the analysis has to know:
 * <ul>
 * <li>A comparison only its block's branch uses is evaluated at the branch ("fused"), so its
 *     operands are used there.</li>
 * <li>A load or store at {@code ptradd p, c} (a constant 0..32767) reads p itself; such a ptradd
 *     that only loads and stores use is not computed at all ("folded").</li>
 * <li>A block whose φs do not read each other and are not live into the other side of a
 *     predecessor's branch gets its φs written at the end of each predecessor ("direct"); else
 *     the predecessors write an "incoming" place, which the block copies on entry. A branch
 *     writes the direct φs of both its sides, so these all conflict with each other.</li>
 * <li>A cast that only copies bytes ({@link #transparent}) has no place; its users read its
 *     source.</li>
 * <li>An instruction with a 2-word result may write its first word before it has read all its
 *     operands, so such a result never shares a place with an operand of the same instruction.
 *     A 1-word result is stored once, after every read.</li>
 * <li>A φ and its operands prefer the same place (then the copy at the end of the predecessor
 *     is no copy at all).</li>
 * </ul>
 * Parameters (written by the caller), memory slots (their address may be anywhere) and incoming
 * places get places of their own.
 */
final class FrameLayout {
    final Set<Instr> fused = new HashSet<>();
    final Set<Instr> folded = new HashSet<>();
    final Set<Block> direct = new HashSet<>();
    /** Offsets in the frame: parameters, slots and values. */
    final Map<Object, Integer> offset = new IdentityHashMap<>();
    final Map<Instr.Phi, Integer> incoming = new IdentityHashMap<>();
    int size;

    private final MFunction f;
    private final List<Value> values = new ArrayList<>();
    /**
     * Pairs that may have exactly the same place but must not overlap otherwise (a direct φ and
     * the operand it gets from a predecessor). If they also interfere, the graph says so.
     */
    private final Map<Integer, Set<Integer>> samePlaceOnly = new HashMap<>();
    private final Map<Value, Integer> index = new IdentityHashMap<>();

    /** The value ranges of the function (indexed addressing needs a non-negative index). */
    final Ranges ranges;

    FrameLayout(MFunction f, Ranges ranges) {
        this.f = f;
        this.ranges = ranges;
        findFolds();
        for (Value.Param p : f.params()) {
            offset.put(p, size);
            size += bytes(p.type());
        }
        for (Value.Slot s : f.slots()) {
            offset.put(s, size);
            size += Math.max(1, s.size());
        }
        for (Block b : f.blocks()) {
            for (Instr i : b.instrs()) {
                if (needsPlace(i)) {
                    index.put(i, values.size());
                    values.add(i);
                }
            }
        }
        liveOut = liveOut();
        for (Block b : f.blocks()) {
            if (directPhis(b)) {
                direct.add(b);
            }
        }
        for (Block b : f.blocks()) {
            for (Instr i : b.instrs()) {
                if (i instanceof Instr.Phi p && !direct.contains(b)) {
                    incoming.put(p, size);
                    size += bytes(p.type());
                }
            }
        }
        colour(interference());
    }

    private Map<Block, BitSet> liveOut;
    private final Map<Block, BitSet> liveIn = new HashMap<>();

    static int bytes(MType t) {
        return t.size <= 2 ? 2 : 4;
    }

    private boolean needsPlace(Instr i) {
        return i.type() != MType.VOID && !fused.contains(i) && !folded.contains(i);
    }

    // ------------------------------------------------------------------ what the code generator folds

    /**
     * Where a load or store goes: base (a pointer, or a global or frame slot) plus a constant,
     * plus an index (an i16 in X) if the base is a global or slot indexed by a value.
     */
    record Address(Value base, int offset, Value index) {}

    Address address(Value v) {
        Value base = v;
        int offset = 0;
        if (v instanceof Instr.PtrAdd p && foldable(p)) {
            base = p.op(0);
            offset = (int) p.op(1).type().signed(((Value.Const) p.op(1)).bits());
        }
        if (base instanceof Instr.PtrAdd q && (indexable(q) || offset == 0 && pointerIndexable(q))) {
            return new Address(q.op(0), offset, q.op(1));
        }
        ConstAddress c = constAddress(base);
        if (c != null) {
            return new Address(new Value.Const(MType.I32, c.address()), offset, c.index());
        }
        return new Address(base, offset, null);
    }

    /**
     * A pointer made from a number, {@code (volatile uint8_t *)0x210B} (a hardware register):
     * {@code inttoptr} of a constant, or of a constant plus a 16-bit index that is never negative
     * and stays in the same bank ({@code REG8(0x210B + i)}). The access goes to that address
     * directly (long addressing, with X for the index) instead of through dampf_ptr. The base is
     * then a constant in the Address.
     */
    record ConstAddress(long address, Value index) {}

    ConstAddress constAddress(Value v) {
        if (!(v instanceof Instr.Cast c) || c.kind != Instr.CastKind.INTTOPTR) {
            return null;
        }
        Value x = c.op(0);
        boolean signed = false;
        while (x instanceof Instr.Cast z && (z.kind == Instr.CastKind.ZEXT || z.kind == Instr.CastKind.SEXT)) {
            signed |= z.kind == Instr.CastKind.SEXT;
            x = z.op(0);
        }
        if (x instanceof Value.Const k) {
            long a = k.bits() & 0xFFFFFFL;
            return a == k.bits() ? new ConstAddress(a, null) : null;
        }
        if (x instanceof Instr.Bin b && b.op == Instr.BinOp.ADD && b.type() == MType.I16
                && (b.op(0) instanceof Value.Const) != (b.op(1) instanceof Value.Const)) {
            Value.Const k = (Value.Const) (b.op(0) instanceof Value.Const ? b.op(0) : b.op(1));
            Value index = b.op(0) instanceof Value.Const ? b.op(1) : b.op(0);
            Ranges.Range r = ranges.at(index, b.block());
            // the sum never wraps, and with a sign extension (C's int arithmetic) it is never
            // negative either, so the extension adds nothing
            if (r != null && r.lo() >= 0 && k.bits() + r.hi() <= (signed ? 0x7FFF : 0xFFFF)) {
                return new ConstAddress(k.bits(), index);
            }
        }
        return null;
    }

    /**
     * A global or frame slot plus a 16-bit value that is never negative: X can hold it for
     * {@code lda symbol,x} (X adds as an unsigned number, so a negative offset would carry into the
     * bank).
     */
    boolean indexable(Instr.PtrAdd q) {
        if (!(q.op(0) instanceof Value.GlobalAddr || q.op(0) instanceof Value.SlotAddr)
                || q.op(1) instanceof Value.Const || q.op(1).type() != MType.I16) {
            return false;
        }
        Ranges.Range r = ranges.at(q.op(1), q.block());
        return r != null && r.lo() >= 0;
    }

    /**
     * A pointer plus a 16-bit value that is never negative: {@code [dp],y} with the value in Y,
     * the sum never computed. Y adds as an unsigned number and carries into the bank, so a
     * negative offset would leave the object's bank; a nonnegative one stays in the object, which
     * never crosses a bank. The pointer is a value with a place of its own: a sum used as the
     * base of another sum is never folded (findFolds counts that as another use), and a pointer
     * made from a number is excluded.
     */
    boolean pointerIndexable(Instr.PtrAdd q) {
        Value p = q.op(0);
        if (!(p instanceof Instr || p instanceof Value.Param) || constAddress(p) != null
                || p instanceof Instr i && transparent(i)
                || q.op(1) instanceof Value.Const || q.op(1).type() != MType.I16) {
            return false;
        }
        Ranges.Range r = ranges.at(q.op(1), q.block());
        return r != null && r.lo() >= 0;
    }

    static boolean foldable(Instr.PtrAdd p) {
        if (!(p.op(1) instanceof Value.Const k)) {
            return false;
        }
        long o = p.op(1).type().signed(k.bits());
        return o >= 0 && o < 0x8000;   // negative: Y would carry into the bank
    }

    private void findFolds() {
        Map<Instr, Integer> uses = new HashMap<>();
        Set<Instr> otherUse = new HashSet<>();
        for (Block b : f.blocks()) {
            for (Instr i : b.instrs()) {
                for (int k = 0; k < i.operands().size(); k++) {
                    if (i.op(k) instanceof Instr d) {
                        uses.merge(d, 1, Integer::sum);
                        boolean asAddress = k == 0 && (i instanceof Instr.Load || i instanceof Instr.Store);
                        if (d instanceof Instr.PtrAdd && !asAddress) {
                            otherUse.add(d);
                        }
                    }
                }
            }
        }
        for (Block b : f.blocks()) {
            if (b.terminator() instanceof Instr.Branch br && br.op(0) instanceof Instr.Cmp c && c.block() == b
                    && uses.get(c) == 1) {
                fused.add(c);
            }
            for (Instr i : b.instrs()) {
                if (i instanceof Instr.PtrAdd p && foldable(p) && !otherUse.contains(p) || transparent(i)) {
                    folded.add(i);
                }
            }
        }
        // a global indexed by a value, used only as the address of loads and stores (directly or
        // plus a constant): the accesses index with X, the sum is never computed
        Map<Instr, Boolean> onlyAddresses = new HashMap<>();
        for (Block b : f.blocks()) {
            for (Instr i : b.instrs()) {
                for (int k = 0; k < i.operands().size(); k++) {
                    if (i.op(k) instanceof Instr.PtrAdd q && (indexable(q) || pointerIndexable(q))) {
                        // through a pointer only directly: Y holds the index, there is no room
                        // for a constant on top
                        boolean ok = k == 0 && (i instanceof Instr.Load || i instanceof Instr.Store)
                                || indexable(q) && i instanceof Instr.PtrAdd p && k == 0 && folded.contains(p);
                        onlyAddresses.merge(q, ok, Boolean::logicalAnd);
                    }
                }
            }
        }
        onlyAddresses.forEach((q, ok) -> {
            if (ok) {
                folded.add(q);
            }
        });
        // a constant plus an index made into a pointer (REG8(0x210B + i)), used only as an
        // address: the accesses index with X from the constant, the sum is never computed
        Map<Instr, Boolean> sums = new HashMap<>();
        for (Block b : f.blocks()) {
            for (Instr i : b.instrs()) {
                if (!(i instanceof Instr.Cast c) || c.kind != Instr.CastKind.INTTOPTR) {
                    continue;
                }
                ConstAddress a = constAddress(c);
                if (a == null || a.index() == null) {
                    continue;
                }
                Value x = c.op(0);
                List<Instr> chain = new ArrayList<>();
                while (x instanceof Instr.Cast z && (z.kind == Instr.CastKind.ZEXT || z.kind == Instr.CastKind.SEXT)) {
                    chain.add(z);
                    x = z.op(0);
                }
                chain.add((Instr) x);
                chain.add(c);
                for (Instr d : chain) {
                    sums.merge(d, true, Boolean::logicalAnd);
                }
            }
        }
        // each link of such a chain may have no other use than the next link, or (the cast) addresses.
        // The next link is always a cast: a sum that takes d as its index (REG8(h + 1) next to
        // REG8(h)) needs d's value in X, so d is computed
        for (Block b : f.blocks()) {
            for (Instr i : b.instrs()) {
                for (int k = 0; k < i.operands().size(); k++) {
                    if (i.op(k) instanceof Instr d && sums.containsKey(d)) {
                        boolean link = sums.containsKey(i) && i instanceof Instr.Cast
                                && !(d instanceof Instr.Cast c && c.kind == Instr.CastKind.INTTOPTR);
                        boolean address = k == 0 && (i instanceof Instr.Load || i instanceof Instr.Store)
                                || i instanceof Instr.PtrAdd p && k == 0 && folded.contains(p);
                        if (!link && !address) {
                            sums.put(d, false);
                        }
                    }
                }
            }
        }
        sums.forEach((d, ok) -> {
            if (ok) {
                folded.add(d);
            }
        });
    }

    /**
     * A cast whose bytes are its source's: zero extension (values are kept zero-extended in their
     * places, and the missing high word reads as 0), truncation to i16 (the low word), and the
     * casts between i32 and pointers. It gets no place and no code; its users read the source.
     */
    static boolean transparent(Instr i) {
        return i instanceof Instr.Cast c && switch (c.kind) {
            case ZEXT, PTRTOINT, INTTOPTR -> true;
            case TRUNC -> c.type() == MType.I16;
            case SEXT -> false;
        };
    }

    /**
     * Whether the predecessors of s can write its φs directly: the write happens before the
     * predecessor's branch, so on the way to another target the φ must not be live there (its
     * place may then hold anything; the interference graph keeps it from what is live there), and
     * the branch must not read it.
     */
    private boolean directPhis(Block s) {
        for (Block p : s.preds()) {
            Instr t = p.terminator();
            if (t instanceof Instr.Jump) {
                continue;
            }
            // the branch itself reads after the writes: not a φ of s (a loop's exit test on its
            // counter, at the end of the latch)
            BitSet branch = new BitSet();
            reads(t, branch);
            for (Instr i : s.instrs()) {
                if (!(i instanceof Instr.Phi phi)) {
                    break;
                }
                Integer x = index.get(phi);
                if (x != null && branch.get(x)) {
                    return false;
                }
            }
            for (Block other : t.successors()) {
                if (other == s) {
                    continue;
                }
                BitSet in = liveIn.get(other);
                for (Instr i : s.instrs()) {
                    if (!(i instanceof Instr.Phi phi)) {
                        break;
                    }
                    Integer x = index.get(phi);
                    if (x != null && in.get(x)) {
                        return false;
                    }
                }
            }
        }
        for (Instr i : s.instrs()) {
            if (i instanceof Instr.Phi p) {
                for (Value v : p.operands()) {
                    // through a transparent cast too: it has no place, it reads the φ's own
                    // (l = c with a byte counter c is zext c; writing c's φ first lost the old c)
                    if (Selection.throughCopies(v) instanceof Instr.Phi q && q.block() == s) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ liveness

    /** The values the code for instruction i reads (where it reads them). */
    private void reads(Instr i, BitSet out) {
        if (fused.contains(i) || folded.contains(i) || i instanceof Instr.Phi) {
            return;   // read where they are used, or at the end of the predecessors
        }
        for (int k = 0; k < i.operands().size(); k++) {
            Value v = i.op(k);
            if (k == 0 && (i instanceof Instr.Load || i instanceof Instr.Store)) {
                // the load or store reads the base and the index itself and adds the constant
                Address a = address(v);
                read(a.base(), out);
                if (a.index() != null) {
                    read(a.index(), out);
                }
                continue;
            }
            read(v, out);
        }
    }

    private void read(Value v, BitSet out) {
        if (v instanceof Instr d && (fused.contains(d) || folded.contains(d))) {
            for (int k = 0; k < d.operands().size(); k++) {
                read(d.op(k), out);
            }
            return;
        }
        Integer x = index.get(v);
        if (x != null) {
            out.set(x);
        }
    }

    private Map<Block, BitSet> liveOut() {
        Map<Block, BitSet> in = new HashMap<>();
        Map<Block, BitSet> out = new HashMap<>();
        for (Block b : f.blocks()) {
            in.put(b, new BitSet());
            out.put(b, new BitSet());
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int k = f.blocks().size() - 1; k >= 0; k--) {
                Block b = f.blocks().get(k);
                BitSet live = exitLive(b, in);
                out.put(b, (BitSet) live.clone());
                List<Instr> is = b.instrs();
                for (int j = is.size() - 1; j >= 0; j--) {
                    Instr i = is.get(j);
                    Integer d = index.get(i);
                    if (d != null) {
                        live.clear(d);
                    }
                    reads(i, live);
                }
                if (!live.equals(in.get(b))) {
                    in.put(b, live);
                    changed = true;
                }
            }
        }
        liveIn.putAll(in);
        return out;
    }

    /** Live at the end of b: what the successors need, and the operands of their φs for this edge. */
    private BitSet exitLive(Block b, Map<Block, BitSet> in) {
        BitSet live = new BitSet();
        Instr t = b.terminator();
        if (t == null) {
            return live;
        }
        for (Block s : new LinkedHashSet<>(t.successors())) {
            BitSet si = (BitSet) in.get(s).clone();
            for (Instr i : s.instrs()) {
                if (i instanceof Instr.Phi p) {
                    Integer d = index.get(p);
                    if (d != null) {
                        si.clear(d);
                    }
                    for (int k = 0; k < p.from.size(); k++) {
                        if (p.from.get(k) == b) {
                            read(p.op(k), live);
                        }
                    }
                }
            }
            live.or(si);
        }
        return live;
    }

    // ------------------------------------------------------------------ interference and colouring

    private BitSet[] interference() {
        BitSet[] g = new BitSet[values.size()];
        for (int k = 0; k < g.length; k++) {
            g[k] = new BitSet();
        }
        Map<Block, BitSet> out = liveOut;
        for (Block b : f.blocks()) {
            BitSet live = (BitSet) out.get(b).clone();
            // direct φs of the successors are written here, while everything live at the end still
            // is, and before the terminator reads its condition
            Instr t = b.terminator();
            BitSet atEnd = (BitSet) live.clone();
            if (t != null) {
                reads(t, atEnd);
            }
            if (t != null) {
                // the direct φs of all successors are written here, one after the other: those of
                // a branch's two sides conflict with each other as much as those of one side
                List<Integer> written = new ArrayList<>();
                for (Block s : new LinkedHashSet<>(t.successors())) {
                    if (direct.contains(s)) {
                        written.addAll(phis(s));
                    }
                }
                for (Block s : new LinkedHashSet<>(t.successors())) {
                    if (direct.contains(s)) {
                        List<Integer> phis = phis(s);
                        for (int p : phis) {
                            // the φ gets its operand's value here, so this write lets the two share a
                            // place, but only exactly the same one (any other overlap would mix
                            // their words); they still conflict if they do anywhere else
                            BitSet others = (BitSet) atEnd.clone();
                            Value own = ownOperand((Instr.Phi) values.get(p), b);
                            if (own != null) {
                                int o = index.get(own);
                                others.clear(o);
                                samePlaceOnly.computeIfAbsent(p, k -> new HashSet<>()).add(o);
                                samePlaceOnly.computeIfAbsent(o, k -> new HashSet<>()).add(p);
                            }
                            interfere(g, p, others);
                            for (int q : written) {
                                if (p != q) {
                                    g[p].set(q);
                                    g[q].set(p);
                                }
                            }
                        }
                    }
                }
            }
            List<Instr> is = b.instrs();
            for (int j = is.size() - 1; j >= 0; j--) {
                Instr i = is.get(j);
                if (i instanceof Instr.Phi) {
                    break;
                }
                BitSet used = new BitSet();
                reads(i, used);
                Integer d = index.get(i);
                if (d != null) {
                    interfere(g, d, live);
                    if (bytes(i.type()) == 4) {
                        interfere(g, d, used);   // written word by word, maybe before the last read
                    }
                    live.clear(d);
                }
                live.or(used);
            }
            if (!direct.contains(b)) {
                // the φs are copied in from their incoming places on entry, one after the other
                List<Integer> phis = phis(b);
                for (int p : phis) {
                    interfere(g, p, live);
                    for (int q : phis) {
                        if (p != q) {
                            g[p].set(q);
                            g[q].set(p);
                        }
                    }
                }
            }
        }
        return g;
    }

    /** The operand φ p gets from b, if it has a place of its own of the φ's size (else null). */
    private Value ownOperand(Instr.Phi p, Block b) {
        Value v = null;
        for (int k = 0; k < p.from.size(); k++) {
            if (p.from.get(k) == b) {
                if (v != null && v != p.op(k)) {
                    return null;
                }
                v = p.op(k);
            }
        }
        return v != null && v != p && index.containsKey(v) && bytes(v.type()) == bytes(p.type()) ? v : null;
    }

    private List<Integer> phis(Block b) {
        List<Integer> l = new ArrayList<>();
        for (Instr i : b.instrs()) {
            if (i instanceof Instr.Phi p && index.containsKey(p)) {
                l.add(index.get(p));
            }
        }
        return l;
    }

    private static void interfere(BitSet[] g, int d, BitSet others) {
        for (int x = others.nextSetBit(0); x >= 0; x = others.nextSetBit(x + 1)) {
            if (x != d) {
                g[d].set(x);
                g[x].set(d);
            }
        }
    }

    /** Whether [o, o + len) is free of every coloured value that interferes with v. */
    private boolean fits(BitSet[] g, int[] at, int v, int o, int len) {
        for (int x = g[v].nextSetBit(0); x >= 0; x = g[v].nextSetBit(x + 1)) {
            int xl = bytes(values.get(x).type());
            if (at[x] >= 0 && at[x] < o + len && o < at[x] + xl) {
                return false;
            }
        }
        for (int x : samePlaceOnly.getOrDefault(v, Set.of())) {
            int xl = bytes(values.get(x).type());
            if (at[x] >= 0 && at[x] < o + len && o < at[x] + xl && !(at[x] == o && xl == len)) {
                return false;
            }
        }
        return true;
    }

    /** The values that would like the same place: a direct φ and its operands. */
    private Map<Integer, List<Integer>> affinities() {
        Map<Integer, List<Integer>> a = new HashMap<>();
        for (Block b : f.blocks()) {
            if (!direct.contains(b)) {
                continue;
            }
            for (Instr i : b.instrs()) {
                if (i instanceof Instr.Phi p && index.containsKey(p)) {
                    for (Value v : p.operands()) {
                        Integer x = index.get(v);
                        if (x != null && bytes(v.type()) == bytes(p.type())) {
                            a.computeIfAbsent(index.get(p), k -> new ArrayList<>()).add(x);
                            a.computeIfAbsent(x, k -> new ArrayList<>()).add(index.get(p));
                        }
                    }
                }
            }
        }
        return a;
    }

    /** Gives every value the lowest offset that no interfering value covers (a partner's, if free). */
    private void colour(BitSet[] g) {
        int base = size;
        int[] at = new int[values.size()];
        java.util.Arrays.fill(at, -1);
        Map<Integer, List<Integer>> partners = affinities();
        int top = 0;
        for (int v = 0; v < values.size(); v++) {
            int len = bytes(values.get(v).type());
            Integer preferred = null;
            for (int x : partners.getOrDefault(v, List.of())) {
                if (at[x] >= 0 && fits(g, at, v, at[x], len)) {
                    preferred = at[x];
                    break;
                }
            }
            if (preferred != null) {
                at[v] = preferred;
                top = Math.max(top, preferred + len);
                offset.put(values.get(v), base + preferred);
                continue;
            }
            int o = 0;
            boolean moved = true;
            while (moved) {
                moved = false;
                for (int x = g[v].nextSetBit(0); x >= 0; x = g[v].nextSetBit(x + 1)) {
                    int xl = bytes(values.get(x).type());
                    if (at[x] >= 0 && at[x] < o + len && o < at[x] + xl) {
                        o = at[x] + xl;
                        moved = true;
                    }
                }
                for (int x : samePlaceOnly.getOrDefault(v, Set.of())) {
                    int xl = bytes(values.get(x).type());
                    if (at[x] >= 0 && at[x] < o + len && o < at[x] + xl && !(at[x] == o && xl == len)) {
                        o = at[x] + xl;
                        moved = true;
                    }
                }
            }
            at[v] = o;
            top = Math.max(top, o + len);
            offset.put(values.get(v), base + o);
        }
        size = base + top;
    }
}
