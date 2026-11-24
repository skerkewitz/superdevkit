package dampf;

import dampf.frontend.AstBuilder;
import dampf.frontend.CompileError;
import dampf.frontend.Diagnostic;
import dampf.frontend.Parser;
import dampf.frontend.Preprocessor;
import dampf.frontend.SystemHeaders;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Command line entry point.
 *
 * <pre>
 * dampf --version                                                      print dampf's version
 * dampf build|check|parse @file ...  (or --config file)                options from an argument file (ADR-6F3CJW9N);
 *                                                                      a folder stands for its .c files; --lib names
 *                                                                      a library's file or folder, which the -D
 *                                                                      defines do not reach (ADR-6FKYXP8C)
 * dampf parse [-I dir] [-D name[=value]] file.c ... [--lib file.c ...]
 *                                                                      preprocess and parse
 * dampf build [-I dir] [-D name[=value]] [--entry name] [--backend mir|naive] [--replace fn=file.s]
 *             [--block-counts file] [--slowrom] [--sram bytes] [--assembler dampf|ca65] [--opt-limit n]
 *             -o out.sfc file.c ... [--lib file.c ...]
 *                                                                      compile to a ROM (--replace: f's code from an assembly
 *                                                                      file, as DAMPF_ASM says, which it overrides;
 *                                                                      block counts: dampf profile --blocks;
 *                                                                      --slowrom: no FastROM, for a SlowROM cartridge;
 *                                                                      --sram: battery-backed save RAM, defines SDEVKIT_SRAM_SIZE;
 *                                                                      --assembler ca65: ca65 and ld65 instead of dampf's own;
 *                                                                      --opt-limit: at most n pass applications, see PassLimit)
 * dampf check [-I dir] [-D name[=value]] file.c ... [--lib file.c ...]
 *                                                                      front end: every problem per file, then the
 *                                                                      files that pass together (recursion, calls of
 *                                                                      functions defined nowhere, double definitions)
 * dampf profile rom.sfc profile.txt [top] [--skip file.c ...] [--instructions] [--blocks file]
 *                                                                      cycles per function (dampf bench --profile), and
 *                                                                      per kind of instruction
 * </pre>
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws IOException {
        if (args.length == 1 && (args[0].equals("--version") || args[0].equals("version"))) {
            System.out.println("dampf " + version());
            return;
        }
        if (args.length > 0 && args[0].equals("mir")) {
            dampf.tools.Mir.main(java.util.Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (args.length > 0 && args[0].equals("bench")) {
            dampf.tools.Bench.main(java.util.Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (args.length > 0 && args[0].equals("profile")) {
            dampf.tools.Profile.main(java.util.Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (args.length == 0 || !List.of("parse", "build", "check").contains(args[0])) {
            usage();
            System.exit(2);
        }
        try {
            args = ConfigFile.expand(List.of(args)).toArray(String[]::new);
        } catch (ConfigFile.Error e) {
            System.err.println("dampf: " + e.getMessage());
            System.exit(2);
        }
        List<Path> includes = new ArrayList<>();
        List<String> defines = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        List<Path> library = new ArrayList<>();
        String entry = "main";
        Path output = null;
        java.util.Map<String, Path> replacements = new java.util.LinkedHashMap<>();
        Compiler.Backend backend = null;
        Path blockCounts = null;
        boolean fastRom = true;
        boolean ca65 = false;
        int sram = 0;
        long optLimit = -1;
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--slowrom")) {
                fastRom = false;
            } else if (a.equals("--sram") && i + 1 < args.length) {
                String value = args[++i];
                try {
                    sram = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    sram = -1;
                }
                if (sram < 0 || sram > Compiler.Options.MAX_SRAM) {
                    System.err.println("dampf: --sram takes the bytes of save RAM, 0 to " + Compiler.Options.MAX_SRAM + ": " + value);
                    System.exit(2);
                }
            } else if (a.equals("--assembler") && i + 1 < args.length) {
                String name = args[++i];
                if (!name.equals("dampf") && !name.equals("ca65")) {
                    System.err.println("unknown assembler '" + name + "' (dampf or ca65)");
                    usage();
                    System.exit(2);
                }
                ca65 = name.equals("ca65");
            } else if (a.equals("--opt-limit") && i + 1 < args.length) {
                String value = args[++i];
                try {
                    optLimit = Long.parseLong(value);
                } catch (NumberFormatException e) {
                    optLimit = -1;
                }
                if (optLimit < 0) {
                    System.err.println("--opt-limit " + value + ": a number of pass applications, 0 or more");
                    usage();
                    System.exit(2);
                }
            } else if (a.equals("--lib") && i + 1 < args.length) {
                try {
                    List<Path> lib = ConfigFile.sources(Path.of(args[++i]));
                    files.addAll(lib);
                    library.addAll(lib);
                } catch (ConfigFile.Error e) {
                    System.err.println("dampf: --lib: " + e.getMessage());
                    System.exit(2);
                }
            } else if (a.equals("--block-counts") && i + 1 < args.length) {
                blockCounts = Path.of(args[++i]);
            } else if (a.equals("--backend") && i + 1 < args.length) {
                String name = args[++i];
                try {
                    backend = Compiler.Backend.valueOf(name.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    System.err.println("unknown backend '" + name + "' (mir or naive)");
                    usage();
                    System.exit(2);
                }
            } else if (a.equals("--replace") && i + 1 < args.length) {
                String[] kv = args[++i].split("=", 2);
                replacements.put(kv[0], Path.of(kv[1]));
            } else if (a.equals("--entry") || a.equals("-o")) {
                if (i + 1 == args.length) {
                    usage();
                    System.exit(2);
                }
                if (a.equals("-o")) {
                    output = Path.of(args[++i]);
                } else {
                    entry = args[++i];
                }
            } else if (a.equals("-I") || a.equals("-D")) {
                if (i + 1 == args.length) {
                    usage();
                    System.exit(2);
                }
                String value = args[++i];
                if (a.equals("-I")) {
                    includes.add(Path.of(value));
                } else {
                    defines.add(value);
                }
            } else if (a.startsWith("-I")) {
                includes.add(Path.of(a.substring(2)));
            } else if (a.startsWith("-D")) {
                defines.add(a.substring(2));
            } else {
                try {
                    files.addAll(ConfigFile.sources(Path.of(a)));
                } catch (ConfigFile.Error e) {
                    System.err.println("dampf: " + e.getMessage());
                    System.exit(2);
                }
            }
        }
        Compiler.Options o = new Compiler.Options(files, includes, defines, entry, output, replacements,
                backend != null ? backend : Compiler.Backend.MIR,
                blockCounts == null || !args[0].equals("build") ? null : Compiler.Options.readBlockCounts(blockCounts),
                fastRom, ca65, sram, optLimit).withLibrary(library);
        if (args[0].equals("build")) {
            if (output == null || files.isEmpty()) {
                usage();
                System.exit(2);
            }
            try {
                Compiler.Result r = Compiler.compile(o);
                r.warnings().forEach(System.err::println);
                System.err.println("wrote " + r.rom().rom() + " and " + r.rom().dbg());
                return;
            } catch (CompileError e) {
                e.diagnostics().forEach(System.err::println);
                System.exit(1);
            }
        }
        if (args[0].equals("check")) {
            // every file on its own, so one unsupported construct does not hide the others
            int ok = 0;
            List<Parser.Result> passed = new ArrayList<>();
            for (Path f : files) {
                try {
                    AstBuilder b = new AstBuilder(SystemHeaders.directory());
                    Parser.Result parsed = Parser.parse(new Preprocessor(SystemHeaders.directory(), includes, o.definesFor(f)).run(f), f.toString());
                    b.addFile(parsed);
                    passed.add(parsed);
                    ok++;
                    System.err.println("ok      " + f);
                    b.warnings().forEach(w -> System.err.println("        " + w));
                } catch (CompileError e) {
                    System.err.println("FAILS   " + e.diagnostics().getFirst());
                    e.diagnostics().stream().skip(1).forEach(d -> System.err.println("        " + d));
                }
            }
            System.err.printf("%d of %d file(s) pass the front end%n", ok, files.size());
            // then what only the whole program shows: recursion, functions called but defined in
            // none of the files, definitions in two files, a function in two interrupt contexts
            boolean whole = true;
            if (!passed.isEmpty()) {
                try {
                    AstBuilder b = new AstBuilder(SystemHeaders.directory());
                    for (Parser.Result parsed : passed) {
                        b.addFile(parsed);
                    }
                    dampf.ast.Program program = b.finish();
                    dampf.check.CallGraph graph = dampf.check.CallGraph.of(program);
                    graph.checkNoRecursion();
                    dampf.ast.Function entryFunction = program.function(o.entry());
                    if (entryFunction != null && entryFunction.isDefined()) {
                        graph.contexts(entryFunction);
                    }
                    System.err.println("the " + passed.size() + " file(s) together: ok");
                } catch (CompileError e) {
                    whole = false;
                    System.err.println("FAILS   " + e.diagnostics().getFirst() + "   (the " + passed.size() + " file(s) together)");
                }
            }
            System.exit(ok == files.size() && whole ? 0 : 1);
        }
        int errors = 0;
        for (Path f : files) {
            Parser.Result r;
            try {
                r = Parser.parse(new Preprocessor(SystemHeaders.directory(), includes, o.definesFor(f)).run(f), f.toString());
            } catch (CompileError e) {
                e.diagnostics().forEach(System.err::println);
                errors++;
                continue;
            }
            for (Diagnostic d : r.diagnostics()) {
                System.err.println(d);
            }
            errors += (int) r.diagnostics().stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR).count();
        }
        System.err.printf("%d file(s), %d error(s)%n", files.size(), errors);
        System.exit(errors == 0 ? 0 : 1);
    }

    /** dampf's version, from {@code build.gradle.kts} (ADR-6F3CJT1P). */
    public static String version() {
        try (var in = Main.class.getResourceAsStream("/dampf/version.properties")) {
            var properties = new java.util.Properties();
            properties.load(in);
            return properties.getProperty("version");
        } catch (IOException | NullPointerException e) {
            throw new IllegalStateException("dampf/version.properties is missing from the build", e);
        }
    }

    private static void usage() {
        System.err.println("usage: dampf --version");
        System.err.println("       dampf parse|build|check @file ...   (or --config file: options from an argument file; a folder means its .c files)");
        System.err.println("       dampf parse [-I dir] [-D name[=value]] file.c ... [--lib file.c ...]");
        System.err.println("       dampf build [-I dir] [-D name[=value]] [--entry name] [--backend mir|naive] [--replace fn=file.s] [--block-counts file] [--slowrom] [--sram bytes] [--assembler dampf|ca65] [--opt-limit n] -o out.sfc file.c ... [--lib file.c ...]");
        System.err.println("       dampf check [-I dir] [-D name[=value]] file.c ... [--lib file.c ...]");
        System.err.println("       (--lib: a library's file or folder, compiled without the -D defines)");
        System.err.println("       dampf profile rom.sfc profile.txt [top] [--skip file.c ...] [--instructions] [--blocks file]");
    }
}
