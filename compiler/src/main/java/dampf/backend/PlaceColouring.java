package dampf.backend;

import dampf.lir.LBlock;
import dampf.lir.LCopy;
import dampf.lir.LFunction;
import dampf.lir.LInstr;
import dampf.lir.LItem;
import dampf.lir.LMark;
import dampf.lir.LOperand;
import dampf.lir.PlaceCheck;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Gives the frame places of one function their addresses after selection, from its LIR (B2 of
 * docs/current-compiler-flaws.md, ADR records 48 and 49): liveness of the places' bytes over the
 * code selection wrote, an interference graph (a place written while another one is live, unless
 * the write copies those bytes of it), coalescing (the two places of a copy become one where they
 * do not conflict: a φ copy, {@link LCopy}, then needs no code), and greedy colouring by byte
 * ranges, in the order of the code. Places selection relied on sharing (its marks, {@link LMark})
 * are one place. Parameters, frame slots, callee parameters and the values computed in them are
 * fixed ({@link Frame#fixed}); a pointer read as {@code [place],y} is coloured first and must
 * lie in the direct page.
 */
final class PlaceColouring {
    private PlaceColouring() {}

    /** A place: a value's, a parameter's or a slot's, or a φ's incoming place. Compared by the key's identity. */
    record Place(Object key, boolean incoming) {
        static Place of(LOperand.Owner o) {
            return new Place(o.key(), o.incoming());
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Place p && p.key == key && p.incoming == incoming;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(key) * 2 + (incoming ? 1 : 0);
        }
    }

    /** What the colouring is told about the places. */
    interface Frame {
        /** The address of a place that is not coloured, or null. */
        Integer fixed(Place p);

        /** The bytes of a place. */
        int bytes(Place p);

        /**
         * Whether a fixed place lies outside the function's frame: a callee's parameter, or a value
         * computed into one. The callee may change it, which the liveness here does not see, so
         * nothing is coalesced with it.
         */
        default boolean external(Place p) {
            return false;
        }

    }

    /** A set of places that share one address: the members of a mark. */
    private static final class Group {
        final List<Place> members = new ArrayList<>();
        Integer fixed;
        boolean external;
        int bytes;
        boolean direct;   // read as [place],y: must lie in the direct page
        int first;        // its first byte's index in the liveness bit sets
        int at = -1;
        /** The group this one was coalesced into, or null. */
        Group into;
        final BitSet conflicts = new BitSet();
        /** Groups a copy goes between: they may have exactly the same address, but not overlap otherwise. */
        final BitSet samePlaceOnly = new BitSet();
        int index;
    }

    /**
     * The address of every place the code of lf names, the coloured ones at or above {@code start}.
     * {@code tracked}: the places whose liveness is followed (the function's own).
     */
    static Map<Place, Integer> colour(LFunction lf, Predicate<LOperand.Owner> tracked, Frame frame, int start) {
        Map<Place, Group> groupOf = new HashMap<>();
        List<Group> groups = new ArrayList<>();
        // the places, in the order the code names them
        for (LItem it : lf.items()) {
            for (LOperand.Owner o : owners(it)) {
                group(Place.of(o), groupOf, groups, frame);
            }
        }
        for (LItem it : lf.items()) {
            if (it instanceof LMark m) {
                for (LMark.Copy c : m.copies()) {
                    if (c.to().word() != c.from().word()) {
                        throw new IllegalStateException("a copy between different bytes of two places in " + lf.name());
                    }
                    merge(groupOf.get(Place.of(c.to())), groupOf.get(Place.of(c.from())), groupOf, groups, lf);
                }
            }
        }
        for (int k = 0; k < groups.size(); k++) {
            groups.get(k).index = k;
        }
        int units = 0;
        for (Group g : groups) {
            g.first = units;
            units += g.bytes;
        }
        for (LInstr i : lf.instrs()) {
            if (i.op() instanceof LOperand.Mem m && m.indirect() && m.owner() != null) {
                groupOf.get(Place.of(m.owner())).direct = true;
            }
        }
        List<Group[]> copies = interfere(lf, tracked, groupOf, units);
        coalesce(copies, groups, lf);

        List<Group> order = new ArrayList<>();
        for (Group g : groups) {
            if (g.into != null) {
                continue;   // coalesced into another
            }
            if (g.fixed != null) {
                if (g.direct && !inDirectPage(g.fixed)) {
                    throw new IllegalStateException("a pointer of " + lf.name() + " read as [place],y is not in the direct page");
                }
                g.at = g.fixed;
            } else {
                order.add(g);
            }
        }
        order.sort(Comparator.comparing((Group g) -> !g.direct));   // (stable: else in the order of the code)
        for (Group g : order) {
            int o;
            Integer partner = partner(g, groups);
            if (partner != null && partner >= start && (!g.direct || inDirectPage(partner))) {
                o = partner;   // a copy's other place: the copy needs no code
            } else {
                o = start;
                for (Group n = blocker(g, o, groups); n != null; n = blocker(g, o, groups)) {
                    o = n.at + n.bytes;
                }
                if (g.direct && !inDirectPage(o)) {
                    throw new IllegalStateException("no place in the direct page is left for a pointer of " + lf.name());
                }
            }
            g.at = o;
        }
        Map<Place, Integer> out = new LinkedHashMap<>();
        for (Group g : groups) {
            for (Place p : g.members) {
                out.put(p, rep(g).at);
            }
        }
        return out;
    }

    /** The address of a placed group a copy goes between with g, where g fits (exactly on it), or null. */
    private static Integer partner(Group g, List<Group> groups) {
        for (int x = g.samePlaceOnly.nextSetBit(0); x >= 0; x = g.samePlaceOnly.nextSetBit(x + 1)) {
            Group n = groups.get(x);
            if (n.at >= 0 && !n.external && !g.conflicts.get(x) && blocker(g, n.at, groups) == null) {
                return n.at;
            }
        }
        return null;
    }

    private static Group rep(Group g) {
        while (g.into != null) {
            g = g.into;
        }
        return g;
    }

    /**
     * Aggressive coalescing: the two places of each copy, in the order of the code, become one if
     * they do not conflict (then the copy is no code). A group that conflicts with a member of a
     * coalesced group conflicts with the group (its bit set has the group's index).
     */
    private static void coalesce(List<Group[]> copies, List<Group> groups, LFunction lf) {
        for (Group[] c : copies) {
            Group a = rep(c[0]);
            Group b = rep(c[1]);
            if (a == b || a.conflicts.get(b.index) || b.conflicts.get(a.index) || a.external || b.external
                    || a.fixed != null && b.fixed != null && !a.fixed.equals(b.fixed)
                    || a.fixed != null && b.bytes > a.bytes || b.fixed != null && a.bytes > b.bytes) {
                continue;   // (a fixed place cannot grow over its neighbours)
            }
            if (b.fixed != null || a.fixed == null && b.index < a.index) {
                Group t = a;   // the fixed one, else the one the code names first, stays
                a = b;
                b = t;
            }
            b.into = a;
            a.bytes = Math.max(a.bytes, b.bytes);
            a.direct |= b.direct;
            for (BitSet[] sets : List.of(new BitSet[] {b.conflicts, a.conflicts}, new BitSet[] {b.samePlaceOnly, a.samePlaceOnly})) {
                BitSet from = sets[0];
                for (int x = from.nextSetBit(0); x >= 0; x = from.nextSetBit(x + 1)) {
                    Group n = groups.get(x);
                    (sets[0] == b.conflicts ? n.conflicts : n.samePlaceOnly).set(a.index);
                    (sets[0] == b.conflicts ? rep(n).conflicts : rep(n).samePlaceOnly).set(a.index);
                }
                sets[1].or(from);
            }
        }
        for (Group g : groups) {
            if (g.into == null) {
                // what conflicts with a coalesced member conflicts with its group
                for (BitSet s : List.of(g.conflicts, g.samePlaceOnly)) {
                    BitSet reps = new BitSet();
                    for (int x = s.nextSetBit(0); x >= 0; x = s.nextSetBit(x + 1)) {
                        reps.set(rep(groups.get(x)).index);
                    }
                    s.or(reps);
                }
            }
        }
    }

    /** A pointer's three bytes at this address are in the direct page ([dp],y). */
    static boolean inDirectPage(int at) {
        return Selection.FRAME_START + at + 3 < 0x100;
    }

    /** A placed group that g at o would overlap (one it may share exactly with, only if not exactly), or null. */
    private static Group blocker(Group g, int o, List<Group> groups) {
        for (int x = g.conflicts.nextSetBit(0); x >= 0; x = g.conflicts.nextSetBit(x + 1)) {
            Group n = groups.get(x);
            if (n.at >= 0 && n.at < o + g.bytes && o < n.at + n.bytes) {
                return n;
            }
        }
        for (int x = g.samePlaceOnly.nextSetBit(0); x >= 0; x = g.samePlaceOnly.nextSetBit(x + 1)) {
            Group n = groups.get(x);
            if (n.at >= 0 && n.at < o + g.bytes && o < n.at + n.bytes && n.at != o) {
                return n;
            }
        }
        return null;
    }

    private static List<LOperand.Owner> owners(LItem it) {
        List<LOperand.Owner> out = new ArrayList<>();
        if (it instanceof LInstr i) {
            if (i.op() instanceof LOperand.Mem m && m.frame() && m.owner() != null) {
                out.add(m.owner());
            } else if (i.op() instanceof LOperand.Imm k && k.owner() != null) {
                out.add(k.owner());
            }
        } else if (it instanceof LMark m) {
            for (LMark.Copy c : m.copies()) {
                out.add(c.to());
                out.add(c.from());
            }
        } else if (it instanceof LCopy c) {
            out.add(c.to().owner());
            out.add(c.from().owner());
        }
        return out;
    }

    private static Group group(Place p, Map<Place, Group> groupOf, List<Group> groups, Frame frame) {
        Group g = groupOf.get(p);
        if (g == null) {
            g = new Group();
            g.members.add(p);
            g.fixed = frame.fixed(p);
            g.external = g.fixed != null && frame.external(p);
            g.bytes = frame.bytes(p);
            groupOf.put(p, g);
            groups.add(g);
        }
        return g;
    }

    private static void merge(Group a, Group b, Map<Place, Group> groupOf, List<Group> groups, LFunction lf) {
        if (a == b) {
            return;
        }
        if (a.fixed != null && b.fixed != null && !a.fixed.equals(b.fixed)) {
            throw new IllegalStateException("a copy without code between two fixed places in " + lf.name());
        }
        for (Place p : b.members) {
            a.members.add(p);
            groupOf.put(p, a);
        }
        a.fixed = a.fixed != null ? a.fixed : b.fixed;
        a.external |= b.external;
        a.bytes = Math.max(a.bytes, b.bytes);
        groups.remove(b);
    }

    /**
     * What an item reads and writes: indexes of place bytes. {@code copyOf}: the group whose byte
     * at the same offset each written byte copies (A was loaded from it), or null.
     */
    private record Access(List<Integer> reads, List<Integer> writes, Group copyOf) {}

    /**
     * The interference graph: a place written while a byte of another one is live after the
     * write conflicts with it, unless the write stores what A was loaded from that byte at the
     * same offset of the other place (a copy: the two may share exactly the same address, see
     * {@code samePlaceOnly}). Marks are copies of places in the same group.
     */
    private static List<Group[]> interfere(LFunction lf, Predicate<LOperand.Owner> tracked, Map<Place, Group> groupOf, int units) {
        List<Group[]> copies = new ArrayList<>();
        Map<LItem, Access> access = new IdentityHashMap<>();
        List<Group> byUnit = new ArrayList<>();
        for (int k = 0; k < units; k++) {
            byUnit.add(null);
        }
        for (Group g : new java.util.LinkedHashSet<>(groupOf.values())) {
            for (int k = 0; k < g.bytes; k++) {
                byUnit.set(g.first + k, g);
            }
        }
        for (LBlock b : lf.blocks()) {
            // what A was loaded from (a group and the byte of it), forgotten when A or that place changes
            Group aFrom = null;
            int aByte = -1;
            boolean aWide = false;
            for (LItem it : b.items()) {
                if (it instanceof LMark m) {
                    List<Integer> r = new ArrayList<>();
                    List<Integer> w = new ArrayList<>();
                    for (LMark.Copy c : m.copies()) {
                        Group g = groupOf.get(Place.of(c.from()));
                        for (int k = 0; k < c.bytes(); k++) {
                            if (tracked.test(c.from())) {
                                r.add(g.first + c.from().word() + k);
                            }
                            if (tracked.test(c.to())) {
                                w.add(g.first + c.to().word() + k);
                            }
                        }
                    }
                    access.put(m, new Access(r, w, null));
                    continue;
                }
                if (it instanceof LCopy c) {
                    // LDA from, STA to, or nothing: what A holds afterwards is not known
                    Group from = groupOf.get(Place.of(c.from().owner()));
                    Group to = groupOf.get(Place.of(c.to().owner()));
                    List<Integer> r = new ArrayList<>();
                    List<Integer> w = new ArrayList<>();
                    for (int k = 0; k < 2; k++) {
                        if (tracked.test(c.from().owner())) {
                            r.add(from.first + c.from().owner().word() + k);
                        }
                        if (tracked.test(c.to().owner())) {
                            w.add(to.first + c.to().owner().word() + k);
                        }
                    }
                    access.put(c, new Access(r, w, from));
                    aFrom = null;
                    continue;
                }
                if (!(it instanceof LInstr i)) {
                    continue;
                }
                PlaceCheck.Touches t = PlaceCheck.touches(i, tracked);
                Group copyOf = null;
                if (t != null) {
                    Group g = groupOf.get(Place.of(t.owner()));
                    List<Integer> at = new ArrayList<>();
                    if (t.owner().word() + t.width() > g.bytes) {
                        throw new IllegalStateException(i + " reaches past its place in " + lf.name());
                    }
                    for (int k = 0; k < t.width(); k++) {
                        at.add(g.first + t.owner().word() + k);
                    }
                    if (i.mnemonic().equals("sta") && aFrom != null && aWide == i.m16() && aByte == t.owner().word()) {
                        copyOf = aFrom;
                    }
                    access.put(i, new Access(t.reads() ? at : List.of(), t.writes() ? at : List.of(), copyOf));
                    if (t.writes() && g == aFrom) {
                        aFrom = null;   // A no longer equals what that place holds
                    }
                }
                switch (i.mnemonic()) {
                    case "lda" -> {
                        boolean plain = t != null && i.op() instanceof LOperand.Mem m && !m.indirect();
                        aFrom = plain ? groupOf.get(Place.of(t.owner())) : null;
                        aByte = plain ? t.owner().word() : -1;
                        aWide = i.m16();
                    }
                    case "sta", "stx", "sty", "stz", "ldx", "ldy", "cpx", "cpy", "cmp", "clc", "sec",
                         "inx", "dex", "iny", "dey", "txy", "tyx", "nop" -> { }
                    default -> aFrom = null;
                }
            }
        }

        // liveness of the bytes, backwards to a fixed point
        Map<LBlock, BitSet> liveIn = new IdentityHashMap<>();
        Map<LBlock, List<LBlock>> preds = new IdentityHashMap<>();
        for (LBlock b : lf.blocks()) {
            liveIn.put(b, new BitSet());
            for (LBlock s : b.successors()) {
                preds.computeIfAbsent(s, k -> new ArrayList<>()).add(b);
            }
        }
        ArrayDeque<LBlock> work = new ArrayDeque<>(lf.blocks().reversed());
        while (!work.isEmpty()) {
            LBlock b = work.removeFirst();
            BitSet live = liveOut(b, liveIn);
            List<LItem> items = b.items();
            for (int k = items.size() - 1; k >= 0; k--) {
                transfer(access.get(items.get(k)), live);
            }
            if (!live.equals(liveIn.get(b))) {
                liveIn.put(b, live);
                for (LBlock p : preds.getOrDefault(b, List.of())) {
                    if (!work.contains(p)) {
                        work.add(p);
                    }
                }
            }
        }
        for (LBlock b : lf.blocks()) {
            BitSet live = liveOut(b, liveIn);
            List<LItem> items = b.items();
            for (int k = items.size() - 1; k >= 0; k--) {
                Access a = access.get(items.get(k));
                if (a == null) {
                    continue;
                }
                // the offsets (in its group) of the bytes a copy writes: the same bytes of the source may share
                BitSet copied = new BitSet();
                for (int w : a.writes()) {
                    copied.set(w - byUnit.get(w).first);
                }
                for (int w : a.writes()) {
                    Group g = byUnit.get(w);
                    if (a.copyOf() != null && a.copyOf() != g) {
                        g.samePlaceOnly.set(a.copyOf().index);
                        a.copyOf().samePlaceOnly.set(g.index);
                        copies.add(new Group[] {g, a.copyOf()});
                    }
                    for (int x = live.nextSetBit(0); x >= 0; x = live.nextSetBit(x + 1)) {
                        Group n = byUnit.get(x);
                        if (n != g && !(n == a.copyOf() && copied.get(x - n.first))) {
                            g.conflicts.set(n.index);
                            n.conflicts.set(g.index);
                        }
                    }
                }
                transfer(a, live);
            }
        }
        return copies;
    }

    private static BitSet liveOut(LBlock b, Map<LBlock, BitSet> liveIn) {
        BitSet live = new BitSet();
        for (LBlock s : b.successors()) {
            live.or(liveIn.get(s));
        }
        return live;
    }

    private static void transfer(Access a, BitSet live) {
        if (a == null) {
            return;
        }
        a.writes().forEach(live::clear);
        a.reads().forEach(live::set);
    }
}
