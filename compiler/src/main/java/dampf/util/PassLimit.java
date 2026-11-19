package dampf.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/**
 * A limit on pass applications, to find the pass that broke a program (LLVM has it as
 * {@code -opt-bisect-limit}). Every application of a MIR or LIR pass that changed something
 * counts, in the order they run; once the limit is reached no pass runs any more. A program that
 * is wrong with all passes and right with none is then bisected to the first limit n at which it
 * is wrong, and the n-th application is the one that broke it ({@code dev/bisect_passes.sh}).
 *
 * <p>An application is one pass on one function (inlining: one call site). The passes that work
 * on the whole module at once ({@code ParamRanges}, {@code PointerParams}, {@code IndexParams})
 * count once per run. Not counted, and always done: lowering, the code generator's selection
 * (its {@code SelectionPlan} decisions included) and {@code BranchRelax}, which are part of
 * making code at all.
 *
 * <p>The limit comes from {@code dampf build --opt-limit n}, or else from the system property
 * {@value #PROPERTY} (the tests: {@code -PoptLimit=n}) or the environment variable {@value #ENV}.
 * When the limit is reached, the last application is named on standard error and, if
 * {@value #LOG_PROPERTY} or {@value #LOG_ENV} names a file, appended to it.
 */
public final class PassLimit {
    public static final String PROPERTY = "dampf.optLimit";
    public static final String ENV = "DAMPF_OPT_LIMIT";
    public static final String LOG_PROPERTY = "dampf.optLimit.log";
    public static final String LOG_ENV = "DAMPF_OPT_LIMIT_LOG";

    private final long limit;
    private final String program;
    private long applied;
    private String last;

    /**
     * @param limit   how many applications may run; negative: no limit
     * @param program what the limit is applied to, for the report (a source file's name)
     */
    public PassLimit(long limit, String program) {
        this.limit = limit;
        this.program = program;
    }

    /** No limit. */
    public static PassLimit none() {
        return new PassLimit(-1, "");
    }

    /** The limit the environment sets, for this program. */
    public static PassLimit configured(String program) {
        return new PassLimit(configured(), program);
    }

    /** The limit of the system property, else of the environment variable; -1 if neither is set. */
    public static long configured() {
        String v = System.getProperty(PROPERTY);
        if (v == null || v.isBlank()) {
            v = System.getenv(ENV);
        }
        if (v == null || v.isBlank()) {
            return -1;
        }
        try {
            return Long.parseLong(v.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("the pass limit '" + v + "' is no number");
        }
    }

    /** Whether there is a limit. */
    public boolean limited() {
        return limit >= 0;
    }

    /** Whether another application may run. */
    public boolean allows() {
        return limit < 0 || applied < limit;
    }

    /** Runs a pass that says whether it changed something, unless the limit is reached. */
    public boolean apply(String pass, String function, BooleanSupplier run) {
        if (!allows()) {
            return false;
        }
        boolean changed = run.getAsBoolean();
        if (changed) {
            count(pass, function);
        }
        return changed;
    }

    /** Runs a pass that returns the number of its changes, unless the limit is reached. */
    public int apply(String pass, String function, IntSupplier run) {
        if (!allows()) {
            return 0;
        }
        int changes = run.getAsInt();
        if (changes > 0) {
            count(pass, function);
        }
        return changes;
    }

    /** Counts an application the caller made (only after {@link #allows()} said it may). */
    public void count(String pass, String function) {
        applied++;
        last = pass + " on " + function;
        if (applied == limit) {
            report();
        }
    }

    /** The applications so far. */
    public long applied() {
        return applied;
    }

    /** The last application, as "pass on function"; null before the first. */
    public String last() {
        return last;
    }

    private void report() {
        String line = "dampf: pass limit " + limit + " reached" + (program.isEmpty() ? "" : " in " + program)
                + "; the last application: " + last;
        System.err.println(line);
        String log = System.getProperty(LOG_PROPERTY);
        if (log == null || log.isBlank()) {
            log = System.getenv(LOG_ENV);
        }
        if (log != null && !log.isBlank()) {
            try {
                Files.writeString(Path.of(log), line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                System.err.println("dampf: cannot write " + log + ": " + e.getMessage());
            }
        }
    }
}
