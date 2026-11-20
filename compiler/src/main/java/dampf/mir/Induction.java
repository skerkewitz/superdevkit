package dampf.mir;

import dampf.frontend.SourcePos;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Induction variables: in a loop whose counter i grows by a constant c per round
 * ({@link CountedLoops}), a value {@code a * i + b} computed from it (an affine value: the zero
 * extension of a byte counter, {@code + b}, {@code * F}, {@code << s}, sums of such values; b a
 * constant or a value from outside the loop) becomes a variable of its own that starts at the
 * same expression on the start value and grows by {@code a * c}: an addition per round instead
 * of the arithmetic. That is done for a value with a factor a other than 1 (the offset of
 * {@code array[i]} for an array of structs, {@code (i - 1) * 2}, {@code r * 16 + c} of a table).
 *
 * <p>If nothing but such values, the step and one loop test read the counter, every one becomes
 * a variable (also {@code i - 1} and {@code r * 16 + c}), and the test moves to the first
 * ({@link #plan}): {@code i < 64} with the offset {@code o = i * 2} is {@code o <= 126}. The
 * counter and its step then feed only each other and go (DeadCode); the loop has the one
 * variable that its accesses read, which the code generator keeps in X. A byte counter whose
 * only reader is its zero extension becomes a 16-bit one this way.
 *
 * <p>Exact: in 16 bits everything is modulo 2^16, where {@code a * (i + c) + b = a * i + b + a * c};
 * a byte counter's zero extension must not wrap on the way back into the loop, which the value
 * ranges show. The moved test is exact when the values it compares map without wrapping
 * ({@link #plan}).
 *
 * <p>The new φ carries the range of its value in the loop head (where the counter also has the
 * value after the last round) ({@link Instr.Phi#known}): the value ranges cannot see on their
 * own that it stays within, and indexed addressing needs to know it is never negative.
 */
public final class Induction {
    private Induction() {}

    /** Returns whether anything changed. */
    public static boolean run(MFunction f) {
        Ranges ranges = new Ranges(f);
        List<CountedLoops.Loop> loops = CountedLoops.find(f);
        Map<Instr, List<Instr>> users = users(f);
        Map<Instr, Value> replaced = new HashMap<>();
        if (twins(loops, replaced)) {
            DeadCode.replaceUses(f, replaced);
            return true;   // the users have changed: the rest in the next run
        }
        Map<Block, Block> idom = Cfg.idoms(f);
        for (CountedLoops.Loop loop : loops) {
            Block h = loop.head();
            for (CountedLoops.Counter k : loop.counters()) {
                Instr.Phi i = k.phi();
                Instr.Bin step = k.step();
                if (step == null || i.type() != MType.I8 && i.type() != MType.I16
                        || i.type() == MType.I8 && !staysInByte(i, step, k.c(), ranges)
                        || replaced.containsKey(i) || replaced.containsKey(step)) {
                    continue;
                }
                reduce(loop, k, step, users, ranges, idom, replaced);
            }
        }
        if (replaced.isEmpty()) {
            // the loop tests move only once the variables are made and the products gone
            for (Block h : f.blocks()) {
                for (Instr x : List.copyOf(h.instrs())) {
                    if (!(x instanceof Instr.Phi i)) {
                        break;
                    }
                    CountedLoops.Counter k = i.type() == MType.I8 ? counter(loops, h, i) : null;
                    Instr.Bin step = k == null ? null : k.step();
                    if (step != null && staysInByte(i, step, k.c(), ranges)) {
                        moveTest(i, h, step, k.c(), users, replaced);
                    }
                }
            }
        }
        DeadCode.replaceUses(f, replaced);
        return !replaced.isEmpty();
    }

    /**
     * Two counters of one loop with the same type, step and start values hold the same value in
     * every round: the second is replaced by the first (the counter and a pointer's offset of
     * {@code *p++}, the offsets of two arrays walked together). Its step then repeats the first's
     * and value numbering merges them. Returns whether any went.
     */
    private static boolean twins(List<CountedLoops.Loop> loops, Map<Instr, Value> replaced) {
        for (CountedLoops.Loop loop : loops) {
            List<CountedLoops.Counter> ks = loop.counters();
            for (int x = 0; x < ks.size(); x++) {
                for (int y = x + 1; y < ks.size(); y++) {
                    Instr.Phi p = ks.get(x).phi();
                    Instr.Phi q = ks.get(y).phi();
                    if (replaced.containsKey(p) || replaced.containsKey(q) || p.type() != q.type()
                            || ks.get(x).c() != ks.get(y).c() || !sameStarts(p, q, loop)) {
                        continue;
                    }
                    replaced.put(q, p);
                    if (q.known != null) {
                        p.known = p.known == null ? q.known : p.known.meet(q.known) != null ? p.known.meet(q.known) : p.known;
                    }
                }
            }
        }
        return !replaced.isEmpty();
    }

    /** Whether p and q get the same value on every way into the loop. */
    private static boolean sameStarts(Instr.Phi p, Instr.Phi q, CountedLoops.Loop loop) {
        for (int n = 0; n < p.from.size(); n++) {
            Block from = p.from.get(n);
            if (loop.blocks().contains(from)) {
                continue;
            }
            int m = q.from.indexOf(from);
            if (m < 0 || p.from.indexOf(from) != n) {
                return false;
            }
            Value a = p.op(n);
            Value b = q.op(m);
            if (a != b && !(a instanceof Value.Const ka && b instanceof Value.Const kb && ka.bits() == kb.bits())) {
                return false;
            }
        }
        return true;
    }

    /**
     * The users of every instruction, counting only live ones: DeadCode runs after this pass, and
     * a dead reader (a zero extension nobody uses, a comparison value numbering will merge) must
     * not keep a counter.
     */
    private static Map<Instr, List<Instr>> users(MFunction f) {
        java.util.Set<Instr> live = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        java.util.ArrayDeque<Instr> work = new java.util.ArrayDeque<>();
        for (Block b : f.blocks()) {
            for (Instr i : b.instrs()) {
                if ((i.isTerminator() || DeadCode.hasEffect(i)) && live.add(i)) {
                    work.add(i);
                }
            }
        }
        while (!work.isEmpty()) {
            for (Value v : work.pop().operands()) {
                if (v instanceof Instr d && live.add(d)) {
                    work.add(d);
                }
            }
        }
        Map<Instr, List<Instr>> users = new HashMap<>();
        for (Block b : f.blocks()) {
            for (Instr i : b.instrs()) {
                if (!live.contains(i)) {
                    continue;
                }
                for (Value v : i.operands()) {
                    if (v instanceof Instr d) {
                        users.computeIfAbsent(d, k -> new ArrayList<>()).add(i);
                    }
                }
            }
        }
        return users;
    }

    /** i as a counter of the loop headed by h (CountedLoops), or null. */
    private static CountedLoops.Counter counter(List<CountedLoops.Loop> loops, Block h, Instr.Phi i) {
        CountedLoops.Loop l = CountedLoops.at(loops, h);
        return l == null ? null : l.counter(i);
    }

    /**
     * The loop's test moves from the 8-bit counter i to a variable o = zext(i) * F of the same
     * loop (the kind this pass makes: it starts at zext(start) * F and grows by c * F), if i
     * serves nothing else: {@code i < N} becomes {@code o < N * F} (unsigned, and equality).
     * Exact: o never wraps (i stays in its byte, 0 < F, F * 255 <= 0xFFFF), so the order is the
     * same. The counter and its step then feed only each other and go (DeadCode); in the game's
     * pair loop the offset in X is all that is left.
     */
    private static void moveTest(Instr.Phi i, Block h, Instr.Bin step, long c, Map<Instr, List<Instr>> users,
                                 Map<Instr, Value> replaced) {
        Instr.Cmp test = null;
        for (Instr u : users.getOrDefault(i, List.of())) {
            if (u == step) {
                continue;
            }
            if (u instanceof Instr.Cmp t && test == null && t.op(0) == i && t.op(1) instanceof Value.Const
                    && switch (t.pred) {
                        case EQ, NE, ULT, ULE, UGT, UGE -> true;
                        default -> false;
                    }) {
                test = t;
                continue;
            }
            return;   // i is used for something else
        }
        if (test == null || !users.getOrDefault(step, List.of()).stream().allMatch(u -> u == i)) {
            return;
        }
        for (Instr x : h.instrs()) {
            if (!(x instanceof Instr.Phi o)) {
                break;
            }
            long factor = o == i || o.type() != MType.I16 ? 0 : factorOf(i, o, c);
            if (factor > 0) {
                long n = ((Value.Const) test.op(1)).bits() & 0xFF;
                Instr.Cmp moved = new Instr.Cmp(test.pred, o, new Value.Const(MType.I16, n * factor), test.pos());
                test.block().insertBefore(test, moved);
                replaced.put(test, moved);
                return;
            }
        }
    }

    /** F if o = zext(i) * F with 0 < F, F * 255 <= 0xFFFF, read off o's start and step; else 0. */
    private static long factorOf(Instr.Phi i, Instr.Phi o, long c) {
        long factor = 0;
        for (int k = 0; k < i.from.size(); k++) {
            int ko = o.from.indexOf(i.from.get(k));
            if (ko < 0) {
                return 0;
            }
            Value vi = i.op(k);
            Value vo = o.op(ko);
            long f;
            Value.Const d = vo instanceof Instr.Bin s && s.op == Instr.BinOp.ADD
                    ? (s.op(0) == o && s.op(1) instanceof Value.Const k1 ? k1 : s.op(1) == o && s.op(0) instanceof Value.Const k2 ? k2 : null)
                    : null;
            if (d != null && vi instanceof Instr.Bin si && (si.op(0) == i || si.op(1) == i)) {
                // the back edge: i grows by c, o by c * F
                long ds = MType.I16.signed(d.bits());
                if (c == 0 || ds % c != 0) {
                    return 0;
                }
                f = ds / c;
            } else if (vi instanceof Value.Const k0 && vo instanceof Value.Const o0) {
                long u = k0.bits() & 0xFF;
                if (u == 0) {
                    if (o0.bits() != 0) {
                        return 0;
                    }
                    continue;   // says nothing about F
                }
                if (o0.bits() % u != 0) {
                    return 0;
                }
                f = o0.bits() / u;
            } else if (vo instanceof Instr.Bin m && m.op == Instr.BinOp.MUL && m.op(1) instanceof Value.Const fk
                    && m.op(0) instanceof Instr.Cast z && z.kind == Instr.CastKind.ZEXT && z.op(0) == vi) {
                f = fk.bits();
            } else {
                return 0;
            }
            if (f <= 0 || f * 0xFF > 0xFFFF || factor != 0 && f != factor) {
                return 0;
            }
            factor = f;
        }
        return factor;
    }

    /** An 8-bit counter does not wrap when it steps (so its zero extension steps by c as well). */
    private static boolean staysInByte(Instr.Phi i, Instr.Bin step, long c, Ranges ranges) {
        Ranges.Range r = ranges.atUnsigned(i, step.block());
        return r != null && r.lo() + c >= 0 && r.hi() + c <= 0xFF;
    }

    // ------------------------------------------------------------------ affine values

    /**
     * The affine values of a counter i in its loop: i itself; the step; the zero extension of a
     * byte counter (or of its step) to 16 bits; and in 16 bits {@code x + y}, {@code x * F},
     * {@code x << s}, where x is affine, F and s constants, and y a constant, a value from outside
     * the loop, or affine too. Each is {@code a * i + b} modulo 2^16 (a byte counter read as its
     * unsigned value, which it keeps as it does not wrap). The leaves are those that something
     * else reads.
     */
    private static final class Affine {
        final Instr.Phi i;
        final Instr.Bin step;
        final Set<Block> loop;
        final Map<Instr, List<Instr>> users;
        /** The factor a of every affine value but i. */
        final Map<Instr, Long> a = new LinkedHashMap<>();
        final List<Instr> leaves = new ArrayList<>();
        /** The counter or its step is read by something else: the counter stays. */
        boolean others;
        Instr.Cmp test;
        /** What the test compares: the counter, its step, or one of them zero-extended. */
        Instr testReads;

        Affine(Instr.Phi i, Instr.Bin step, Set<Block> loop, Map<Instr, List<Instr>> users, Instr.Cmp test) {
            this.i = i;
            this.step = step;
            this.loop = loop;
            this.users = users;
            this.test = test;
            find();
        }

        boolean affine(Value v) {
            return v == i || v instanceof Instr x && a.containsKey(x);
        }

        long factor(Value v) {
            return v == i ? 1 : a.get((Instr) v);
        }

        private boolean outside(Value v) {
            return !(v instanceof Instr d) || d.block() != null && !loop.contains(d.block());
        }

        /** u's factor if u is affine given what is known; null if not (yet). */
        private Long factorOf(Instr u) {
            if (!loop.contains(u.block())) {
                return null;
            }
            if (u == step) {
                return 1L;
            }
            if (u instanceof Instr.Cast c) {
                return c.kind == Instr.CastKind.ZEXT && c.type() == MType.I16 && c.op(0).type() == MType.I8
                        && (c.op(0) == i || c.op(0) == step) ? 1L : null;
            }
            if (!(u instanceof Instr.Bin b) || b.type() != MType.I16) {
                return null;
            }
            Value x = b.op(0);
            Value y = b.op(1);
            return switch (b.op) {
                case ADD -> affine(x) && affine(y) ? Long.valueOf(factor(x) + factor(y))
                        : affine(x) && (y instanceof Value.Const || outside(y)) ? Long.valueOf(factor(x))
                        : affine(y) && (x instanceof Value.Const || outside(x)) ? Long.valueOf(factor(y)) : null;
                case MUL -> affine(x) && y instanceof Value.Const k ? Long.valueOf(factor(x) * MType.I16.signed(k.bits()))
                        : affine(y) && x instanceof Value.Const k ? Long.valueOf(factor(y) * MType.I16.signed(k.bits())) : null;
                case SHL -> affine(x) && y instanceof Value.Const k && k.bits() >= 0 && k.bits() < 16
                        ? Long.valueOf(factor(x) << k.bits()) : null;
                default -> null;
            };
        }

        private void find() {
            boolean grew = true;
            while (grew) {
                grew = false;
                List<Instr> known = new ArrayList<>(List.of(i));
                known.addAll(a.keySet());
                for (Instr x : known) {
                    for (Instr u : users.getOrDefault(x, List.of())) {
                        if (u != i && u != test && !a.containsKey(u)) {
                            Long f = factorOf(u);
                            if (f != null) {
                                a.put(u, f);
                                grew = true;
                            }
                        }
                    }
                }
            }
            List<Instr> known = new ArrayList<>(List.of(i));
            known.addAll(a.keySet());
            for (Instr x : known) {
                boolean read = false;
                for (Instr u : users.getOrDefault(x, List.of())) {
                    if (u == i && x == step || a.containsKey(u)) {
                        continue;
                    }
                    if (u == test && (x == i || x == step || x instanceof Instr.Cast)) {
                        testReads = x;
                        continue;
                    }
                    read = true;
                }
                if (read) {
                    if (x == i || x == step) {
                        others = true;
                    } else {
                        leaves.add(x);
                    }
                }
            }
        }

        /** A key equal for leaves that compute the same thing. */
        String key(Value v) {
            if (v == i) {
                return "i";
            }
            if (!affine(v)) {
                return v instanceof Value.Const c ? "#" + c.bits() : "@" + System.identityHashCode(v);
            }
            Instr x = (Instr) v;
            StringBuilder sb = new StringBuilder(x instanceof Instr.Bin b ? b.op.name() : "ZEXT").append('(');
            for (Value o : x.operands()) {
                sb.append(key(o)).append(',');
            }
            return sb.append(')').toString();
        }

        /**
         * Leaf (or any affine value) v with i replaced by n, at the end of block at (folded where
         * every operand is a constant). With {@code wide}, n replaces the zero extension of i
         * instead (a 16-bit value), and the step of a byte counter becomes n + c in 16 bits.
         */
        Value clone(Value v, Value n, boolean wide, Block at, SourcePos pos, Map<Value, Value> memo) {
            if (!affine(v)) {
                return v;
            }
            Value known = memo.get(v);
            if (known != null) {
                return known;
            }
            Value out;
            if (v == i) {
                out = n;
            } else if (v instanceof Instr.Cast c) {
                out = wide ? clone(c.op(0), n, true, at, pos, memo) : zext(clone(c.op(0), n, false, at, pos, memo), at, pos);
            } else {
                Instr.Bin b = (Instr.Bin) v;
                Value x = clone(b.op(0), n, wide, at, pos, memo);
                Value y = clone(b.op(1), n, wide, at, pos, memo);
                MType t = wide && b == step ? MType.I16 : b.type();
                if (wide && b == step && y instanceof Value.Const k) {
                    y = new Value.Const(MType.I16, MType.I16.wrap(t.signed(b.type().signed(k.bits()))));
                }
                out = x instanceof Value.Const p && y instanceof Value.Const q
                        ? new Value.Const(t, t.wrap(fold(b.op, p.bits(), q.bits())))
                        : ins(at, new Instr.Bin(b.op, x, y, pos));
            }
            memo.put(v, out);
            return out;
        }

        private static Value zext(Value v, Block at, SourcePos pos) {
            return v instanceof Value.Const k ? new Value.Const(MType.I16, k.bits() & 0xFF)
                    : ins(at, new Instr.Cast(Instr.CastKind.ZEXT, v, MType.I16, pos));
        }

        /** The signed range of v in the loop head, from the counter's range there; null if unknown. */
        Ranges.Range range(Value v, Ranges.Range ri, Ranges ranges, Block h) {
            if (v == i) {
                return ri;
            }
            if (!affine(v)) {
                return ranges.at(v, h);
            }
            if (v instanceof Instr.Cast c) {
                return range(c.op(0), ri, ranges, h);   // a byte that does not wrap: its unsigned value
            }
            Instr.Bin b = (Instr.Bin) v;
            Ranges.Range x = range(b.op(0), ri, ranges, h);
            Ranges.Range y = range(b.op(1), ri, ranges, h);
            if (x == null || y == null) {
                return null;
            }
            if (b == step && i.type() == MType.I8) {
                return new Ranges.Range(x.lo() + y.lo(), x.hi() + y.hi());   // stays in its byte
            }
            return Ranges.bin(b.op, MType.I16, x, y);
        }

        /** b of v = a * i + b as an interval of integers (i = 0), from the signed ranges of what v adds; null if unknown. */
        Ranges.Range offset(Value v, Ranges ranges, Block h) {
            if (v == i) {
                return new Ranges.Range(0, 0);
            }
            if (!affine(v)) {
                if (v instanceof Value.Const k) {
                    long x = k.type().signed(k.bits());
                    return new Ranges.Range(x, x);
                }
                return ranges.at(v, h);
            }
            if (v instanceof Instr.Cast c) {
                return offset(c.op(0), ranges, h);
            }
            Instr.Bin b = (Instr.Bin) v;
            Ranges.Range x = offset(b.op(0), ranges, h);
            Ranges.Range y = offset(b.op(1), ranges, h);
            if (x == null || y == null) {
                return null;
            }
            return switch (b.op) {
                case ADD -> new Ranges.Range(x.lo() + y.lo(), x.hi() + y.hi());
                case MUL -> {
                    long p1 = x.lo() * y.lo();
                    long p2 = x.lo() * y.hi();
                    long p3 = x.hi() * y.lo();
                    long p4 = x.hi() * y.hi();
                    yield new Ranges.Range(Math.min(Math.min(p1, p2), Math.min(p3, p4)), Math.max(Math.max(p1, p2), Math.max(p3, p4)));
                }
                case SHL -> new Ranges.Range(x.lo() << y.lo(), x.hi() << y.lo());
                default -> null;
            };
        }
    }

    /** x, inserted at the end of block b (before its terminator). */
    private static Instr ins(Block b, Instr x) {
        b.insertBefore(b.terminator(), x);
        return x;
    }

    private static long fold(Instr.BinOp op, long x, long y) {
        return switch (op) {
            case ADD -> x + y;
            case MUL -> x * y;
            case SHL -> x << (y & 0xFFFF);
            default -> throw new IllegalArgumentException(op.name());
        };
    }

    /**
     * The derived variables of counter k. A leaf that multiplies (a other than 1) becomes a
     * variable of its own; if nothing but leaves, the step and one loop test read the counter,
     * every leaf does, and the test moves to the first ({@link #plan}): the counter goes.
     */
    private static void reduce(CountedLoops.Loop loop, CountedLoops.Counter k, Instr.Bin step, Map<Instr, List<Instr>> users,
                               Ranges ranges, Map<Block, Block> idom, Map<Instr, Value> replaced) {
        Instr.Phi i = k.phi();
        Instr.Cmp test = null;
        for (CountedLoops.Exit e : loop.exits()) {
            if (e.counter() == k && e.invariant(loop) && test == null) {
                test = e.cmp();
            }
        }
        Affine af = new Affine(i, step, loop.blocks(), users, test);
        if (!af.others && test != null && af.testReads != null && af.leaves.isEmpty()) {
            sibling(af, loop, k, ranges, idom, replaced);
            return;
        }
        List<Instr> leaves = af.leaves.stream().filter(l -> !replaced.containsKey(l)).toList();
        boolean goes = !af.others && test != null && af.testReads != null && !leaves.isEmpty()
                && leaves.size() == af.leaves.size();
        Move moved = goes ? plan(af, leaves.getFirst(), loop, k, ranges) : null;
        goes = moved != null;
        Ranges.Range ri = i.type() == MType.I8 ? ranges.atUnsigned(i, loop.head()) : ranges.at(i, loop.head());
        Map<String, Instr.Phi> made = new HashMap<>();
        Map<Long, Instr.Phi> byFactor = new HashMap<>();
        Map<Instr.Phi, Long> offsets = new HashMap<>();
        Instr.Phi target = null;
        for (Instr l : leaves) {
            if (!goes && af.factor(l) == 1) {
                continue;   // i + b beside i: nothing saved
            }
            Ranges.Range r = ri == null ? null : af.range(l, ri, ranges, loop.head());
            // the same a * i + b, however it is computed, is one variable
            Ranges.Range b = af.offset(l, ranges, loop.head());
            String key = b != null && b.lo() == b.hi()
                    ? "a" + MType.I16.wrap(af.factor(l)) + "b" + MType.I16.wrap(b.lo()) : af.key(l);
            Instr.Phi o = made.get(key);
            long a = MType.I16.wrap(af.factor(l));
            if (o == null && b != null && b.lo() == b.hi() && byFactor.containsKey(a)) {
                // a variable with the same factor exists: this one is it plus a constant (i and
                // i + 1 of a pair loop's outer counter), an addition where it is read, not a
                // variable of its own
                Instr.Phi base = byFactor.get(a);
                long delta = MType.I16.wrap(b.lo() - offsets.get(base));
                Instr sum = new Instr.Bin(Instr.BinOp.ADD, base, new Value.Const(MType.I16, delta), l.pos());
                l.block().insertBefore(l, sum);
                replaced.put(l, sum);
                continue;
            }
            if (o == null) {
                o = variable(af, loop.head(), k.c(), l);
                o.known = r;
                made.put(key, o);
                if (b != null && b.lo() == b.hi()) {
                    byFactor.putIfAbsent(a, o);
                    offsets.put(o, b.lo());
                }
            } else {
                o.known = r == null || o.known == null ? null : o.known.join(r);
            }
            replaced.put(l, o);
            if (target == null) {
                target = o;
            }
        }
        if (goes) {
            move(af, leaves.getFirst(), target, moved, loop, k, idom, replaced);
        }
    }

    /** The new φ in h for leaf l: l on the start values, plus c * a after each round. */
    private static Instr.Phi variable(Affine af, Block h, long c, Instr l) {
        Instr.Phi o = h.add(new Instr.Phi(MType.I16, l.pos()));
        Block sb = af.step.block();
        Instr after = sb.instrs().get(sb.instrs().indexOf(af.step) + 1);
        Instr next = new Instr.Bin(Instr.BinOp.ADD, o, new Value.Const(MType.I16, MType.I16.wrap(c * af.factor(l))), l.pos());
        sb.insertBefore(after, next);
        for (int n = 0; n < af.i.from.size(); n++) {
            Block pred = af.i.from.get(n);
            Value v = af.i.op(n);
            if (v == af.step) {
                o.add(pred, next);
                continue;
            }
            o.add(pred, v instanceof Value.Undef ? new Value.Undef(MType.I16) : af.clone(l, v, false, pred, l.pos(), new HashMap<>()));
        }
        return o;
    }

    /**
     * How the test moves: the new predicate, and the bound it compares with, l(N + delta), that is
     * l(N) + a * delta (plus one more if the predicate became "less than" from "at most" for a
     * bound in a place).
     */
    private record Move(Instr.Pred pred, long delta, boolean plusOne) {}

    /**
     * Whether the loop test can move from the counter to the derived variable o = a * i + b of
     * leaf l, and how: {@code i pred N} becomes {@code o' pred' l(N)}, o' being o (or its step,
     * when the test reads the stepped counter). Exact when a * x + b, over every value the test
     * compares (the counter's and the bound's), lies in one reading of 16 bits without wrapping:
     * the map is then monotone (it turns the order round for a &lt; 0), and one to one for
     * equality. Before mapping, the test is made "at most" or "at least" where it can: {@code i < N}
     * is {@code i <= N - 1}, and {@code i != N} for a counter that never passes N is {@code i > N},
     * so {@code i >= N + 1}: the new variable's range in the loop then excludes the value after
     * the last round, which equality alone cannot say. Null if the test cannot move.
     */
    private static Move plan(Affine af, Instr l, CountedLoops.Loop loop, CountedLoops.Counter k, Ranges ranges) {
        Ranges.Range b = af.offset(l, ranges, loop.head());
        return b == null ? null : plan(af, af.factor(l), b, loop, k, ranges);
    }

    /** {@link #plan} for a variable a * i + b, b in the given interval. */
    private static Move plan(Affine af, long a, Ranges.Range b, CountedLoops.Loop loop, CountedLoops.Counter k, Ranges ranges) {
        Instr.Cmp t = af.test;
        Instr.Phi i = k.phi();
        boolean counterLeft = t.op(0) == af.testReads;
        Value bound = counterLeft ? t.op(1) : t.op(0);
        Instr.Pred pred = counterLeft ? t.pred : Ranges.swap(t.pred);
        boolean widened = af.testReads instanceof Instr.Cast;
        if (bound.type() != af.testReads.type() || a == 0 || MType.I16.wrap(a) == 0) {
            return null;
        }
        boolean unsigned = switch (pred) {
            case ULT, ULE, UGT, UGE, EQ, NE -> true;
            default -> false;
        };
        Block tb = t.block();
        Ranges.Range xs = unsigned ? ranges.atUnsigned(af.testReads, tb) : ranges.at(af.testReads, tb);
        Ranges.Range xb = unsigned ? ranges.atUnsigned(bound, tb) : ranges.at(bound, tb);
        if (xs == null || xb == null) {
            return null;
        }
        if (i.type() == MType.I8 && !widened && !unsigned && (xs.join(xb).lo() < 0 || xs.join(xb).hi() > 127)) {
            return null;   // a signed byte test, but the leaf reads the byte unsigned
        }
        // at most / at least, on the integers
        long delta = 0;
        Instr.Pred p = switch (pred) {
            case EQ -> xs.lo() >= xb.hi() ? Instr.Pred.ULE : xs.hi() <= xb.lo() ? Instr.Pred.UGE : pred;
            case NE -> xs.lo() >= xb.hi() ? Instr.Pred.UGT : xs.hi() <= xb.lo() ? Instr.Pred.ULT : pred;
            default -> pred;
        };
        switch (p) {
            case ULT, SLT -> {
                p = unsigned ? Instr.Pred.ULE : Instr.Pred.SLE;
                delta = -1;
            }
            case UGT, SGT -> {
                p = unsigned ? Instr.Pred.UGE : Instr.Pred.SGE;
                delta = 1;
            }
            default -> { }
        }
        // the compared values, the counter's (or its step's: l of i + c is o after its step) and
        // the bound's; the leaf maps each x to a * x + b
        Ranges.Range x = xs.join(new Ranges.Range(xb.lo() + delta, xb.hi() + delta));
        long y1 = a * x.lo();
        long y2 = a * x.hi();
        Ranges.Range y = new Ranges.Range(Math.min(y1, y2) + b.lo(), Math.max(y1, y2) + b.hi());
        if (Math.abs(y.lo()) > (1L << 40) || Math.abs(y.hi()) > (1L << 40)) {
            return null;
        }
        if (p == Instr.Pred.EQ || p == Instr.Pred.NE) {
            return y.hi() - y.lo() > 0xFFFF ? null : new Move(p, 0, false);
        }
        boolean fitsU = y.lo() >= 0 && y.hi() <= 0xFFFF;
        boolean fitsS = y.lo() >= -0x8000 && y.hi() <= 0x7FFF;
        if (!fitsU && !fitsS) {
            return null;
        }
        Instr.Pred np = a > 0 ? p : Ranges.swap(p);
        np = fitsU ? toUnsigned(np) : toSigned(np);
        // a bound in a place: "at most B" is "less than B + 1", which a CPX tests
        boolean constant = bound instanceof Value.Const && b.lo() == b.hi();
        boolean plusOne = (np == Instr.Pred.ULE || np == Instr.Pred.SLE) && !constant && y.hi() < (fitsU ? 0xFFFF : 0x7FFF);
        if (plusOne) {
            np = np == Instr.Pred.ULE ? Instr.Pred.ULT : Instr.Pred.SLT;
        }
        return new Move(np, delta, plusOne);
    }

    /** Moves the test as {@link #plan} found: the new bound where the loop is entered from. */
    private static void move(Affine af, Instr l, Instr.Phi o, Move m, CountedLoops.Loop loop, CountedLoops.Counter k,
                             Map<Block, Block> idom, Map<Instr, Value> replaced) {
        Instr.Cmp t = af.test;
        Value bound = t.op(0) == af.testReads ? t.op(1) : t.op(0);
        Block at = idom.get(loop.head());
        SourcePos pos = t.pos();
        boolean widened = af.testReads instanceof Instr.Cast;
        boolean stepped = af.testReads == k.step() || widened && ((Instr.Cast) af.testReads).op(0) == k.step();

        // l with i (or its zero extension, for a widened test) replaced by the bound
        Map<Value, Value> memo = new HashMap<>();
        if (widened) {
            for (Instr z : af.a.keySet()) {
                if (z instanceof Instr.Cast c && c.op(0) == af.i) {
                    memo.put(z, bound);
                }
            }
            if (memo.isEmpty()) {
                return;   // the leaf does not read i zero-extended
            }
        }
        Value nb = af.clone(l, bound, widened, at, pos, memo);
        // a test of the stepped counter compares o after its step: l(i + c) = o + a * c
        Value lhs = stepped ? o.op(o.from.indexOf(loop.latches().getFirst())) : o;
        moveTo(t, lhs, nb, af.factor(l), m, at, replaced);
    }

    /**
     * The new test: lhs pred' (nb + a * delta, plus one), the addition in 16 bits after the zero
     * extension (N + delta in N's own type could wrap: a byte 0 - 1 is 255).
     */
    private static void moveTo(Instr.Cmp t, Value lhs, Value nb, long a, Move m, Block at, Map<Instr, Value> replaced) {
        SourcePos pos = t.pos();
        long add = a * m.delta + (m.plusOne ? 1 : 0);
        if (add != 0) {
            nb = nb instanceof Value.Const c ? new Value.Const(MType.I16, MType.I16.wrap(c.bits() + add))
                    : ins(at, new Instr.Bin(Instr.BinOp.ADD, nb, new Value.Const(MType.I16, MType.I16.wrap(add)), pos));
        }
        Instr.Cmp moved = new Instr.Cmp(m.pred, lhs, nb, pos);
        t.block().insertBefore(t, moved);
        replaced.put(t, moved);
    }

    /**
     * A counter read only by its step and its test, beside a 16-bit counter s of the loop that is
     * a * i + b for constants a and b (both start at constants, s steps by a times i's step): the
     * test moves to s (L5; the pointer's offset beside a counter, which steps by the element
     * size, or counts up where the counter counts down). The counter then goes.
     */
    private static void sibling(Affine af, CountedLoops.Loop loop, CountedLoops.Counter k, Ranges ranges,
                                Map<Block, Block> idom, Map<Instr, Value> replaced) {
        Instr.Phi i = k.phi();
        for (CountedLoops.Counter s : loop.counters()) {
            Instr.Phi o = s.phi();
            if (s == k || o.type() != MType.I16 || replaced.containsKey(o) || s.c() % k.c() != 0) {
                continue;
            }
            long a = s.c() / k.c();
            Long b = null;
            boolean ok = true;
            for (int n = 0; n < i.from.size() && ok; n++) {
                Block from = i.from.get(n);
                if (loop.blocks().contains(from)) {
                    continue;
                }
                int m = o.from.indexOf(from);
                if (m < 0 || !(i.op(n) instanceof Value.Const ki) || !(o.op(m) instanceof Value.Const ko)) {
                    ok = false;
                    break;
                }
                long x = i.type() == MType.I8 ? ki.bits() & 0xFF : i.type().signed(ki.bits());
                long bb = MType.I16.wrap(ko.bits() - a * x);
                ok = b == null || b == bb;
                b = bb;
            }
            if (!ok || b == null) {
                continue;
            }
            long bs = MType.I16.signed(b);
            Move m = plan(af, a, new Ranges.Range(bs, bs), loop, k, ranges);
            if (m == null) {
                continue;
            }
            Instr.Cmp t = af.test;
            Value bound = t.op(0) == af.testReads ? t.op(1) : t.op(0);
            Block at = idom.get(loop.head());
            SourcePos pos = t.pos();
            boolean widened = af.testReads instanceof Instr.Cast;
            boolean stepped = af.testReads == k.step() || widened && ((Instr.Cast) af.testReads).op(0) == k.step();
            // a * N + b
            Value n = bound;
            if (i.type() == MType.I8 && !widened) {
                n = n instanceof Value.Const c ? new Value.Const(MType.I16, c.bits() & 0xFF)
                        : ins(at, new Instr.Cast(Instr.CastKind.ZEXT, n, MType.I16, pos));
            }
            Value nb = n instanceof Value.Const c ? new Value.Const(MType.I16, MType.I16.wrap(c.bits() * a + b))
                    : ins(at, new Instr.Bin(Instr.BinOp.ADD,
                            ins(at, new Instr.Bin(Instr.BinOp.MUL, n, new Value.Const(MType.I16, MType.I16.wrap(a)), pos)),
                            new Value.Const(MType.I16, b), pos));
            // the stepped counter is compared: s after its step, computed where the test is
            Value lhs = o;
            if (stepped) {
                lhs = new Instr.Bin(Instr.BinOp.ADD, o, new Value.Const(MType.I16, MType.I16.wrap(s.c())), pos);
                t.block().insertBefore(t, (Instr) lhs);
            }
            moveTo(t, lhs, nb, a, m, at, replaced);
            return;
        }
    }

    private static Instr.Pred toUnsigned(Instr.Pred p) {
        return switch (p) {
            case SLT, ULT -> Instr.Pred.ULT;
            case SLE, ULE -> Instr.Pred.ULE;
            case SGT, UGT -> Instr.Pred.UGT;
            case SGE, UGE -> Instr.Pred.UGE;
            default -> p;
        };
    }

    private static Instr.Pred toSigned(Instr.Pred p) {
        return switch (p) {
            case SLT, ULT -> Instr.Pred.SLT;
            case SLE, ULE -> Instr.Pred.SLE;
            case SGT, UGT -> Instr.Pred.SGT;
            case SGE, UGE -> Instr.Pred.SGE;
            default -> p;
        };
    }
}
