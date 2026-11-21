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
                   boolean highWordInX) {
        returnsX = highWordInX;
        int n = 0;
        for (Reg r : Reg.values()) {
            while (true) {
                Analysis an = PlaceColouring.analyse(lf, tracked, frame);
                Map<LItem, Integer> position = new IdentityHashMap<>();
                for (LItem it : lf.items()) {
                    position.put(it, position.size());
                }
                Round round = new Round(RedundantLoads.flagsLiveOut(lf), registerLiveOut(lf, r), readUnits(an), position, named(lf, an),
                        freeLoads(lf));
                List<Plan> plans = new ArrayList<>();
                for (Group g : an.groups) {
                    if (g.into != null || g.fixed != null || g.direct || g.bytes != 2 || !allTracked(g, tracked)) {
                        continue;
                    }
                    Plan p = plan(lf, an, round, g, r, weights);
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
                         Map<LBlock, java.util.Set<Group>> named, java.util.Set<LItem> free) {}

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
    private static Plan plan(LFunction lf, Analysis an, Round round, Group g, Reg r, Map<LBlock, Double> weights) {
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
            BitSet live = an.liveOut(b);
            if (!live.intersects(units) && !round.named().getOrDefault(b, java.util.Set.of()).contains(g)) {
                continue;      // g is neither live nor named here
            }
            int flags = flagsOut.getOrDefault(b, 0);
            boolean rLive = rLiveOut.getOrDefault(b, false);
            double w = weights.getOrDefault(b, 1.0);
            List<LItem> items = b.items();
            for (int k = items.size() - 1; k >= 0; k--) {
                LItem it = items.get(k);
                boolean gLiveAfter = live.intersects(units);
                boolean named = touches(an, g, it);
                if (gLiveAfter || named) {
                    region.set(round.position().get(it));
                }
                if (named) {
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
                        return null;   // a branch reads what the old instruction left in N and Z
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
                }
            }
        }
        if (reads == 0) {
            return null;       // only read where A holds it already: RedundantLoads and DeadStores take it all
        }
        return new Plan(g, r, replace, benefit, region);
    }

    /**
     * Whether A holds g's value before item k of a block: loaded from or stored to g's place
     * earlier in the block and not changed since (by owner, as {@link PlaceColouring} follows copies).
     */
    private static boolean aHolds(Analysis an, Group g, List<LItem> items, int k) {
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
                case "lda", "sta" -> {
                    return mine && i.m16();
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
                    && !(i.op() instanceof LOperand.Mem q && q.indirect()) && !aHolds(an, g, items, j) ? j : -1;
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
            out.addAll(r != null ? r : List.of(it));
        }
        lf.rebuild(out);
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

    /** Whether the function being looked at returns a high word in X (one function at a time). */
    private static boolean returnsX;

    static boolean reads(LInstr i, Reg r) {
        String m = i.mnemonic();
        if (m.equals("jsl") || m.equals("jsr") || m.equals("mvn") || m.equals("mvp")) {
            return true;       // a callee (a runtime routine) may take it
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
