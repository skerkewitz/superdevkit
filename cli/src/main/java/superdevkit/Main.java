package superdevkit;

import java.io.IOException;
import java.nio.file.Path;
import superdevkit.sfx.Sfx;

/**
 * The superdevkit command (ADR record 31; docs/superdevkit-transition.md, 7.4). Its commands,
 * {@code init}, {@code build} and {@code run}, come with M7; until then it gives its version and
 * converts a game's sound effects ({@code sfx}), and the example's {@code build.sh} and
 * {@code dampf} do the building.
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        if (args.length == 1 && (args[0].equals("--version") || args[0].equals("version"))) {
            System.out.println("superdevkit " + dampf.Main.version());
            return 0;
        }
        if (args.length == 4 && args[0].equals("sfx")) {
            try {
                Sfx.Report r = Sfx.build(Path.of(args[1]), args[2], Path.of(args[3]));
                System.out.println("superdevkit sfx: " + args[3] + "/" + args[2] + ".c and .h");
                r.lines().forEach(l -> System.out.println("  " + l));
                return 0;
            } catch (Sfx.Failure e) {
                e.messages().forEach(m -> System.err.println("error: " + m));
                return 1;
            } catch (IOException e) {
                System.err.println("error: " + e.getMessage());
                return 1;
            }
        }
        System.err.println("""
                usage: superdevkit --version
                       superdevkit sfx assets/sfx/sfx.json NAME out_dir
                           the effects of sfx.json as NAME.c (the samples as BRR) and NAME.h
                           (SFX_<ID> per effect, NAME_bank for sdevkit_sfx_init)
                init, build and run come with milestone M7; until then an example's build.sh builds it,
                with dampf (bin/dampf) for the SNES.""");
        return 2;
    }
}
