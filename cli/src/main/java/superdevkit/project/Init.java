package superdevkit.project;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;

/**
 * {@code superdevkit init NAME [--from EXAMPLE]}: a new project in ./NAME, a copy of one of the kit's
 * examples (hello by default; ADR record 31: a template that is a working example the tests build
 * cannot go stale), without its build folder. Its superdevkit.toml gets the new name and this kit's
 * version instead of the examples' path to the repository, and the project gets a CLAUDE.md that
 * points an AI working on it to the kit's guides (lib/C_STYLE.md, lib/SNES_HARDWARE.md).
 */
public final class Init {
    private Init() {
    }

    public static int run(String name, String example, Path parent, boolean planOnly, PrintStream out)
            throws Project.Error, IOException {
        if (!name.matches("[A-Za-z0-9_-]+")) {
            throw new Project.Error("a project's name has letters, digits, _ and - only: " + name);
        }
        Path home = Kit.installation();
        if (home == null) {
            throw new Project.Error("this superdevkit does not know its folder (run the installed command, bin/superdevkit)");
        }
        Path from = home.resolve("examples").resolve(example);
        if (!Files.isRegularFile(from.resolve(Project.FILE))) {
            throw new Project.Error("no example '" + example + "' with a " + Project.FILE + " in " + home.resolve("examples"));
        }
        Path to = parent.resolve(name).toAbsolutePath().normalize();
        if (Files.exists(to)) {
            throw new Project.Error(to + " is there already");
        }
        String version = Project.major(dampf.Main.version());
        if (planOnly) {
            out.println("superdevkit init " + name + ": " + from + " -> " + to);
            out.println("  " + Project.FILE + ": name = \"" + name + "\", superdevkit = \"" + version + "\"");
            out.println("  CLAUDE.md: the kit's guides, " + home.resolve("lib/C_STYLE.md") + " and " + home.resolve("lib/SNES_HARDWARE.md"));
            return 0;
        }
        try (var s = Files.walk(from)) {
            for (Path src : s.toList()) {
                Path rel = from.relativize(src);
                if (rel.getNameCount() > 0 && rel.getName(0).toString().equals("build")) {
                    continue;
                }
                Path dst = to.resolve(rel.toString());
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst);
                } else {
                    Files.copy(src, dst);
                }
            }
        }
        Path toml = to.resolve(Project.FILE);
        String t = Files.readString(toml);
        t = t.replaceFirst("(?m)^name\\s*=.*$", Matcher.quoteReplacement("name = \"" + name + "\""));
        if (t.matches("(?s).*(?m:^kit\\s*=.*$).*")) {
            t = t.replaceFirst("(?m)^kit\\s*=.*$", Matcher.quoteReplacement("superdevkit = \"" + version + "\""
                    + "                # the kit's version this game is built with"));
        }
        Files.writeString(toml, t, StandardCharsets.UTF_8);
        Files.writeString(to.resolve("CLAUDE.md"), "# " + name + "\n\n"
                + "A game for the SNES, built with superdevkit " + version + " (`superdevkit build snes`, `superdevkit run`;\n"
                + "the project is " + Project.FILE + "). Before writing game code, read the kit's guides: "
                + home.resolve("lib/C_STYLE.md") + "\n(C for dampf, the kit's compiler) and " + home.resolve("lib/SNES_HARDWARE.md")
                + " (the hardware and what\nthe kit has of it).\n", StandardCharsets.UTF_8);
        out.println("superdevkit init: " + to + ", from the example " + example);
        out.println("  next: cd " + name + " && superdevkit run");
        return 0;
    }
}
