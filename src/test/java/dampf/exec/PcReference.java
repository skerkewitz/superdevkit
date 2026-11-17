package dampf.exec;

import dampf.ast.Type;
import dampf.ast.Variable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a test program on the PC: compiled by gcc with the host's own headers, with the
 * undefined behaviour sanitizer, so a test program with undefined behaviour fails instead of
 * passing by luck. Returns the values of the given globals after the entry function returned.
 */
final class PcReference {
    private PcReference() {}

    static Map<String, Long> run(Path source, String entry, List<Variable> globals, Path workDir) throws IOException, InterruptedException {
        StringBuilder harness = new StringBuilder();
        harness.append("#include <stdio.h>\n");
        harness.append("#include \"").append(source.toAbsolutePath()).append("\"\n");
        harness.append("int main(void)\n{\n    ").append(entry).append("();\n");
        for (Variable v : globals) {
            harness.append("    printf(\"%s %lld\\n\", \"").append(v.name()).append("\", (long long)").append(v.name()).append(");\n");
        }
        harness.append("    return 0;\n}\n");
        Path c = workDir.resolve("pc_harness.c");
        Path exe = workDir.resolve("pc_harness");
        Files.writeString(c, harness);
        exec(workDir, "gcc", "-std=c99", "-O1", "-Wall", "-fsanitize=undefined", "-fno-sanitize-recover=all",
                "-o", exe.toString(), c.toString());
        String out = exec(workDir, exe.toString());
        Map<String, Long> values = new LinkedHashMap<>();
        for (String line : out.strip().split("\n")) {
            String[] parts = line.split(" ");
            values.put(parts[0], Long.parseLong(parts[1]));
        }
        return values;
    }

    static Map<String, Long> wrap(Map<String, Long> raw, List<Variable> globals) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Variable v : globals) {
            out.put(v.name(), ((Type.Int) v.type()).wrap(raw.get(v.name())));
        }
        return out;
    }

    private static String exec(Path dir, String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IOException(String.join(" ", cmd) + " failed:\n" + out);
        }
        return out;
    }
}
