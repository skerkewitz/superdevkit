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
import java.util.List;

/** The whole pipeline: C files in, SNES ROM with debug information out. */
public final class Compiler {
    /**
     * @param entry  the function the runtime calls after start-up; must be {@code void name(void)}
     * @param output the ROM to write; the assembly, .dbg, label and map files go next to it
     */
    public record Options(List<Path> sources, List<Path> includeDirs, List<String> defines, String entry, Path output) {}

    /** What a compilation produced. */
    public record Result(Program program, String assembly, RomBuilder.Rom rom) {}

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
        NaiveCodeGen gen = new NaiveCodeGen(program, entry,
                file -> outDir.relativize(Path.of(file).toAbsolutePath().normalize()).toString().replace('\\', '/'));
        String asm = gen.generate();
        RomBuilder.Rom rom = RomBuilder.build(asm, outDir, baseName);
        return new Result(program, asm, rom);
    }
}
