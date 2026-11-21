package dampf.exec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * One share of {@code dev/random_soak.sh}: the random programs of one generator with the seeds
 * FIRST to FIRST + COUNT - 1, checked as {@link RandomProgramTest} checks them, one after another
 * in this process (the script starts several of them at once). Prints a line per failure (its
 * seed and the message, which names the command that runs it again) and a count at the end;
 * exits with 1 if one failed.
 *
 * <pre>
 *   java -cp ... dampf.exec.RandomSoak loop|random FIRST COUNT WORKDIR
 *   java -cp ... dampf.exec.RandomSoak first loop|random      the first seed when none is given
 * </pre>
 */
public final class RandomSoak {
    private RandomSoak() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 2 && args[0].equals("first")) {
            System.out.println(RandomProgramTest.firstSeed(args[1].equals("loop") ? RandomProgramTest.LOOP : RandomProgramTest.RANDOM));
            return;
        }
        if (args.length != 4 || !args[0].equals("loop") && !args[0].equals("random")) {
            System.err.println("usage: RandomSoak loop|random FIRST COUNT WORKDIR");
            System.exit(2);
        }
        String factory = args[0].equals("loop") ? RandomProgramTest.LOOP : RandomProgramTest.RANDOM;
        long first = Long.parseLong(args[1]);
        long count = Long.parseLong(args[2]);
        Path work = Files.createDirectories(Path.of(args[3]));
        int failed = 0;
        for (long seed = first; seed < first + count; seed++) {
            try {
                RandomProgramTest.check(seed, factory, work);
            } catch (Throwable t) {
                failed++;
                System.out.println(args[0] + " seed " + seed + " FAILED: " + t.getMessage());
                System.out.flush();
            }
            delete(work.resolve("seed" + seed));
        }
        System.out.println(args[0] + " " + first + ".." + (first + count - 1) + ": " + count + " programs, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void delete(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> all = Files.walk(dir)) {
            for (Path p : all.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
