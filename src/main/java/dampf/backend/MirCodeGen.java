package dampf.backend;

import dampf.frontend.CompileError;
import dampf.frontend.SourcePos;
import dampf.lir.BranchRelax;
import dampf.lir.ByteStretches;
import dampf.lir.DeadStores;
import dampf.lir.IndexInY;
import dampf.lir.RedundantLoads;
import dampf.lir.KnownCarry;
import dampf.lir.LFunction;
import dampf.lir.Peephole;
import dampf.mir.Block;
import dampf.mir.Instr;
import dampf.mir.MFunction;
import dampf.mir.MModule;
import dampf.mir.MType;
import dampf.mir.Printer;
import dampf.mir.Ranges;
import dampf.mir.Value;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Generates 65816 code from MIR, one instruction at a time. Like {@link NaiveCodeGen} it aims to
 * be obviously correct, not fast: it is the back end the MIR passes are measured with, and it
 * gets better as they do.
 *
 * <p><b>Machine state</b> as in the naive generator: A/X/Y 16 bit, DBR $7E, JSL/RTL, a result in
 * A (and X for the high word of an i32 or a pointer).
 *
 * <p><b>Values.</b> Every MIR value (parameter, instruction result) has a fixed place in RAM: 2
 * bytes for i1, i8 and i16, 4 bytes for i32 and pointers. An i1 or i8 is kept zero-extended to
 * 16 bits, so a 16-bit load gives its value. An instruction loads its operands from there and
 * stores its result there, except where {@link SelectionPlan} decided otherwise (a result handed
 * on in A to its only reader, a loop counter kept in X, an index in Y); the LIR passes then remove
 * loads of what a register holds and stores nobody reads.
 *
 * <p><b>Frames</b> form a compiled stack. dampf has no recursion, so the call graph is acyclic,
 * and a function's frame can start where the frames of all the functions it calls end: frames
 * that may be live at the same time never overlap, and the others share memory. All frames lie
 * in low RAM from $0020 ({@code dampf_frame}); the leaves of the call graph, which are small and
 * often hot, start there, in the direct page, where an access is a cycle cheaper and a pointer can
 * be used as {@code [place],y} without copying it. A caller stores the arguments straight into
 * the callee's parameters.
 *
 * <p><b>φs</b> get a second place, "incoming": every predecessor stores its operand there right
 * before its jump, and the block copies it into the φ's own place on entry. That is correct for
 * any set of φs (also when they swap values), with no analysis.
 */
public final class MirCodeGen {
    /** What selection shares, and the selection of each kind of instruction. */
    private final Selection sel = new Selection(this);

    /** The frames must fit the low RAM from FRAME_START up to the stack at $1E00. */
    static final int MAX_FRAME = 0x1E00 - 0x20;

    final MModule module;
    final String entry;
    final java.util.function.Function<String, String> debugName;
    final Map<MFunction, Integer> base = new HashMap<>();
    final Map<MFunction, Integer> frameSize = new HashMap<>();
    final Map<String, List<MFunction>> callees = new HashMap<>();
    int frameTotal;

    final List<AsmWriter> banked = new ArrayList<>();
    int entryBlock = -1;
    final Map<String, List<String>> sourceLines = new HashMap<>();
    final Map<String, Path> sourceFiles = new LinkedHashMap<>();

    /**
     * @param entry     the label of the function the runtime calls
     * @param debugName maps a source file path to the name written into the debug information
     */
    public MirCodeGen(MModule module, String entry, java.util.function.Function<String, String> debugName) {
        this(module, entry, debugName, null);
    }

    /** {@code blockCounts}: see {@link dampf.Compiler.Options}; null without a profile. */
    public MirCodeGen(MModule module, String entry, java.util.function.Function<String, String> debugName,
                      java.util.function.BiFunction<String, String, Long> blockCounts) {
        this.blockCounts = blockCounts;
        this.module = module;
        this.entry = entry;
        this.debugName = debugName;
        for (MModule.Global g : module.globals()) {
            if (g.readOnly()) {
                sel.rom.add(g.symbol());
            }
        }
    }

    /** At most this many segments per assembly module: ca65 allows about 256 per object file. */
    private static final int SEGMENTS_PER_MODULE = 200;

    public List<String> generate() throws CompileError {
        MFunction entryFunction = module.function(entry);
        if (entryFunction == null || !entryFunction.params().isEmpty()) {
            throw new IllegalStateException("the entry function " + entry + " must exist and have no parameters");
        }
        for (MModule.Global g : module.globals()) {
            if (g.size() > 0x8000) {
                throw new CompileError(entryFunction.pos(), "'" + g.symbol() + "' is " + g.size()
                        + " bytes; objects can be at most 32 KB (docs/memory-map.md)");
            }
        }
        sel.layout();
        for (MFunction f : module.functions()) {
            // selection writes the function once, assuming nothing about the registers where a
            // block starts and every jump and branch in its long form
            AsmWriter code = new AsmWriter();
            List<Block> order = function(f, code);
            // loads of what a register holds on every way in, then stores no path reads; then the
            // code is shorter, so more branches may reach
            new RedundantLoads(Selection.FRAME).run(code.function());
            new IndexInY(Selection.FRAME).run(code.function());
            new Peephole(Selection.FRAME, Selection.FRAME_START).run(code.function());
            ByteStretches.run(code.function());
            KnownCarry.run(code.function());
            new DeadStores(Selection.FRAME, ownValueBytes(f)).run(code.function());
            BranchRelax.run(code.function());
            Map<String, String> renamed = dampf.lir.LocalLabels.renumber(code.function(), "@L");
            Map<String, Integer> bounds = new HashMap<>();
            code.loopBounds().forEach((label, rounds) -> bounds.put(renamed.getOrDefault(label, label), rounds));
            written.put(f, new Written(code.function(), bounds, order, sel.layout.ranges));
            if (code.unknownInstructions() > 0) {
                throw new IllegalStateException("the cycle table does not know an instruction in " + f.name());
            }
            if (f == entryFunction) {
                entryBlock = banked.size();
            }
            banked.add(code);
        }
        AsmWriter ram = new AsmWriter();
        globals(ram);

        List<String> modules = new ArrayList<>();
        int chunks = Math.max(1, (banked.size() + SEGMENTS_PER_MODULE - 1) / SEGMENTS_PER_MODULE);
        for (int m = 0; m < chunks; m++) {
            AsmWriter w = new AsmWriter();
            prelude(w, m, chunks);
            if (m == 0) {
                w.append(ram);
                w.blank();   // (as when the text was copied in)
            }
            for (int i = m * SEGMENTS_PER_MODULE; i < Math.min(banked.size(), (m + 1) * SEGMENTS_PER_MODULE); i++) {
                if (i == entryBlock) {
                    w.blank();
                    w.raw("        .export dampf_entry");
                    w.raw("dampf_entry = " + entry + "             ; the runtime calls this after start-up");
                }
                w.append(banked.get(i));
                w.blank();
            }
            dampf.lir.LVerifier.check(w.unit());
            modules.add(w.toString());
        }
        return modules;
    }

    /**
     * The frame offsets of the bytes of f's value places: its parameters, values and φ incoming
     * places (not its slots, whose address may be taken). Only f reads them, while it runs.
     */
    java.util.BitSet ownValueBytes(MFunction f) {
        FrameLayout l = layouts.get(f);
        int b = base.get(f);
        java.util.BitSet own = new java.util.BitSet();
        l.offset.forEach((k, v) -> {
            if (k instanceof Value.Param || k instanceof Instr) {
                own.set(b + v, b + v + 2 * Selection.words(((Value) k).type()));
            }
        });
        l.incoming.forEach((p, v) -> own.set(b + v, b + v + 2 * Selection.words(p.type())));
        return own;
    }

    void prelude(AsmWriter w, int module, int modules) {
        w.raw("; Generated by dampf from MIR" + (modules > 1 ? " (module " + (module + 1) + " of " + modules + ")" : "") + ".");
        w.raw("; A/X/Y are 16 bit (A goes to 8 bit where SEP #$20 says so), DBR = $7E; every value has a fixed place in dampf_frame.");
        w.blank();
        w.raw("        .p816");
        w.raw("        .smart  off");
        w.raw("        .a16");
        w.raw("        .i16");
        w.raw("        .importzp dampf_regs, dampf_ptr");
        w.raw("        .import dampf_mul16, dampf_mul32, dampf_udiv16, dampf_udiv32, dampf_sdiv32");
        if (modules > 1) {
            List<String> all = new ArrayList<>();
            this.module.functions().forEach(f -> all.add(f.name()));
            this.module.globals().forEach(g -> all.add(g.symbol()));
            for (int i = 0; i < all.size(); i += 8) {
                w.raw("        .global " + String.join(", ", all.subList(i, Math.min(all.size(), i + 8))));
            }
        }
        for (Map.Entry<String, Path> e : sourceFiles.entrySet()) {
            w.raw(fileDirective(e.getKey(), e.getValue()));
        }
        w.blank();
        for (int r = 0; r <= 14; r += 2) {
            w.raw("R" + r + (r < 10 ? "  " : " ") + "= dampf_regs+" + r);
        }
        w.raw(Selection.FRAME + " = $" + Selection.hex(Selection.FRAME_START, 4) + "            ; the frames (compiled stack), $0020-$1DFF; below $0100 in the direct page");
    }

    String fileDirective(String file, Path path) {
        long size = 0;
        long mtime = 0;
        try {
            size = Files.size(path);
            mtime = Files.getLastModifiedTime(path).toMillis() / 1000;
        } catch (IOException e) {
            // size and time only help a debugger notice a changed file
        }
        return "        .dbg    file, \"" + debugName.apply(file) + "\", " + size + ", " + mtime;
    }

    /** A function's code as written (second pass), for the timing analysis. */
    record Written(LFunction code, Map<String, Integer> loopBounds, List<Block> order, Ranges ranges) {}

    final Map<MFunction, Written> written = new HashMap<>();
    final Map<String, Timing.Bound> bounds = new HashMap<>();

    /** How often each block ran, (function, block) → count or null, from a profile; null without one. */
    final java.util.function.BiFunction<String, String, Long> blockCounts;

    /**
     * Best and worst case in CPU cycles per function (by label), after generate(); see {@link Timing}.
     * -1 for a worst case without a bound, and for the best case of a function that never returns.
     */
    public Map<String, long[]> timing() {
        Map<String, long[]> out = new TreeMap<>();
        for (MFunction f : module.functions()) {
            Timing.Bound b = bound(f.name(), new HashSet<>());
            out.put(f.name(), new long[] {b.best() < Timing.UNBOUNDED ? b.best() : -1, b.bounded() ? b.worst() : -1});
        }
        Timing.RUNTIME.forEach((name, b) -> out.put(name, new long[] {b.best(), b.worst()}));
        return out;
    }

    /**
     * Why a function has no worst case (by label, only for those): the first loop in it without a
     * bound ("loop at file:line"), else the first call of a function without one ("calls f").
     */
    public Map<String, String> unboundedWhy() {
        Map<String, String> out = new TreeMap<>();
        for (MFunction f : module.functions()) {
            if (bound(f.name(), new HashSet<>()).bounded()) {
                continue;
            }
            Ranges r = written.get(f).ranges();
            SourcePos first = null;
            for (dampf.mir.Loops.Loop loop : dampf.mir.Loops.find(f)) {
                if (Timing.headRuns(loop, r) >= Timing.UNBOUNDED) {
                    SourcePos at = loop.head().terminator().pos();
                    if (at != null && (first == null || at.line() < first.line())) {
                        first = at;
                    }
                }
            }
            String why = first == null ? null : "loop at " + first.file().substring(first.file().lastIndexOf('/') + 1) + ":" + first.line();
            for (Block b : f.blocks()) {
                for (Instr i : b.instrs()) {
                    if (why == null && i instanceof Instr.Call c && !bound(c.callee, new HashSet<>()).bounded()) {
                        why = "calls " + c.callee;
                    }
                }
            }
            out.put(f.name(), why == null ? "a loop without a bound" : why);
        }
        return out;
    }

    Timing.Bound bound(String name, Set<String> path) {
        Timing.Bound known = bounds.get(name);
        if (known != null) {
            return known;
        }
        Timing.Bound runtime = Timing.RUNTIME.get(name);
        if (runtime != null) {
            return runtime;
        }
        MFunction f = module.function(name);
        if (f == null || !path.add(name)) {
            return new Timing.Bound(0, Timing.UNBOUNDED);
        }
        Written w = written.get(f);
        Timing.Bound b = Timing.of(f, w.code(), w.loopBounds(), w.order(), w.ranges(), callee -> bound(callee, path));
        path.remove(name);
        bounds.put(name, b);
        return b;
    }
    final Map<MFunction, FrameLayout> layouts = new HashMap<>();

    /**
     * A value used as an argument of a call later in its block, and otherwise only between its
     * definition and the call, with no other call on the way, is computed straight into the
     * callee's parameter place: the call need not copy it
     * (about five copies per call in the game's player code). The callee's frame never overlaps
     * the caller's (frames on a call path are disjoint), and nothing but this call writes the
     * place before it. Its own place in the caller's frame stays unused.
     */
    void argumentsInPlace() {
        for (MFunction f : module.functions()) {
            Map<Instr, Integer> uses = new HashMap<>();
            for (Block b : f.blocks()) {
                for (Instr i : b.instrs()) {
                    for (Value v : i.operands()) {
                        if (v instanceof Instr d) {
                            uses.merge(d, 1, Integer::sum);
                        }
                    }
                }
            }
            for (Block b : f.blocks()) {
                List<Instr> is = b.instrs();
                for (int n = 0; n < is.size(); n++) {
                    if (!(is.get(n) instanceof Instr.Call c) || module.function(c.callee) == null) {
                        continue;
                    }
                    MFunction callee = module.function(c.callee);
                    for (int k = 0; k < c.operands().size(); k++) {
                        if (!(c.op(k) instanceof Instr v) || v instanceof Instr.Phi || v.block() != b
                                || !sel.place.containsKey(v) || c.operands().indexOf(v) != k
                                || c.operands().lastIndexOf(v) != k) {
                            continue;
                        }
                        // its other uses, if any, lie between it and the call: the callee may
                        // change its parameter, so nothing may read the place after the call
                        int before = 0;
                        boolean callBetween = false;
                        for (int j = is.indexOf(v) + 1; j < n; j++) {
                            callBetween |= is.get(j) instanceof Instr.Call;
                            if (is.get(j).operands().contains(v)) {
                                before++;
                            }
                        }
                        if (uses.getOrDefault(v, 0) != 1 + before) {
                            continue;   // used after the call, or in another block
                        }
                        Value.Param p = callee.params().get(k);
                        if (!callBetween && sel.place.containsKey(p) && Selection.words(p.type()) == Selection.words(v.type())) {
                            sel.place.put(v, sel.place.get(p));
                        }
                    }
                }
            }
        }
    }

    /**
     * Where f's frame starts: after the frames of all the functions it calls (and so after
     * everything they call). Leaves start at 0, where the direct page is.
     */
    int base(MFunction f, Set<MFunction> path) {
        Integer known = base.get(f);
        if (known != null) {
            return known;
        }
        if (!path.add(f)) {
            throw new IllegalStateException("recursion through " + f.name());
        }
        int b = 0;
        for (MFunction c : callees.getOrDefault(f.name(), List.of())) {
            b = Math.max(b, base(c, path) + frameSize.get(c));
        }
        path.remove(f);
        base.put(f, b);
        return b;
    }

    void globals(AsmWriter ram) {
        for (MModule.Global g : module.globals()) {
            boolean zero = g.init().stream().allMatch(d -> d.symbol() == null && d.value() == 0);
            AsmWriter w = g.readOnly() ? new AsmWriter() : ram;
            String segment = g.readOnly() ? NaiveCodeGen.romSegment(g.symbol()) : zero ? "BSS" : "DATA";
            w.blank();
            w.raw("        .segment \"" + segment + "\"");
            if (segment.equals("BSS")) {
                w.raw(Selection.pad(g.symbol() + ":", 28) + ".res    " + Math.max(1, g.size()));
                continue;
            }
            w.raw(g.symbol() + ":");
            data(w, g);
            if (g.readOnly()) {
                banked.add(w);
            }
        }
    }

    void data(AsmWriter w, MModule.Global g) {
        TreeMap<Integer, MModule.Datum> addresses = new TreeMap<>();
        byte[] bytes = new byte[g.size()];
        for (MModule.Datum d : g.init()) {
            if (d.symbol() != null) {
                addresses.put(d.offset(), d);
            } else {
                for (int b = 0; b < d.size(); b++) {
                    bytes[d.offset() + b] = (byte) (d.value() >> (8 * b));
                }
            }
        }
        StringBuilder line = new StringBuilder();
        int count = 0;
        for (int o = 0; o < g.size(); o++) {
            MModule.Datum a = addresses.get(o);
            if (a != null) {
                flush(w, line);
                count = 0;
                String target = a.symbol() + (a.value() == 0 ? "" : (a.value() > 0 ? "+" : "") + a.value());
                if (sel.rom.contains(a.symbol())) {
                    w.raw("        .word   .loword(" + target + ")");
                    w.raw("        .byte   ^(" + target + "), 0");
                } else {
                    w.raw("        .word   " + target);
                    w.raw("        .byte   $7E, 0                  ; bank $7E: RAM");
                }
                o += 3;
                continue;
            }
            line.append(count == 0 ? "" : ",").append('$').append(Selection.hex(bytes[o] & 0xFF, 2));
            if (++count == 16) {
                flush(w, line);
                count = 0;
            }
        }
        flush(w, line);
    }

    static void flush(AsmWriter w, StringBuilder line) {
        if (!line.isEmpty()) {
            w.raw("        .byte   " + line);
            line.setLength(0);
        }
    }

    /** Writes f's code; returns the order of its blocks. */
    List<Block> function(MFunction f, AsmWriter code) {
        sel.out = code;
        SourcePos lastPos = null;
        sel.lastTransfer = false;
        sel.aHolds.clear();
        sel.yHolds = null;
        sel.xHolds = null;
        sel.ptrP = null;
        sel.ptrQ = null;
        sel.m8 = false;
        f.number();
        sel.out.blank();
        sel.out.raw("; ---- " + f.name() + "    (" + f.pos() + ")");
        sel.out.raw("        .segment \"" + NaiveCodeGen.codeSegment(f.name()) + "\"");
        sel.out.raw(".proc " + f.name());
        for (Value.Param p : f.params()) {
            sel.out.note("parameter " + p.type() + " " + p + " at " + Selection.at(sel.place.get(p)));
        }
        sel.layout = layouts.get(f);
        sel.ranges = sel.layout.ranges;
        sel.plan.findMasks(f);
        sel.ranges = sel.layout.ranges;
        sel.forward.clear();
        sel.fused.clear();
        sel.fused.addAll(sel.layout.fused);
        sel.folded.clear();
        sel.folded.addAll(sel.layout.folded);
        sel.plan.findXCounters(f);
        sel.plan.findYIndexes(f);
        sel.plan.findForwards(f);
        sel.forward.removeAll(sel.xSteps.keySet());
        List<Block> blocks = layoutOrder(f, blockCounts == null ? null : blockName -> blockCounts.apply(f.name(), blockName));
        for (int k = 0; k < blocks.size(); k++) {
            Block b = blocks.get(k);
            Block next = k + 1 < blocks.size() ? blocks.get(k + 1) : null;
            sel.place(b.name());
            sel.xTaken = sel.xBlocks.get(b);
            for (Instr i : b.instrs()) {
                if (i instanceof Instr.Phi p) {
                    sel.out.note(Printer.instr(p));
                    if (sel.direct.contains(b) || sel.xResident.containsKey(p)) {
                        continue;   // set by the predecessors (or kept in X)
                    }
                    for (int w = 0; w < Selection.words(p.type()); w++) {
                        sel.ins("lda", Selection.at(sel.incoming.get(p) + 2 * w));
                        sel.ins("sta", Selection.at(sel.place.get(p) + 2 * w));
                    }
                    continue;
                }
                if (i.pos() != null && (lastPos == null || !lastPos.file().equals(i.pos().file()) || lastPos.line() != i.pos().line())) {
                    sourceLine(i.pos());
                    lastPos = i.pos();
                }
                sel.out.note(Printer.instr(i));
                if (sel.fused.contains(i)) {
                    continue;   // compared at the branch
                }
                if (sel.folded.contains(i)) {
                    continue;   // the loads and stores add the offset themselves; a copying cast reads its source
                }
                if (i.isTerminator()) {
                    phiCopies(b, i);
                }
                instr(i, next);
            }
        }
        sel.flushHeld();
        sel.out.raw(".endproc");
        return blocks;
    }

    /**
     * The order the blocks are written in: chains in which a block is followed by a successor
     * not written yet (for a branch the true side, so a loop body or the then-part falls through
     * and the branch skips only one jump), the entry block first.
     */
    static List<Block> layoutOrder(MFunction f) {
        return layoutOrder(f, null);
    }

    /**
     * With {@code counts} (how often each block ran, by name, from a profile): the successor that
     * ran more often follows, and blocks that never ran go to the end of the function, so the
     * code that runs stays together and its branches reach.
     */
    static List<Block> layoutOrder(MFunction f, java.util.function.Function<String, Long> counts) {
        List<Block> order = new ArrayList<>();
        Set<Block> placed = new HashSet<>();
        int scan = 0;
        Block b = f.entry();
        while (order.size() < f.blocks().size()) {
            if (b == null || placed.contains(b)) {
                while (placed.contains(f.blocks().get(scan))) {
                    scan++;
                }
                b = f.blocks().get(scan);
            }
            order.add(b);
            placed.add(b);
            Block next = null;
            Instr t = b.terminator();
            if (t != null) {
                for (Block s : t.successors()) {
                    if (placed.contains(s)) {
                        continue;
                    }
                    // with a profile: the successor that ran more often; without: one only this block
                    // reaches (the next test of an ||), since a block with other ways in needs a
                    // jump from somewhere anyway
                    boolean better = next == null || (counts != null ? count(counts, s) > count(counts, next)
                            : s.preds().size() == 1 && next.preds().size() > 1);
                    if (better) {
                        next = s;
                    }
                }
            }
            b = next;
        }
        rotateLoops(order);
        if (counts != null) {
            List<Block> cold = new ArrayList<>();
            for (Block x : order) {
                if (x != f.entry() && count(counts, x) == 0) {
                    cold.add(x);
                }
            }
            order.removeAll(cold);
            order.addAll(cold);
        }
        return order;
    }

    static long count(java.util.function.Function<String, Long> counts, Block b) {
        Long n = counts.apply(b.name());
        return n == null ? 0 : n;
    }

    /**
     * A small loop head (only φs, a comparison and a branch) goes right after the block that
     * jumps back to it: that block falls through into the test, and the test branches back into
     * the loop. Per round one branch instead of a jump and a branch.
     */
    static void rotateLoops(List<Block> order) {
        for (Block h : List.copyOf(order)) {
            if (h == order.getFirst() || !(h.terminator() instanceof Instr.Branch) || !onlyTest(h)) {
                continue;
            }
            int at = order.indexOf(h);
            Block latch = null;
            for (Block p : h.preds()) {
                if (order.indexOf(p) > at) {
                    if (latch != null || !(p.terminator() instanceof Instr.Jump)) {
                        latch = null;
                        break;
                    }
                    latch = p;
                }
            }
            if (latch == null || order.indexOf(latch) + 1 < order.size() && order.get(order.indexOf(latch) + 1) == h) {
                continue;
            }
            order.remove(h);
            order.add(order.indexOf(latch) + 1, h);
        }
    }

    static boolean onlyTest(Block h) {
        for (Instr i : h.instrs()) {
            if (!(i instanceof Instr.Phi || i instanceof Instr.Cmp || i.isTerminator())) {
                return false;
            }
        }
        return true;
    }

    void sourceLine(SourcePos pos) {
        // (a held REP and mask stay held across the line: a SEP on the next line drops them both;
        // otherwise the next instruction that needs 16 bits writes them, under the new line)
        sel.out.blank();
        String file = pos.file();
        sourceFiles.putIfAbsent(file, Path.of(file));
        sel.out.sourceHeader("; " + Path.of(file).getFileName() + ":" + pos.line() + "   " + lineText(file, pos.line()));
        sel.out.raw("        .dbg    line, \"" + debugName.apply(file) + "\", " + pos.line());
    }

    String lineText(String file, int line) {
        List<String> lines = sourceLines.computeIfAbsent(file, f -> {
            try {
                return Files.readAllLines(Path.of(f));
            } catch (IOException | RuntimeException e) {
                return List.of();
            }
        });
        return line >= 1 && line <= lines.size() ? lines.get(line - 1).strip() : "";
    }

    /** Before a block's jump: every φ of every successor gets this block's operand. */
    void phiCopies(Block b, Instr terminator) {
        Value xInit = null;
        for (Block s : new LinkedHashSet<>(terminator.successors())) {
            for (Instr i : s.instrs()) {
                if (!(i instanceof Instr.Phi p)) {
                    break;
                }
                Value v = p.op(p.from.indexOf(b));
                if (sel.xResident.containsKey(p)) {
                    if (v != sel.xResident.get(p)) {
                        xInit = v;   // entering the loop: X gets the start value (after the other copies)
                    }
                    continue;        // (along the back edge the step has updated X already)
                }
                int to = sel.direct.contains(s) ? sel.place.get(p) : sel.incoming.get(p);
                for (int w = 0; w < Selection.words(p.type()); w++) {
                    if (sel.operand(v, w).equals(Selection.at(to + 2 * w))) {
                        continue;   // the operand shares the φ's place
                    }
                    if (sel.operand(v, w).equals(Selection.imm(0)) && !sel.aHolds.contains(Selection.imm(0))) {
                        sel.ins("stz", Selection.at(to + 2 * w));   // no load of the zero
                        continue;
                    }
                    sel.ins("lda", sel.operand(v, w));
                    sel.ins("sta", Selection.at(to + 2 * w));
                }
            }
        }
        if (xInit != null) {
            sel.ins("ldx", sel.operand(xInit, 0));   // (not where X belongs to another loop: the guard checks)
        }
    }

    void instr(Instr i, Block next) {
        switch (i) {
            case Instr.Bin b when sel.xSteps.containsKey(b) -> {
                long c = sel.xSteps.get(b);
                if (c >= -3 && c <= 3) {
                    for (long n = 0; n < Math.abs(c); n++) {
                        sel.xOp(c > 0 ? "inx" : "dex", null);
                    }
                } else {
                    sel.xOp("txa", null);
                    sel.xOp("clc", null);
                    sel.xOp("adc", Selection.imm(c));
                    sel.xOp("tax", null);
                }
            }
            case Instr.Bin b -> sel.arith.bin(b);
            case Instr.Cmp c -> sel.branch.cmp(c);
            case Instr.Cast c -> sel.arith.cast(c);
            case Instr.PtrAdd p -> {
                sel.ins("clc", null);
                if (p.op(1).type() == MType.I16 && sel.aHolds.contains(sel.operand(p.op(1), 0))) {
                    sel.ins("adc", sel.operand(p.op(0), 0));   // A has the offset already
                } else {
                    sel.ins("lda", sel.operand(p.op(0), 0));
                    sel.ins("adc", sel.operand(p.op(1), 0));
                }
                sel.result(p, 0);
                if (p.op(1).type() == MType.I32) {
                    sel.ins("lda", sel.operand(p.op(0), 1));
                    sel.ins("adc", sel.operand(p.op(1), 1));
                    sel.result(p, 1);
                } else if (!sel.operand(p.op(0), 1).equals(sel.dest(p, 1))) {
                    // an i16 offset stays within the bank (Instr.PtrAdd): the bank word is copied
                    sel.ins("lda", sel.operand(p.op(0), 1));
                    sel.result(p, 1);
                }
            }
            case Instr.Load l -> sel.memory.load(l);
            case Instr.Store s -> sel.memory.store(s);
            case Instr.Copy c -> sel.memory.copy(c);
            case Instr.Call c -> sel.calls.call(c);
            case Instr.Phi p -> throw new IllegalStateException("φ after the start of a block");
            case Instr.Jump j -> {
                if (j.target != next) {
                    sel.branch.jumpTo(j.target);
                }
            }
            case Instr.Branch br -> {
                if (br.op(0) instanceof Instr.Cmp c && sel.fused.contains(c)) {
                    sel.branch.compareAndBranch(c, br.ifTrue, br.ifFalse, next);
                } else {
                    sel.loadAlways(sel.operand(br.op(0), 0));
                    sel.branch.jumpOn("bne", null, false, br.ifTrue, br.ifFalse, next);
                }
            }
            case Instr.Switch s -> {
                MType t = s.op(0).type();
                for (int k = 0; k < s.cases.size(); k++) {
                    long c = t.wrap(s.cases.get(k));
                    String nextCase = sel.label();
                    for (int w = 0; w < Selection.words(t); w++) {
                        sel.ins("lda", sel.operand(s.op(0), w));
                        sel.ins("cmp", Selection.imm(c >> (16 * w)));
                        // in the long form; BranchRelax makes it BEQ to the case where that reaches
                        sel.ins("bne", nextCase, Selection.words(t) == 1);
                    }
                    sel.ins("jmp", s.targets.get(k).name(), true);
                    sel.place(nextCase);
                }
                if (s.otherwise != next) {
                    sel.ins("jmp", s.otherwise.name(), true);
                }
            }
            case Instr.Unreachable u -> throw new IllegalStateException("the checks of hints are taken out before code generation");
            case Instr.Ret r -> {
                if (!r.operands().isEmpty()) {
                    Value v = r.op(0);
                    if (Selection.words(v.type()) == 2) {
                        sel.ins("lda", sel.operand(v, 1));
                        sel.ins("tax", null);
                    }
                    sel.ins("lda", sel.operand(v, 0));
                }
                sel.ins("rtl", null);
            }
        }
    }
}
