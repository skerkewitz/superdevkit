package superdevkit.project;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code superdevkit run}: after the build, starts the program (ADR-6F3CJZBY): the desktop's in its
 * window (the machine's own target by default: linux, also under WSL, whose windows WSLg shows; win64
 * on Windows), or the ROM in Mesen for snes (MESEN names it, else MesenCE in the kit's .mesen/,
 * ADR-6F3CK1V6). It waits until the program ends. For web it serves the page and opens it in the
 * browser ({@link Serve}), until Ctrl+C.
 */
public final class Run {
    private Run() {
    }

    public static String ownTarget() {
        return Desktop.windows() ? "win64" : "linux";
    }

    static Path mesen(Kit kit) {
        String env = System.getenv("MESEN");
        if (env != null && !env.isBlank()) {
            return Path.of(env);
        }
        Path linux = kit.home().resolve(".mesen/Mesen");
        return Files.isRegularFile(linux) ? linux : kit.home().resolve(".mesen/Mesen.exe");
    }

    public static Build.Step step(Project p, Kit kit, String target) {
        if (target.equals("web")) {
            return Serve.step(p);
        }
        Path program = target.equals("snes") ? mesen(kit) : Desktop.output(p, target);
        List<String> cmd = target.equals("snes") ? List.of(program.toString(), Build.rom(p).toAbsolutePath().toString())
                : List.of(program.toAbsolutePath().toString());
        return new Build.Step("start", String.join(" ", cmd), List.of(), out -> {
            if (!Files.isRegularFile(program)) {
                throw new Build.Failure(target.equals("snes") ? "no Mesen at " + program + " (install MesenCE and name it with MESEN=path; in the kit's repository, dev/fetch_mesen.sh)"
                        : "no " + program);
            }
            Process pr = new ProcessBuilder(cmd).directory(p.dir().toFile()).inheritIO().start();
            int status = pr.waitFor();
            if (status != 0) {
                throw new Build.Failure(program.getFileName() + " ended with status " + status);
            }
        });
    }
}
