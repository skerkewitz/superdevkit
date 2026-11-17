package dampf.frontend;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the C preprocessor ({@code gcc -E}) on one source file.
 *
 * <p>Host headers are switched off ({@code -nostdinc}); dampf's own {@code include/} directory
 * provides {@code <stdint.h>}. {@code -undef} drops the host's predefined macros (such as
 * {@code __x86_64__}), and {@code __DAMPF__} is defined so headers can tell the dampf build apart.
 * Line markers are kept so every token maps back to its original file and line.
 */
public final class Preprocessor {
    private final String command;
    private final Path dampfInclude;
    private final List<Path> includeDirs;
    private final List<String> defines;

    public Preprocessor(String command, Path dampfInclude, List<Path> includeDirs, List<String> defines) {
        this.command = command;
        this.dampfInclude = dampfInclude;
        this.includeDirs = List.copyOf(includeDirs);
        this.defines = List.copyOf(defines);
    }

    public List<String> commandLine(Path source) {
        List<String> cmd = new ArrayList<>(List.of(command, "-E", "-std=c99", "-nostdinc", "-undef", "-D__DAMPF__=1"));
        for (String d : defines) {
            cmd.add("-D" + d);
        }
        cmd.add("-I" + dampfInclude);
        for (Path dir : includeDirs) {
            cmd.add("-I" + dir);
        }
        cmd.add(source.toString());
        return cmd;
    }

    /**
     * Preprocesses {@code source}. If gcc fails (a missing header, a broken directive), the error
     * is gcc's first error message at its position, as a CompileError like any other.
     */
    public String run(Path source) throws IOException, CompileError {
        dampf.util.Processes.Result r = dampf.util.Processes.run(null, java.time.Duration.ofMinutes(2), commandLine(source));
        if (r.exit() != 0) {
            java.util.regex.Pattern at = java.util.regex.Pattern.compile("^(.+?):(\\d+):(\\d+): (?:fatal )?error: (.*)$");
            for (String line : r.err().split("\\R")) {
                java.util.regex.Matcher m = at.matcher(line);
                if (m.matches()) {
                    throw new CompileError(new SourcePos(m.group(1), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))),
                            m.group(4));
                }
            }
            String first = r.err().strip().isEmpty() ? "gcc failed" : r.err().strip().split("\\R")[0];
            throw new CompileError(new SourcePos(source.toString(), 1, 1), "preprocessing failed: " + first);
        }
        return r.out();
    }
}
