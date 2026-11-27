package dampf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dampf.frontend.CompileError;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** DAMPF_BUDGET: the worst case of a function against its budget, at build time. */
class BudgetTest {
    @TempDir
    Path tmp;

    private static final String PROGRAM = """
            #include <stdint.h>
            #include <dampf.h>
            uint8_t t[32];
            uint16_t s;
            BUDGET void sum(void) {
                uint8_t i;
                for (i = 0; i < 32; i++) {
                    s = (uint16_t)(s + t[i]);
                }
            }
            void test_main(void) { sum(); }
            """;

    private Compiler.Result build(String code) throws Exception {
        Path f = tmp.resolve("b.c");
        Files.writeString(f, code);
        return Compiler.compile(new Compiler.Options(List.of(f), List.of(), List.of(), "test_main", tmp.resolve("b.sfc"),
                Map.of(), Compiler.Backend.MIR));
    }

    private long worst() throws Exception {
        return build(PROGRAM.replace("BUDGET ", "")).timing().get("_sum")[1];
    }

    @Test
    void aFunctionWithinItsBudgetBuilds() throws Exception {
        long w = worst();
        Compiler.Result r = build(PROGRAM.replace("BUDGET", "DAMPF_BUDGET(" + (2 * w) + ")"));
        assertTrue(r.warnings().isEmpty(), r.warnings().toString());
        // within 90 %: a warning
        Compiler.Result close = build(PROGRAM.replace("BUDGET", "DAMPF_BUDGET(" + w + ")"));
        assertEquals(1, close.warnings().size());
        assertTrue(close.warnings().getFirst().contains("100 % of its budget of " + w), close.warnings().getFirst());
    }

    @Test
    void aFunctionOverItsBudgetIsAnError() throws Exception {
        long w = worst();
        CompileError e = assertThrows(CompileError.class,
                () -> build(PROGRAM.replace("BUDGET", "DAMPF_BUDGET(" + (w - 1) + ")")));
        String msg = e.diagnostics().getFirst().message();
        assertTrue(msg.contains("'sum' takes up to " + w + " master cycles, over its budget of " + (w - 1)), msg);
        assertTrue(msg.contains("DAMPF_BUDGET counts master cycles now, not CPU cycles"), msg);
        assertEquals(5, e.diagnostics().getFirst().pos().line());
    }

    @Test
    void aBudgetInTheOldUnitSaysSo() throws Exception {
        // a budget written in CPU cycles, as up to dampf 0.10: about a sixth of the master cycles
        long w = worst();
        CompileError old = assertThrows(CompileError.class,
                () -> build(PROGRAM.replace("BUDGET", "DAMPF_BUDGET(" + (w / 7) + ")")));
        assertTrue(old.diagnostics().getFirst().message().contains("a budget written in CPU cycles needs about 6.5 times its number"),
                old.diagnostics().getFirst().message());
        // far below even that: no hint
        CompileError far = assertThrows(CompileError.class,
                () -> build(PROGRAM.replace("BUDGET", "DAMPF_BUDGET(" + (w / 20) + ")")));
        assertTrue(!far.diagnostics().getFirst().message().contains("CPU cycles"), far.diagnostics().getFirst().message());
    }

    @Test
    void aFunctionWithoutAWorstCaseCannotHaveABudget() throws Exception {
        String unbounded = PROGRAM.replace("i < 32", "t[i] != 0");
        CompileError e = assertThrows(CompileError.class, () -> build(unbounded.replace("BUDGET", "DAMPF_BUDGET(5000)")));
        String msg = e.diagnostics().getFirst().message();
        assertTrue(msg.contains("'sum' has no worst case (loop at b.c:7)"), msg);
    }

    @Test
    void aBudgetBelongsInFrontOfADefinition() throws Exception {
        CompileError e = assertThrows(CompileError.class, () -> build(PROGRAM.replace("BUDGET ", "")
                .replace("void test_main", "DAMPF_BUDGET(100) void sum(void);\nvoid test_main")));
        assertTrue(e.diagnostics().getFirst().message().contains("DAMPF_BUDGET belongs in front of a function definition"));
    }
}
