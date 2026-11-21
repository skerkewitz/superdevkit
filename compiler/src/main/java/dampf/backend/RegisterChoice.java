package dampf.backend;

import dampf.backend.PlaceColouring.Analysis;
import dampf.backend.PlaceColouring.Group;
import dampf.backend.PlaceColouring.Place;
import dampf.lir.LBlock;
import dampf.lir.LCopy;
import dampf.lir.LFunction;
import dampf.lir.LInstr;
import dampf.lir.LItem;
import dampf.lir.LMark;
import dampf.lir.IndexInY;
import dampf.lir.LOperand;
import dampf.lir.RedundantLoads;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Keeps places in X or Y instead of the frame where that saves cycles (B3 of
 * docs/current-compiler-flaws.md), on the LIR selection wrote, before the places get addresses.
 * A candidate is a group of places ({@link PlaceColouring.Analysis}: marked places and coalesced
 * copies as one) of one word; it goes to register R for its whole live range when
 *
 * <ul>
 * <li>every item that names it has a form with R ({@link #replacement}): {@code LDA p} is
 *     {@code TXA}, {@code STA p} {@code TAX}, {@code LDX p} goes, {@code LDY p} is {@code TXY},
 *     {@code INC p} {@code INX}, {@code STZ p} {@code LDX #0}, a φ copy to or from another place
 *     {@code LDX}/{@code STX} (and the same for Y); arithmetic with p as its operand has none;</li>
 * <li>nothing else writes R while it is live (another index, a call), and R holds nothing else
 *     where it is written;</li>
 * <li>no flags that are read change;</li>
 * <li>the cycles saved, each instruction's weighted by its block ({@link Weights}), are more than
 *     none.</li>
 * </ul>
 *
 * Candidates are taken greedily by the cycles they save, X first, then Y. Selection's own patterns
 * (a loop's counter in X, an index in Y) keep their values out of the frame already; this takes
 * what they leave.
 */
final class RegisterChoice {
    private RegisterChoice() {}

    enum Reg {
        X("x", "ldx", "stx", "inx", "dex", "txa", "tax"),
        Y("y", "ldy", "sty", "iny", "dey", "tya", "tay");

        final String name;
        final String load;
        final String store;
        final String inc;
        final String dec;
        final String toA;
        final String fromA;

        Reg(String name, String load, String store, String inc, String dec, String toA, String fromA) {
            this.name = name;
            this.load = load;
            this.store = store;
            this.inc = inc;
            this.dec = dec;
            this.toA = toA;
            this.fromA = fromA;
        }

        Reg other() {
            return this == X ? Y : X;
        }

        /** The transfer from this register to the other ({@code TXY}, {@code TYX}). */
        String toOther() {
            return this == X ? "txy" : "tyx";
        }
    }

    /** A candidate's plan: what each of its items becomes, and the weighted cycles it saves. */
    private record Plan(Group group, Reg reg, Map<LItem, List<LItem>> replace, double benefit, BitSet region) {}

    /**
     * Puts places in registers where it pays; returns how many groups went. {@code highWordInX}:
     * the function returns a value of two words, its high word in X (dampf's convention: a result
     * in A, and X for a high word; Y is never returned).
     */
    static int run(LFunction lf, Predicate<LOperand.Owner> tracked, PlaceColouring.Frame frame, Map<LBlock, Double> weights,
                   boolean highWordInX, boolean resultInA) {
        return run(lf, tracked, frame, weights, highWordInX, resultInA, List.of());
    }

    /**
     * {@code loops}: the blocks of each loop of the function; a candidate may also go to r for one
     * loop only (splitting): loaded on the ways in, stored on the ways out where the loop changes it.
     */
    static int run(LFunction lf, Predicate<LOperand.Owner> tracked, PlaceColouring.Frame frame, Map<LBlock, Double> weights,
                   boolean highWordInX, boolean resultInA, List<java.util.Set<LBlock>> loops) {
        returnsX = highWordInX;
        returnsA = resultInA;
        int n = 0;
        for (Reg r : Reg.values()) {
            while (true) {
                Analysis an = PlaceColouring.analyse(lf, tracked, frame);
                Map<LItem, Integer> position = new IdentityHashMap<>();
                for (LItem it : lf.items()) {
                    position.put(it, position.size());
                }
                Round round = new Round(RedundantLoads.flagsLiveOut(lf), registerLiveOut(lf, r), readUnits(an), position, named(lf, an),
                        freeLoads(lf), aLiveOut(lf), registerLiveOut(lf, r.other()));
                List<Plan> plans = new ArrayList<>();
                for (Group g : an.groups) {
                    if (g.into != null || g.fixed != null || g.direct || g.bytes != 2 || !allTracked(g, tracked)) {
                        continue;
                    }
                    Plan p = plan(lf, an, round, g, r, weights, null);
                    for (java.util.Set<LBlock> loop : loops) {
                        Plan q = plan(lf, an, round, g, r, weights, loop);
                        if (q != null && (p == null || q.benefit() > p.benefit())) {
                            p = q;
                        }
                    }
                    if (p != null && p.benefit() > 0) {
                        plans.add(p);
                    }
                }
                // by benefit; one whose live range meets a chosen one's waits for the next round,
                // where it is judged on the code with the chosen one in r
                plans.sort(java.util.Comparator.comparingDouble(Plan::benefit).reversed());
                BitSet taken = new BitSet();
                Map<LItem, List<LItem>> replace = new IdentityHashMap<>();
                int chosen = 0;
                for (Plan p : plans) {
                    if (!p.region().intersects(taken)) {
                        taken.or(p.region());
                        replace.putAll(p.replace());
                        chosen++;
                    }
                }
                if (chosen == 0) {
                    break;
                }
                apply(lf, replace);
                n += chosen;
            }
        }
        return n;
    }

    /**
     * For g in r in one loop only: a load of r from g's place at the end of every block outside
     * the loop that enters it (where g is live into the loop), and a store at the start of every
     * block outside it that the loop leaves to (where the loop writes g and g is live there); into
     * replace and region. Returns their weighted cycles, or null where they cannot go: a block
     * that enters the loop and goes elsewhere too, a block left to from outside the loop as well,
     * r or the flags read at the head from outside.
     */
    private static Double loopEdges(LFunction lf, Analysis an, Round round, Group g, Reg r, Map<LBlock, Double> weights,
                                    java.util.Set<LBlock> loop, BitSet units, Map<LItem, List<LItem>> replace, BitSet region) {
        LOperand.Mem anyPlace = placeOf(lf, an, g);
        if (anyPlace == null) {
            return null;
        }
        Map<LBlock, List<LBlock>> preds = new IdentityHashMap<>();
        for (LBlock b : lf.blocks()) {
            for (LBlock t : b.successors()) {
                preds.computeIfAbsent(t, k -> new ArrayList<>()).add(b);
            }
        }
        LBlock head = null;
        for (LBlock b : loop) {
            for (LBlock p : preds.getOrDefault(b, List.of())) {
                if (!loop.contains(p)) {
                    if (head != null && head != b) {
                        return null;   // entered at two blocks
                    }
                    head = b;
                }
            }
        }
        if (head == null) {
            return null;
        }
        boolean writes = false;
        for (LBlock b : loop) {
            for (LItem it : b.items()) {
                PlaceColouring.Access a = an.access.get(it);
                if (a != null && a.writes().stream().anyMatch(units::get)) {
                    writes = true;
                }
            }
        }
        double cost = 0;
        if (an.liveIn.get(head).intersects(units)) {
            LOperand.Mem place = liveOwner(an, an.liveIn.get(head), units, anyPlace);
            if (place == null) {
                return null;
            }
            // the head must not need r or the flags from outside: the load writes both
            if (liveAtStart(head, round.rLiveOut().getOrDefault(head, false), r)
                    || (flagsAtStart(head, round.flagsOut().getOrDefault(head, 0)) & (RedundantLoads.N | RedundantLoads.Z)) != 0) {
                return null;
            }
            for (LBlock p : preds.getOrDefault(head, List.of())) {
                if (loop.contains(p)) {
                    continue;
                }
                if (p.successors().size() != 1 || p.items().isEmpty()) {
                    return null;   // the load would need an edge of its own
                }
                LInstr load = new LInstr(r.load, place, null, true, true, false);
                LItem anchor = p.items().getLast();
                LInstr last = p.last();
                boolean jumps = last != null && !last.fallsThrough() && anchor == last;
                if (replace.containsKey(anchor)) {
                    return null;
                }
                replace.put(anchor, jumps ? List.of(load, anchor) : List.of(anchor, load));
                region.set(round.position().get(anchor));
                cost += weights.getOrDefault(p, 1.0) * load.cycles();
            }
        }
        if (writes) {
            java.util.Set<LBlock> stored = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            for (LBlock b : loop) {
                for (LBlock t : b.successors()) {
                    if (loop.contains(t) || !an.liveIn.get(t).intersects(units) || !stored.add(t)) {
                        continue;
                    }
                    for (LBlock p : preds.getOrDefault(t, List.of())) {
                        if (!loop.contains(p)) {
                            return null;   // the store would need an edge of its own
                        }
                    }
                    // right after the block's label (comments before it belong to the way in from above)
                    LItem anchor = t.label() != null ? t.label() : t.items().isEmpty() ? null : t.items().getFirst();
                    LOperand.Mem place = liveOwner(an, an.liveIn.get(t), units, anyPlace);
                    if (anchor == null || replace.containsKey(anchor) || place == null) {
                        return null;
                    }
                    LInstr store = new LInstr(r.store, place, null, true, true, false);
                    replace.put(anchor, anchor instanceof dampf.lir.LLabel ? List.of(anchor, store) : List.of(store, anchor));
                    region.set(round.position().get(anchor));
                    cost += weights.getOrDefault(t, 1.0) * store.cycles();
                }
            }
        }
        return cost;
    }

    /**
     * g's place named by the one owner whose bytes are live here (the check of the places follows
     * owners: a load before the loop must read the value that comes in, a store after it write the
     * one read there); null if it is not one plain place.
     */
    private static LOperand.Mem liveOwner(Analysis an, BitSet live, BitSet units, LOperand.Mem anyPlace) {
        Group owner = null;
        for (int u = live.nextSetBit(0); u >= 0; u = live.nextSetBit(u + 1)) {
            if (!units.get(u)) {
                continue;
            }
            Group g0 = an.byUnit.get(u);
            if (owner != null && owner != g0 || g0.members.size() != 1) {
                return null;
            }
            owner = g0;
        }
        if (owner == null) {
            return null;
        }
        Place p = owner.members.getFirst();
        return anyPlace.owned(new LOperand.Owner(p.key(), p.incoming(), 0));
    }

    /** An operand of g's place (word 0, no index), from any instruction that names it; null if none. */
    private static LOperand.Mem placeOf(LFunction lf, Analysis an, Group g) {
        for (LInstr i : lf.instrs()) {
            if (i.op() instanceof LOperand.Mem m && m.frame() && m.owner() != null && m.owner().word() == 0
                    && m.index() == LOperand.Index.NONE && !m.indirect() && an.of(Place.of(m.owner())) == g) {
                return m;
            }
        }
        return null;
    }

    /** Whether r is read at the start of b before it is written. */
    private static boolean liveAtStart(LBlock b, boolean liveOut, Reg r) {
        boolean live = liveOut;
        List<LInstr> code = b.instrs();
        for (int j = code.size() - 1; j >= 0; j--) {
            if (writes(code.get(j), r)) {
                live = false;
            }
            if (reads(code.get(j), r)) {
                live = true;
            }
        }
        return live;
    }

    /** The flags read at the start of b before they are written. */
    private static int flagsAtStart(LBlock b, int liveOut) {
        int live = liveOut;
        List<LInstr> code = b.instrs();
        for (int j = code.size() - 1; j >= 0; j--) {
            live = (live & ~RedundantLoads.writes(code.get(j))) | RedundantLoads.reads(code.get(j));
        }
        return live;
    }

    private static boolean allTracked(Group g, Predicate<LOperand.Owner> tracked) {
        for (Place p : g.members) {
            if (!tracked.test(new LOperand.Owner(p.key(), p.incoming(), 0))) {
                return false;
            }
        }
        return true;
    }

    /** What one round of choosing knows of the code, whatever the candidate. */
    /**
     * {@code free}: the loads of A that RedundantLoads will take anyway (A holds what they load on
     * every way in, by {@link dampf.lir.RegContents}): they cost nothing now, so a register saves
     * nothing there.
     */
    private record Round(Map<LBlock, Integer> flagsOut, Map<LBlock, Boolean> rLiveOut, BitSet read, Map<LItem, Integer> position,
                         Map<LBlock, java.util.Set<Group>> named, java.util.Set<LItem> free, Map<LBlock, Boolean> aLiveOut,
                         Map<LBlock, Boolean> otherLiveOut) {}

    private static java.util.Set<LItem> freeLoads(LFunction lf) {
        java.util.Set<LItem> out = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        dampf.lir.RegContents contents = new dampf.lir.RegContents();
        Map<LBlock, dampf.lir.RegContents.State> entries = contents.entries(lf);
        for (LBlock b : lf.blocks()) {
            dampf.lir.RegContents.State st = entries.get(b);
            if (st == null) {
                continue;
            }
            for (LInstr i : b.instrs()) {
                if (i.mnemonic().equals("lda") && i.m16() && i.op() != null && st.a().contains(i.op())) {
                    out.add(i);
                }
                st = contents.after(i, st);
            }
        }
        return out;
    }

    /** The groups each block's items name. */
    private static Map<LBlock, java.util.Set<Group>> named(LFunction lf, Analysis an) {
        Map<LBlock, java.util.Set<Group>> out = new IdentityHashMap<>();
        for (LBlock b : lf.blocks()) {
            java.util.Set<Group> s = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            for (LItem it : b.items()) {
                switch (it) {
                    case LInstr i when owner(i) != null && an.of(Place.of(owner(i))) != null -> s.add(an.of(Place.of(owner(i))));
                    case LCopy c -> {
                        s.add(an.of(Place.of(c.to().owner())));
                        s.add(an.of(Place.of(c.from().owner())));
                    }
                    case LMark m -> m.copies().forEach(c -> {
                        s.add(an.of(Place.of(c.to())));
                        s.add(an.of(Place.of(c.from())));
                    });
                    default -> { }
                }
            }
            out.put(b, s);
        }
        return out;
    }

    private static BitSet readUnits(Analysis an) {
        BitSet read = new BitSet();
        for (PlaceColouring.Access a : an.access.values()) {
            a.reads().forEach(read::set);
        }
        return read;
    }

    /** The plan for g in r, or null if g cannot go there. */
    private static Plan plan(LFunction lf, Analysis an, Round round, Group g, Reg r, Map<LBlock, Double> weights,
                             java.util.Set<LBlock> only) {
        BitSet units = new BitSet();
        for (int u = 0; u < an.byUnit.size(); u++) {
            if (an.byUnit.get(u) != null && PlaceColouring.rep(an.byUnit.get(u)) == g) {
                units.set(u);
            }
        }
        if (!units.intersects(round.read())) {
            return null;       // never read: DeadStores takes its stores
        }
        Map<LBlock, Integer> flagsOut = round.flagsOut();
        Map<LBlock, Boolean> rLiveOut = round.rLiveOut();
        Map<LItem, List<LItem>> replace = new IdentityHashMap<>();
        BitSet region = new BitSet();   // where g is live or named
        double benefit = 0;
        int reads = 0;                  // the reads that are not free
        for (LBlock b : lf.blocks()) {
            if (only != null && !only.contains(b)) {
                continue;      // in the place outside the loop
            }
            BitSet live = an.liveOut(b);
            if (!live.intersects(units) && !round.named().getOrDefault(b, java.util.Set.of()).contains(g)) {
                continue;      // g is neither live nor named here
            }
            int flags = flagsOut.getOrDefault(b, 0);
            boolean rLive = rLiveOut.getOrDefault(b, false);
            boolean aLive = round.aLiveOut().getOrDefault(b, true);
            double w = weights.getOrDefault(b, 1.0);
            List<LItem> items = b.items();
            Map<LItem, Boolean> aLiveAfter = new IdentityHashMap<>();
            java.util.Set<LItem> done = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            for (int k = items.size() - 1; k >= 0; k--) {
                LItem it = items.get(k);
                aLiveAfter.put(it, aLive);
                boolean gLiveAfter = live.intersects(units);
                boolean named = touches(an, g, it);
                if (gLiveAfter || named) {
                    region.set(round.position().get(it));
                }
                Double fused = named && !done.contains(it) ? fused(an, g, r, items, k, aLiveAfter, flags, round.free()) : null;
                if (fused != null) {
                    // a step or a comparison of g, more than one instruction (see fused)
                    if (rLive && isStep(items, k)) {
                        return null;   // r holds something else that is read later
                    }
                    for (LItem x : fusedReplace(an, g, r, items, k, replace, done)) {
                        region.set(round.position().get(x));   // r is g's over the whole form
                    }
                    benefit += w * fused;
                    // a read: the load of the CPX form or of the step, unless A holds it already
                    // (then CPX q saves nothing); LDA q / STA p as LDX q reads nothing of g
                    LItem load = ((LInstr) it).mnemonic().equals("lda") ? it
                            : stepForm(an, g, items, k) != null ? items.get(stepForm(an, g, items, k).get(0)) : null;
                    if (load != null && !round.free().contains(load)) {
                        reads++;
                    }
                } else if (named && done.contains(it)) {
                    // replaced with the instruction after it
                } else if (named && r == Reg.Y && it instanceof LInstr ldx && plainOf(an, g, ldx, "ldx")
                        && IndexInY.convertible(items, k, round.otherLiveOut().getOrDefault(b, true)) != null) {
                    // LDX p / LDA t,X with p in Y: LDA t,Y (X keeps what it held; the patterns' choice of Y for an index)
                    for (int u : IndexInY.convertible(items, k, round.otherLiveOut().getOrDefault(b, true))) {
                        LInstr use = (LInstr) items.get(u);
                        LOperand.Mem m = (LOperand.Mem) use.op();
                        replace.put(use, List.of(use.withOperand(new LOperand.Mem(m.width(), m.base(), m.offset(), LOperand.Index.Y, false))));
                        region.set(round.position().get(use));
                    }
                    replace.put(it, List.of());
                    benefit += w * (round.free().contains(it) ? 0 : ldx.cycles());
                    reads++;
                } else if (named) {
                    boolean nz = (flags & (RedundantLoads.N | RedundantLoads.Z)) != 0;
                    // A has it already: RedundantLoads would take the load, unless a branch reads its flags
                    Replacement rp = it instanceof LInstr i && i.mnemonic().equals("lda") && i.m16() && !nz && aHolds(an, g, items, k)
                            ? new Replacement(List.of(), 0, false, false)
                            : replacement(an, g, r, it);
                    int load = rp == null ? swappable(an, g, items, k) : -1;
                    if (load >= 0) {
                        // LDA q / ADC p: TXA / ADC q (the operation is commutative; CLC or SEC between stay)
                        LInstr q = (LInstr) items.get(load);
                        LInstr op = (LInstr) it;
                        LInstr transfer = new LInstr(r.toA, (LOperand) null, q.comment(), true, true, false);
                        LInstr swapped = new LInstr(op.mnemonic(), q.op(), op.comment(), true, true, false);
                        replace.put(q, List.of(transfer));
                        rp = new Replacement(List.of(swapped), q.cycles() + op.cycles() - transfer.cycles(), false, false);
                    }
                    if (rp == null) {
                        return null;
                    }
                    if (rp.flagsChanged() && (flags & (RedundantLoads.N | RedundantLoads.Z)) != 0) {
                        rp = keepFlags(rp, r, items, k, flags);
                        if (rp == null) {
                            return null;   // a branch reads what the old instruction left in N and Z
                        }
                    }
                    if (rp.writesReg() && rLive) {
                        return null;   // r holds something else that is read later
                    }
                    replace.put(it, rp.items());
                    boolean free = round.free().contains(it);
                    benefit += w * ((free ? (nz ? 3 : 0) : rp.before()) - cycles(rp.items()));
                    PlaceColouring.Access acc = an.access.get(it);
                    if (!free && acc != null && acc.reads().stream().anyMatch(units::get)) {
                        reads++;
                    }
                } else if (it instanceof LInstr i && writes(i, r) && gLiveAfter) {
                    return null;       // r is needed for something else while g is live
                }
                PlaceColouring.Access a = an.access.get(it);
                if (a != null) {
                    a.writes().forEach(live::clear);
                    a.reads().forEach(live::set);
                }
                if (it instanceof LInstr i) {
                    flags = (flags & ~RedundantLoads.writes(i)) | RedundantLoads.reads(i);
                    if (writes(i, r)) {
                        rLive = false;
                    }
                    if (reads(i, r)) {
                        rLive = true;
                    }
                    if (writesA(i)) {
                        aLive = false;
                    }
                    if (readsA(i)) {
                        aLive = true;
                    }
                } else if (it instanceof LCopy) {
                    aLive = false;     // LDA, STA (or nothing): A is written before it is read
                }
            }
        }
        if (reads == 0) {
            return null;       // only read where A holds it already: RedundantLoads and DeadStores take it all
        }
        if (only != null) {
            Double edges = loopEdges(lf, an, round, g, r, weights, only, units, replace, region);
            if (edges == null) {
                return null;
            }
            benefit -= edges;
        }
        return new Plan(g, r, replace, benefit, region);
    }

    /**
     * Whether A holds g's value before item k of a block: loaded from or stored to g's place
     * earlier in the block and not changed since (by owner, as {@link PlaceColouring} follows copies).
     */
    private static boolean aHolds(Analysis an, Group g, List<LItem> items, int k) {
        return aHolds(an, g, items, k, false);
    }

    /**
     * {@code estimate}: only to judge what RedundantLoads would do with the code as it is (no load
     * is left out on it), so a store another form may take still counts.
     */
    private static boolean aHolds(Analysis an, Group g, List<LItem> items, int k, boolean estimate) {
        for (int j = k - 1; j >= 0; j--) {
            if (items.get(j) instanceof LCopy) {
                return false;
            }
            if (!(items.get(j) instanceof LInstr i)) {
                continue;
            }
            boolean mine = touches(an, g, i) && i.op() instanceof LOperand.Mem m && m.owner().word() == 0
                    && m.index() == LOperand.Index.NONE && !m.indirect();
            switch (i.mnemonic()) {
                case "lda" -> {
                    // (not a load the CPX form may take with the CMP after it)
                    int n = next(items, j);
                    return mine && i.m16() && !(n >= 0 && items.get(n) instanceof LInstr c && c.mnemonic().equals("cmp"));
                }
                case "sta" -> {
                    // (not a store a step form may replace with INX, or LDA q / STA p with LDX q:
                    // A would not hold g then)
                    return mine && i.m16() && (estimate || stepForm(an, g, items, j) == null && loadForm(an, g, items, j) == null);
                }
                case "stz", "inc", "dec", "stx", "sty" -> {
                    if (mine) {
                        return false;
                    }
                }
                case "ldx", "ldy", "cpx", "cpy", "cmp", "clc", "sec", "inx", "dex", "iny", "dey", "tax", "tay",
                     "txy", "tyx", "nop" -> { }
                default -> {
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * For a commutative operation with g's place as its operand ({@code ADC p}, {@code AND p},
     * {@code ORA p}, {@code EOR p}, 16 bits) right after {@code LDA q} of something else (a
     * {@code CLC} or {@code SEC} between): the index of that load, else -1.
     */
    private static int swappable(Analysis an, Group g, List<LItem> items, int k) {
        if (!(items.get(k) instanceof LInstr op) || !java.util.Set.of("adc", "and", "ora", "eor").contains(op.mnemonic())
                || !op.m16() || !(op.op() instanceof LOperand.Mem m) || m.index() != LOperand.Index.NONE || m.indirect()
                || m.owner().word() != 0) {
            return -1;
        }
        for (int j = k - 1; j >= 0; j--) {
            if (!(items.get(j) instanceof LInstr i)) {
                continue;
            }
            if (i.mnemonic().equals("clc") || i.mnemonic().equals("sec")) {
                continue;
            }
            // (not where A holds g before the load: RedundantLoads makes that LDA q / ADC p alone)
            return i.mnemonic().equals("lda") && i.m16() && i.op() != null && !touches(an, g, i)
                    && !(i.op() instanceof LOperand.Mem q && q.indirect()) && !aHolds(an, g, items, j, true) ? j : -1;
        }
        return -1;
    }

    private static boolean touches(Analysis an, Group g, LItem it) {
        return switch (it) {
            case LInstr i -> owner(i) != null && an.of(Place.of(owner(i))) == g;
            case LCopy c -> an.of(Place.of(c.to().owner())) == g || an.of(Place.of(c.from().owner())) == g;
            case LMark m -> m.copies().stream().anyMatch(c -> an.of(Place.of(c.to())) == g || an.of(Place.of(c.from())) == g);
            default -> false;
        };
    }

    private static LOperand.Owner owner(LInstr i) {
        return switch (i.op()) {
            case LOperand.Mem m when m.frame() -> m.owner();
            case LOperand.Imm k -> k.owner();
            case null, default -> null;
        };
    }

    /**
     * What an item that names g becomes with g in r: the items, the cycles of the old code,
     * whether it now sets N and Z where the old code did not (or no longer sets them), and
     * whether it writes r.
     */
    private record Replacement(List<LItem> items, int before, boolean flagsChanged, boolean writesReg) {}

    private static Replacement replacement(Analysis an, Group g, Reg r, LItem it) {
        switch (it) {
            case LMark m -> {
                // a copy without code inside the group (both sides are g: marks merge their places)
                return new Replacement(List.of(), 0, false, false);
            }
            case LCopy c -> {
                boolean to = an.of(Place.of(c.to().owner())) == g;
                boolean from = an.of(Place.of(c.from().owner())) == g;
                if (to && from) {
                    return new Replacement(List.of(), 0, false, false);
                }
                int before = cycles(List.of(new LInstr("lda", c.from(), null, true, true, false),
                        new LInstr("sta", c.to(), null, true, true, false)));
                return to ? new Replacement(List.of(new LInstr(r.load, c.from(), null, true, true, false)), before, true, true)
                        : new Replacement(List.of(new LInstr(r.store, c.to(), null, true, true, false)), before, false, false);
            }
            case LInstr i -> {
                if (!(i.op() instanceof LOperand.Mem m) || m.index() != LOperand.Index.NONE || m.indirect()
                        || m.owner().word() != 0 || !i.x16()) {
                    return null;
                }
                int before = i.cycles();
                String mn = i.mnemonic();
                if (mn.equals(r.load)) {
                    return new Replacement(List.of(), before, true, false);
                }
                if (mn.equals(r.other().load)) {
                    return one(r.toOther(), i, before, false, false);   // LDY p is TXY: N and Z the same
                }
                if (mn.equals(r.store)) {
                    return new Replacement(List.of(), before, false, false);
                }
                if (mn.equals(r.other().store)) {
                    return one(r.other().toOther(), i, before, true, true);
                }
                if (!i.m16()) {
                    return null;       // A's byte: X and Y stay 16 bits wide
                }
                return switch (mn) {
                    case "lda" -> one(r.toA, i, before, false, false);
                    case "sta" -> one(r.fromA, i, before, true, true);
                    case "stz" -> new Replacement(List.of(new LInstr(r.load, new LOperand.Imm("$0000"), null, true, true, false)),
                            before, true, true);
                    case "inc" -> one(r.inc, i, before, false, true);
                    case "dec" -> one(r.dec, i, before, false, true);
                    default -> null;   // arithmetic with the place as its operand: no form with r
                };
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * A replacement that changes N and Z where a branch reads them, made to keep them, or null:
     * where they describe A (the instruction that set them last set them from A), {@code STA p}
     * as {@code TAX} sets them the same, and {@code STZ p} as {@code LDX #0} is followed by
     * {@code CMP #0}; a {@code LDX p} that goes becomes {@code CPX #0}. Both compares set the carry:
     * only where it is not read.
     */
    private static Replacement keepFlags(Replacement rp, Reg r, List<LItem> items, int k, int flagsAfter) {
        LInstr i = (LInstr) items.get(k);
        boolean carry = (flagsAfter & CARRY) != 0;
        if (i.mnemonic().equals("sta") && nzFromA(items, k)) {
            return new Replacement(rp.items(), rp.before(), false, rp.writesReg());
        }
        if (i.mnemonic().equals("stz") && nzFromA(items, k) && !carry) {
            List<LItem> out = new ArrayList<>(rp.items());
            out.add(new LInstr("cmp", new LOperand.Imm("$0000"), null, true, true, false));
            return new Replacement(out, rp.before(), false, rp.writesReg());
        }
        if (i.mnemonic().equals(r.load) && rp.items().isEmpty() && !carry) {
            return new Replacement(List.of(new LInstr(r == Reg.X ? "cpx" : "cpy", new LOperand.Imm("$0000"), null, true, true, false)),
                    rp.before(), false, false);
        }
        return null;
    }

    /** Whether N and Z before item k were last set from A's 16 bits (in this block). */
    private static boolean nzFromA(List<LItem> items, int k) {
        for (int j = k - 1; j >= 0; j--) {
            if (!(items.get(j) instanceof LInstr p)) {
                if (items.get(j) instanceof LCopy || items.get(j) instanceof LMark) {
                    return false;
                }
                continue;
            }
            if ((RedundantLoads.writes(p) & (RedundantLoads.N | RedundantLoads.Z)) == 0) {
                continue;      // a store, CLC, a width change: the flags stay
            }
            if (!p.m16()) {
                return false;
            }
            return switch (p.mnemonic()) {
                case "lda", "and", "ora", "eor", "adc", "sbc", "txa", "tya", "pla" -> true;
                case "asl", "lsr", "rol", "ror", "inc", "dec" -> p.op() == null || p.op() instanceof LOperand.Acc;
                case "tax", "tay" -> p.x16();
                default -> false;
            };
        }
        return false;
    }

    private static Replacement one(String mnemonic, LInstr old, int before, boolean flagsChanged, boolean writesReg) {
        return new Replacement(List.of(new LInstr(mnemonic, (LOperand) null, old.comment(), old.m16(), old.x16(), false)),
                before, flagsChanged, writesReg);
    }

    private static int cycles(List<LItem> items) {
        int c = 0;
        for (LItem it : items) {
            if (it instanceof LInstr i) {
                c += i.cycles();
            }
        }
        return c;
    }

    private static void apply(LFunction lf, Map<LItem, List<LItem>> replace) {
        List<LItem> out = new ArrayList<>();
        for (LItem it : lf.items()) {
            List<LItem> r = replace.get(it);
            for (LItem n : r != null ? r : List.of(it)) {
                // TAX right after TAX (two stores of the same place, now one register): one is enough
                if (n instanceof LInstr i && TRANSFERS.contains(i.mnemonic()) && last(out) instanceof LInstr p
                        && p.mnemonic().equals(i.mnemonic()) && p.m16() == i.m16()) {
                    continue;
                }
                out.add(n);
            }
        }
        lf.rebuild(out);
    }

    private static final java.util.Set<String> TRANSFERS = java.util.Set.of("tax", "tay", "txa", "tya");

    /** The last instruction of out, or null if a label or nothing comes after it. */
    private static LItem last(List<LItem> out) {
        for (int k = out.size() - 1; k >= 0; k--) {
            if (out.get(k) instanceof LInstr) {
                return out.get(k);
            }
            if (out.get(k) instanceof dampf.lir.LLabel || out.get(k) instanceof LCopy || out.get(k) instanceof LMark) {
                return null;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ forms of more than one instruction

    /** The instruction before k in the block (comments skipped), -1 if none. */
    private static int prev(List<LItem> items, int k) {
        for (int j = k - 1; j >= 0; j--) {
            if (items.get(j) instanceof LInstr) {
                return j;
            }
            if (items.get(j) instanceof LCopy || items.get(j) instanceof LMark) {
                return -1;
            }
        }
        return -1;
    }

    /** The instruction after k in the block, -1 if none. */
    private static int next(List<LItem> items, int k) {
        for (int j = k + 1; j < items.size(); j++) {
            if (items.get(j) instanceof LInstr) {
                return j;
            }
            if (items.get(j) instanceof LCopy || items.get(j) instanceof LMark) {
                return -1;
            }
        }
        return -1;
    }

    private static boolean plainOf(Analysis an, Group g, LInstr i, String mnemonic) {
        return i.mnemonic().equals(mnemonic) && i.m16() && i.x16() && touches(an, g, i) && i.op() instanceof LOperand.Mem m
                && m.index() == LOperand.Index.NONE && !m.indirect() && m.owner().word() == 0;
    }

    /** A small constant step: ADC #1 to #3 after CLC, or SBC #1 to #3 after SEC; its size, else 0. */
    private static int step(LInstr i, LInstr carry) {
        if (!(i.op() instanceof LOperand.Imm k) || k.value() == null || k.value() < 1 || k.value() > 3 || !i.m16()) {
            return 0;
        }
        if (i.mnemonic().equals("adc") && carry.mnemonic().equals("clc")) {
            return k.value().intValue();
        }
        if (i.mnemonic().equals("sbc") && carry.mnemonic().equals("sec")) {
            return -k.value().intValue();
        }
        return 0;
    }

    /**
     * The items of a step of g that ends with the store at k: {@code LDA p / CLC / ADC #c} (or
     * {@code CLC / LDA p / ADC #c}) and
     * then one or more {@code STA p} (the value and the φ's incoming place it was coalesced with),
     * as indexes: load, carry, operation, stores; null if it is no such step.
     */
    private static List<Integer> stepForm(Analysis an, Group g, List<LItem> items, int k) {
        List<Integer> stores = new ArrayList<>();
        int j = k;
        while (j >= 0 && items.get(j) instanceof LInstr st && plainOf(an, g, st, "sta")) {
            stores.add(0, j);
            j = prev(items, j);
        }
        int a = j;
        int c = a < 0 ? -1 : prev(items, a);
        int l = c < 0 ? -1 : prev(items, c);
        if (c >= 0 && items.get(c) instanceof LInstr first && first.mnemonic().equals("lda") && l >= 0) {
            int t = c;         // CLC / LDA p / ADC #c, as selection writes it
            c = l;
            l = t;
        }
        if (l < 0 || !(items.get(l) instanceof LInstr load) || !plainOf(an, g, load, "lda")
                || step((LInstr) items.get(a), (LInstr) items.get(c)) == 0) {
            return null;
        }
        List<Integer> form = new ArrayList<>(List.of(l, c, a));
        form.addAll(stores);
        return form;
    }

    /**
     * The items of {@code LDA q} and one or more {@code STA p} of g after it, where q is something
     * X (Y) loads as well (an immediate, or a place without an index); as indexes, the load first;
     * null if it is no such form. With A not read afterwards it is {@code LDX q}.
     */
    private static List<Integer> loadForm(Analysis an, Group g, List<LItem> items, int k) {
        List<Integer> stores = new ArrayList<>();
        int j = k;
        while (j >= 0 && items.get(j) instanceof LInstr st && plainOf(an, g, st, "sta")) {
            stores.add(0, j);
            j = prev(items, j);
        }
        if (j < 0 || !(items.get(j) instanceof LInstr q) || !q.mnemonic().equals("lda") || !q.m16() || touches(an, g, q)
                || !comparable(q.op())) {
            return null;
        }
        List<Integer> form = new ArrayList<>(List.of(j));
        form.addAll(stores);
        return form;
    }

    /** Whether item k ends a step of g ({@code STA p} after {@code LDA p / CLC / ADC #c}). */
    private static boolean isStep(List<LItem> items, int k) {
        return items.get(k) instanceof LInstr i && i.mnemonic().equals("sta");
    }

    /**
     * The weighted-less cycles saved by a form of g that spans instructions, at item k, or null:
     * <ul>
     * <li>{@code LDA p / CLC / ADC #c / STA p} with c up to 3 (or {@code SEC / SBC}) is c times
     *     {@code INX} ({@code DEX}), where A and the carry are not read afterwards;</li>
     * <li>{@code LDA p / CMP q} is {@code CPX q}, where A is not read afterwards.</li>
     * </ul>
     */
    private static Double fused(Analysis an, Group g, Reg r, List<LItem> items, int k, Map<LItem, Boolean> aLiveAfter, int flagsAfter,
                                java.util.Set<LItem> free) {
        if (!(items.get(k) instanceof LInstr i)) {
            return null;
        }
        if (plainOf(an, g, i, "sta") && !aLiveAfter.getOrDefault(i, true)
                && (flagsAfter & (CARRY | OVERFLOW)) == 0) {   // INX sets N and Z as ADC would, not C and V
            List<Integer> form = stepForm(an, g, items, k);
            if (form != null) {
                int before = 0;
                for (int x : form) {
                    before += ((LInstr) items.get(x)).cycles();
                }
                int n = step((LInstr) items.get(form.get(2)), (LInstr) items.get(form.get(1)));
                return (double) (before - 2 * Math.abs(n));
            }
        }
        if (plainOf(an, g, i, "sta") && !aLiveAfter.getOrDefault(i, true)) {
            List<Integer> form = loadForm(an, g, items, k);
            if (form != null) {
                int before = 0;
                for (int x : form) {
                    before += ((LInstr) items.get(x)).cycles();
                }
                LInstr q = (LInstr) items.get(form.get(0));
                return (double) (before - new LInstr(r.load, q.op(), null, true, true, false).cycles());
            }
        }
        if (plainOf(an, g, i, "lda")) {
            int n = next(items, k);
            if (n >= 0 && items.get(n) instanceof LInstr cmp && cmp.mnemonic().equals("cmp") && cmp.m16()
                    && comparable(cmp.op()) && !touches(an, g, cmp) && !aLiveAfter.getOrDefault(cmp, true)) {
                LInstr cpx = new LInstr(r == Reg.X ? "cpx" : "cpy", cmp.op(), cmp.comment(), true, true, false);
                return (double) ((free.contains(i) ? 0 : i.cycles()) + cmp.cycles() - cpx.cycles());
            }
        }
        return null;
    }

    private static final int CARRY = 4;
    private static final int OVERFLOW = 8;

    /** An operand CPX and CPY take: an immediate, or direct page or absolute without an index. */
    private static boolean comparable(LOperand op) {
        return op instanceof LOperand.Imm
                || op instanceof LOperand.Mem m && m.index() == LOperand.Index.NONE && !m.indirect() && m.width() != LOperand.Width.LONG;
    }

    /** Puts the replacement of the form fused() found at k into replace, and its items into done; returns them all. */
    private static List<LItem> fusedReplace(Analysis an, Group g, Reg r, List<LItem> items, int k, Map<LItem, List<LItem>> replace,
                                            java.util.Set<LItem> done) {
        LInstr i = (LInstr) items.get(k);
        List<Integer> load = i.mnemonic().equals("sta") ? loadForm(an, g, items, k) : null;
        if (load != null && stepForm(an, g, items, k) == null) {
            LInstr q = (LInstr) items.get(load.get(0));
            List<LItem> all = new ArrayList<>();
            for (int x : load) {
                replace.put(items.get(x), x == load.get(0) ? List.of(new LInstr(r.load, q.op(), q.comment(), true, true, false))
                        : List.of());
                done.add(items.get(x));
                all.add(items.get(x));
            }
            return all;
        }
        if (i.mnemonic().equals("sta")) {
            List<Integer> form = stepForm(an, g, items, k);
            int n = step((LInstr) items.get(form.get(2)), (LInstr) items.get(form.get(1)));
            List<LItem> steps = new ArrayList<>();
            for (int s = 0; s < Math.abs(n); s++) {
                steps.add(new LInstr(n > 0 ? r.inc : r.dec, (LOperand) null, null, true, true, false));
            }
            List<LItem> all = new ArrayList<>();
            for (int x : form) {
                replace.put(items.get(x), x == form.get(2) ? steps : List.of());
                done.add(items.get(x));
                all.add(items.get(x));
            }
            return all;
        } else {
            int n = next(items, k);
            LInstr cmp = (LInstr) items.get(n);
            replace.put(i, List.of());
            replace.put(cmp, List.of(new LInstr(r == Reg.X ? "cpx" : "cpy", cmp.op(), cmp.comment(), true, true, false)));
            done.add(cmp);
            return List.of(i, cmp);
        }
    }

    // ------------------------------------------------------------------ what reads and writes X and Y

    static boolean writes(LInstr i, Reg r) {
        String m = i.mnemonic();
        if (m.equals("jsl") || m.equals("jsr") || m.equals("mvn") || m.equals("mvp")) {
            return true;
        }
        if ((m.equals("rep") || m.equals("sep")) && i.op() instanceof LOperand.Imm k && (k.value() == null || (k.value() & 0x10) != 0)) {
            return true;       // the index width
        }
        return r == Reg.X ? switch (m) {
            case "ldx", "inx", "dex", "tax", "tsx", "plx", "tyx" -> true;
            default -> false;
        } : switch (m) {
            case "ldy", "iny", "dey", "tay", "ply", "txy" -> true;
            default -> false;
        };
    }

    /** Whether the function being looked at returns a high word in X, and a value in A (one function at a time). */
    private static boolean returnsX;
    private static boolean returnsA;

    /** Instructions that read neither A nor its high byte. */
    private static final java.util.Set<String> NO_A = java.util.Set.of("ldx", "ldy", "stx", "sty", "stz", "inx", "iny", "dex",
            "dey", "cpx", "cpy", "txy", "tyx", "tsx", "txs", "clc", "sec", "cli", "sei", "clv", "rep", "sep", "bra", "brl", "jmp",
            "jml", "beq", "bne", "bcc", "bcs", "bmi", "bpl", "bvc", "bvs", "nop", "phx", "phy", "plx", "ply", "txa", "tya", "pla",
            "tdc", "tsc");

    /** Whether i reads A (any byte of it); what is not known reads it. */
    static boolean readsA(LInstr i) {
        String m = i.mnemonic();
        if (m.equals("rtl") || m.equals("rts")) {
            return returnsA;
        }
        if (m.equals("jsl") || m.equals("jsr")) {
            // a runtime routine takes A; a C function takes its arguments in its frame
            return !(i.op() instanceof LOperand.Label l) || l.name().startsWith("dampf_");
        }
        if (m.equals("lda")) {
            return !i.m16();   // 8 bits: the high byte stays
        }
        if (Set_OF_MEMORY_RMW.contains(m)) {
            return i.op() == null || i.op() instanceof LOperand.Acc;
        }
        return !NO_A.contains(m);
    }

    private static final java.util.Set<String> Set_OF_MEMORY_RMW = java.util.Set.of("inc", "dec", "asl", "lsr", "rol", "ror");

    /** Whether i writes all of A. */
    static boolean writesA(LInstr i) {
        return switch (i.mnemonic()) {
            case "lda" -> i.m16();
            case "txa", "tya", "pla", "tdc", "tsc" -> i.m16();
            default -> false;
        };
    }

    /** Whether A is read at the end of each block before it is written. */
    private static Map<LBlock, Boolean> aLiveOut(LFunction lf) {
        Map<LBlock, Boolean> in = new IdentityHashMap<>();
        Map<LBlock, Boolean> out = new IdentityHashMap<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int k = lf.blocks().size() - 1; k >= 0; k--) {
                LBlock b = lf.blocks().get(k);
                boolean live = false;
                for (LBlock s : b.successors()) {
                    live |= in.getOrDefault(s, false);
                }
                out.put(b, live);
                List<LItem> items = b.items();
                for (int j = items.size() - 1; j >= 0; j--) {
                    if (items.get(j) instanceof LInstr i) {
                        if (writesA(i)) {
                            live = false;
                        }
                        if (readsA(i)) {
                            live = true;
                        }
                    } else if (items.get(j) instanceof LCopy) {
                        live = false;
                    }
                }
                if (in.getOrDefault(b, false) != live) {
                    in.put(b, live);
                    changed = true;
                }
            }
        }
        return out;
    }

    static boolean reads(LInstr i, Reg r) {
        String m = i.mnemonic();
        if (m.equals("mvn") || m.equals("mvp")) {
            return true;
        }
        if (m.equals("jsl") || m.equals("jsr")) {
            return false;      // a C function takes its arguments in its frame, a runtime routine in A and R0..R6
        }
        if (m.equals("rtl") || m.equals("rts")) {
            return r == Reg.X && returnsX;
        }
        if (i.op() instanceof LOperand.Mem mem && mem.index() == (r == Reg.X ? LOperand.Index.X : LOperand.Index.Y)) {
            return true;
        }
        if (i.op() instanceof LOperand.Raw raw && raw.text().toLowerCase().contains("," + r.name)) {
            return true;
        }
        return r == Reg.X ? switch (m) {
            case "stx", "cpx", "txa", "txy", "txs", "phx", "inx", "dex" -> true;
            default -> false;
        } : switch (m) {
            case "sty", "cpy", "tya", "tyx", "phy", "iny", "dey" -> true;
            default -> false;
        };
    }

    /** Whether r is live at the end of each block: read on some way before it is written. */
    private static Map<LBlock, Boolean> registerLiveOut(LFunction lf, Reg r) {
        Map<LBlock, Boolean> in = new IdentityHashMap<>();
        Map<LBlock, Boolean> out = new IdentityHashMap<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int k = lf.blocks().size() - 1; k >= 0; k--) {
                LBlock b = lf.blocks().get(k);
                boolean live = false;
                for (LBlock s : b.successors()) {
                    live |= in.getOrDefault(s, false);
                }
                out.put(b, live);
                List<LInstr> code = b.instrs();
                for (int j = code.size() - 1; j >= 0; j--) {
                    LInstr i = code.get(j);
                    if (writes(i, r)) {
                        live = false;
                    }
                    if (reads(i, r)) {
                        live = true;
                    }
                }
                if (in.getOrDefault(b, false) != live) {
                    in.put(b, live);
                    changed = true;
                }
            }
        }
        return out;
    }
}
