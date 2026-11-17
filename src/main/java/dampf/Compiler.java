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
     * @param output       the ROM to write; the assembly, .dbg, label and map files go next to it
     * @param replacements functions linked from hand-written assembly instead of being compiled
     *                     (C name to .s file): the escape hatch for reference versions
     *                     (docs/optimizations.md). The function keeps dampf's calling convention:
     *                     parameters in its static frame ({@code _<name>__<param>}), result in A
     *                     (and X for 32 bits), JSL/RTL, A/X/Y 16 bit, DBR $7E.
     */
    public record Options(List<Path> sources, List<Path> includeDirs, List<String> defines, String entry, Path output,
                          Map<String, Path> replacements, Backend backend) {
        public Options(List<Path> sources, List<Path> includeDirs, List<String> defines, String entry, Path output) {
            this(sources, includeDirs, defines, entry, output, Map.of(), Backend.NAIVE);
        }

        public Options(List<Path> sources, List<Path> includeDirs, List<String> defines, String entry, Path output,
                       Map<String, Path> replacements) {
            this(sources, includeDirs, defines, entry, output, replacements, Backend.NAIVE);
        }

        public Options withBackend(Backend b) {
            return new Options(sources, includeDirs, defines, entry, output, replacements, b);
        }
    }

    /** Which code generator: the naive one straight from the AST, or the one from MIR. */
    public enum Backend { NAIVE, MIR }

    /**
     * What a compilation produced. {@code timing}: per function (by label) the best and worst case
     * in CPU cycles, worst -1 if unbounded (the code generator from MIR only; empty otherwise).
     */
    public record Result(Program program, List<String> assembly, RomBuilder.Rom rom, Map<String, long[]> timing) {}

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
        if (o.backend() == Backend.MIR) {
            if (!o.replacements().isEmpty()) {
                throw new CompileError(entry.pos(), "--replace works with the naive code generator only");
            }
            NaiveCodeGen.checkEntry(entry);
            dampf.mir.MModule mir = Lower.lower(program);
            dampf.mir.Optimizer.run(mir);
            MirCodeGen gen = new MirCodeGen(mir, entry.label(), debugName);
            asm = new java.util.ArrayList<>(gen.generate());
            timing = gen.timing();
            writeTiming(timing, outDir.resolve(baseName + ".timing.txt"));
        } else {
            asm = new java.util.ArrayList<>(new NaiveCodeGen(program, entry, o.replacements().keySet(), debugName).generate());
        }
        for (Path s : o.replacements().values()) {
            asm.add(Files.readString(s));
        }
        RomBuilder.Rom rom = RomBuilder.build(asm, outDir, baseName);
        return new Result(program, asm, rom, timing);
    }

    /** The timing report: every function's best and worst case, the most expensive first. */
    private static void writeTiming(Map<String, long[]> timing, Path file) throws IOException {
        StringBuilder out = new StringBuilder("""
                # dampf timing: best and worst case per function in CPU cycles, from entry to return,
                # calls included, interrupts and DMA not (docs/timing.md). "unbounded": a loop without
                # a known bound on the way.
                """);
        out.append(String.format("%12s %12s  %s%n", "best", "worst", "function"));
        timing.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[1] < 0 ? Long.MAX_VALUE : b.getValue()[1],
                        a.getValue()[1] < 0 ? Long.MAX_VALUE : a.getValue()[1]))
                .forEach(e -> out.append(String.format("%12d %12s  %s%n", e.getValue()[0],
                        e.getValue()[1] < 0 ? "unbounded" : Long.toString(e.getValue()[1]), e.getKey())));
        Files.writeString(file, out);
    }
}
