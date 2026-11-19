package superdevkit;

import java.io.PrintStream;

/** The command's entry with its own streams, for the tests of other packages. */
public final class MainAccess {
    private MainAccess() {
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        return Main.run(args, out, err);
    }
}
