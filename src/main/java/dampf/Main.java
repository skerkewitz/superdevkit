package dampf;

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
 * dampf parse [-I dir] [-D name[=value]] file.c ...   preprocess and parse, report syntax errors
 * </pre>
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws IOException {
        if (args.length == 0 || !args[0].equals("parse")) {
            usage();
            System.exit(2);
        }
        List<Path> includes = new ArrayList<>();
        List<String> defines = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (a.equals("-I") || a.equals("-D")) {
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
        Preprocessor pp = new Preprocessor("gcc", SystemHeaders.directory(), includes, defines);
        int errors = 0;
        for (Path f : files) {
            Parser.Result r = Parser.parse(pp.run(f), f.toString());
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
    }
}
