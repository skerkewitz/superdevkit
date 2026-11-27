package dampf.backend;

import dampf.ast.Interrupt;
import dampf.frontend.CompileError;
import dampf.frontend.SourcePos;
import dampf.lir.BranchRelax;
import dampf.lir.LOperand;
import dampf.lir.ByteStretches;
import dampf.lir.DeadStores;
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
import dampf.target.ContextAreas;
import dampf.target.SnesTarget;
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
 * <p><b>Machine state</b> as in the naive generator: A/X/Y 16 bit, DBR the target's data bank
 * ({@link SnesTarget#DATA_BANK}), JSL/RTL, a result in
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
 * any set of φs (also when they swap values), with no analysis. The copies are {@link
 * dampf.lir.LCopy}: where the colouring of the places on the code gives both sides of one the same
 * address (coalescing, ADR-6F8NBHZ6), it is no code.
 *
 * <p><b>Places</b> are named by their owners during selection; {@link FrameLayout} proposes
 * addresses for selection's decisions, and after selection {@link PlaceColouring} gives them their
 * addresses on the code, callees first ({@link #colourPlaces}), checked by {@link
 * dampf.lir.PlaceCheck} (ADR-6F8NBG4P).
 */
public final class MirCodeGen {
    /** What selection shares, and the selection of each kind of instruction. */
    private final Selection sel = new Selection(this);

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
        this(module, entry, debugName, blockCounts, dampf.util.PassLimit.configured(""));
    }

    /** {@code limit}: the LIR passes count their applications in it and stop at its limit. */
    public MirCodeGen(MModule module, String entry, java.util.function.Function<String, String> debugName,
                      java.util.function.BiFunction<String, String, Long> blockCounts, dampf.util.PassLimit limit) {
        this.blockCounts = blockCounts;
        this.limit = limit;
        this.module = module;
        this.entry = entry;
        this.debugName = debugName;
        for (MModule.Global g : module.globals()) {
            if (g.readOnly()) {
                sel.rom.add(g.symbol());
            }
        }
    }

    /**
     * The variables that live in WRAM's second bank, by symbol ({@link VariableBanks}), before
     * {@link #generate}: the code reaches them long, and a pointer to one carries bank $7F.
     */
    public MirCodeGen secondBank(Set<String> symbols) {
        sel.secondBank.clear();
        sel.secondBank.addAll(symbols);
        return this;
    }

    /** At most this many segments per assembly module: ca65 allows about 256 per object file. */
    private static final int SEGMENTS_PER_MODULE = 200;

    /**
     * The interrupt context of each function, by label (DAMPF_INTERRUPT, ADR-6FM9TXV4): those an
     * interrupt handler reaches; the main code's are not in it.
     */
    private Map<String, Interrupt> contextOf = Map.of();
    /** The handler of each interrupt, by label. */
    private Map<Interrupt, String> handlers = Map.of();
    /** Each interrupt context's direct page and the bytes of its frames, after generate(). */
    public final Map<Interrupt, Integer> directPage = new java.util.EnumMap<>(Interrupt.class);
    final Map<Interrupt, Integer> contextFrames = new java.util.EnumMap<>(Interrupt.class);

    /**
     * The program's interrupt handlers and the contexts of the functions they reach
     * ({@link dampf.check.CallGraph#contexts}), before {@link #generate}.
     */
    public MirCodeGen interrupts(Map<String, Interrupt> contexts, Map<Interrupt, String> handlerLabels) {
        this.contextOf = Map.copyOf(contexts);
        this.handlers = Map.copyOf(handlerLabels);
        return this;
    }

    /** f's interrupt context, null for the main code. */
    Interrupt context(MFunction f) {
        return contextOf.get(f.name());
    }

    /**
     * The symbol of the absolute addresses of f's frame places: {@code dampf_frame} for the main
     * code, {@code dampf_frame_irq} for the IRQ context's. Its direct-page operands are written
     * with {@code dampf_frame} in every context: they are relative to the direct page register,
     * which the entry stub sets to the context's page.
     */
    String frameSymbol(MFunction f) {
        Interrupt c = context(f);
        return c == null ? Selection.FRAME : Selection.FRAME + "_" + c.key;
    }

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
            for (MFunction c : callees.getOrDefault(f.name(), List.of())) {
                if (context(f) != null && context(c) != context(f)) {
                    throw new IllegalStateException(f.name() + " (" + context(f) + ") calls " + c.name() + " of another context");
                }
            }
        }
        Map<MFunction, AsmWriter> codes = new HashMap<>();
        Map<MFunction, List<Block>> orders = new HashMap<>();
        Map<MFunction, Ranges> ranges = new HashMap<>();
        for (MFunction f : module.functions()) {
            if (f.isAsm()) {
                continue;   // hand-written: no code
            }
            // selection writes the function once, assuming nothing about the registers where a
            // block starts and every jump and branch in its long form
            AsmWriter code = new AsmWriter();
            orders.put(f, function(f, code));
            codes.put(f, code);
            ranges.put(f, sel.layout.ranges);
        }
        // the places, on the code: a function's frame lies above the frames of all it calls
        for (MFunction f : module.functions()) {
            for (Value.Param p : f.params()) {
                paramOwner.put(p, f);
            }
        }
        frameTotal = 0;
        Set<MFunction> done = new HashSet<>();
        for (MFunction f : module.functions()) {
            colourPlaces(f, codes, done, new HashSet<>());
        }
        if (handlers.isEmpty() && frameTotal > SnesTarget.MAX_FRAME) {
            throw new IllegalStateException("the frames need " + frameTotal + " bytes, more than " + SnesTarget.MAX_FRAME);
        }
        if (!handlers.isEmpty()) {
            placeContexts();
        }
        for (MFunction f : module.functions()) {
            if (f.isAsm()) {
                continue;
            }
            AsmWriter code = codes.get(f);
            List<Block> order = orders.get(f);
            sel.absFrame = frameSymbol(f);
            String frame = sel.absFrame;
            // loads of what a register holds on every way in, then stores no path reads; then the
            // code is shorter, so more branches may reach
            // (each counts in the pass limit; BranchRelax does not, it is part of making the code)
            LFunction lf = code.function();
            parameterNotes(f, lf);
            assignPlaces(lf);
            checkPlaces(f, lf);
            lf.rebuild(lf.items().stream().filter(it -> !(it instanceof dampf.lir.LMark)).toList());
            boolean resultInA = f.result() != MType.VOID;
            limit.apply("RedundantLoads", f.name(), () -> new RedundantLoads(resultInA).run(lf));
            limit.apply("Peephole", f.name(), () -> new Peephole(frame).run(lf));
            limit.apply("ByteStretches", f.name(), () -> ByteStretches.run(lf));
            limit.apply("ByteLoops", f.name(), () -> dampf.lir.ByteLoops.run(lf, resultInA));
            limit.apply("KnownCarry", f.name(), () -> KnownCarry.run(lf));
            limit.apply("DeadStores", f.name(), () -> new DeadStores(ownValueBytes(f)).run(lf));
            BranchRelax.run(lf);
            Map<String, String> renamed = dampf.lir.LocalLabels.renumber(code.function(), "@L");
            Map<String, Integer> bounds = new HashMap<>();
            code.loopBounds().forEach((label, rounds) -> bounds.put(renamed.getOrDefault(label, label), rounds));
            written.put(f, new Written(code.function(), bounds, order, ranges.get(f)));
            if (code.unknownInstructions() > 0) {
                throw new IllegalStateException("the cycle table does not know an instruction in " + f.name());
            }
            if (f.checkOf() != null) {
                checkCode.add(code);   // the C body an assembly function is checked against: not in the ROM
                continue;
            }
            if (f == entryFunction) {
                entryBlock = banked.size();
            }
            banked.add(code);
        }
        AsmWriter ram = new AsmWriter();
        asmFrames(ram);
        globals(ram);

        List<String> modules = new ArrayList<>();
        int chunks = Math.max(1, (banked.size() + SEGMENTS_PER_MODULE - 1) / SEGMENTS_PER_MODULE);
        for (int m = 0; m < chunks; m++) {
            AsmWriter w = new AsmWriter();
            prelude(w, m, chunks);
            if (m == 0) {
                w.append(ram);
                w.blank();   // (as when the text was copied in)
                handlerExports(w);
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

    /** The code of the C bodies that assembly functions are checked against ({@link #checkModule}). */
    final List<AsmWriter> checkCode = new ArrayList<>();

    /**
     * One more module with the C bodies of the assembly functions (each under {@link
     * dampf.mir.Lower#CHECK_PREFIX} + its label), after {@link #generate}: linked with the program's
     * modules into the ROM in which dampf runs each assembly function against its C body
     * (DAMPF_ASM). Null without assembly functions.
     */
    public String checkModule() {
        if (checkCode.isEmpty()) {
            return null;
        }
        AsmWriter w = new AsmWriter();
        w.raw("; Generated by dampf from MIR: the C bodies of the assembly functions, for the check against them.");
        w.blank();
        w.raw("        .p816");
        w.raw("        .smart  off");
        w.raw("        .a16");
        w.raw("        .i16");
        w.raw("        .importzp dampf_regs, dampf_ptr");
        w.raw("        .import dampf_mul16, dampf_mul32, dampf_udiv16, dampf_udiv32, dampf_sdiv32");
        List<String> all = new ArrayList<>();
        module.functions().forEach(f -> all.add(f.name()));
        module.globals().forEach(g -> all.add(g.symbol()));
        for (int i = 0; i < all.size(); i += 8) {
            w.raw("        .global " + String.join(", ", all.subList(i, Math.min(all.size(), i + 8))));
        }
        for (int r = 0; r <= 14; r += 2) {
            w.raw("R" + r + (r < 10 ? "  " : " ") + "= dampf_regs+" + r);
        }
        w.raw(Selection.FRAME + " = $" + Selection.hex(SnesTarget.FRAME_START, 4));
        for (AsmWriter c : checkCode) {
            w.append(c);
            w.blank();
        }
        return w.toString();
    }

    /** The CPU address of a parameter's place (for the check against the C body), after {@link #generate}. */
    public int paramAddress(String function, int index) {
        MFunction f = module.function(function);
        return SnesTarget.FRAME_START + placeAt.get(new PlaceColouring.Place(f.params().get(index), false));
    }

    /**
     * The frames of the assembly functions (DAMPF_ASM): equates for their parameters and locals,
     * which the assembly imports. An assembly function is a leaf, so its frame starts at
     * dampf_frame; the convention lays the parameters out in their order, 2 bytes for 8 and 16
     * bits, 4 for 32 bits and pointers ({@link dampf.asm.AsmFunction#paramBytes}).
     */
    void asmFrames(AsmWriter w) {
        for (MFunction f : module.functions()) {
            if (!f.isAsm()) {
                continue;
            }
            w.blank();
            w.note(f.name() + ": hand-written assembly (DAMPF_ASM); its frame");
            int expected = 0;
            for (int k = 0; k < f.params().size(); k++) {
                Value.Param p = f.params().get(k);
                int at = placeAt.get(new PlaceColouring.Place(p, false));
                if (at != expected) {
                    throw new IllegalStateException("the parameter " + p + " of " + f.name() + " is at " + at + ", not " + expected);
                }
                w.raw(asmSymbols.get(f.name()).get(k) + " = " + Selection.FRAME + "+" + at + "           ; parameter " + p.type() + " " + p);
                expected += dampf.asm.AsmFunction.paramBytes(p.type().size);
            }
            w.raw(f.name() + dampf.asm.AsmFunction.LOCALS + " = " + Selection.FRAME + "+" + expected + "           ; its locals");
        }
    }

    /** The symbols of each assembly function's parameters (by its label), in order. */
    final Map<String, List<String>> asmSymbols = new HashMap<>();

    /**
     * Declares an assembly function's parameter symbols and its bounds in CPU cycles, from {@link
     * dampf.asm.AsmFunction}; before {@link #generate}.
     */
    public void asm(String label, List<String> paramSymbols, long best, long worst) {
        asmSymbols.put(label, List.copyOf(paramSymbols));
        bounds.put(label, new Timing.Bound(best, worst));
    }

    /**
     * Each interrupt context's direct page, below the stack ({@link ContextAreas}), and the check
     * that the main code's frames end below the lowest of them.
     */
    void placeContexts() {
        int mainEnd = 0;
        for (Interrupt c : handlers.keySet()) {
            contextFrames.put(c, 0);
        }
        for (MFunction f : module.functions()) {
            int end = placedBase.get(f) + placedSize.get(f);
            Interrupt c = context(f);
            if (c == null) {
                mainEnd = Math.max(mainEnd, end);
            } else {
                contextFrames.merge(c, end, Math::max);
            }
        }
        directPage.putAll(ContextAreas.pages(contextFrames));
        int room = ContextAreas.mainEnd(directPage) - SnesTarget.FRAME_START;
        if (mainEnd > room) {
            throw new IllegalStateException("the frames of the main code need " + mainEnd + " bytes, more than " + room
                    + " (the interrupt contexts take " + ContextAreas.describe(directPage) + ")");
        }
    }

    /** The symbols the runtime's interrupt entry stubs import: each handler and its direct page. */
    void handlerExports(AsmWriter w) {
        for (Map.Entry<Interrupt, String> h : new java.util.TreeMap<>(handlers).entrySet()) {
            String k = h.getKey().key;
            w.raw("        .export dampf_" + k + "_handler, dampf_" + k + "_dp");
            w.raw("dampf_" + k + "_handler = " + h.getValue() + "             ; the runtime's " + h.getKey() + " entry calls this");
            w.raw("dampf_" + k + "_dp = $" + Selection.hex(directPage.get(h.getKey()), 4)
                    + "                   ; with the direct page here");
            w.blank();
        }
    }

    /**
     * The frame offsets of the bytes of f's value places: its parameters, values and φ incoming
     * places (not its slots, whose address may be taken). Only f reads them, while it runs.
     */
    java.util.BitSet ownValueBytes(MFunction f) {
        java.util.BitSet own = new java.util.BitSet();
        for (Value.Param p : f.params()) {
            int at = placeAt.get(new PlaceColouring.Place(p, false));
            own.set(at, at + FrameLayout.bytes(p.type()));
        }
        for (Block b : f.blocks()) {
            for (Instr i : b.instrs()) {
                for (boolean incoming : new boolean[] {false, true}) {
                    Integer at = placeAt.get(new PlaceColouring.Place(i, incoming));
                    if (at != null && !argumentOf.containsKey(i)) {
                        own.set(at, at + FrameLayout.bytes(i.type()));
                    }
                }
            }
        }
        return own;
    }

    /** The address of every place, after {@link #colourPlaces}. */
    final Map<PlaceColouring.Place, Integer> placeAt = new HashMap<>();
    /** A value computed in a callee's parameter place ({@link #argumentsInPlace}): that parameter. */
    final Map<Instr, Value.Param> argumentOf = new IdentityHashMap<>();
    /** The function each parameter belongs to. */
    final Map<Value.Param, MFunction> paramOwner = new IdentityHashMap<>();
    /** Where each function's frame begins, after colouring. */
    final Map<MFunction, Integer> placedBase = new HashMap<>();
    final Map<MFunction, Integer> placedSize = new HashMap<>();

    /**
     * Gives the places of f their addresses on its code ({@link PlaceColouring}), after those of
     * every function it calls: its frame begins where the highest of theirs ends.
     */
    void colourPlaces(MFunction f, Map<MFunction, AsmWriter> codes, Set<MFunction> done, Set<MFunction> path) {
        if (done.contains(f)) {
            return;
        }
        if (!path.add(f)) {
            throw new IllegalStateException("recursion through " + f.name());
        }
        int b = 0;
        for (MFunction c : callees.getOrDefault(f.name(), List.of())) {
            colourPlaces(c, codes, done, path);
            b = Math.max(b, placedBase.get(c) + placedSize.get(c));
        }
        path.remove(f);
        FrameLayout l = layouts.get(f);
        int newBase = b;
        if (f.isAsm()) {
            // hand-written: its parameters and locals at their convention's offsets, from dampf_frame
            for (Value.Param p : f.params()) {
                placeAt.put(new PlaceColouring.Place(p, false), newBase + l.offset.get(p));
            }
            for (Value.Slot s : f.slots()) {
                placeAt.put(new PlaceColouring.Place(s, false), newBase + l.offset.get(s));
            }
            placedBase.put(f, newBase);
            placedSize.put(f, l.slotsEnd);
            frameTotal = Math.max(frameTotal, newBase + l.slotsEnd);
            done.add(f);
            return;
        }
        java.util.Set<Object> own = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        own.addAll(f.params());
        for (Block blk : f.blocks()) {
            own.addAll(blk.instrs());
        }
        PlaceColouring.Frame frame = new PlaceColouring.Frame() {
            @Override
            public Integer fixed(PlaceColouring.Place p) {
                if (p.key() instanceof Value.Param q) {
                    MFunction g = paramOwner.get(q);
                    return (g == f ? newBase : placedBase.get(g)) + layouts.get(g).offset.get(q);
                }
                if (p.key() instanceof Value.Slot s) {
                    return newBase + l.offset.get(s);
                }
                Value.Param q = argumentOf.get((Instr) p.key());
                if (q != null) {
                    MFunction g = paramOwner.get(q);
                    return placedBase.get(g) + layouts.get(g).offset.get(q);
                }
                return null;
            }

            @Override
            public boolean external(PlaceColouring.Place p) {
                return p.key() instanceof Value.Param q && paramOwner.get(q) != f
                        || p.key() instanceof Instr i && argumentOf.containsKey(i);
            }

            @Override
            public int bytes(PlaceColouring.Place p) {
                return switch (p.key()) {
                    case Value.Slot s -> Math.max(1, s.size());
                    case Value v -> FrameLayout.bytes(v.type());
                    default -> throw new IllegalStateException("a place of " + p.key());
                };
            }
        };
        LFunction lf = codes.get(f).function();
        java.util.function.Predicate<LOperand.Owner> tracked = o -> own.contains(o.key());
        report(f, dampf.lir.PlaceCheck.written(lf, tracked, o -> o.key() instanceof Value.Param));
        Map<String, Double> weights = Weights.of(f, blockCounts == null ? null : name -> blockCounts.apply(f.name(), name));
        RegisterChoice.run(lf, tracked, frame, Weights.of(lf, weights), Selection.words(f.result()) == 2,
                f.result() != MType.VOID, Weights.loops(f, lf), limit);
        Map<PlaceColouring.Place, Integer> at = PlaceColouring.colour(lf, tracked, frame, newBase + l.slotsEnd, limit);
        int end = newBase + l.slotsEnd;
        for (Map.Entry<PlaceColouring.Place, Integer> e : at.entrySet()) {
            if (own.contains(e.getKey().key()) && !(e.getKey().key() instanceof Instr i && argumentOf.containsKey(i))) {
                end = Math.max(end, e.getValue() + frame.bytes(e.getKey()));
            }
            placeAt.putIfAbsent(e.getKey(), e.getValue());
            if (own.contains(e.getKey().key())) {
                placeAt.put(e.getKey(), e.getValue());
            }
        }
        for (Value.Param p : f.params()) {
            placeAt.put(new PlaceColouring.Place(p, false), newBase + l.offset.get(p));
        }
        for (Value.Slot s : f.slots()) {
            placeAt.put(new PlaceColouring.Place(s, false), newBase + l.offset.get(s));
        }
        placedBase.put(f, newBase);
        placedSize.put(f, end - newBase);
        frameTotal = Math.max(frameTotal, end);
        done.add(f);
    }

    /** The notes that say where f's parameters are, at the start of its code. */
    void parameterNotes(MFunction f, LFunction lf) {
        List<dampf.lir.LItem> items = new ArrayList<>();
        for (Value.Param p : f.params()) {
            items.add(dampf.lir.LDirective.note("parameter " + p.type() + " " + p + " at "
                    + sel.at(placeAt.get(new PlaceColouring.Place(p, false))).text()));
        }
        items.addAll(lf.items());
        lf.rebuild(items);
    }

    void prelude(AsmWriter w, int module, int modules) {
        w.raw("; Generated by dampf from MIR" + (modules > 1 ? " (module " + (module + 1) + " of " + modules + ")" : "") + ".");
        w.raw("; A/X/Y are 16 bit (A goes to 8 bit where SEP #$20 says so), DBR = $" + Selection.hex(SnesTarget.DATA_BANK, 2)
                + "; every value has a fixed place in dampf_frame.");
        w.blank();
        w.raw("        .p816");
        w.raw("        .smart  off");
        w.raw("        .a16");
        w.raw("        .i16");
        w.raw("        .importzp dampf_regs, dampf_ptr");
        w.raw("        .import dampf_mul16, dampf_mul32, dampf_udiv16, dampf_udiv32, dampf_sdiv32");
        if (!handlers.isEmpty()) {
            w.raw("        .import dampf_mul16_soft, dampf_mul32_soft");
        }
        if (modules > 1 || !asmSymbols.isEmpty()) {
            // (the assembly of DAMPF_ASM imports the globals and its parameters' places)
            List<String> all = new ArrayList<>();
            this.module.functions().stream().filter(f -> f.checkOf() == null).forEach(f -> all.add(f.name()));
            this.module.globals().forEach(g -> all.add(g.symbol()));
            for (MFunction f : this.module.functions()) {
                if (f.isAsm()) {
                    all.addAll(asmSymbols.get(f.name()));
                    all.add(f.name() + dampf.asm.AsmFunction.LOCALS);
                }
            }
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
        w.raw(Selection.FRAME + " = $" + Selection.hex(SnesTarget.FRAME_START, 4) + "            ; the frames (compiled stack), $"
                + Selection.hex(SnesTarget.FRAME_START, 4) + "-$" + Selection.hex(SnesTarget.STACK - 1, 4) + "; below $0100 in the direct page");
        for (Map.Entry<Interrupt, Integer> e : directPage.entrySet()) {
            w.raw(Selection.FRAME + "_" + e.getKey().key + " = $" + Selection.hex(e.getValue() + SnesTarget.FRAME_START, 4)
                    + "        ; the " + e.getKey() + " context's frames, absolute; its direct page is $" + Selection.hex(e.getValue(), 4));
        }
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
    final dampf.util.PassLimit limit;

    /**
     * Best and worst case in CPU cycles per function (by label), after generate(); see {@link Timing}.
     * -1 for a worst case without a bound, and for the best case of a function that never returns.
     */
    public Map<String, long[]> timing() {
        Map<String, long[]> out = new TreeMap<>();
        for (MFunction f : module.functions()) {
            if (f.checkOf() != null) {
                continue;   // not in the ROM
            }
            Timing.Bound b = bound(f.name(), new HashSet<>());
            out.put(f.name(), new long[] {b.best() < Timing.UNBOUNDED ? b.best() : -1, b.bounded() ? b.worst() : -1});
        }
        Timing.RUNTIME.forEach((name, b) -> out.put(name, new long[] {b.best(), b.worst()}));
        if (!handlers.isEmpty()) {
            Timing.RUNTIME_SOFT.forEach((name, b) -> out.put(name, new long[] {b.best(), b.worst()}));
            contextTiming().forEach((name, b) -> out.put(name, new long[] {b.best() < Timing.UNBOUNDED ? b.best() : -1,
                    b.bounded() ? b.worst() : -1}));
        }
        return out;
    }

    /**
     * Each interrupt, by the label of its entry stub ({@code dampf_irq}): from the interrupt being
     * taken to the end of its RTI, the stub's cycles and its handler's bounds. An NMI that comes
     * while the IRQ handler runs adds its own.
     */
    public Map<String, Timing.Bound> contextTiming() {
        Map<String, Timing.Bound> out = new TreeMap<>();
        handlers.forEach((c, label) -> {
            long stub = Timing.INTERRUPT_ENTRY.get(c) + Timing.INTERRUPT_EXIT;
            out.put("dampf_" + c.key, new Timing.Bound(stub, stub).plus(bound(label, new HashSet<>())));
        });
        return out;
    }

    /**
     * Why a function has no worst case (by label, only for those): the first loop in it without a
     * bound ("loop at file:line"), else the first call of a function without one ("calls f").
     */
    public Map<String, String> unboundedWhy() {
        Map<String, String> out = new TreeMap<>();
        for (MFunction f : module.functions()) {
            if (f.isAsm() || f.checkOf() != null || bound(f.name(), new HashSet<>()).bounded()) {
                continue;
            }
            Ranges r = written.get(f).ranges();
            SourcePos first = null;
            for (dampf.mir.CountedLoops.Loop loop : dampf.mir.CountedLoops.find(f)) {
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
        if (runtime == null) {
            runtime = Timing.RUNTIME_SOFT.get(name);
        }
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
                            argumentOf.put(v, p);
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
            String segment = g.readOnly() ? NaiveCodeGen.romSegment(g.symbol())
                    : NaiveCodeGen.ramSegment(zero, sel.secondBank.contains(g.symbol()));
            w.blank();
            w.raw("        .segment \"" + segment + "\"");
            if (zero && !g.readOnly()) {
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
                if (sel.rom.contains(a.symbol()) || sel.secondBank.contains(a.symbol())) {
                    w.raw("        .word   .loword(" + target + ")");
                    w.raw("        .byte   ^(" + target + "), 0");
                } else {
                    w.raw("        .word   " + target);
                    w.raw("        .byte   $" + Selection.hex(SnesTarget.VARIABLE_BANK, 2) + ", 0                  ; bank $"
                            + Selection.hex(SnesTarget.VARIABLE_BANK, 2) + ": RAM");
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
        sel.absFrame = frameSymbol(f);
        sel.interrupt = context(f) != null;
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
        sel.forward.removeAll(sel.xBounds);
        List<Block> blocks = layoutOrder(f, blockCounts == null ? null : blockName -> blockCounts.apply(f.name(), blockName));
        List<SwitchSelection.Written> tables = new ArrayList<>();
        for (int k = 0; k < blocks.size(); k++) {
            Block b = blocks.get(k);
            Block next = k + 1 < blocks.size() ? blocks.get(k + 1) : null;
            sel.place(b.name());
            sel.xTaken = sel.xBlocks.get(b);
            for (Instr i : b.instrs()) {
                if (i instanceof Instr.Phi p) {
                    sel.out.note(Printer.instr(p));
                    if (sel.xResident.containsKey(p)) {
                        continue;   // kept in X
                    }
                    for (int w = 0; w < Selection.words(p.type()); w++) {
                        sel.copy(sel.placeOf(p, w), sel.incomingOf(p, w));
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
                SwitchSelection.Table table = i instanceof Instr.Switch s ? sel.switches.plan(s, b) : null;
                if (table != null) {
                    // the φ copies, but X for a successor's loop counter on the way there
                    Map<Block, Value> xInit = sel.switches.xInits(b, i);
                    phiCopies(b, i, false);
                    tables.add(sel.switches.table((Instr.Switch) i, b, table, xInit));
                    continue;
                }
                if (i.isTerminator()) {
                    phiCopies(b, i, true);
                }
                instr(i, next);
            }
        }
        sel.flushHeld();
        for (SwitchSelection.Written t : tables) {
            // the jump tables of its switches, in the function's segment: JMP (abs,X) reads them
            // from the bank of the code (C lines: the switch's)
            if (t.pos() != null) {
                sourceLine(t.pos());
            }
            sel.out.label(t.label());
            for (int k = 0; k < t.entries().size(); k += 4) {
                List<String> words = new ArrayList<>();
                for (String e : t.entries().subList(k, Math.min(t.entries().size(), k + 4))) {
                    words.add(".loword(" + e + ")");
                }
                sel.out.raw("        .word   " + String.join(", ", words));
            }
        }
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
        // the latches of each block's loops: the way to the next round is the likely one (a
        // filter loop's continue), as in Ball and Larus' loop heuristic
        Map<Block, Set<Block>> latchesOf = new HashMap<>();
        for (dampf.mir.Loops.Loop loop : dampf.mir.Loops.find(f)) {
            for (Block x : loop.blocks()) {
                latchesOf.computeIfAbsent(x, k -> new HashSet<>()).addAll(loop.latches());
            }
        }
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
                    // with a profile: the successor that ran more often; without: the way to the next
                    // round of a loop, then one only this block reaches (the next test of an ||),
                    // since a block with other ways in needs a jump from somewhere anyway
                    boolean better;
                    if (next == null) {
                        better = true;
                    } else if (counts != null) {
                        better = count(counts, s) > count(counts, next);
                    } else {
                        Set<Block> latches = latchesOf.getOrDefault(b, Set.of());
                        int rs = latches.contains(s) ? 2 : s.preds().size() == 1 ? 1 : 0;
                        int rn = latches.contains(next) ? 2 : next.preds().size() == 1 ? 1 : 0;
                        better = rs > rn;
                    }
                    if (better) {
                        next = s;
                    }
                }
            }
            b = next;
        }
        rotateLoops(f, order);
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
     * Gives every frame operand its address from its owner (ADR-6F8NBFAQ): word w of a value, a
     * φ's incoming place, a frame slot, as {@link #colourPlaces} placed them, and expands the φ
     * copies ({@link dampf.lir.LCopy}). An operand of the frame without an owner is a bug of
     * selection.
     */
    void assignPlaces(LFunction lf) {
        // the φ copies: LDA, STA, or nothing where both places got the same address
        List<dampf.lir.LItem> expanded = new ArrayList<>();
        for (dampf.lir.LItem it : lf.items()) {
            if (it instanceof dampf.lir.LCopy c) {
                LOperand to = assign(c.to());
                LOperand from = assign(c.from());
                if (!to.equals(from)) {
                    expanded.add(new dampf.lir.LInstr("lda", from, null, true, true, false));
                    expanded.add(new dampf.lir.LInstr("sta", to, null, true, true, false));
                } else {
                    // one place: for the check, the bytes are the other owner's now
                    expanded.add(new dampf.lir.LMark(List.of(new dampf.lir.LMark.Copy(c.to().owner(), c.from().owner(), 2))));
                }
            } else {
                expanded.add(it);
            }
        }
        lf.rebuild(expanded);
        for (dampf.lir.LBlock b : lf.blocks()) {
            List<dampf.lir.LItem> items = b.items();
            for (int k = 0; k < items.size(); k++) {
                if (!(items.get(k) instanceof dampf.lir.LInstr in) || in.op() == null) {
                    continue;
                }
                items.set(k, in.withOperand(assign(in.op())));
            }
        }
    }

    /**
     * The places of f's values, φ incoming places and parameters against what its code reads and
     * writes ({@link dampf.lir.PlaceCheck}); a problem is a bug of the frame layout or of selection.
     */
    void checkPlaces(MFunction f, LFunction lf) {
        java.util.Set<Object> own = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        own.addAll(f.params());
        for (Block b : f.blocks()) {
            own.addAll(b.instrs());
        }
        report(f, dampf.lir.PlaceCheck.check(lf, o -> own.contains(o.key()),
                o -> o.key() instanceof dampf.mir.Value.Param));
    }

    private static void report(MFunction f, List<String> errors) {
        if (!errors.isEmpty()) {
            throw new IllegalStateException("the frame places of " + f.name() + " are wrong: " + String.join("; ",
                    errors.subList(0, Math.min(5, errors.size()))));
        }
    }

    /** The operand with the address its owner's place has (an operand without an owner as it is). */
    private LOperand assign(LOperand op) {
        switch (op) {
            case LOperand.Mem m when m.frame() -> {
                if (m.owner() == null) {
                    throw new IllegalStateException("a frame operand without an owner: " + m.text());
                }
                Integer base = placeAt.get(PlaceColouring.Place.of(m.owner()));
                if (base == null) {
                    throw new IllegalStateException("no place for " + m.text());
                }
                int at = base + m.owner().word();
                if (m.indirect()) {
                    return new LOperand.Mem(LOperand.Width.NONE, Selection.FRAME, at, m.index(), true, m.owner());
                }
                return sel.at(at).indexed(m.index()).owned(m.owner());
            }
            case LOperand.Imm i when i.expr().contains(Selection.FRAME) -> {
                if (i.owner() == null) {
                    throw new IllegalStateException("a frame address without an owner: " + i.text());
                }
                return new LOperand.Imm(".loword(" + sel.absFrame + "+" + (placeAt.get(PlaceColouring.Place.of(i.owner())) + i.owner().word()) + ")",
                        i.owner());
            }
            default -> {
                return op;
            }
        }
    }

    /**
     * A small loop head (only φs, pointer sums, a comparison and a branch) goes right after the block that
     * jumps back to it: that block falls through into the test, and the test branches back into
     * the loop. Per round one branch instead of a jump and a branch.
     */
    static void rotateLoops(MFunction f, List<Block> order) {
        // the latch of each loop head: the one block with the edge back to it
        Map<Block, List<Block>> latches = new HashMap<>();
        for (dampf.mir.Loops.Loop loop : dampf.mir.Loops.find(f)) {
            latches.put(loop.head(), loop.latches());
        }
        for (Block h : List.copyOf(order)) {
            if (h == order.getFirst() || !(h.terminator() instanceof Instr.Branch) || !onlyTest(h)) {
                continue;
            }
            List<Block> back = latches.getOrDefault(h, List.of());
            Block latch = back.size() == 1 && back.getFirst().terminator() instanceof Instr.Jump ? back.getFirst() : null;
            if (latch == null || order.indexOf(latch) + 1 < order.size() && order.get(order.indexOf(latch) + 1) == h) {
                continue;
            }
            order.remove(h);
            order.add(order.indexOf(latch) + 1, h);
        }
    }

    static boolean onlyTest(Block h) {
        for (Instr i : h.instrs()) {
            // a pointer sum: mostly no code (the loads and stores add the offset themselves),
            // left behind by PointerOffsets where the pointer was a φ of the head
            if (!(i instanceof Instr.Phi || i instanceof Instr.Cmp || i instanceof Instr.PtrAdd || i.isTerminator())) {
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

    /**
     * Before a block's jump: every φ of every successor gets this block's operand; {@code loadX}:
     * also X for a successor's φ that lives in X (else the jump does that on its way there).
     */
    void phiCopies(Block b, Instr terminator, boolean loadX) {
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
                for (int w = 0; w < Selection.words(p.type()); w++) {
                    LOperand to = sel.incomingOf(p, w);
                    if (sel.operand(v, w).equals(Selection.imm(0)) && !sel.aHolds.contains(Selection.imm(0))) {
                        sel.ins("stz", to);   // no load of the zero
                        continue;
                    }
                    // (a value A holds, perhaps handed on and never stored, is stored from A)
                    if (sel.operand(v, w) instanceof LOperand.Mem from && from.owner() != null && from.frame()
                            && to instanceof LOperand.Mem toPlace && !sel.aHolds.contains(from)) {
                        sel.copy(toPlace, from);
                        continue;
                    }
                    sel.ins("lda", sel.operand(v, w));
                    sel.ins("sta", to);
                }
            }
        }
        if (xInit != null && loadX) {
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
                sel.ins("clc");
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
                } else if (sel.operand(p.op(0), 1).equals(sel.dest(p, 1))) {
                    // the bank word is in p's place already: p shares it with its base
                    if (sel.operand(p.op(0), 1) instanceof LOperand.Mem from && from.owner() != null
                            && sel.dest(p, 1) instanceof LOperand.Mem to) {
                        sel.out.mark(List.of(new dampf.lir.LMark.Copy(to.owner(), from.owner(), 2)));
                    }
                } else {
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
            case Instr.Switch s -> sel.switches.chain(s, next);
            case Instr.Unreachable u -> throw new IllegalStateException("the checks of hints are taken out before code generation");
            case Instr.Ret r -> {
                if (!r.operands().isEmpty()) {
                    Value v = r.op(0);
                    if (Selection.words(v.type()) == 2) {
                        sel.ins("lda", sel.operand(v, 1));
                        sel.ins("tax");
                    }
                    sel.ins("lda", sel.operand(v, 0));
                }
                sel.ins("rtl");
            }
        }
    }
}
