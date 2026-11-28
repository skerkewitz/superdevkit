package dampf.lir;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The accumulator's width placed across blocks (M12 row 3): every {@code REP #$20} and
 * {@code SEP #$20} of a function is taken out and placed again where the code needs it, by the
 * cycles of the blocks as they are weighted, so that byte code runs in 8 bits across branches and
 * the switches go where they run least. It generalises {@link ByteStretches} (one block) and
 * {@link ByteLoops} (a loop from entry to exit), which run before it.
 *
 * <p>Each instruction that depends on the width keeps the width it was written for, except one
 * whose low byte is the same in 8 bits ({@code LDA}, {@code AND}, {@code ORA}, {@code EOR},
 * {@code ADC}, {@code SBC}, {@code TXA}, {@code TYA}, {@code ASL A}, {@code INC A},
 * {@code DEC A}, not from a hardware register, an immediate whose value is known) and whose flags
 * nothing reads: it may run in 8 bits, a cycle cheaper for each byte it reads. In 8 bits it leaves
 * the high byte of A as it was, so it may only where nothing reads the high byte before A is
 * loaded again in full: no 16-bit use of A, no {@code TAX}/{@code TAY}/{@code XBA}, no call, no
 * return of a value in A. Calls and returns run with 16 bits (the calling convention), and so does
 * a function's start.
 *
 * <p>The choice is a minimum cut: each such instruction is a node that is 16 or 8 bits, a switch
 * between two neighbours of different widths (in a block, or between a block's start or end and
 * its code) costs the switch's cycles times the block's weight, the end of a block and the start
 * of each block it goes to have the same width, and an instruction that reads the high byte in 16
 * bits keeps every instruction before it on the way from the last full load of A in 16 bits.
 * Binary choices with costs between pairs that are only paid where the two differ make a cut of
 * a graph exact. The code is rewritten where the cut is cheaper than the code as it was; then the
 * switches lie at the start of the first instruction of their width, or before the branch that
 * ends a block, and an immediate in 8 bits gets {@code .a8}/{@code .a16} around it.
 *
 * <p>{@link LParser} (and the printer's cycle column) follow the switches in the order of the
 * text, the code in the order of its edges: where the two would differ the function is left as it
 * was.
 */
public final class Widths {
    /** Instructions whose effect and cycles do not depend on the accumulator's width (index registers 16 bit). */
    private static final Set<String> ANY_WIDTH = Set.of("bcc", "bcs", "beq", "bne", "bmi", "bpl", "bvc", "bvs", "bra", "brl",
            "jmp", "jml", "ldx", "ldy", "stx", "sty", "inx", "iny", "dex", "dey", "cpx", "cpy", "tax", "tay", "txy", "tyx", "tsx",
            "txs", "tcd", "tdc", "tcs", "tsc", "xba", "clc", "sec", "clv", "cli", "sei", "nop", "phx", "phy", "plx", "ply", "phb",
            "plb", "phd", "pld", "phk", "mvn", "mvp");
    /** Instructions whose low byte of A is the same in 8 bits (given the carry), as in {@link ByteStretches}. */
    private static final Set<String> SAME_LOW_BYTE = Set.of("lda", "and", "ora", "eor", "adc", "sbc", "asl", "inc", "dec",
            "txa", "tya");
    /** Width-independent instructions that read all of C (B and A). */
    private static final Set<String> READ_C = Set.of("tax", "tay", "xba", "tcd", "tcs", "mvn", "mvp");
    /** Instructions that read A, in its width. */
    private static final Set<String> READ_A = Set.of("sta", "adc", "sbc", "and", "ora", "eor", "cmp", "bit", "pha", "tsb", "trb");
    /** Instructions that load A in full when they run with 16 bits (the high byte is theirs then). */
    private static final Set<String> LOAD_A = Set.of("lda", "txa", "tya", "pla");

    private Widths() {}

    /** The kind of an instruction here. */
    private enum Kind { SWITCH, ANY, FIXED, FREE }

    /**
     * Places the width switches of f again; returns how many instructions now run in 8 bits that
     * ran in 16 (0 where the code stays as it was). {@code weights}: how often each block runs
     * (1 where it has none); {@code resultInA}: the function returns a value in A.
     */
    public static int run(LFunction f, boolean resultInA, Map<LBlock, Double> weights, boolean fastRom) {
        List<LBlock> blocks = f.blocks();
        if (blocks.isEmpty()) {
            return 0;
        }
        Map<LBlock, Integer> entry = entryWidths(f);
        if (entry == null) {
            return 0;
        }
        // the instructions, their kinds and the width they run in
        Map<LInstr, Kind> kind = new IdentityHashMap<>();
        Map<LInstr, Boolean> wide = new IdentityHashMap<>();
        Map<LBlock, Integer> flagsOut = RedundantLoads.flagsLiveOut(f);
        for (LBlock b : blocks) {
            Integer start = entry.get(b);
            boolean m16 = start == null || start == 16;
            List<LInstr> code = b.instrs();
            int[] liveAfter = new int[code.size()];
            int live = flagsOut.getOrDefault(b, ALL_FLAGS);
            for (int k = code.size() - 1; k >= 0; k--) {
                liveAfter[k] = live;
                live = (live & ~RedundantLoads.writes(code.get(k))) | RedundantLoads.reads(code.get(k));
            }
            for (int k = 0; k < code.size(); k++) {
                LInstr in = code.get(k);
                String m = in.mnemonic();
                if (!in.x16() || m.equals("plp") || m.equals("rti") || m.equals("rep") || m.equals("sep")) {
                    if (!isWidth(in)) {
                        return 0;   // another processor state than dampf's (or a switch of it written otherwise)
                    }
                }
                if (isWidth(in)) {
                    kind.put(in, Kind.SWITCH);
                    m16 = m.equals("rep");
                    continue;
                }
                if (start == null) {
                    m16 = in.m16();   // a block no way reaches: its code stays as it is
                }
                wide.put(in, m16);
                if (ANY_WIDTH.contains(m) || in.isConditionalBranch()) {
                    kind.put(in, Kind.ANY);
                } else if (start != null && m16 != in.m16() && LVerifier.dependsOnWidth(in)) {
                    return 0;   // written for another width than it runs in
                } else {
                    kind.put(in, start != null && m16 && narrows(in, liveAfter[k]) ? Kind.FREE : Kind.FIXED);
                }
            }
        }
        // the graph: a node per instruction that depends on the width, and per block start and end
        Graph g = new Graph();
        Map<LInstr, Integer> node = new IdentityHashMap<>();
        Map<LBlock, Integer> in = new IdentityHashMap<>();
        Map<LBlock, Integer> out = new IdentityHashMap<>();
        long switchCost = Math.round(new LInstr("rep", "#$20", null, true).master(fastRom).worst() * SCALE);
        for (LBlock b : blocks) {
            long w = weight(weights, b);
            in.put(b, g.add());
            out.put(b, g.add());
            int prev = in.get(b);
            // a switch in a block of a jump alone (the long form of a branch) keeps BranchRelax
            // from making it one branch: the jump's cycles on top
            List<LInstr> all = b.instrs();
            long here = all.size() == 1 && all.getFirst().isJump() ? switchCost + Math.round(all.getFirst().master(fastRom).worst() * SCALE)
                    : switchCost;
            for (LInstr i : b.instrs()) {
                Kind k = kind.get(i);
                if (k == Kind.SWITCH || k == Kind.ANY) {
                    continue;
                }
                int n = g.add();
                node.put(i, n);
                boolean m16 = wide.get(i);
                if (k == Kind.FIXED) {
                    g.fix(n, m16);
                } else {
                    long c16 = cost(i, true, fastRom);
                    long c8 = cost(i, false, fastRom);
                    g.unary(n, sat(w, c16), sat(w, c8) + 1);   // + 1: no change without a gain
                }
                g.pair(prev, n, sat(w, switchCost));
                prev = n;
            }
            g.pair(prev, out.get(b), sat(w, here));
        }
        g.fix(in.get(blocks.getFirst()), true);
        for (LBlock b : blocks) {
            for (LBlock s : b.successors()) {
                g.pair(out.get(b), in.get(s), Graph.INF);
            }
        }
        // the high byte: an instruction in 8 bits keeps every reader of the high byte after it in 8 bits too
        for (LBlock b : blocks) {
            List<LInstr> code = b.instrs();
            for (int k = 0; k < code.size(); k++) {
                LInstr i = code.get(k);
                if (kind.get(i) == Kind.FREE) {
                    highByteReaders(f, b, k, i, kind, wide, node, g, resultInA);
                }
            }
        }
        boolean[] wideNow = g.cut();
        // what it costs as it was, and as the cut has it
        long before = 0;
        long after = 0;
        int narrowed = 0;
        for (LBlock b : blocks) {
            long w = weight(weights, b);
            for (LInstr i : b.instrs()) {
                Kind k = kind.get(i);
                before = add(before, sat(w, k == Kind.SWITCH ? switchCost : cost(i, i.m16(), fastRom)));
                if (k == Kind.FREE && !wideNow[node.get(i)]) {
                    narrowed++;
                }
            }
        }
        if (narrowed == 0 && !fewerSwitches(blocks, kind, node, in, out, wideNow)) {
            return 0;
        }
        List<LItem> old = f.items();
        List<LBlock> oldBlocks = new ArrayList<>(blocks);
        List<LItem> items = new ArrayList<>();
        for (LBlock b : blocks) {
            items.addAll(rewrite(b, kind, node, wideNow[in.get(b)], wideNow[out.get(b)], wideNow));
        }
        f.rebuild(items);
        if (f.blocks().size() != oldBlocks.size()) {
            f.rebuild(old);
            return 0;
        }
        for (int n = 0; n < oldBlocks.size(); n++) {
            long w = weight(weights, oldBlocks.get(n));
            for (LInstr i : f.blocks().get(n).instrs()) {
                after = add(after, sat(w, isWidth(i) ? switchCost : cost(i, i.m16(), fastRom)));
            }
        }
        if (after >= before || !consistent(f)) {
            f.rebuild(old);
            return 0;
        }
        return narrowed;
    }

    /** All four flags. */
    private static final int ALL_FLAGS = RedundantLoads.N | RedundantLoads.Z | RedundantLoads.C | RedundantLoads.V;
    /** Costs are master cycles times the weight times this (so that the + 1 of a change is less than any cycle). */
    private static final long SCALE = 64;

    private static long cost(LInstr i, boolean m16, boolean fastRom) {
        LInstr as = m16 == i.m16() ? i : new LInstr(i.mnemonic(), i.op(), null, m16, i.x16(), false);
        return Math.round(as.master(fastRom).worst() * SCALE);
    }

    /** A block's weight in 1/256 (1 where it has none), at least 1. */
    private static long weight(Map<LBlock, Double> weights, LBlock b) {
        return Math.max(1, Math.round(weights.getOrDefault(b, 1.0) * 256));
    }

    private static long sat(long w, long c) {
        return c >= Graph.INF / Math.max(1, w) ? Graph.INF : w * c;
    }

    private static long add(long a, long b) {
        return a >= Graph.INF - b ? Graph.INF : a + b;
    }

    /** Whether the cut takes out switches without narrowing anything (else nothing changes). */
    private static boolean fewerSwitches(List<LBlock> blocks, Map<LInstr, Kind> kind, Map<LInstr, Integer> node,
                                         Map<LBlock, Integer> in, Map<LBlock, Integer> out, boolean[] wide) {
        int before = 0;
        int after = 0;
        for (LBlock b : blocks) {
            boolean cur = wide[in.get(b)];
            for (LInstr i : b.instrs()) {
                Kind k = kind.get(i);
                if (k == Kind.SWITCH) {
                    before++;
                } else if (k != Kind.ANY) {
                    if (wide[node.get(i)] != cur) {
                        after++;
                    }
                    cur = wide[node.get(i)];
                }
            }
            if (cur != wide[out.get(b)]) {
                after++;
            }
        }
        return after < before;
    }

    /**
     * Whether the 16-bit instruction gives the same low byte in 8 bits and nothing reads the flags
     * it sets ({@code liveAfter}): one of {@link #SAME_LOW_BYTE} on A, not a hardware register (a
     * 16-bit read touches two), an immediate known.
     */
    private static boolean narrows(LInstr in, int liveAfter) {
        String m = in.mnemonic();
        LOperand op = in.op();
        if (!SAME_LOW_BYTE.contains(m)) {
            return false;
        }
        if ((m.equals("asl") || m.equals("inc") || m.equals("dec")) && !(op instanceof LOperand.Acc)) {
            return false;
        }
        if (op instanceof LOperand.Mem mem && mem.base().startsWith("$")) {
            return false;
        }
        if (op instanceof LOperand.Imm imm && imm.value() == null) {
            return false;   // an immediate written as an expression: its low byte is not known here
        }
        return (RedundantLoads.writes(in) & liveAfter) == 0;
    }

    /**
     * The readers of the high byte A has after the 8-bit-capable instruction i (at k in b), on
     * every way until A is loaded in full: a reader in 16 bits keeps i in 16 bits (an edge of the
     * graph), one that reads all of C whatever the width (a transfer, a call, a return of A) keeps
     * it so for good.
     */
    private static void highByteReaders(LFunction f, LBlock b, int k, LInstr i, Map<LInstr, Kind> kind,
                                        Map<LInstr, Boolean> wide, Map<LInstr, Integer> node, Graph g, boolean resultInA) {
        int n = node.get(i);
        ArrayDeque<LBlock> work = new ArrayDeque<>();
        Map<LBlock, Boolean> seen = new IdentityHashMap<>();
        List<LInstr> first = b.instrs().subList(k + 1, b.instrs().size());
        if (!scan(first, kind, wide, node, g, n, resultInA)) {
            return;
        }
        work.addAll(b.successors());
        while (!work.isEmpty()) {
            LBlock s = work.removeFirst();
            if (seen.put(s, true) != null) {
                continue;
            }
            if (scan(s.instrs(), kind, wide, node, g, n, resultInA)) {
                work.addAll(s.successors());
            }
        }
    }

    /** One stretch of {@link #highByteReaders}; false where A is loaded in full in it (or i stays 16 bit anyway). */
    private static boolean scan(List<LInstr> code, Map<LInstr, Kind> kind, Map<LInstr, Boolean> wide, Map<LInstr, Integer> node,
                                Graph g, int n, boolean resultInA) {
        for (LInstr j : code) {
            String m = j.mnemonic();
            Kind k = kind.get(j);
            if (k == Kind.SWITCH) {
                continue;
            }
            if (READ_C.contains(m) || j.isCall() || j.isReturn() && resultInA) {
                g.fix(n, true);
                return false;
            }
            if (j.isReturn() || m.equals("tdc") || m.equals("tsc")) {
                return false;
            }
            if (k == Kind.ANY) {
                continue;
            }
            boolean readsA = READ_A.contains(m) || (m.equals("asl") || m.equals("lsr") || m.equals("rol") || m.equals("ror")
                    || m.equals("inc") || m.equals("dec")) && j.op() instanceof LOperand.Acc;
            if (readsA && wide.get(j)) {
                if (k == Kind.FIXED) {
                    g.fix(n, true);
                    return false;
                }
                g.implies(node.get(j), n);   // j in 16 bits reads the high byte: then i is 16 bit too
            }
            if (LOAD_A.contains(m) && wide.get(j)) {
                return false;   // loaded in full (or, where j itself goes to 8 bits, j's own readers keep it)
            }
        }
        return true;
    }

    /** The code of b with the widths of the cut: the old switches out, new ones where the width changes. */
    private static List<LItem> rewrite(LBlock b, Map<LInstr, Kind> kind, Map<LInstr, Integer> node, boolean startWide,
                                       boolean endWide, boolean[] wide) {
        List<LItem> out = new ArrayList<>();
        boolean cur = startWide;
        for (LItem it : b.items()) {
            if (!(it instanceof LInstr i)) {
                out.add(it);
                continue;
            }
            Kind k = kind.get(i);
            if (k == Kind.SWITCH) {
                continue;
            }
            if (k == Kind.ANY) {
                if (i.endsBlock() && cur != endWide && !i.isReturn()) {
                    out.add(switchTo(endWide));
                    cur = endWide;
                }
                out.add(i.m16() == cur ? i : new LInstr(i.mnemonic(), i.op(), i.comment(), cur, i.x16(), i.relaxable()));
                continue;
            }
            boolean w = wide[node.get(i)];
            if (w != cur) {
                out.add(switchTo(w));
                cur = w;
            }
            if (w == i.m16()) {
                out.add(i);
            } else if (i.op() instanceof LOperand.Imm imm) {
                out.add(directive(LDirective.Kind.A8, ".a8"));
                out.add(new LInstr(i.mnemonic(), String.format("#$%02X", imm.value() & 0xFF), i.comment(), false, i.x16(), i.relaxable()));
                out.add(directive(LDirective.Kind.A16, ".a16"));
            } else {
                out.add(new LInstr(i.mnemonic(), i.op(), i.comment(), false, i.x16(), i.relaxable()));
            }
        }
        LInstr last = b.last();
        if (cur != endWide && (last == null || last.fallsThrough() && !last.endsBlock())) {
            out.add(switchTo(endWide));
        }
        return out;
    }

    private static LInstr switchTo(boolean m16) {
        return m16 ? new LInstr("rep", "#$20", null, false, true, false) : new LInstr("sep", "#$20", null, true, true, false);
    }

    private static LDirective directive(LDirective.Kind kind, String name) {
        return new LDirective(kind, null, List.of(), null, "        " + name);
    }

    /**
     * The width at the start of each reachable block (16 or 8), followed from the function's start
     * along the edges; null where two ways in disagree.
     */
    static Map<LBlock, Integer> entryWidths(LFunction f) {
        Map<LBlock, Integer> entry = new IdentityHashMap<>();
        ArrayDeque<LBlock> work = new ArrayDeque<>();
        entry.put(f.blocks().getFirst(), 16);
        work.add(f.blocks().getFirst());
        while (!work.isEmpty()) {
            LBlock b = work.removeFirst();
            int width = entry.get(b);
            for (LInstr i : b.instrs()) {
                if (isWidth(i)) {
                    width = i.mnemonic().equals("rep") ? 16 : 8;
                }
            }
            for (LBlock s : b.successors()) {
                Integer was = entry.get(s);
                if (was == null) {
                    entry.put(s, width);
                    work.add(s);
                } else if (was != width) {
                    return null;
                }
            }
        }
        return entry;
    }

    /**
     * Whether every instruction whose width matters runs in the width it was written for: along
     * the edges, and in the order of the text in a block no way reaches (as {@link LParser} reads
     * them back).
     */
    private static boolean consistent(LFunction f) {
        Map<LBlock, Integer> entry = entryWidths(f);
        if (entry == null) {
            return false;
        }
        boolean text = true;
        for (LBlock b : f.blocks()) {
            Integer start = entry.get(b);
            boolean flow = start == null ? text : start == 16;
            for (LInstr i : b.instrs()) {
                if (isWidth(i)) {
                    flow = i.mnemonic().equals("rep");
                    text = flow;   // LParser's width in the order of the text
                } else if (LVerifier.dependsOnWidth(i) && i.m16() != flow) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isWidth(LInstr i) {
        return (i.mnemonic().equals("rep") || i.mnemonic().equals("sep")) && i.op() instanceof LOperand.Imm k
                && k.expr().equals("$20");
    }

    /** A graph for a minimum s-t cut (Dinic's algorithm): the source's side is 16 bits, the sink's 8. */
    static final class Graph {
        static final long INF = Long.MAX_VALUE / 8;
        private int nodes = 2;   // 0 the source, 1 the sink
        private int[] head = new int[16];
        private int[] next = new int[64];
        private int[] to = new int[64];
        private long[] cap = new long[64];
        private int edges;

        Graph() {
            Arrays.fill(head, -1);
        }

        int add() {
            if (nodes == head.length) {
                head = Arrays.copyOf(head, nodes * 2);
                Arrays.fill(head, nodes, head.length, -1);
            }
            return nodes++;
        }

        private void edge(int u, int v, long c) {
            if (edges + 2 > to.length) {
                next = Arrays.copyOf(next, to.length * 2);
                cap = Arrays.copyOf(cap, to.length * 2);
                to = Arrays.copyOf(to, to.length * 2);
            }
            to[edges] = v;
            cap[edges] = c;
            next[edges] = head[u];
            head[u] = edges++;
            to[edges] = u;
            cap[edges] = 0;
            next[edges] = head[v];
            head[v] = edges++;
        }

        /** n costs c16 with 16 bits and c8 with 8. */
        void unary(int n, long c16, long c8) {
            long base = Math.min(c16, c8);
            if (c8 > base) {
                edge(0, n, c8 - base);   // cut where n is on the sink's side (8 bits)
            }
            if (c16 > base) {
                edge(n, 1, c16 - base);
            }
        }

        void fix(int n, boolean m16) {
            if (m16) {
                edge(0, n, INF);
            } else {
                edge(n, 1, INF);
            }
        }

        /** u and v cost c where their widths differ. */
        void pair(int u, int v, long c) {
            edge(u, v, c);
            edge(v, u, c);
        }

        /** v in 16 bits makes u 16 bits (u on the sink's side and v on the source's is not allowed). */
        void implies(int v, int u) {
            edge(v, u, INF);
        }

        /** The minimum cut: true for the nodes on the source's side (16 bits). */
        boolean[] cut() {
            int[] level = new int[nodes];
            int[] it = new int[nodes];
            while (bfs(level)) {
                for (int i = 0; i < nodes; i++) {
                    it[i] = head[i];
                }
                while (dfs(0, INF, level, it) > 0) {
                    // augment until blocked
                }
            }
            boolean[] side = new boolean[nodes];
            ArrayDeque<Integer> work = new ArrayDeque<>();
            work.add(0);
            side[0] = true;
            while (!work.isEmpty()) {
                int u = work.removeFirst();
                for (int e = head[u]; e >= 0; e = next[e]) {
                    if (cap[e] > 0 && !side[to[e]]) {
                        side[to[e]] = true;
                        work.add(to[e]);
                    }
                }
            }
            return side;
        }

        private boolean bfs(int[] level) {
            Arrays.fill(level, -1);
            ArrayDeque<Integer> work = new ArrayDeque<>();
            level[0] = 0;
            work.add(0);
            while (!work.isEmpty()) {
                int u = work.removeFirst();
                for (int e = head[u]; e >= 0; e = next[e]) {
                    if (cap[e] > 0 && level[to[e]] < 0) {
                        level[to[e]] = level[u] + 1;
                        work.add(to[e]);
                    }
                }
            }
            return level[1] >= 0;
        }

        private long dfs(int u, long f, int[] level, int[] it) {
            if (u == 1) {
                return f;
            }
            for (; it[u] >= 0; it[u] = next[it[u]]) {
                int e = it[u];
                int v = to[e];
                if (cap[e] > 0 && level[v] == level[u] + 1) {
                    long d = dfs(v, Math.min(f, cap[e]), level, it);
                    if (d > 0) {
                        cap[e] -= d;
                        cap[e ^ 1] += d;
                        return d;
                    }
                }
            }
            return 0;
        }
    }
}
