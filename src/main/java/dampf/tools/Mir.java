package dampf.tools;

import dampf.Compiler;
import dampf.ast.Program;
import dampf.frontend.CompileError;
import dampf.mir.Interpreter;
import dampf.mir.Lower;
import dampf.mir.MModule;
import dampf.mir.Printer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code dampf mir}: prints a program's MIR, or runs it in the MIR interpreter.
 *
 * <pre>
 * dampf mir [-O] [-I dir] [-D def] file.c ...            print the MIR (-O: after the MIR passes)
 * dampf mir [-I dir] [-D def] --bench main file.c ...    run the game's benchmark (bench.c) in the
 *                                                        interpreter: one line per tick, as bench.lua
 *                                                        writes them (clocks 0), for report.py
 * </pre>
 */
public final class Mir {
    /** Thrown at the "done" marker ($21FB) to leave the program, like the emulator and the Mesen script stop there. */
    private static final class Done extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Done() {
            super(null, null, false, false);
        }
    }

    private Mir() {}

    public static void main(String[] args) throws IOException {
        List<Path> includes = new ArrayList<>();
        List<String> defines = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        String bench = null;
        boolean optimize = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("-O")) {
                optimize = true;
            } else if (a.equals("-I")) {
                includes.add(Path.of(args[++i]));
            } else if (a.startsWith("-I")) {
                includes.add(Path.of(a.substring(2)));
            } else if (a.equals("-D")) {
                defines.add(args[++i]);
            } else if (a.startsWith("-D")) {
                defines.add(a.substring(2));
            } else if (a.equals("--bench")) {
                bench = args[++i];
            } else {
                files.add(Path.of(a));
            }
        }
        Program program;
        try {
            program = Compiler.frontEnd(new Compiler.Options(files, includes, defines, "main", Path.of("unused.sfc")));
        } catch (CompileError e) {
            e.diagnostics().forEach(System.err::println);
            System.exit(1);
            return;
        }
        MModule m = Lower.lower(program);
        if (optimize) {
            dampf.mir.Optimizer.run(m);
        }
        if (bench == null) {
            System.out.print(Printer.print(m));
            return;
        }
        Interpreter in = new Interpreter(m, Long.MAX_VALUE);
        StringBuilder out = new StringBuilder();
        StringBuilder hashes = new StringBuilder();
        int[] state = new int[1];
        int[] lo = new int[1];
        int[] tick = new int[1];
        in.onPortWrite((port, value) -> {
            switch (port) {
                case 0x21FC -> state[0] = value;
                case 0x21FD -> lo[0] = value;
                case 0x21FE -> hashes.append(String.format("%04X", lo[0] + 256 * value));
                case 0x21FA -> {
                    out.append(tick[0]++).append(' ').append(state[0]).append(" 0 ").append(hashes).append('\n');
                    hashes.setLength(0);
                }
                case 0x21FB -> throw new Done(); // the program is done; on the SNES it now waits forever
                default -> { }
            }
        });
        try {
            in.call(program.function(bench).label(), List.of());
        } catch (Done d) {
            // finished at the marker
        }
        System.out.print(out);
    }
}
