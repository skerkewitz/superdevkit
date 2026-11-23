package superdevkit.project;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The target web (M8, docs/archive/superdevkit-transition.md): the game on Rachael in the browser, built by
 * Emscripten's emcc ({@code EMCC}, default emcc on the PATH) in the three steps of the desktop
 * ({@link Desktop}): the game's files alone, without the host's headers; the symbol check on their
 * objects (wasm objects, {@link Symbols}); the link with Rachael, the desktop's frontend, lib/web
 * and raylib's web build.
 *
 * <p>build/web/index.html is the page (lib/web/shell.html), with index.js and index.wasm beside it,
 * and index.data with the music when the project has any. The frontend's loops wait for the
 * browser's frames with Asyncify (lib/web/web.h); the save RAM is kept in IndexedDB (-lidbfs.js).
 * The debug view is only in a build with --debug (ADR-6FD7KEJV), which is compiled with -O0 -g
 * and -DSDEVKIT_WEB_DEBUG, the game's files too, so a game can keep its own debug code to it.
 * The page must come from a web server (superdevkit run web), not from a file.
 */
final class Web {
    private Web() {
    }

    static Path output(Project p) {
        return p.dir().resolve("build").resolve("web").resolve("index.html");
    }

    /** emcc, or the command that runs it (emcc.bat on Windows goes through cmd). */
    static List<String> emcc() {
        String e = System.getenv().getOrDefault("EMCC", "emcc");
        return Desktop.windows() ? List.of("cmd", "/c", e) : List.of(e);
    }

    static List<Build.Step> steps(Project p, Kit kit, boolean debug) throws Build.Failure, IOException {
        List<Build.Step> steps = new ArrayList<>();
        Path ray = Raylib.folder(kit, "web");
        steps.add(new Build.Step("raylib", Raylib.VERSION + " for web in " + ray
                + (Raylib.present(kit, "web") ? "" : " (fetched from GitHub first)"), List.of(),
                out -> Raylib.ensure(kit, "web", out)));
        Path web = p.dir().resolve("build").resolve("web");
        Path obj = web.resolve("obj");
        Path musicFiles = obj.resolve("music_files.c");
        List<Path> sources = Build.gameSources(p);
        List<Path> objects = new ArrayList<>();
        List<String> emcc = emcc();
        String tool = Path.of(emcc.getLast()).getFileName().toString();
        List<String> common = new ArrayList<>(emcc);
        common.addAll(List.of("-std=c99", debug ? "-O0" : "-O2", "-Wall", "-Wextra", "-Wno-unused-parameter"));
        if (debug) {
            common.add("-g");
            common.add("-DSDEVKIT_WEB_DEBUG");    // the debug view and its keys, for the game too (ADR-6FD7KEJV)
        }
        if (p.sram() > 0) {
            common.add("-DSDEVKIT_SRAM_SIZE=" + p.sram());
        }
        steps.add(new Build.Step("mkdir", Build.rel(p, obj), List.of(), out -> {
            Desktop.deleteTree(obj);
            Files.createDirectories(obj);
        }));
        for (Path s : sources) {
            String parent = s.getParent() == null ? "" : s.getParent().getFileName() + "_";
            Path o = obj.resolve(parent + s.getFileName().toString().replaceFirst("\\.c$", "") + ".o");
            objects.add(o);
            List<String> cmd = new ArrayList<>(common);
            // clang leaves the loops of functions called memcpy and memset alone (dampf's <string.h>)
            cmd.addAll(List.of("-nostdinc", "-ffreestanding", "-isystem", kit.dampfInclude().toString()));
            for (String d : p.definesFor("web")) {
                cmd.add("-D" + d);
            }
            for (Path i : Build.includes(p, kit)) {
                cmd.add("-I");
                cmd.add(i.toString());
            }
            cmd.addAll(List.of("-c", s.toString(), "-o", o.toString()));
            steps.add(new Build.Step(tool, String.join(" ", Desktop.show(p, cmd)), Desktop.needs(p, s), out -> exec(p, cmd, out)));
        }
        Path music = web.resolve("music");
        steps.add(Desktop.music(p, "web", musicFiles));
        steps.add(Desktop.symbols(p, kit, objects));
        Path shell = obj.resolve("shell.html");
        Path page = output(p);
        List<String> link = new ArrayList<>(common);
        link.addAll(List.of("-I", kit.lib().resolve("include").toString(), "-I", kit.lib().resolve("rachael").toString(),
                "-I", kit.lib().resolve("web").toString(), "-I", ray.resolve("include").toString(),
                "-DGAME_NAME=\"" + p.name() + "\""));
        for (Path o : objects) {
            link.add(o.toString());
        }
        link.addAll(Desktop.cFiles(kit.lib().resolve("rachael")));
        link.addAll(Desktop.cFiles(kit.lib().resolve("desktop")));
        link.addAll(Desktop.cFiles(kit.lib().resolve("web")));
        link.add(musicFiles.toString());
        link.add(ray.resolve("lib/libraylib.a").toString());
        link.addAll(List.of(
                "-sUSE_GLFW=3",                 // raylib's web build draws through Emscripten's GLFW
                "-sASYNCIFY",                   // the frontend waits for the browser's frames (lib/web/web.h)
                "-sASYNCIFY_IGNORE_INDIRECT",   // ... on direct calls only: main, window, the splash
                "-sASYNCIFY_STACK_SIZE=65536",
                "-sSTACK_SIZE=1048576",
                "-sALLOW_MEMORY_GROWTH",
                "-sINVOKE_RUN=0",               // the page starts it with a click (sound, the keyboard)
                // FS: a check's files (tools/web_check, ADR-6FD1Y6W2); HEAPF32: raylib's miniaudio plays from it
                "-sEXPORTED_RUNTIME_METHODS=callMain,FS,HEAPF32",
                "-lidbfs.js",                   // the save RAM in IndexedDB
                "--shell-file", shell.toString()));
        if (!p.music().isEmpty()) {
            link.addAll(List.of("--preload-file", music + "@/music"));
        }
        link.addAll(List.of("-o", page.toString()));
        Path template = kit.lib().resolve("web/shell.html");
        steps.add(new Build.Step(tool, String.join(" ", Desktop.show(p, link)), List.of(template), out -> {
            Files.writeString(shell, Files.readString(template, StandardCharsets.UTF_8).replace("@GAME@", p.name())
                    .replace("@DEBUG_KEYS@", debug ? ", 'F1', 'F5', 'F6', 'F7', 'F8'" : ""),
                    StandardCharsets.UTF_8);
            for (String old : List.of("index.html", "index.js", "index.wasm", "index.data")) {
                Files.deleteIfExists(web.resolve(old));
            }
            exec(p, link, out);
        }));
        return steps;
    }

    /** emcc, with a message that says what to install when it is not there. */
    private static void exec(Project p, List<String> cmd, java.io.PrintStream out) throws Exception {
        String tool = emcc().getLast();
        // cmd /c starts for any name and only then fails, so on Windows emcc is looked for first
        if (Desktop.windows() && !found(tool, System.getenv("PATH"))) {
            throw noEmscripten(tool, "not found");
        }
        try {
            Desktop.exec(p.dir(), cmd, out);
        } catch (IOException e) {
            throw noEmscripten(tool, e.getMessage());
        }
    }

    private static Build.Failure noEmscripten(String tool, String why) {
        return new Build.Failure("no Emscripten (" + tool + "): the target web builds with emcc; install the Emscripten "
                + "SDK and put emcc on the PATH, or name it with EMCC=path. The SNES needs nothing else: "
                + "superdevkit build snes (" + why + ")");
    }

    /** Whether Windows would find the command: a path as it is, a name in the PATH's folders. */
    static boolean found(String command, String path) {
        boolean named = command.contains("/") || command.contains("\\");
        String[] dirs = named ? new String[] {""} : (path == null ? "" : path).split(File.pathSeparator);
        for (String dir : dirs) {
            String d = dir.replace("\"", "").strip();
            if (d.isEmpty() && !named) {
                continue;
            }
            for (String extension : List.of("", ".bat", ".cmd", ".exe")) {
                try {
                    if (Files.isRegularFile(Path.of(d, command + extension))) {
                        return true;
                    }
                } catch (InvalidPathException e) {
                    break;   // a PATH entry that is no path holds nothing
                }
            }
        }
        return false;
    }
}
