package superdevkit.project;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The superdevkit folder a project builds with (ADR records 31 and 41): the one its
 * superdevkit.toml names with {@code kit} (working on the kit and a game at once; the kit's own
 * examples name the repository), else the installation this command runs from, whose version
 * must be the one {@code superdevkit} asks for. A kit folder has the library (lib/) and dampf's
 * headers (include/ in an installation, compiler/src/main/resources/dampf/include in the
 * repository).
 */
public record Kit(Path home) {
    /** The installation this command runs from: the launcher sets dampf.include to its include/. */
    public static Path installation() {
        String inc = System.getProperty("dampf.include");
        return inc == null ? null : Path.of(inc).toAbsolutePath().normalize().getParent();
    }

    public static Kit of(Project p) throws Project.Error {
        Path home;
        if (p.kit() != null) {
            home = p.dir().resolve(p.kit()).toAbsolutePath().normalize();
        } else {
            home = installation();
            if (home == null) {
                throw new Project.Error("this superdevkit does not know its folder (run the installed command, bin/superdevkit), "
                        + "or name a kit in " + Project.FILE + ": kit = \"path\"");
            }
            String mine = dampf.Main.version();
            String want = p.version();
            boolean ok = want.split("\\.").length == 2 ? Project.major(mine).equals(want) : mine.equals(want);
            if (!ok) {
                throw new Project.Error("the project needs superdevkit " + want + ", this is " + mine
                        + " (" + home + "); install that version, or name a kit folder in " + Project.FILE + ": kit = \"path\"");
            }
        }
        Kit k = new Kit(home);
        if (!Files.isRegularFile(k.lib().resolve("include/sdevkit/snes.h"))) {
            throw new Project.Error("no superdevkit at " + home + " (its lib/include/sdevkit/snes.h is missing)");
        }
        return k;
    }

    public Path lib() {
        return home.resolve("lib");
    }

    /** dampf's headers (stdint.h, string.h, dampf.h), which the desktop compilers get too. */
    public Path dampfInclude() {
        Path installed = home.resolve("include");
        return Files.isRegularFile(installed.resolve("stdint.h")) ? installed
                : home.resolve("compiler/src/main/resources/dampf/include");
    }
}
