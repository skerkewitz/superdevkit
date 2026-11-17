package dampf.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs an external program (gcc, ca65, ld65, a test program) with a time limit: a program that
 * hangs is killed and reported, instead of making dampf or a test wait forever. The output goes to
 * temporary files, so a program that writes a lot cannot block on a full pipe either.
 */
public final class Processes {
    private Processes() {}

    /** What a program printed, and how it ended. */
    public record Result(int exit, String out, String err) {}

    public static Result run(Path dir, Duration limit, List<String> cmd) throws IOException {
        Path out = Files.createTempFile("dampf-out", ".txt");
        Path err = Files.createTempFile("dampf-err", ".txt");
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectOutput(out.toFile()).redirectError(err.toFile());
            if (dir != null) {
                pb.directory(dir.toFile());
            }
            Process p = pb.start();
            try {
                if (!p.waitFor(limit.toMillis(), TimeUnit.MILLISECONDS)) {
                    p.descendants().forEach(ProcessHandle::destroyForcibly);
                    p.destroyForcibly();
                    throw new IOException(String.join(" ", cmd) + " did not finish within " + limit.toSeconds()
                            + " s and was stopped (does it hang?)");
                }
            } catch (InterruptedException e) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("interrupted: " + String.join(" ", cmd), e);
            }
            return new Result(p.exitValue(), Files.readString(out, StandardCharsets.UTF_8), Files.readString(err, StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(out);
            Files.deleteIfExists(err);
        }
    }

    /** Runs the program and returns its output; throws with its messages if it fails. */
    public static String check(Path dir, Duration limit, List<String> cmd) throws IOException {
        Result r = run(dir, limit, cmd);
        if (r.exit() != 0) {
            throw new IOException(String.join(" ", cmd) + " failed:\n" + r.out() + r.err());
        }
        return r.out();
    }
}
