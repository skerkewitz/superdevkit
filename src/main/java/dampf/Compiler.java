package dampf;

import dampf.ast.Function;
import dampf.ast.Program;
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
     *                     (C name to .s file): the escape hatch for reference versions
     *                     (docs/optimizations.md). The function keeps dampf's calling convention:
     *                     parameters in its static frame ({@code _<name>__<param>}), result in A
     *                     (and X for 32 bits), JSL/RTL, A/X/Y 16 bit, DBR $7E.
     * @param blockCounts  how often each block ran, (function label, block name) → count, from a
     *                     profile ({@code dampf profile --blocks}); null without one. Only the order
     *                     of the blocks follows it, so a profile of other code makes the code
     *                     slower, never wrong.
     * @param fastRom      the ROM in banks $80-$FF runs at 3.58 MHz (FastROM, the default); false
     *                     for a SlowROM cartridge
     * @param ca65         assemble and link with ca65 and ld65 instead of dampf's own assembler and
     *                     linker (which build the same ROM; see docs/transition-to-lir.md)
     */
    public record Options(List<Path> sources, List<Path> includeDirs, List<String> defines, String entry, Path output,
                          Map<String, Path> replacements, Backend backend,
                          java.util.function.BiFunction<String, String, Long> blockCounts, boolean fastRom, boolean ca65) {
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
            return new Options(sources, includeDirs, defines, entry, output, replacements, b, blockCounts, fastRom, ca65);
        }

        public Options withBlockCounts(java.util.function.BiFunction<String, String, Long> counts) {
            return new Options(sources, includeDirs, defines, entry, output, replacements, backend, counts, fastRom, ca65);
        }

        public Options withFastRom(boolean fast) {
            return new Options(sources, includeDirs, defines, entry, output, replacements, backend, blockCounts, fast, ca65);
        }

        /** {@code true}: assemble and link with ca65 and ld65. */
        public Options withCa65(boolean useCa65) {
            return new Options(sources, includeDirs, defines, entry, output, replacements, backend, blockCounts, fastRom, useCa65);
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
     * {@code warnings}: messages that do not stop the build (a function close to its budget).
     */
    public record Result(Program program, List<String> assembly, RomBuilder.Rom rom, Map<String, long[]> timing,
                         List<String> warnings) {}

    private Compiler() {}

    public static Program frontEnd(Options o) throws CompileError, IOException {
        Preprocessor pp = new Preprocessor("gcc", SystemHeaders.directory(), o.includeDirs(), o.defines());
        AstBuilder builder = new AstBuilder(SystemHeaders.directory());
        for (Path source : o.sources()) {
            builder.addFile(Parser.parse(pp.run(source), source.toString()));
        }
        Program program = builder.finish();
        CallGraph.of(program).checkNoRecursion();
        return program;
    }

    public static Result compile(Options o) throws CompileError, IOException {
        Program program = frontEnd(o);
        Function entry = program.function(o.entry());
        if (entry == null || !entry.isDefined()) {
            throw new CompileError(new SourcePos(o.sources().getFirst().toString(), 1, 1),
                    "entry function '" + o.entry() + "' is not defined");
        }
        Path outDir = o.output().toAbsolutePath().getParent();
        Files.createDirectories(outDir);   // the timing report is written before the ROM builder makes it
        String fileName = o.output().getFileName().toString();
        String baseName = fileName.endsWith(".sfc") ? fileName.substring(0, fileName.length() - 4) : fileName;
        // Debug file names are relative to the .dbg file, so Mesen finds the sources wherever the ROM is opened.
        for (String name : o.replacements().keySet()) {
            Function f = program.function(name);
            if (f == null || !f.isDefined()) {
                throw new CompileError(new SourcePos(o.sources().getFirst().toString(), 1, 1),
                        "--replace " + name + ": there is no function '" + name + "' to replace");
            }
        }
        java.util.function.Function<String, String> debugName =
                file -> outDir.relativize(Path.of(file).toAbsolutePath().normalize()).toString().replace('\\', '/');
        List<String> asm;
        Map<String, long[]> timing = Map.of();
        List<String> warnings = new java.util.ArrayList<>();
        if (o.backend() == Backend.MIR) {
            if (!o.replacements().isEmpty()) {
                throw new CompileError(entry.pos(), "--replace works with the naive code generator only");
            }
            NaiveCodeGen.checkEntry(entry);
            dampf.mir.MModule mir = Lower.lower(program);
            dampf.mir.Optimizer.run(mir);
            MirCodeGen gen = new MirCodeGen(mir, entry.label(), debugName, o.blockCounts());
            asm = new java.util.ArrayList<>(gen.generate());
            timing = gen.timing();
            Map<String, String> why = gen.unboundedWhy();
            writeTiming(timing, why, outDir.resolve(baseName + ".timing.txt"));
            checkBudgets(program, timing, why, warnings);
        } else {
            asm = new java.util.ArrayList<>(new NaiveCodeGen(program, entry, o.replacements().keySet(), debugName).generate());
        }
        for (Path s : o.replacements().values()) {
            asm.add(Files.readString(s));
        }
        RomBuilder.Rom rom = o.ca65() ? RomBuilder.build(asm, outDir, baseName, o.fastRom())
                : RomBuilder.buildNative(asm, outDir, baseName, o.fastRom());
        return new Result(program, asm, rom, timing, warnings);
    }

    /** Above this share of its budget, a function gets a warning. */
    static final double BUDGET_WARNING = 0.9;

    /**
     * DAMPF_BUDGET: a function's worst case (CPU cycles, calls included) must not be above its
     * budget, and must exist. Checked on the function as compiled on its own; a copy inlined
     * into a caller is part of the caller's bound.
     */
    private static void checkBudgets(Program program, Map<String, long[]> timing, Map<String, String> why,
                                     List<String> warnings) throws CompileError {
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
        if (!errors.isEmpty()) {
            throw new CompileError(errors);
        }
    }

    /** The timing report: every function's best and worst case, the most expensive first. */
    private static void writeTiming(Map<String, long[]> timing, Map<String, String> why, Path file) throws IOException {
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
                    String reason = why.get(e.getKey());
                    out.append(String.format("%12s %12s  %s%s%n", e.getValue()[0] < 0 ? "never" : Long.toString(e.getValue()[0]),
                            e.getValue()[1] < 0 ? "unbounded" : Long.toString(e.getValue()[1]), e.getKey(),
                            reason == null ? "" : "   (" + reason + ")"));
                });
        Files.writeString(file, out);
    }
}
