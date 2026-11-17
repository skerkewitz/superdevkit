package dampf.frontend;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

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

    /** Preprocesses {@code source}; throws with gcc's messages if it fails. */
    public String run(Path source) throws IOException {
        Process p = new ProcessBuilder(commandLine(source)).start();
        CompletableFuture<byte[]> err = CompletableFuture.supplyAsync(() -> {
            try {
                return p.getErrorStream().readAllBytes();
            } catch (IOException e) {
                return e.toString().getBytes(StandardCharsets.UTF_8);
            }
        });
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            int exit = p.waitFor();
            if (exit != 0) {
                throw new IOException("preprocessing " + source + " failed:\n" + new String(err.join(), StandardCharsets.UTF_8));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while preprocessing " + source, e);
        }
        return out;
    }
}
