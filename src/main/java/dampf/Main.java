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

/**
 * Command line entry point.
 *
 * <pre>
 * dampf parse [-I dir] [-D name[=value]] file.c ...                   preprocess and parse
 * dampf build [-I dir] [-D name[=value]] [--entry name] [--backend mir|naive] [--replace fn=file.s] -o out.sfc file.c ...   compile to a ROM
 * dampf check [-I dir] [-D name[=value]] file.c ...                   front end only: first problem per file
 * dampf profile rom.sfc profile.txt [top]                              cycles per function (tools/profile.lua)
 * </pre>
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws IOException {
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
        List<Path> includes = new ArrayList<>();
        List<String> defines = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        String entry = "main";
        Path output = null;
        java.util.Map<String, Path> replacements = new java.util.LinkedHashMap<>();
        Compiler.Backend backend = null;
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--backend") && i + 1 < args.length) {
                String name = args[++i];
                try {
                    backend = Compiler.Backend.valueOf(name.toUpperCase());
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
                files.add(Path.of(a));
            }
        }
        if (args[0].equals("build")) {
            if (output == null || files.isEmpty()) {
                usage();
                System.exit(2);
            }
            try {
                Compiler.Result r = Compiler.compile(new Compiler.Options(files, includes, defines, entry, output, replacements,
                        backend != null ? backend : replacements.isEmpty() ? Compiler.Backend.MIR : Compiler.Backend.NAIVE));
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
                    Parser.Result parsed = Parser.parse(new Preprocessor("gcc", SystemHeaders.directory(), includes, defines).run(f), f.toString());
                    b.addFile(parsed);
                    passed.add(parsed);
                    ok++;
                    System.err.println("ok      " + f);
                } catch (CompileError e) {
                    System.err.println("FAILS   " + e.diagnostics().getFirst());
                }
            }
            System.err.printf("%d of %d file(s) pass the front end%n", ok, files.size());
            // then what only the whole program shows: recursion, functions called but defined in
            // none of the files, definitions in two files
            boolean whole = true;
            if (passed.size() > 0) {
                try {
                    AstBuilder b = new AstBuilder(SystemHeaders.directory());
                    for (Parser.Result parsed : passed) {
                        b.addFile(parsed);
                    }
                    dampf.check.CallGraph.of(b.finish()).checkNoRecursion();
                    System.err.println("the " + passed.size() + " file(s) together: ok");
                } catch (CompileError e) {
                    whole = false;
                    System.err.println("FAILS   " + e.diagnostics().getFirst() + "   (the " + passed.size() + " file(s) together)");
                }
            }
            System.exit(ok == files.size() && whole ? 0 : 1);
        }
        Preprocessor pp = new Preprocessor("gcc", SystemHeaders.directory(), includes, defines);
        int errors = 0;
        for (Path f : files) {
            Parser.Result r;
            try {
                r = Parser.parse(pp.run(f), f.toString());
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

    private static void usage() {
        System.err.println("usage: dampf parse [-I dir] [-D name[=value]] file.c ...");
        System.err.println("       dampf build [-I dir] [-D name[=value]] [--entry name] [--backend mir|naive] [--replace fn=file.s] -o out.sfc file.c ...");
        System.err.println("       dampf check [-I dir] [-D name[=value]] file.c ...");
        System.err.println("       dampf profile rom.sfc profile.txt [top]");
    }
}
