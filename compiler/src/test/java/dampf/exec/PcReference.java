package dampf.exec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a test program on the PC: compiled by clang with the host's own headers, once with the
 * undefined behaviour sanitizer, so a test program with undefined behaviour fails instead of
 * passing by luck, and once without it for the values. Returns the values of the given globals
 * after the entry function returned. The kit's public headers ({@link #KIT_INCLUDE}) are on the
 * include path, as dampf's tests give them to dampf.
 */
final class PcReference {
    private PcReference() {}

    /** The kit's public headers (lib/include): a test program may include sdevkit/publish.h. */
    static final Path KIT_INCLUDE = Path.of("lib/include");

    static Map<String, Long> run(Path source, String entry, List<Observed> globals, Path workDir) throws IOException, InterruptedException {
        StringBuilder harness = new StringBuilder();
        harness.append("#include <stdio.h>\n");
        harness.append("#include \"").append(source.toAbsolutePath()).append("\"\n");
        harness.append("int main(void)\n{\n    ").append(entry).append("();\n");
        for (Observed v : globals) {
            harness.append("    printf(\"%s %lld\\n\", \"").append(v.path()).append("\", (long long)").append(v.path()).append(");\n");
        }
        harness.append("    return 0;\n}\n");
        Path c = workDir.resolve("pc_harness.c");
        Path checked = workDir.resolve("pc_harness_ub");
        Path exe = workDir.resolve("pc_harness");
        Files.writeString(c, harness);
        String include = dampf.frontend.SystemHeaders.directory().toString();
        String kit = KIT_INCLUDE.toAbsolutePath().toString();
        // clang is the reference (ADR record "clang is the tests' reference on the PC"): the
        // sanitized build finds undefined behaviour (it must run without a report), the values come
        // from a plain optimized build. gcc's -fsanitize=bounds gave false reports and wrong values
        // on a[i] op= (... ? a[i] : ...) (gcc 16.2.1, RandomLoopProgram seeds 1157, 15241 and 53266).
        exec(workDir, "clang", "-std=c99", "-O0", "-Wall", "-fsanitize=undefined", "-fno-sanitize-recover=all",
                "-I", kit, "-idirafter", include, "-o", checked.toString(), c.toString());
        exec(workDir, checked.toString());
        exec(workDir, "clang", "-std=c99", "-O1", "-Wall", "-I", kit, "-idirafter", include, "-o", exe.toString(), c.toString());
        String out = exec(workDir, exe.toString());
        Map<String, Long> values = new LinkedHashMap<>();
        for (String line : out.strip().split("\n")) {
            int sp = line.lastIndexOf(' ');
            values.put(line.substring(0, sp), Long.parseLong(line.substring(sp + 1)));
        }
        return values;
    }

    static Map<String, Long> wrap(Map<String, Long> raw, List<Observed> globals) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Observed v : globals) {
            out.put(v.path(), v.type().wrap(raw.get(v.path())));
        }
        return out;
    }

    private static String exec(Path dir, String... cmd) throws IOException, InterruptedException {
        return dampf.util.Processes.check(dir, java.time.Duration.ofMinutes(1), List.of(cmd));
    }
}
