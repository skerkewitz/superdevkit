package superdevkit;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import superdevkit.project.Build;
import superdevkit.project.Init;
import superdevkit.project.Kit;
import superdevkit.project.Project;
import superdevkit.project.Run;
import superdevkit.sfx.Sfx;

/**
 * The superdevkit command (ADR record 31; docs/superdevkit-transition.md, 7.4):
 *
 * <pre>
 * superdevkit init NAME [--from EXAMPLE]      a new project in ./NAME, copied from an example (hello)
 * superdevkit build TARGET [FOLDER]           snes, linux or win64; the project in FOLDER (default .)
 * superdevkit run [TARGET] [FOLDER]           builds and starts it: the desktop (linux or win64),
 *                                             or snes in Mesen
 *     --verbose   every step as it runs     --plan     the steps, without running them
 *     --dry-run   checks that everything is there, and does nothing                --debug    no optimization (desktop)
 * superdevkit png tiles|tiles2|palette|mode7 picture.png NAME out_dir [FIRST]
 * superdevkit sfx assets/sfx/sfx.json NAME out_dir
 * superdevkit --version
 * </pre>
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        return run(args, System.out, System.err);
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 1 && (args[0].equals("--version") || args[0].equals("version"))) {
            out.println("superdevkit " + dampf.Main.version());
            return 0;
        }
        if (args.length == 0) {
            return usage(err);
        }
        switch (args[0]) {
            case "png" -> {
                return png(args, err);
            }
            case "sfx" -> {
                return sfx(args, out, err);
            }
            case "build", "run", "init" -> {
                return project(args, out, err);
            }
            default -> {
                return usage(err);
            }
        }
    }

    private static int project(String[] args, PrintStream out, PrintStream err) {
        boolean verbose = false, dryRun = false, plan = false, debug = false;
        String from = null;
        List<String> words = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--verbose" -> verbose = true;
                case "--dry-run" -> dryRun = true;
                case "--plan" -> {
                    plan = true;
                    dryRun = true;
                }
                case "--debug" -> debug = true;
                case "--from" -> {
                    if (i + 1 == args.length) {
                        return usage(err);
                    }
                    from = args[++i];
                }
                default -> {
                    if (args[i].startsWith("--")) {
                        err.println("error: unknown option " + args[i]);
                        return usage(err);
                    }
                    words.add(args[i]);
                }
            }
        }
        try {
            if (args[0].equals("init")) {
                if (words.size() != 1) {
                    return usage(err);
                }
                return Init.run(words.getFirst(), from == null ? "hello" : from, Path.of("."), plan || dryRun, out);
            }
            String target;
            Path dir;
            if (args[0].equals("build")) {
                if (words.isEmpty() || words.size() > 2) {
                    return usage(err);
                }
                target = words.get(0);
                dir = Path.of(words.size() == 2 ? words.get(1) : ".");
            } else {
                if (words.size() > 2) {
                    return usage(err);
                }
                target = words.isEmpty() ? Run.ownTarget() : words.getFirst();
                dir = Path.of(words.size() == 2 ? words.get(1) : ".");
                if (words.size() == 1 && !Build.TARGETS.contains(target)) {
                    dir = Path.of(target);          // run FOLDER
                    target = Run.ownTarget();
                }
            }
            if (!Build.TARGETS.contains(target)) {
                err.println("error: no target '" + target + "' (" + String.join(", ", Build.TARGETS) + ")");
                return 2;
            }
            Project p = Project.read(dir.toAbsolutePath().normalize());
            Kit kit = Kit.of(p);
            List<Build.Step> steps = Build.plan(p, kit, target, debug);
            if (args[0].equals("run")) {
                steps.add(Run.step(p, kit, target));
            }
            if (plan || verbose) {
                out.println("superdevkit " + args[0] + " " + target + ": " + p.name() + " (kit " + kit.home() + ")");
            }
            int status = Build.run(steps, plan, dryRun, verbose, out, err);
            if (status == 0 && !plan && !dryRun && args[0].equals("build")) {
                out.println("built " + Build.rel(p, target.equals("snes") ? Build.rom(p)
                        : p.dir().resolve("build").resolve(target).resolve(p.name() + (target.equals("win64") ? ".exe" : ""))));
            }
            return status;
        } catch (Project.Error | Build.Failure e) {
            err.println("error: " + e.getMessage());
            return 1;
        } catch (IOException e) {
            err.println("error: " + e.getMessage());
            return 1;
        }
    }

    private static int png(String[] args, PrintStream err) {
        if (args.length != 5 && args.length != 6) {
            return usage(err);
        }
        try {
            superdevkit.gfx.Png2Snes.Mode mode = superdevkit.gfx.Png2Snes.Mode.valueOf(args[1].toUpperCase(java.util.Locale.ROOT));
            int first = args.length == 6 ? Integer.parseInt(args[5]) : 1;
            if (args.length == 6 && mode != superdevkit.gfx.Png2Snes.Mode.MODE7) {
                throw new IllegalArgumentException("only mode7 takes a first colour");
            }
            superdevkit.gfx.Png2Snes.convert(mode, Path.of(args[2]), args[3], Path.of(args[4]), first);
            return 0;
        } catch (superdevkit.gfx.ConvertException | IOException e) {
            err.println("error: " + e.getMessage());
            return 1;
        } catch (IllegalArgumentException e) {
            err.println("error: png tiles|tiles2|palette|mode7 picture.png NAME out_dir [FIRST]: " + e.getMessage());
            return 2;
        }
    }

    private static int sfx(String[] args, PrintStream out, PrintStream err) {
        if (args.length != 4) {
            return usage(err);
        }
        try {
            Sfx.Report r = Sfx.build(Path.of(args[1]), args[2], Path.of(args[3]));
            out.println("superdevkit sfx: " + args[3] + "/" + args[2] + ".c and .h");
            r.lines().forEach(l -> out.println("  " + l));
            return 0;
        } catch (Sfx.Failure e) {
            e.messages().forEach(m -> err.println("error: " + m));
            return 1;
        } catch (IOException e) {
            err.println("error: " + e.getMessage());
            return 1;
        }
    }

    private static int usage(PrintStream err) {
        err.println("""
                usage: superdevkit init NAME [--from EXAMPLE]   a new project in ./NAME, from an example (hello)
                       superdevkit build TARGET [FOLDER]         snes, linux or win64; the project in FOLDER (.)
                       superdevkit run [TARGET] [FOLDER]         builds and starts it (the desktop; snes in Mesen)
                           --verbose  every step   --plan  the steps only   --dry-run  checks only   --debug  -O0
                       superdevkit png tiles|tiles2|palette|mode7 picture.png NAME out_dir [FIRST]
                       superdevkit sfx assets/sfx/sfx.json NAME out_dir
                       superdevkit --version""");
        return 2;
    }
}
