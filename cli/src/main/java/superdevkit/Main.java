package superdevkit;

/**
 * The superdevkit command (ADR record 31; docs/superdevkit-transition.md, 7.4). Its commands,
 * {@code init}, {@code build} and {@code run}, come with M7; until then it gives its version, and
 * the example's {@code build.sh} and {@code dampf} do the building.
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        if (args.length == 1 && (args[0].equals("--version") || args[0].equals("version"))) {
            System.out.println("superdevkit " + dampf.Main.version());
            return;
        }
        System.err.println("""
                usage: superdevkit --version
                init, build and run come with milestone M7; until then an example's build.sh builds it,
                with dampf (bin/dampf) for the SNES.""");
        System.exit(2);
    }
}
