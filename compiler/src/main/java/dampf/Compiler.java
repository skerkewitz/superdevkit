package dampf;

import dampf.ast.Function;
import dampf.ast.Program;
import dampf.ast.Type;
import dampf.backend.MirCodeGen;
import dampf.backend.NaiveCodeGen;
import dampf.mir.Lower;
import dampf.check.CallGraph;
import dampf.frontend.AstBuilder;
import dampf.frontend.CompileError;
import dampf.frontend.Parser;
import dampf.frontend.Preprocessor;
import dampf.frontend.SourcePos;
import dampf.frontend.SystemHeaders;
import dampf.snes.RomBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/** The whole pipeline: C files in, SNES ROM with debug information out. */
public final class Compiler {
    /**
     * @param entry        the function the runtime calls after start-up; must be {@code void name(void)}
     * @param output       the ROM to write; the assembly, .dbg and label files go next to it (with ca65, also ld65's map)
     * @param replacements functions linked from hand-written assembly instead of being compiled
     *                     (C name to .s file, {@code --replace}): as DAMPF_ASM in the source, which
     *                     they override, with its convention and checks (docs/hints.md)
     * @param blockCounts  how often each block ran, (function label, block name) → count, from a
     *                     profile ({@code dampf profile --blocks}); null without one. Only the order
     *                     of the blocks follows it, so a profile of other code makes the code
     *                     slower, never wrong.
     * @param fastRom      the ROM in banks $80-$FF runs at 3.58 MHz (FastROM, the default); false
     *                     for a SlowROM cartridge
     * @param ca65         assemble and link with ca65 and ld65 instead of dampf's own assembler and
     *                     linker (which build the same ROM; see ADR-6F3CJKHB)
     * @param sram         bytes of battery-backed save RAM the cartridge has (0: none): the header
     *                     announces it (rounded up to 2, 4, 8, 16 or 32 KB, at $70:0000 in LoROM),
     *                     and the preprocessor gets {@code SDEVKIT_SRAM_SIZE} with the exact number
     *                     for the kit's {@code sdevkit_sram()} (ADR-6F3CK03Z)
     * @param optLimit     how many pass applications may run ({@link dampf.util.PassLimit}, to find
     *                     the pass that broke a program); negative: the limit the environment sets,
     *                     else none
     * @param library      the sources (among {@code sources}) of a library ({@code --lib}): compiled
     *                     without {@code defines}, which are for the program's own files (ADR-6FEWEE95, ADR-6FKYXP8C)
     */
    public record Options(List<Path> sources, List<Path> includeDirs, List<String> defines, String entry, Path output,
                          Map<String, Path> replacements, Backend backend,
                          java.util.function.BiFunction<String, String, Long> blockCounts, boolean fastRom, boolean ca65,
                          int sram, long optLimit, java.util.Set<Path> library) {
        /** The largest save RAM of a LoROM cartridge that dampf announces: 32 KB at $70:0000. */
        public static final int MAX_SRAM = 32 * 1024;

        public Options(List<Path> sources, List<Path> includeDirs, List<String> defines, String entry, Path output,
                       Map<String, Path> replacements, Backend backend,
                       java.util.function.BiFunction<String, String, Long> blockCounts, boolean fastRom, boolean ca65,
                       int sram, long optLimit) {
            this(sources, includeDirs, defines, entry, output, replacements, backend, blockCounts, fastRom, ca65, sram, optLimit,
                    java.util.Set.of());
        }

        public Options(List<Path> sources, List<Path> includeDirs, List<String> defines, String entry, Path output,
                       Map<String, Path> replacements, Backend backend,
                       java.util.function.BiFunction<String, String, Long> blockCounts, boolean fastRom, boolean ca65,
                       int sram) {
            this(sources, includeDirs, defines, entry, output, replacements, backend, blockCounts, fastRom, ca65, sram, -1);
        }

        public Options(List<Path> sources, List<Path> includeDirs, List<String> defines, String entry, Path output,
                       Map<String, Path> replacements, Backend backend,
                       java.util.function.BiFunction<String, String, Long> blockCounts, boolean fastRom, boolean ca65) {
            this(sources, includeDirs, defines, entry, output, replacements, backend, blockCounts, fastRom, ca65, 0);
        }

        public Options(List<Path> sources, List<Path> includeDirs, List<String> defines, String entry, Path output,
                       Map<String, Path> replacements, Backend backend) {
            this(sources, includeDirs, defines, entry, output, replacements, backend, null, true, false);
        }

        public Options(List<Path> sources, List<Path> includeDirs, List<String> defines, String entry, Path output,
                       Map<String, Path> replacements, Backend backend,
                       java.util.function.BiFunction<String, String, Long> blockCounts) {
            this(sources, includeDirs, defines, entry, output, replacements, backend, blockCounts, true, false);
        }

        public Options(List<Path> sources, List<Path> includeDirs, List<String> defines, String entry, Path output) {
            this(sources, includeDirs, defines, entry, output, Map.of(), Backend.NAIVE);
        }

        public Options(List<Path> sources, List<Path> includeDirs, List<String> defines, String entry, Path output,
                       Map<String, Path> replacements) {
            this(sources, includeDirs, defines, entry, output, replacements, Backend.NAIVE);
        }

        public Options withBackend(Backend b) {
            return new Options(sources, includeDirs, defines, entry, output, replacements, b, blockCounts, fastRom, ca65, sram, optLimit, library);
        }

        public Options withBlockCounts(java.util.function.BiFunction<String, String, Long> counts) {
            return new Options(sources, includeDirs, defines, entry, output, replacements, backend, counts, fastRom, ca65, sram, optLimit, library);
        }

        public Options withFastRom(boolean fast) {
            return new Options(sources, includeDirs, defines, entry, output, replacements, backend, blockCounts, fast, ca65, sram, optLimit, library);
        }

        /** {@code true}: assemble and link with ca65 and ld65. */
        public Options withCa65(boolean useCa65) {
            return new Options(sources, includeDirs, defines, entry, output, replacements, backend, blockCounts, fastRom, useCa65, sram, optLimit, library);
        }

        /** With this many bytes of save RAM (0: none). */
        public Options withSram(int bytes) {
            if (bytes < 0 || bytes > MAX_SRAM) {
                throw new IllegalArgumentException("save RAM of " + bytes + " bytes: 0 to " + MAX_SRAM);
            }
            return new Options(sources, includeDirs, defines, entry, output, replacements, backend, blockCounts, fastRom, ca65, bytes, optLimit, library);
        }

        /** With at most this many pass applications (negative: the limit the environment sets, else none). */
        public Options withOptLimit(long limit) {
            return new Options(sources, includeDirs, defines, entry, output, replacements, backend, blockCounts, fastRom, ca65, sram, limit, library);
        }

        /** With these of the sources as a library's ({@code --lib}): compiled without {@code defines}. */
        public Options withLibrary(java.util.Collection<Path> files) {
            return new Options(sources, includeDirs, defines, entry, output, replacements, backend, blockCounts, fastRom, ca65, sram, optLimit,
                    java.util.Set.copyOf(files));
        }

        /** The defines the preprocessor gets: the user's, and SDEVKIT_SRAM_SIZE with save RAM. */
        public List<String> allDefines() {
            if (sram == 0) {
                return defines;
            }
            List<String> all = new java.util.ArrayList<>(defines);
            all.add("SDEVKIT_SRAM_SIZE=" + sram);
            return all;
        }

        /** The defines the preprocessor gets for one source: a library's get only SDEVKIT_SRAM_SIZE. */
        public List<String> definesFor(Path source) {
            if (!library.contains(source)) {
                return allDefines();
            }
            return sram == 0 ? List.of() : List.of("SDEVKIT_SRAM_SIZE=" + sram);
        }

        /** Block counts from a file of lines "function block count" ({@code dampf profile --blocks}). */
        public static java.util.function.BiFunction<String, String, Long> readBlockCounts(Path file) throws IOException {
            Map<String, Long> m = new java.util.HashMap<>();
            for (String line : Files.readAllLines(file)) {
                String[] p = line.strip().split("\\s+");
                if (p.length == 3) {
                    m.merge(p[0] + " " + p[1], Long.parseLong(p[2]), Long::sum);
                }
            }
            return (function, block) -> m.get(function + " " + block);
        }
    }

    /** Which code generator: the naive one straight from the AST, or the one from MIR. */
    public enum Backend { NAIVE, MIR }

    /**
     * What a compilation produced. {@code timing}: per function (by label) the best and worst case
     * in CPU cycles, worst -1 if unbounded (the code generator from MIR only; empty otherwise).
     * {@code warnings}: messages that do not stop the build (a constant that does not fit the type
     * it is converted to, as gcc warns; a function close to its budget).
     */
    public record Result(Program program, List<String> assembly, RomBuilder.Rom rom, Map<String, long[]> timing,
                         List<String> warnings, List<dampf.emu.AsmCheck.Report> asmChecks) {
        public Result(Program program, List<String> assembly, RomBuilder.Rom rom, Map<String, long[]> timing,
                      List<String> warnings) {
            this(program, assembly, rom, timing, warnings, List.of());
        }
    }

    private Compiler() {}

    public static Program frontEnd(Options o) throws CompileError, IOException {
        return frontEnd(o, new java.util.ArrayList<>());
    }

    /** The front end; its warnings (docs/subset.md, "Warnings") are added to {@code warnings}. */
    public static Program frontEnd(Options o, List<String> warnings) throws CompileError, IOException {
        AstBuilder builder = new AstBuilder(SystemHeaders.directory());
        // every file, so that the errors of one do not hide those of the next
        List<dampf.frontend.Diagnostic> errors = new java.util.ArrayList<>();
        for (Path source : o.sources()) {
            try {
                Preprocessor pp = new Preprocessor(SystemHeaders.directory(), o.includeDirs(), o.definesFor(source));
                builder.addFile(Parser.parse(pp.run(source), source.toString()));
            } catch (CompileError e) {
                errors.addAll(e.diagnostics());
            }
        }
        if (!errors.isEmpty()) {
            throw new CompileError(errors);
        }
        Program program = builder.finish();
        CallGraph.of(program).checkNoRecursion();
        builder.warnings().forEach(w -> warnings.add(w.toString()));
        return program;
    }

    public static Result compile(Options o) throws CompileError, IOException {
        List<String> warnings = new java.util.ArrayList<>();
        Program program = frontEnd(o, warnings);
        Function entry = program.function(o.entry());
        if (entry == null || !entry.isDefined()) {
            throw new CompileError(new SourcePos(o.sources().getFirst().toString(), 1, 1),
                    "entry function '" + o.entry() + "' is not defined");
        }
        Path outDir = o.output().toAbsolutePath().getParent();
        Files.createDirectories(outDir);   // the timing report is written before the ROM builder makes it
        String fileName = o.output().getFileName().toString();
        String baseName = fileName.endsWith(".sfc") ? fileName.substring(0, fileName.length() - 4) : fileName;
        Map<String, AsmFunctions.Asm> asmFunctions = AsmFunctions.collect(program, o, entry);
        // Debug file names are relative to the .dbg file, so Mesen finds the sources wherever the ROM is opened.
        java.util.function.Function<String, String> debugName =
                file -> outDir.relativize(Path.of(file).toAbsolutePath().normalize()).toString().replace('\\', '/');
        // interrupt handlers (DAMPF_INTERRUPT): the context of every function they reach, ADR-6FM9TXV4
        Map<Function, dampf.ast.Interrupt> contexts = CallGraph.of(program).contexts(entry);
        Map<dampf.ast.Interrupt, String> handlers = new java.util.EnumMap<>(dampf.ast.Interrupt.class);
        Map<String, dampf.ast.Interrupt> contextByLabel = new java.util.HashMap<>();
        contexts.forEach((f, c) -> contextByLabel.put(f.label(), c));
        for (Function f : program.functions()) {
            if (f.interrupt() != null && f.isDefined()) {
                handlers.put(f.interrupt(), f.label());
            }
        }
        checkAsmInInterrupts(asmFunctions, contexts, program);
        List<String> asm;
        Map<String, long[]> timing = Map.of();
        MirCodeGen gen = null;
        if (o.backend() == Backend.MIR) {
            NaiveCodeGen.checkEntry(entry);
            Map<String, Integer> locals = new java.util.HashMap<>();
            asmFunctions.values().forEach(a -> locals.put(a.function().label(), a.result().localsBytes()));
            // with assembly functions, their C bodies too: the check runs them against each other
            dampf.mir.MModule mir = Lower.lower(program, locals, true);
            dampf.util.PassLimit limit = new dampf.util.PassLimit(
                    o.optLimit() >= 0 ? o.optLimit() : dampf.util.PassLimit.configured(), baseName);
            dampf.mir.Reachable.run(mir, roots(entry, handlers.values(), asmFunctions.values()));
            dampf.mir.Optimizer.run(mir, false, true, limit);
            gen = new MirCodeGen(mir, entry.label(), debugName, o.blockCounts(), limit).interrupts(contextByLabel, handlers);
            for (AsmFunctions.Asm a : asmFunctions.values()) {
                gen.asm(a.function().label(), a.paramSymbols(), a.result().best(), a.result().worst());
            }
            asm = new java.util.ArrayList<>(gen.generate());
            timing = gen.timing();
            Map<String, String> why = gen.unboundedWhy();
            Map<String, String> notes = new java.util.HashMap<>(why);
            handlers.forEach((c, label) -> notes.merge("dampf_" + c.key, "the " + c + " from the interrupt to the end of its RTI: entry stub and "
                    + label, (x, y) -> y + "; " + x));
            writeTiming(timing, notes, asmFunctions, outDir.resolve(baseName + ".timing.txt"));
            // with a pass limit, the passes that prove a loop's bound may not have run: a budget
            // that cannot be guaranteed is then a warning, so the program can still be bisected
            checkBudgets(program, timing, why, warnings, limit.limited());
        } else {
            asm = new java.util.ArrayList<>(new NaiveCodeGen(program, entry, asmFunctions.keySet(), debugName)
                    .interrupts(contexts).generate());
        }
        for (AsmFunctions.Asm a : asmFunctions.values()) {
            if (a.text() != null) {
                asm.add(a.text());   // (a file with several functions once)
            }
        }
        RomBuilder.Rom rom = o.ca65() ? RomBuilder.build(asm, outDir, baseName, o.fastRom(), handlers.keySet())
                : RomBuilder.buildNative(asm, outDir, baseName, o.fastRom(), handlers.keySet());
        if (o.sram() > 0) {
            RomBuilder.setSram(rom.rom(), o.sram());
        }
        List<dampf.emu.AsmCheck.Report> checks = List.of();
        if (gen != null && !asmFunctions.isEmpty()) {
            checks = checkAsm(program, asm, gen, asmFunctions, o.fastRom(), handlers.keySet());
        }
        return new Result(program, asm, rom, timing, warnings, checks);
    }

    /**
     * What the program starts from for dead-code elimination ({@link dampf.mir.Reachable}): the
     * entry, the interrupt handlers, the functions written in assembly and their C bodies (checked
     * against them), and every C symbol their assembly names.
     */
    private static java.util.Set<String> roots(Function entry, java.util.Collection<String> handlers,
                                               java.util.Collection<AsmFunctions.Asm> asmFunctions) {
        java.util.Set<String> roots = new java.util.HashSet<>(handlers);
        roots.add(entry.label());
        java.util.regex.Pattern symbol = java.util.regex.Pattern.compile("\\b_[A-Za-z_]\\w*");
        for (AsmFunctions.Asm a : asmFunctions) {
            roots.add(a.function().label());
            roots.add(dampf.mir.Lower.CHECK_PREFIX + a.function().label());   // its C body, checked against it
            if (a.text() != null) {
                java.util.regex.Matcher m = symbol.matcher(a.text());
                while (m.find()) {
                    roots.add(m.group());
                }
            }
        }
        return roots;
    }

    /**
     * Runs every assembly function against its C body in dampf's emulator (DAMPF_ASM is a fact
     * hint, and fact hints are checked: docs/hints.md), in a ROM of its own that holds the C
     * bodies as well; a difference is a compile error.
     */
    private static List<dampf.emu.AsmCheck.Report> checkAsm(Program program, List<String> modules, MirCodeGen gen,
                                                            Map<String, AsmFunctions.Asm> asmFunctions, boolean fastRom,
                                                            java.util.Set<dampf.ast.Interrupt> interrupts)
            throws CompileError, IOException {
        List<String> all = new java.util.ArrayList<>(modules);
        all.add(gen.checkModule());
        dampf.asm.Linker.Image image = RomBuilder.linkInMemory(all, fastRom, interrupts);
        Map<String, Long> at = image.globals();
        java.util.Set<dampf.ast.Variable> read = new java.util.HashSet<>();
        asmFunctions.values().forEach(a -> read.addAll(AsmFunctions.globalsOf(a.function())));
        List<dampf.emu.AsmCheck.Global> globals = new java.util.ArrayList<>();
        for (dampf.ast.Variable v : program.globals()) {
            Long address = at.get(v.label());
            if (!v.inRom() && address != null && address >= 0x2000 && address + v.type().size() <= 0x10000) {
                globals.add(new dampf.emu.AsmCheck.Global(v.name(), (int) (long) address, v.type().size(),
                        read.contains(v) && !AsmFunctions.holdsPointer(v.type())));
            }
        }
        List<dampf.emu.AsmCheck.Function> functions = new java.util.ArrayList<>();
        for (AsmFunctions.Asm a : asmFunctions.values()) {
            Function f = a.function();
            String c = dampf.mir.Lower.CHECK_PREFIX + f.label();
            List<Integer> asmPlaces = new java.util.ArrayList<>();
            List<Integer> cPlaces = new java.util.ArrayList<>();
            int offset = 0;
            for (int k = 0; k < f.params().size(); k++) {
                asmPlaces.add(dampf.target.SnesTarget.FRAME_START + offset);
                offset += dampf.asm.AsmFunction.paramBytes(f.params().get(k).type().size());
                cPlaces.add(gen.paramAddress(c, k));
            }
            Type r = f.returnType();
            functions.add(new dampf.emu.AsmCheck.Function(f.name(), (int) (long) at.get(f.label()), (int) (long) at.get(c),
                    AsmFunctions.checkParams(f), asmPlaces, cPlaces, r instanceof Type.Void ? 0 : r.size(),
                    r instanceof Type.Pointer, a.result().best(), a.result().worst()));
        }
        try {
            return dampf.emu.AsmCheck.run(image.rom(), (int) (long) at.get("dampf_entry"), functions, globals,
                    dampf.emu.AsmCheck.INPUTS);
        } catch (dampf.emu.AsmCheck.Failure e) {
            AsmFunctions.Asm a = asmFunctions.get(e.function());
            throw new CompileError(a.function().definitionPos(), e.getMessage() + " (" + a.name() + ", DAMPF_ASM)");
        }
    }

    /**
     * A function written in assembly (DAMPF_ASM or --replace) may not be reached from an interrupt
     * handler: its convention (ADR-6FMATG2V) assumes the direct page 0 and its frame at dampf_frame
     * in the main code's area, where an interrupt context has its own page and frames, and its code
     * may use the CPU's multiplier, which interrupt code must not touch (ADR-6FM9TXV4).
     */
    private static void checkAsmInInterrupts(Map<String, AsmFunctions.Asm> asmFunctions,
                                             Map<Function, dampf.ast.Interrupt> contexts, Program program) throws CompileError {
        for (AsmFunctions.Asm a : asmFunctions.values()) {
            dampf.ast.Interrupt c = contexts.get(a.function());
            if (c == null) {
                continue;
            }
            String handler = program.functions().stream().filter(f -> f.interrupt() == c).findFirst().orElseThrow().name();
            throw new CompileError(a.function().definitionPos(), "'" + a.function().name() + "' is written in assembly ("
                    + a.name() + ") and reached from " + c.handler() + " '" + handler + "': an assembly function assumes"
                    + " the direct page 0 and the main code's frames and may use the CPU's multiplier, so interrupt code"
                    + " cannot call it yet (docs/hints.md, DAMPF_ASM)");
        }
    }

    /** Above this share of its budget, a function gets a warning. */
    static final double BUDGET_WARNING = 0.9;

    /**
     * DAMPF_BUDGET: a function's worst case (CPU cycles, calls included) must not be above its
     * budget, and must exist. Checked on the function as compiled on its own; a copy inlined
     * into a caller is part of the caller's bound.
     */
    private static void checkBudgets(Program program, Map<String, long[]> timing, Map<String, String> why,
                                     List<String> warnings, boolean onlyWarn) throws CompileError {
        List<dampf.frontend.Diagnostic> errors = new java.util.ArrayList<>();
        for (Function f : program.functions()) {
            long[] t = f.budget() == null ? null : timing.get(f.label());
            if (t == null) {
                continue;
            }
            long budget = f.budget();
            SourcePos at = f.definitionPos();
            if (t[1] < 0) {
                errors.add(new dampf.frontend.Diagnostic(dampf.frontend.Diagnostic.Severity.ERROR, at, "'" + f.name() + "' has no worst case ("
                        + why.getOrDefault(f.label(), "a loop without a bound") + "), so its budget of " + budget
                        + " cycles cannot be guaranteed (DAMPF_BUDGET)"));
            } else if (t[1] > budget) {
                errors.add(new dampf.frontend.Diagnostic(dampf.frontend.Diagnostic.Severity.ERROR, at, "'" + f.name() + "' takes up to " + t[1]
                        + " CPU cycles, over its budget of " + budget + " (DAMPF_BUDGET); best case " + t[0]
                        + ", see the .timing.txt file"));
            } else if (t[1] > budget * BUDGET_WARNING) {
                warnings.add(at + ": warning: '" + f.name() + "' takes up to " + t[1] + " CPU cycles, "
                        + Math.round(100.0 * t[1] / budget) + " % of its budget of " + budget + " (DAMPF_BUDGET)");
            }
        }
        if (onlyWarn) {
            errors.forEach(d -> warnings.add(new dampf.frontend.Diagnostic(dampf.frontend.Diagnostic.Severity.WARNING,
                    d.pos(), d.message() + "; an error without the pass limit").toString()));
        } else if (!errors.isEmpty()) {
            throw new CompileError(errors);
        }
    }

    /** The timing report: every function's best and worst case, the most expensive first. */
    private static void writeTiming(Map<String, long[]> timing, Map<String, String> why, Map<String, AsmFunctions.Asm> asm,
                                    Path file) throws IOException {
        Map<String, String> notes = new java.util.HashMap<>(why);
        asm.values().forEach(a -> notes.put(a.function().label(), "assembly, " + a.name()));
        StringBuilder out = new StringBuilder("""
                # dampf timing: best and worst case per function in CPU cycles, from entry to return,
                # calls included, interrupts and DMA not (docs/timing.md). "unbounded": a loop without
                # a known bound on the way; the first such loop in the function, or the first call
                # of a function without a bound, is named after it. "never": the function never returns.
                """);
        out.append(String.format("%12s %12s  %s%n", "best", "worst", "function"));
        timing.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[1] < 0 ? Long.MAX_VALUE : b.getValue()[1],
                        a.getValue()[1] < 0 ? Long.MAX_VALUE : a.getValue()[1]))
                .forEach(e -> {
                    String reason = notes.get(e.getKey());
                    out.append(String.format("%12s %12s  %s%s%n", e.getValue()[0] < 0 ? "never" : Long.toString(e.getValue()[0]),
                            e.getValue()[1] < 0 ? "unbounded" : Long.toString(e.getValue()[1]), e.getKey(),
                            reason == null ? "" : "   (" + reason + ")"));
                });
        Files.writeString(file, out);
    }
}
