package dampf;

import dampf.ast.Function;
import dampf.ast.Program;
import dampf.backend.NaiveCodeGen;
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
                          Map<String, Path> replacements) {
        public Options(List<Path> sources, List<Path> includeDirs, List<String> defines, String entry, Path output) {
            this(sources, includeDirs, defines, entry, output, Map.of());
        }
    }

    /** What a compilation produced. */
    public record Result(Program program, List<String> assembly, RomBuilder.Rom rom) {}

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
        NaiveCodeGen gen = new NaiveCodeGen(program, entry, o.replacements().keySet(),
                file -> outDir.relativize(Path.of(file).toAbsolutePath().normalize()).toString().replace('\\', '/'));
        List<String> asm = new java.util.ArrayList<>(gen.generate());
        for (Path s : o.replacements().values()) {
            asm.add(Files.readString(s));
        }
        RomBuilder.Rom rom = RomBuilder.build(asm, outDir, baseName);
        return new Result(program, asm, rom);
    }
}
