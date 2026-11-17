package dampf.backend;

import dampf.frontend.CompileError;
import dampf.frontend.SourcePos;
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
import java.util.IdentityHashMap;
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
 * 16 bits, so a 16-bit load gives its value. Every instruction loads its operands from there
 * and stores its result there; nothing stays in a register from one instruction to the next.
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
    private static final String P = "z:dampf_ptr";
    private static final String Q = "z:dampf_ptr+4";
    private static final String VIA_P = "[dampf_ptr],y";
    private static final String VIA_Q = "[dampf_ptr+4],y";
    private static final String FRAME = "dampf_frame";
    /** RAM between $2000 and $FFFF (bank $7E) minus room for the globals: the frames must fit. */
    private static final int MAX_FRAME = 0x1E00 - 0x20;
    /** Where the frames start: the low RAM after the runtime's direct page registers (docs/memory-map.md). */
    private static final int FRAME_START = 0x20;

    private final MModule module;
    private final String entry;
    private final java.util.function.Function<String, String> debugName;
    private final Set<String> rom = new HashSet<>();

    /** Position of every value, frame slot and φ "incoming" place in dampf_frame. */
    private final Map<Object, Integer> place = new IdentityHashMap<>();
    private final Map<Instr.Phi, Integer> incoming = new IdentityHashMap<>();
    private final Map<MFunction, Integer> base = new HashMap<>();
    private final Map<MFunction, Integer> frameSize = new HashMap<>();
    private final Map<String, List<MFunction>> callees = new HashMap<>();
    private int frameTotal;

    private final List<String> banked = new ArrayList<>();
    private int entryBlock = -1;
    private final Map<String, List<String>> sourceLines = new HashMap<>();
    private final Map<String, Path> sourceFiles = new LinkedHashMap<>();

    private AsmWriter out;
    private FrameLayout layout;
    private final Set<Instr> fused = new HashSet<>();
    /** Results that stay in A for the next instruction, their only reader, and are never stored. */
    private final Set<Instr> forward = new HashSet<>();
    private Ranges ranges;
    private final Set<Instr> folded = new HashSet<>();
    private int labels;
    private SourcePos lastPos;

    /**
     * @param entry     the label of the function the runtime calls
     * @param debugName maps a source file path to the name written into the debug information
     */
    public MirCodeGen(MModule module, String entry, java.util.function.Function<String, String> debugName) {
        this.module = module;
        this.entry = entry;
        this.debugName = debugName;
        for (MModule.Global g : module.globals()) {
            if (g.readOnly()) {
                rom.add(g.symbol());
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
        layout();
        for (MFunction f : module.functions()) {
            // twice: the first pass finds out which branches reach their block directly
            near = null;
            jumpSites = 0;
            sitePos.clear();
            siteTargets.clear();
            AsmWriter dry = new AsmWriter();
            function(f, dry);
            near = new ArrayList<>();
            for (int k = 0; k < sitePos.size(); k++) {
                near.add(new boolean[] {reaches(dry, sitePos.get(k), siteTargets.get(k)[0]),
                        reaches(dry, sitePos.get(k), siteTargets.get(k)[1])});
            }
            jumpSites = 0;
            AsmWriter code = new AsmWriter();
            function(f, code);
            if (code.unknownInstructions() > 0) {
                throw new IllegalStateException("the cycle table does not know an instruction in " + f.name());
            }
            if (f == entryFunction) {
                entryBlock = banked.size();
            }
            banked.add(code.toString());
        }
        AsmWriter ram = new AsmWriter();
        globals(ram);

        List<String> modules = new ArrayList<>();
        int chunks = Math.max(1, (banked.size() + SEGMENTS_PER_MODULE - 1) / SEGMENTS_PER_MODULE);
        for (int m = 0; m < chunks; m++) {
            AsmWriter w = new AsmWriter();
            prelude(w, m, chunks);
            if (m == 0) {
                w.raw(ram.toString());
            }
            for (int i = m * SEGMENTS_PER_MODULE; i < Math.min(banked.size(), (m + 1) * SEGMENTS_PER_MODULE); i++) {
                if (i == entryBlock) {
                    w.blank();
                    w.raw("        .export dampf_entry");
                    w.raw("dampf_entry = " + entry + "             ; the runtime calls this after start-up");
                }
                w.raw(banked.get(i));
            }
            modules.add(w.toString());
        }
        return modules;
    }

    /**
     * Conditional branches: per jumpOn call of a function, where the branch was in the first pass
     * and its targets; in the second pass, which targets a short branch reaches. The second pass
     * only gets shorter, so a distance that fitted in the first still fits.
     */
    private final List<Integer> sitePos = new ArrayList<>();
    private final List<Block[]> siteTargets = new ArrayList<>();
    private List<boolean[]> near;
    private int jumpSites;

    private static boolean reaches(AsmWriter w, int branchAt, Block target) {
        Integer to = w.labelAt(target.name());
        if (to == null) {
            return false;
        }
        int d = to - (branchAt + 2);
        return d >= -128 && d <= 127;
    }

    private void prelude(AsmWriter w, int module, int modules) {
        w.raw("; Generated by dampf from MIR" + (modules > 1 ? " (module " + (module + 1) + " of " + modules + ")" : "") + ".");
        w.raw("; A/X/Y are 16 bit throughout, DBR = $7E; every value has a fixed place in dampf_frame.");
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
        w.raw(FRAME + " = $" + hex(FRAME_START, 4) + "            ; the frames (compiled stack), $0020-$1DFF; below $0100 in the direct page");
    }

    private String fileDirective(String file, Path path) {
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

    // ---------------------------------------------------------------- frames

    private static int bytes(MType t) {
        return t.size <= 2 ? 2 : 4;
    }

    private static int words(MType t) {
        return t.size <= 2 ? 1 : 2;
    }

    private final Set<Block> direct = new HashSet<>();
    private final Map<MFunction, FrameLayout> layouts = new HashMap<>();

    private void layout() {
        for (MFunction f : module.functions()) {
            for (Block b : f.blocks()) {
                for (Instr i : b.instrs()) {
                    if (i instanceof Instr.Call c) {
                        List<MFunction> l = callees.computeIfAbsent(f.name(), k -> new ArrayList<>());
                        MFunction callee = module.function(c.callee);
                        if (callee != null && !l.contains(callee)) {
                            l.add(callee);
                        }
                    }
                }
            }
        }
        for (MFunction f : module.functions()) {
            FrameLayout l = new FrameLayout(f);
            layouts.put(f, l);
            frameSize.put(f, l.size);
            direct.addAll(l.direct);
        }
        for (MFunction f : module.functions()) {
            FrameLayout l = layouts.get(f);
            int b = base(f, new LinkedHashSet<>());
            l.offset.forEach((k, v) -> place.put(k, b + v));
            l.incoming.forEach((k, v) -> incoming.put(k, b + v));
            frameTotal = Math.max(frameTotal, b + l.size);
        }
        if (frameTotal > MAX_FRAME) {
            throw new IllegalStateException("the frames need " + frameTotal + " bytes, more than " + MAX_FRAME);
        }
    }

    /**
     * Where f's frame starts: after the frames of all the functions it calls (and so after
     * everything they call). Leaves start at 0, where the direct page is.
     */
    private int base(MFunction f, Set<MFunction> path) {
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

    private static String at(int offset) {
        // a word that lies in the direct page is reached with direct page addressing (D = 0)
        // (ca65 takes dampf_frame for a zero page symbol, so the others need "a:")
        return (FRAME_START + offset + 1 < 0x100 ? "z:" : "a:") + FRAME + "+" + offset;
    }

    // ---------------------------------------------------------------- data

    private void globals(AsmWriter ram) {
        for (MModule.Global g : module.globals()) {
            boolean zero = g.init().stream().allMatch(d -> d.symbol() == null && d.value() == 0);
            AsmWriter w = g.readOnly() ? new AsmWriter() : ram;
            String segment = g.readOnly() ? NaiveCodeGen.romSegment(g.symbol()) : zero ? "BSS" : "DATA";
            w.blank();
            w.raw("        .segment \"" + segment + "\"");
            if (segment.equals("BSS")) {
                w.raw(pad(g.symbol() + ":", 28) + ".res    " + Math.max(1, g.size()));
                continue;
            }
            w.raw(g.symbol() + ":");
            data(w, g);
            if (g.readOnly()) {
                banked.add(w.toString());
            }
        }
    }

    private void data(AsmWriter w, MModule.Global g) {
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
                if (rom.contains(a.symbol())) {
                    w.raw("        .word   .loword(" + target + ")");
                    w.raw("        .byte   ^(" + target + "), 0");
                } else {
                    w.raw("        .word   " + target);
                    w.raw("        .byte   $7E, 0                  ; bank $7E: RAM");
                }
                o += 3;
                continue;
            }
            line.append(count == 0 ? "" : ",").append('$').append(hex(bytes[o] & 0xFF, 2));
            if (++count == 16) {
                flush(w, line);
                count = 0;
            }
        }
        flush(w, line);
    }

    private static void flush(AsmWriter w, StringBuilder line) {
        if (!line.isEmpty()) {
            w.raw("        .byte   " + line);
            line.setLength(0);
        }
    }

    // ---------------------------------------------------------------- functions

    private void function(MFunction f, AsmWriter code) {
        out = code;
        lastPos = null;
        aHolds = null;
        yHolds = null;
        xHolds = null;
        ptrP = null;
        ptrQ = null;
        m8 = false;
        f.number();
        out.blank();
        out.raw("; ---- " + f.name() + "    (" + f.pos() + ")");
        out.raw("        .segment \"" + NaiveCodeGen.codeSegment(f.name()) + "\"");
        out.raw(".proc " + f.name());
        for (Value.Param p : f.params()) {
            out.note("parameter " + p.type() + " " + p + " at " + at(place.get(p)));
        }
        layout = layouts.get(f);
        ranges = layout.ranges;
        forward.clear();
        fused.clear();
        fused.addAll(layout.fused);
        folded.clear();
        folded.addAll(layout.folded);
        findForwards(f);
        List<Block> blocks = layoutOrder(f);
        for (int k = 0; k < blocks.size(); k++) {
            Block b = blocks.get(k);
            Block next = k + 1 < blocks.size() ? blocks.get(k + 1) : null;
            place(b.name());
            for (Instr i : b.instrs()) {
                if (i instanceof Instr.Phi p) {
                    out.note(Printer.instr(p));
                    if (direct.contains(b)) {
                        continue;   // set by the predecessors
                    }
                    for (int w = 0; w < words(p.type()); w++) {
                        ins("lda", at(incoming.get(p) + 2 * w));
                        ins("sta", at(place.get(p) + 2 * w));
                    }
                    continue;
                }
                if (i.pos() != null && (lastPos == null || !lastPos.file().equals(i.pos().file()) || lastPos.line() != i.pos().line())) {
                    sourceLine(i.pos());
                    lastPos = i.pos();
                }
                out.note(Printer.instr(i));
                if (fused.contains(i)) {
                    continue;   // compared at the branch
                }
                if (folded.contains(i)) {
                    continue;   // the loads and stores add the offset themselves; a copying cast reads its source
                }
                if (i.isTerminator()) {
                    phiCopies(b, i);
                }
                instr(i, next);
            }
        }
        flushHeld();
        out.raw(".endproc");
    }

    /**
     * Finds the results that need not be stored: a 1-word result whose only use is the next
     * instruction written in the same block, where that instruction starts by loading it into A
     * and reads it nowhere else. Only instructions whose code is known to begin that way qualify.
     */
    private void findForwards(MFunction f) {
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
                if (!(i instanceof Instr.Phi) && !fused.contains(i) && !folded.contains(i)) {
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
                if (words(seen.type()) == 1 && (user.get(seen) == u || u.isTerminator()) && readsFirst(u, seen, b)) {
                    forward.add(i);
                }
            }
        }
    }

    /** i's code ends with A holding its 1-word result, stored with result(). */
    private static boolean producesInA(Instr i) {
        if (i.type() == MType.VOID || words(i.type()) != 1 || FrameLayout.transparent(i)) {
            return false;
        }
        return i instanceof Instr.Bin || i instanceof Instr.Load || i instanceof Instr.Cast
                || i instanceof Instr.Call || i instanceof Instr.Cmp;
    }

    /** u's code starts by loading v into A, and reads v nowhere else. */
    private boolean readsFirst(Instr u, Instr v, Block b) {
        if (u.isTerminator() && firstPhiCopyReads(b, v)) {
            return true;   // the first φ copy at the end of the block loads v
        }
        long n = u.operands().stream().filter(o -> o == v).count();
        if (n != 1) {
            return u instanceof Instr.Branch br && br.op(0) instanceof Instr.Cmp c && fused.contains(c)
                    && n == 0 && fusedReadsFirst(c, v, b);
        }
        return switch (u) {
            case Instr.Bin x -> words(x.type()) == 1 && switch (x.op) {
                case ADD, AND, OR, XOR -> true;     // commutative: v is loaded first either way (bin swaps)
                case SUB -> x.op(0) == v;
                case SHL, LSHR, ASHR -> x.op(0) == v && x.op(1) instanceof Value.Const k && k.bits() <= UNROLL_SHIFT
                        && k.bits() < x.type().bits();
                default -> false;
            };
            case Instr.Store s -> s.op(1) == v && words(v.type()) == 1 && addressNeedsNoA(s.op(0));
            case Instr.Ret r -> true;
            case Instr.Cast c -> c.kind == Instr.CastKind.SEXT || c.kind == Instr.CastKind.TRUNC && words(c.type()) == 1;
            case Instr.Call c -> c.op(0) == v;
            default -> false;
        };
    }

    /**
     * Whether the code at the end of b starts with a φ copy that loads v (and v is read nowhere
     * else there): the first copy that is not left out because the operand shares the φ's place.
     */
    private boolean firstPhiCopyReads(Block b, Instr v) {
        for (Block s : new LinkedHashSet<>(b.terminator().successors())) {
            for (Instr i : s.instrs()) {
                if (!(i instanceof Instr.Phi p)) {
                    break;
                }
                Value op = p.op(p.from.indexOf(b));
                int to = direct.contains(s) ? place.get(p) : incoming.get(p);
                if (operand(op, 0).equals(at(to)) && (words(p.type()) == 1 || operand(op, 1).equals(at(to + 2)))) {
                    if (op == v) {
                        return false;   // v's place is the φ's: it must be stored
                    }
                    continue;           // no code
                }
                return op == v && words(p.type()) == 1;
            }
        }
        return false;
    }

    /** A fused comparison at the end of b whose code starts with "lda v" (and no φ copies come first). */
    private boolean fusedReadsFirst(Instr.Cmp c, Value v, Block b) {
        for (Block s : b.terminator().successors()) {
            if (!s.instrs().isEmpty() && s.instrs().getFirst() instanceof Instr.Phi) {
                return false;
            }
        }
        if (words(c.op(0).type()) != 1 || c.op(0) == c.op(1)) {
            return false;
        }
        return switch (c.pred) {
            case EQ, NE -> c.op(0) == v && !(c.op(1) instanceof Value.Const k && k.bits() == 0);
            case ULT, UGE -> c.op(0) == v;
            // (against 0 in 16 bits it is a forced load for the sign: v must be in its place)
            case SLT, SGE -> c.op(0) == v && c.op(1) instanceof Value.Const k
                    && !(k.bits() == 0 && c.op(0).type() == MType.I16);
            case UGT, ULE -> c.op(1) == v;
            case SGT, SLE -> c.op(1) == v && c.op(0) instanceof Value.Const;
        };
    }

    /** A load or store at this address needs A for nothing before the access itself. */
    private boolean addressNeedsNoA(Value address) {
        FrameLayout.Address a = address(address);
        if (isFixed(a)) {
            return true;
        }
        Value p = a.base();
        while (p instanceof Instr i && FrameLayout.transparent(i)) {
            p = i.op(0);
        }
        return (p instanceof Value.Param || p instanceof Instr) && place.containsKey(p) && FRAME_START + place.get(p) + 3 < 0x100;
    }

    /**
     * The order the blocks are written in: chains in which a block is followed by a successor
     * not written yet (for a branch the true side, so a loop body or the then-part falls through
     * and the branch skips only one jump), the entry block first.
     */
    static List<Block> layoutOrder(MFunction f) {
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
                    if (!placed.contains(s)) {
                        next = s;
                        break;
                    }
                }
            }
            b = next;
        }
        return order;
    }

    private void sourceLine(SourcePos pos) {
        flushHeld();
        out.blank();
        String file = pos.file();
        sourceFiles.putIfAbsent(file, Path.of(file));
        out.sourceHeader("; " + Path.of(file).getFileName() + ":" + pos.line() + "   " + lineText(file, pos.line()));
        out.raw("        .dbg    line, \"" + debugName.apply(file) + "\", " + pos.line());
    }

    private String lineText(String file, int line) {
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
    private void phiCopies(Block b, Instr terminator) {
        for (Block s : new LinkedHashSet<>(terminator.successors())) {
            for (Instr i : s.instrs()) {
                if (!(i instanceof Instr.Phi p)) {
                    break;
                }
                Value v = p.op(p.from.indexOf(b));
                int to = direct.contains(s) ? place.get(p) : incoming.get(p);
                for (int w = 0; w < words(p.type()); w++) {
                    if (operand(v, w).equals(at(to + 2 * w))) {
                        continue;   // the operand shares the φ's place
                    }
                    ins("lda", operand(v, w));
                    ins("sta", at(to + 2 * w));
                }
            }
        }
    }

    // ---------------------------------------------------------------- operands

    /** Word w (0 low, 1 high) of a value, as an operand of lda, adc, cmp, ... */
    private String operand(Value v, int w) {
        return switch (v) {
            case Value.Const k -> imm(k.bits() >> (16 * w));
            case Value.Undef u -> imm(0);
            case Value.GlobalAddr g -> rom.contains(g.symbol())
                    ? (w == 0 ? "#.loword(" + g.symbol() + ")" : "#^" + g.symbol())
                    : (w == 0 ? "#.loword(" + g.symbol() + ")" : imm(0x7E));
            case Value.SlotAddr s -> w == 0 ? "#.loword(" + FRAME + "+" + place.get(s.slot()) + ")" : imm(0x7E);
            case Value.Param p -> w >= words(p.type()) ? imm(0) : at(place.get(p) + 2 * w);
            case Instr i when FrameLayout.transparent(i) -> w >= words(i.type()) ? imm(0) : operand(i.op(0), w);
            case Instr i -> w >= words(i.type()) ? imm(0) : at(place.get(i) + 2 * w);
        };
    }

    /**
     * Stores word w of i's result from A into its place, unless the next instruction is the only
     * one that reads it and takes it from A ({@link #forward}): then A is noted as holding it.
     */
    private void result(Instr i, int w) {
        if (w == 0 && forward.contains(i)) {
            aHolds = dest(i, 0);
            return;
        }
        ins("sta", dest(i, w));
    }

    private String dest(Instr i, int w) {
        return at(place.get(i) + 2 * w);
    }

    /** The bits above an i1 or i8 are zero in its place. */
    /**
     * Whether an 8-bit add, subtract, multiply or left shift, done in 16 bits on zero-extended
     * operands, provably gives a result below 256, so it needs no mask (from the value ranges,
     * in the unsigned view the 16-bit code computes in).
     */
    private boolean staysByte(Instr.Bin b) {
        if (b.type() != MType.I8 || ranges == null) {
            return false;
        }
        Ranges.Range x = Ranges.unsigned(ranges.at(b.op(0), b.block()), MType.I8);
        Ranges.Range y = Ranges.unsigned(ranges.at(b.op(1), b.block()), MType.I8);
        if (x == null || y == null) {
            return false;
        }
        return switch (b.op) {
            case ADD -> x.hi() + y.hi() <= 0xFF;
            case SUB -> x.lo() - y.hi() >= 0;
            case MUL -> x.hi() * y.hi() <= 0xFF;
            case SHL -> y.lo() == y.hi() && y.lo() < 8 && x.hi() << y.lo() <= 0xFF;
            default -> false;
        };
    }

    private void mask(MType t) {
        if (t == MType.I8) {
            ins("and", imm(0xFF));
        } else if (t == MType.I1) {
            ins("and", imm(1));
        }
    }

    /** A memory operand for byte {@code k} of the object at a fixed address, or null if the address is not fixed. */
    private String fixed(Value address, int k) {
        return switch (address) {
            case Value.GlobalAddr g -> (rom.contains(g.symbol()) ? "f:" : "") + g.symbol() + (k == 0 ? "" : "+" + k);
            case Value.SlotAddr s -> at(place.get(s.slot()) + k);
            default -> null;
        };
    }

    /** Copies a pointer into the direct page for [dp],y. */
    private void pointer(Value address, String dp) {
        ins("lda", operand(address, 0));
        ins("sta", dp);
        ins("lda", operand(address, 1));
        ins("sta", dp + "+2");
    }

    // ---------------------------------------------------------------- instructions

    private void instr(Instr i, Block next) {
        switch (i) {
            case Instr.Bin b -> bin(b);
            case Instr.Cmp c -> cmp(c);
            case Instr.Cast c -> cast(c);
            case Instr.PtrAdd p -> {
                ins("clc", null);
                ins("lda", operand(p.op(0), 0));
                ins("adc", operand(p.op(1), 0));
                result(p, 0);
                if (p.op(1).type() == MType.I32) {
                    ins("lda", operand(p.op(0), 1));
                    ins("adc", operand(p.op(1), 1));
                    result(p, 1);
                } else if (!operand(p.op(0), 1).equals(dest(p, 1))) {
                    // an i16 offset stays within the bank (Instr.PtrAdd): the bank word is copied
                    ins("lda", operand(p.op(0), 1));
                    result(p, 1);
                }
            }
            case Instr.Load l -> load(l);
            case Instr.Store s -> store(s);
            case Instr.Copy c -> copy(c);
            case Instr.Call c -> call(c);
            case Instr.Phi p -> throw new IllegalStateException("φ after the start of a block");
            case Instr.Jump j -> {
                if (j.target != next) {
                    ins("jmp", j.target.name());
                }
            }
            case Instr.Branch br -> {
                if (br.op(0) instanceof Instr.Cmp c && fused.contains(c)) {
                    compareAndBranch(c, br.ifTrue, br.ifFalse, next);
                } else {
                    load(operand(br.op(0), 0));
                    jumpOn("bne", null, false, br.ifTrue, br.ifFalse, next);
                }
            }
            case Instr.Switch s -> {
                MType t = s.op(0).type();
                for (int k = 0; k < s.cases.size(); k++) {
                    long c = t.wrap(s.cases.get(k));
                    String nextCase = label();
                    for (int w = 0; w < words(t); w++) {
                        ins("lda", operand(s.op(0), w));
                        ins("cmp", imm(c >> (16 * w)));
                        ins("bne", nextCase);
                    }
                    ins("jmp", s.targets.get(k).name());
                    place(nextCase);
                }
                if (s.otherwise != next) {
                    ins("jmp", s.otherwise.name());
                }
            }
            case Instr.Ret r -> {
                if (!r.operands().isEmpty()) {
                    Value v = r.op(0);
                    if (words(v.type()) == 2) {
                        ins("lda", operand(v, 1));
                        ins("tax", null);
                    }
                    ins("lda", operand(v, 0));
                }
                ins("rtl", null);
            }
        }
    }

    private void bin(Instr.Bin b) {
        MType t = b.type();
        int n = words(t);
        Value x = b.op(0);
        Value y = b.op(1);
        switch (b.op) {
            case ADD, SUB -> {
                if (n == 1 && b.op == Instr.BinOp.ADD && operand(y, 0).equals(aHolds)) {
                    Value s = x;   // A has the second operand already: add the first to it
                    x = y;
                    y = s;
                }
                ins(b.op == Instr.BinOp.ADD ? "clc" : "sec", null);
                for (int w = 0; w < n; w++) {
                    ins("lda", operand(x, w));
                    ins(b.op == Instr.BinOp.ADD ? "adc" : "sbc", operand(y, w));
                    if (!staysByte(b)) {
                        mask(t);
                    }
                    result(b, w);
                }
            }
            case AND, OR, XOR -> {
                if (n == 1 && operand(y, 0).equals(aHolds)) {
                    Value s = x;   // A has the second operand already
                    x = y;
                    y = s;
                }
                String m = b.op == Instr.BinOp.AND ? "and" : b.op == Instr.BinOp.OR ? "ora" : "eor";
                for (int w = 0; w < n; w++) {
                    ins("lda", operand(x, w));
                    ins(m, operand(y, w));
                    result(b, w);
                }
            }
            case MUL -> {
                if (n == 1 && (x instanceof Value.Const) != (y instanceof Value.Const)) {
                    Value v = x instanceof Value.Const ? y : x;
                    long c = ((Value.Const) (x instanceof Value.Const ? x : y)).bits();
                    multiplyBy(v, c, t);
                    if (!staysByte(b)) {
                        mask(t);
                    }
                    result(b, 0);
                } else if (n == 1) {
                    ins("lda", operand(y, 0));
                    ins("sta", "z:R0");
                    ins("lda", operand(x, 0));
                    ins("jsl", "dampf_mul16");
                    if (!staysByte(b)) {
                        mask(t);
                    }
                    result(b, 0);
                } else {
                    to32(x, "z:R0", "z:R2");
                    to32(y, "z:R4", "z:R6");
                    ins("jsl", "dampf_mul32");
                    from32(b, "z:R8", "z:R10");
                }
            }
            case UDIV, UREM -> {
                boolean rem = b.op == Instr.BinOp.UREM;
                if (n == 1) {
                    ins("lda", operand(y, 0));
                    ins("sta", "z:R0");
                    ins("lda", operand(x, 0));
                    ins("jsl", "dampf_udiv16");
                    if (rem) {
                        ins("lda", "z:R2");
                    }
                    result(b, 0);
                } else {
                    to32(x, "z:R0", "z:R2");
                    to32(y, "z:R4", "z:R6");
                    ins("jsl", "dampf_udiv32");
                    from32(b, rem ? "z:R12" : "z:R8", rem ? "z:R14" : "z:R10");
                }
            }
            case SDIV, SREM -> {
                boolean rem = b.op == Instr.BinOp.SREM;
                if (n == 1) {
                    signExtend(x, t, "z:R0", "z:R2");
                    signExtend(y, t, "z:R4", "z:R6");
                } else {
                    to32(x, "z:R0", "z:R2");
                    to32(y, "z:R4", "z:R6");
                }
                ins("jsl", "dampf_sdiv32");
                if (n == 1) {
                    ins("lda", rem ? "z:R12" : "z:R8");
                    mask(t);
                    result(b, 0);
                } else {
                    from32(b, rem ? "z:R12" : "z:R8", rem ? "z:R14" : "z:R10");
                }
            }
            case SHL, LSHR, ASHR -> shift(b);
        }
    }

    /**
     * A = x * c (low 16 bits): the constant in non-adjacent form, worked through from the top
     * digit down (Horner): shift A left one place per digit, add or subtract x where the digit is
     * +1 or -1. A run of ones such as 15 costs one subtraction: 16 - 1.
     */
    private void multiplyBy(Value x, long c, MType t) {
        List<long[]> digits = dampf.mir.StrengthReduce.naf(t.wrap(c), 16);
        if (digits.isEmpty()) {
            ins("lda", imm(0));
            return;
        }
        String xs = operand(x, 0);
        int top = (int) digits.getLast()[0];
        ins("lda", xs);
        if (digits.getLast()[1] < 0) {
            // the top digit is -1 (only for a constant of 2^16 - ...): start from -x
            ins("eor", imm(0xFFFF));
            ins("inc", "a");
        }
        int k = digits.size() - 2;
        for (int pos = top - 1; pos >= 0; pos--) {
            ins("asl", "a");
            if (k >= 0 && digits.get(k)[0] == pos) {
                if (digits.get(k)[1] > 0) {
                    ins("clc", null);
                    ins("adc", xs);
                } else {
                    ins("sec", null);
                    ins("sbc", xs);
                }
                k--;
            }
        }
    }

    private void to32(Value v, String lo, String hi) {
        ins("lda", operand(v, 0));
        ins("sta", lo);
        ins("lda", operand(v, 1));
        ins("sta", hi);
    }

    private void from32(Instr d, String lo, String hi) {
        ins("lda", lo);
        result(d, 0);
        ins("lda", hi);
        result(d, 1);
    }

    /** A = the value of an i1/i8/i16 as a signed 16-bit number. */
    private void loadSigned16(Value v, MType t) {
        ins("lda", operand(v, 0));
        int m = t == MType.I8 ? 0x80 : t == MType.I1 ? 1 : 0;
        if (m != 0) {
            ins("eor", imm(m));     // (x ^ m) - m: sign-extends the top bit m
            ins("sec", null);
            ins("sbc", imm(m));
        }
    }

    /** Stores A (a signed 16-bit number) to lo, and its sign (0 or $FFFF) to hi. */
    private void storeWithSign(String lo, String hi) {
        String positive = label();
        ins("sta", lo);
        ins("ldx", imm(0));
        ins("cmp", imm(0x8000));
        ins("bcc", positive);
        ins("dex", null);
        place(positive);
        ins("stx", hi);
    }

    private void signExtend(Value v, MType t, String lo, String hi) {
        loadSigned16(v, t);
        storeWithSign(lo, hi);
    }

    /** Shifts by a constant up to this are written out; longer ones and variable ones loop. */
    private static final int UNROLL_SHIFT = 8;

    private void shift(Instr.Bin b) {
        MType t = b.type();
        int bits = t.bits();
        Value x = b.op(0);
        Value y = b.op(1);
        if (y instanceof Value.Const k && k.bits() <= UNROLL_SHIFT && k.bits() < bits) {
            unrolledShift(b, (int) k.bits());
            return;
        }
        // X = the shift count, at most the width (shifting further changes nothing: all 0, or all sign)
        if (y instanceof Value.Const k) {
            ins("ldx", imm(Math.min(k.bits(), bits)));
        } else {
            String have = label();
            String big = label();
            if (words(y.type()) == 2) {
                load(operand(y, 1));
                ins("bne", big);
            }
            ins("lda", operand(y, 0));
            ins("cmp", imm(bits));
            ins("bcc", have);
            place(big);
            ins("lda", imm(bits));
            place(have);
            ins("tax", null);
        }
        String loop = label();
        String done = label();
        if (words(t) == 1) {
            if (b.op == Instr.BinOp.ASHR) {
                loadSigned16(x, t);
            } else {
                ins("lda", operand(x, 0));
            }
            ins("cpx", imm(0));
            ins("beq", done);
            place(loop);
            switch (b.op) {
                case SHL -> ins("asl", "a");
                case LSHR -> ins("lsr", "a");
                default -> {
                    ins("cmp", imm(0x8000));    // C = the sign
                    ins("ror", "a");
                }
            }
            ins("dex", null);
            ins("bne", loop);
            place(done);
            if (b.op != Instr.BinOp.LSHR && !staysByte(b)) {
                mask(t);   // (a right shift of a zero-extended value stays zero-extended)
            }
            result(b, 0);
        } else {
            String lo = dest(b, 0);
            String hi = dest(b, 1);
            to32(x, lo, hi);
            ins("cpx", imm(0));
            ins("beq", done);
            place(loop);
            switch (b.op) {
                case SHL -> {
                    ins("asl", lo);
                    ins("rol", hi);
                }
                case LSHR -> {
                    ins("lsr", hi);
                    ins("ror", lo);
                }
                default -> {
                    ins("lda", hi);
                    ins("cmp", imm(0x8000));
                    ins("ror", hi);
                    ins("ror", lo);
                }
            }
            ins("dex", null);
            ins("bne", loop);
            place(done);
        }
    }

    private void unrolledShift(Instr.Bin b, int count) {
        MType t = b.type();
        Value x = b.op(0);
        if (words(t) == 1) {
            if (b.op == Instr.BinOp.ASHR) {
                loadSigned16(x, t);
            } else {
                ins("lda", operand(x, 0));
            }
            if (t == MType.I16 && count >= 8) {
                // the bytes swap places: a shift by 8 in two instructions
                ins("xba", null);
                switch (b.op) {
                    case SHL -> ins("and", imm(0xFF00));
                    case LSHR -> ins("and", imm(0x00FF));
                    default -> {
                        ins("and", imm(0x00FF));
                        ins("eor", imm(0x0080));   // sign-extend the byte
                        ins("sec", null);
                        ins("sbc", imm(0x0080));
                    }
                }
                count -= 8;
            }
            for (int k = 0; k < count; k++) {
                switch (b.op) {
                    case SHL -> ins("asl", "a");
                    case LSHR -> ins("lsr", "a");
                    default -> {
                        ins("cmp", imm(0x8000));    // C = the sign
                        ins("ror", "a");
                    }
                }
            }
            if (b.op != Instr.BinOp.LSHR && !staysByte(b)) {
                mask(t);
            }
            result(b, 0);
            return;
        }
        String lo = dest(b, 0);
        String hi = dest(b, 1);
        to32(x, lo, hi);
        for (int k = 0; k < count; k++) {
            switch (b.op) {
                case SHL -> {
                    ins("asl", lo);
                    ins("rol", hi);
                }
                case LSHR -> {
                    ins("lsr", hi);
                    ins("ror", lo);
                }
                default -> {
                    ins("lda", hi);
                    ins("cmp", imm(0x8000));
                    ins("ror", hi);
                    ins("ror", lo);
                }
            }
        }
    }

    private void cmp(Instr.Cmp c) {
        switch (c.pred) {
            case EQ, NE -> {
                String differ = label();
                String done = label();
                eqFlags(c, differ);
                ins("bne", differ);
                ins("lda", imm(c.pred == Instr.Pred.EQ ? 1 : 0));
                ins("bra", done);
                place(differ);
                ins("lda", imm(c.pred == Instr.Pred.EQ ? 0 : 1));
                place(done);
                result(c, 0);
            }
            default -> {
                boolean less = orderedFlags(c).equals("bcc");
                ins("lda", imm(0));
                ins("rol", "a");          // A = C = x >= y
                if (less) {
                    ins("eor", imm(1));
                }
                result(c, 0);
            }
        }
    }

    /**
     * Compares the operands of an eq/ne word by word: every word but the last branches to
     * {@code differ} when it differs, the last one leaves Z set if it is equal.
     */
    private void eqFlags(Instr.Cmp c, String differ) {
        int n = words(c.op(0).type());
        for (int w = 0; w < n; w++) {
            if (!(c.op(1) instanceof Value.Const k && (k.bits() >> (16 * w) & 0xFFFF) == 0)) {
                ins("lda", operand(c.op(0), w));
                ins("cmp", operand(c.op(1), w));
            } else {
                load(operand(c.op(0), w));     // the load sets Z for a comparison with 0
            }
            if (w < n - 1) {
                ins("bne", differ);
            }
        }
    }

    /**
     * Sets the carry for an ordered comparison, C = x >= y (with x and y swapped for > and <=);
     * returns the branch that is taken when the comparison is true ("bcc" or "bcs").
     */
    private String orderedFlags(Instr.Cmp c) {
        MType t = c.op(0).type();
        int n = words(t);
        Value a = c.op(0);
        Value b = c.op(1);
        // x < y, or its negation; signed by flipping the sign bits, so an unsigned compare works
        boolean swap = switch (c.pred) {
            case UGT, ULE, SGT, SLE -> true;
            default -> false;
        };
        boolean wantLess = switch (c.pred) {
            case ULT, UGT, SLT, SGT -> true;
            default -> false;
        };
        boolean signed = switch (c.pred) {
            case SLT, SLE, SGT, SGE -> true;
            default -> false;
        };
        Value x = swap ? b : a;
        Value y = swap ? a : b;
        int top = n - 1;
        int bias = !signed ? 0 : t == MType.I8 ? 0x80 : t == MType.I1 ? 1 : 0x8000;
        String yTop = operand(y, top);
        if (bias != 0) {
            if (y instanceof Value.Const k) {
                yTop = imm((k.bits() >> (16 * top)) ^ bias);
            } else {
                ins("lda", operand(y, top));
                ins("eor", imm(bias));
                ins("sta", "z:R0");
                yTop = "z:R0";
            }
        }
        if (n == 2) {
            ins("lda", operand(x, 0));
            ins("cmp", operand(y, 0));
        }
        ins("lda", operand(x, top));
        if (bias != 0) {
            ins("eor", imm(bias));
        }
        ins(n == 2 ? "sbc" : "cmp", yTop);   // C = x >= y
        return wantLess ? "bcc" : "bcs";
    }

    private static String inverse(String branch) {
        return switch (branch) {
            case "bcc" -> "bcs";
            case "bcs" -> "bcc";
            case "beq" -> "bne";
            case "bne" -> "beq";
            case "bmi" -> "bpl";
            case "bpl" -> "bmi";
            default -> throw new IllegalArgumentException(branch);
        };
    }

    /** A branch on the comparison c without its truth value: flags, then the jump. */
    private void compareAndBranch(Instr.Cmp c, Block ifTrue, Block ifFalse, Block next) {
        if ((c.pred == Instr.Pred.SLT || c.pred == Instr.Pred.SGE) && c.op(0).type() == MType.I16
                && c.op(1) instanceof Value.Const k && k.bits() == 0) {
            load(operand(c.op(0), 0));   // N is the sign
            jumpOn(c.pred == Instr.Pred.SLT ? "bmi" : "bpl", null, false, ifTrue, ifFalse, next);
            return;
        }
        if (c.pred == Instr.Pred.EQ || c.pred == Instr.Pred.NE) {
            String differ = label();
            eqFlags(c, differ);
            jumpOn(c.pred == Instr.Pred.EQ ? "beq" : "bne", differ, c.pred == Instr.Pred.NE, ifTrue, ifFalse, next);
        } else {
            jumpOn(orderedFlags(c), null, false, ifTrue, ifFalse, next);
        }
    }

    /**
     * Goes to ifTrue if the branch {@code taken} would be taken, else to ifFalse. Blocks may be
     * far away, so the conditional branch only skips a JMP. {@code differ}, if not null, is a
     * label earlier code branches to; it means true if {@code differMeansTrue}, else false.
     */
    private void jumpOn(String taken, String differ, boolean differMeansTrue, Block ifTrue, Block ifFalse, Block next) {
        int site = jumpSites++;
        if (near == null) {
            sitePos.add(out.position());   // the first pass: where the branch is
            siteTargets.add(new Block[] {ifTrue, ifFalse});
        } else if (differ == null) {
            boolean t = near.get(site)[0];
            boolean f = near.get(site)[1];
            // a short branch straight to the block where it reaches
            if (ifFalse == next && t) {
                ins(taken, ifTrue.name());
                return;
            }
            if (ifTrue == next && f) {
                ins(inverse(taken), ifFalse.name());
                return;
            }
            if (ifTrue != next && ifFalse != next && t) {
                ins(taken, ifTrue.name());
                ins("jmp", ifFalse.name());
                return;
            }
        }
        String skip = label();
        if (ifFalse == next) {
            ins(inverse(taken), skip);
            if (differ != null && differMeansTrue) {
                place(differ);
            }
            ins("jmp", ifTrue.name());
            place(skip);
            if (differ != null && !differMeansTrue) {
                place(differ);
            }
            return;
        }
        ins(taken, skip);
        if (differ != null && !differMeansTrue) {
            place(differ);
        }
        ins("jmp", ifFalse.name());
        place(skip);
        if (differ != null && differMeansTrue) {
            place(differ);
        }
        if (ifTrue != next) {
            ins("jmp", ifTrue.name());
        }
    }

    private void cast(Instr.Cast c) {
        MType from = c.op(0).type();
        MType to = c.type();
        if (c.kind == Instr.CastKind.SEXT) {
            loadSigned16(c.op(0), from);
            if (words(to) == 1) {
                mask(to);
                result(c, 0);
            } else {
                storeWithSign(dest(c, 0), dest(c, 1));
            }
            return;
        }
        // zext, trunc, ptrtoint, inttoptr: the low bits, zero-extended
        ins("lda", operand(c.op(0), 0));
        mask(to);
        result(c, 0);
        if (words(to) == 2) {
            if (words(from) == 2) {
                ins("lda", operand(c.op(0), 1));
                result(c, 1);
            } else {
                ins("stz", dest(c, 1));
            }
        }
    }

    /** An address as a base plus a constant, non-negative offset (folded into Y or the operand). */
    private FrameLayout.Address address(Value v) {
        return layout.address(v);
    }

    private static boolean foldable(Instr.PtrAdd p) {
        return FrameLayout.foldable(p);
    }

    /**
     * The operand for a memory access at byte k of the object, or null if it goes through a
     * pointer. An indexed one loads X with the index first.
     */
    private String fixedAt(FrameLayout.Address a, int k) {
        String f = fixed(a.base(), a.offset() + k);
        if (f == null || a.index() == null) {
            return f;
        }
        ins("ldx", operand(a.index(), 0));
        return f + ",x";
    }

    /** Whether the access goes to a fixed or indexed address (no pointer); emits nothing. */
    private boolean isFixed(FrameLayout.Address a) {
        return fixed(a.base(), a.offset()) != null;
    }

    private void load(Instr.Load l) {
        MType t = l.type();
        FrameLayout.Address a = address(l.op(0));
        boolean direct = isFixed(a);
        String via = direct ? null : via(a.base(), P);
        if (t.size == 1) {
            if (!direct) {
                ins("ldy", imm(a.offset()));
            }
            if (l.isVolatile) {
                // exactly one byte: reading an I/O register can have an effect
                ins("sep", "#$20");
                ins("lda", direct ? fixedAt(a, 0) : via);
                ins("rep", "#$20");
            } else {
                // a 16-bit read and a mask is cheaper than switching to 8 bits; the byte after the
                // object is always in the same bank (the last byte of every bank stays free)
                ins("lda", direct ? fixedAt(a, 0) : via);
            }
            ins("and", imm(t == MType.I1 ? 1 : 0xFF));   // (after an 8-bit read, B still has an old value)
            result(l, 0);
            return;
        }
        for (int w = 0; w < words(t); w++) {
            if (!direct) {
                ins("ldy", imm(a.offset() + 2 * w));
            }
            ins("lda", direct ? fixedAt(a, 2 * w) : via);
            result(l, w);
        }
    }

    private void store(Instr.Store s) {
        FrameLayout.Address a = address(s.op(0));
        Value v = s.op(1);
        MType t = v.type();
        boolean direct = isFixed(a);
        String via = direct ? null : via(a.base(), P);
        if (t.size == 1) {
            ins("lda", operand(v, 0));
            if (!direct) {
                ins("ldy", imm(a.offset()));
            }
            ins("sep", "#$20");
            ins("sta", direct ? fixedAt(a, 0) : via);
            ins("rep", "#$20");
            return;
        }
        for (int w = 0; w < words(t); w++) {
            ins("lda", operand(v, w));
            if (!direct) {
                ins("ldy", imm(a.offset() + 2 * w));
            }
            ins("sta", direct ? fixedAt(a, 2 * w) : via);
        }
    }

    /**
     * The [dp],y operand for a pointer: its own place if that is in the direct page, else
     * dampf_ptr (or dampf_ptr+4) after copying it there, unless it is there already.
     */
    private String via(Value pointer, String dp) {
        while (pointer instanceof Instr i && FrameLayout.transparent(i)) {
            pointer = i.op(0);   // the same bytes
        }
        if (pointer instanceof Value.Param || pointer instanceof Instr) {
            int p = place.get(pointer);
            if (FRAME_START + p + 3 < 0x100) {
                return "[" + FRAME + "+" + p + "],y";
            }
        }
        boolean first = dp.equals(P);
        if ((first ? ptrP : ptrQ) != pointer || pointer instanceof Value.Undef) {
            pointer(pointer, dp);
            if (first) {
                ptrP = pointer;
            } else {
                ptrQ = pointer;
            }
        }
        return first ? VIA_P : VIA_Q;
    }

    private void copy(Instr.Copy c) {
        pointer(c.op(1), P);
        pointer(c.op(0), Q);
        ptrP = c.op(1);
        ptrQ = c.op(0);
        int size = c.size;
        if (size % 2 == 1) {
            ins("ldy", imm(size - 1));
            ins("sep", "#$20");
            ins("lda", VIA_P);
            ins("sta", VIA_Q);
            ins("rep", "#$20");
        }
        if (size >= 2) {
            String loop = label();
            ins("ldy", imm((size & ~1) - 2));
            place(loop);
            ins("lda", VIA_P);
            ins("sta", VIA_Q);
            ins("dey", null);
            ins("dey", null);
            ins("bpl", loop);
        }
    }

    private void call(Instr.Call c) {
        MFunction callee = module.function(c.callee);
        if (callee == null) {
            throw new IllegalStateException("call of " + c.callee + ", which has no MIR");
        }
        for (int k = 0; k < c.operands().size(); k++) {
            Value.Param p = callee.params().get(k);
            for (int w = 0; w < words(p.type()); w++) {
                ins("lda", operand(c.op(k), w));
                ins("sta", at(place.get(p) + 2 * w));
            }
        }
        ins("jsl", c.callee);
        if (c.type() != MType.VOID) {
            result(c, 0);
            if (words(c.type()) == 2) {
                ins("stx", dest(c, 1));
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * What A holds for sure (an operand string: a frame place or an immediate), so a load of the
     * same thing can be left out; null if unknown. Forgotten at labels, calls and stores through
     * pointers, and when an instruction changes A or the place.
     */
    private String aHolds;
    /** The pointers in dampf_ptr and dampf_ptr+4 (forgotten like aHolds), and what Y holds. */
    private Value ptrP;
    private Value ptrQ;
    private String yHolds;
    /** What X holds (a frame place or a constant), like aHolds. */
    private String xHolds;
    /** The accumulator is 8 bit (between SEP #$20 and REP #$20). */
    private boolean m8;

    /**
     * A REP #$20 and an AND #$00FF held back for one instruction: if SEP #$20 comes next (after
     * at most an LDY), all of them go, and the code stays 8 bit between a byte load and a byte
     * store. (A value is masked to 8 bits only for code that reads all 16; a byte store does not,
     * and after SEP nothing assumes what A holds.)
     */
    private boolean heldRep;
    private boolean heldAnd;

    private void flushHeld() {
        if (heldRep) {
            out.ins("rep", "#$20", null);
        }
        if (heldAnd) {
            out.ins("and", imm(0xFF), null);
        }
        heldRep = false;
        heldAnd = false;
    }

    private void ins(String mnemonic, String operand) {
        if (mnemonic.equals("rep") && "#$20".equals(operand)) {
            flushHeld();
            heldRep = true;
            track(mnemonic, operand);
            return;
        }
        if (mnemonic.equals("and") && imm(0xFF).equals(operand) && !m8) {
            heldAnd = true;
            track(mnemonic, operand);
            return;
        }
        if (mnemonic.equals("and") && heldAnd && !heldRep && operand != null && operand.startsWith("#$")
                && Integer.parseInt(operand.substring(2), 16) <= 0xFF) {
            heldAnd = false;   // a narrower mask right after: the held one adds nothing
        }
        if (mnemonic.equals("sep") && "#$20".equals(operand) && (heldRep || heldAnd)) {
            boolean both = heldRep;
            heldRep = false;
            heldAnd = false;
            if (!both) {
                out.ins(mnemonic, operand, null);
            }
            track(mnemonic, operand);
            return;
        }
        if (mnemonic.equals("lda") && !m8 && operand != null && operand.equals(aHolds)) {
            return;   // A has it already (the flags are not set again: see load())
        }
        if (!mnemonic.equals("ldy")) {
            flushHeld();
        }
        if (mnemonic.equals("ldy") && operand != null && operand.equals(yHolds)) {
            return;
        }
        if (mnemonic.equals("ldx") && operand != null && operand.equals(xHolds)) {
            return;
        }
        out.ins(mnemonic, operand, null);
        track(mnemonic, operand);
    }

    /** A load that always happens, for code that branches on the flags it sets. */
    private void load(String operand) {
        flushHeld();
        out.ins("lda", operand, null);
        track("lda", operand);
    }

    private static boolean trackable(String operand) {
        return operand != null && (operand.contains(FRAME) && !operand.startsWith("[") || operand.startsWith("#"));
    }

    private void track(String mnemonic, String operand) {
        boolean writesMemory = switch (mnemonic) {
            case "sta", "stx", "sty", "stz", "inc", "dec", "asl", "lsr", "rol", "ror" -> operand != null && !operand.equals("a");
            default -> false;
        };
        if (writesMemory && (operand.startsWith("[") || operand.equals(xHolds))) {
            xHolds = null;
        }
        switch (mnemonic) {
            case "ldy" -> yHolds = operand != null && operand.startsWith("#") ? operand : null;
            case "ldx" -> xHolds = trackable(operand) ? operand : null;
            case "inx", "dex", "tax", "tsx", "plx" -> xHolds = null;
            case "iny", "dey", "tay", "ply" -> yHolds = null;
            case "jsl" -> {
                aHolds = null;
                yHolds = null;
                xHolds = null;
                ptrP = null;   // the called function uses dampf_ptr too
                ptrQ = null;
            }
            default -> { }
        }
        switch (mnemonic) {
            case "sep" -> {
                if ("#$20".equals(operand)) {
                    m8 = true;
                    aHolds = null;
                }
            }
            case "rep" -> {
                if ("#$20".equals(operand)) {
                    m8 = false;
                }
            }
            case "lda" -> aHolds = !m8 && trackable(operand) ? operand : null;
            case "sta" -> {
                if (operand != null && operand.startsWith("[")) {
                    aHolds = null;   // may write any frame place
                } else if (!m8 && trackable(operand)) {
                    aHolds = operand;
                } else if (operand != null && operand.equals(aHolds)) {
                    aHolds = null;
                }
            }
            case "stx", "sty", "stz", "cmp", "cpx", "cpy", "ldx", "ldy", "inx", "dex", "iny", "dey",
                 "bcc", "bcs", "beq", "bne", "bmi", "bpl", "bvc", "bvs", "bra", "jmp", "clc", "sec" -> {
                if (operand != null && operand.equals(aHolds) && mnemonic.startsWith("st")) {
                    aHolds = null;
                }
            }
            case "asl", "lsr", "rol", "ror", "inc", "dec" -> {
                if ("a".equals(operand) || operand != null && operand.equals(aHolds)) {
                    aHolds = null;
                }
            }
            default -> aHolds = null;   // adc, and, jsl, tax, rtl, ...: A (or everything) changes
        }
    }

    private void place(String label) {
        flushHeld();
        out.label(label);
        aHolds = null;   // something may jump here
        yHolds = null;
        xHolds = null;
        ptrP = null;
        ptrQ = null;
        m8 = false;
    }

    private String label() {
        return "@L" + (labels++);
    }

    private static String imm(long v) {
        return "#$" + hex(v & 0xFFFF, 4);
    }

    private static String hex(long v, int digits) {
        return String.format("%0" + digits + "X", v);
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s + " " : s + " ".repeat(width - s.length());
    }
}
