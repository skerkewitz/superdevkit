package dampf;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dampf.frontend.CompileError;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A function inlined at every call has no code of its own in the ROM, but keeps its entry in the
 * timing report and its budget (ADR-6FVMCY48). A function still called somewhere, or
 * named by hand-written assembly, keeps its code.
 */
class InlinedWithoutCodeTest {
    @TempDir
    Path tmp;

    private static final Path PROGRAM = Path.of("compiler/src/test/resources/exec/inlined_everywhere.c");

    private Compiler.Result build(Path source, String name) throws Exception {
        return Compiler.compile(new Compiler.Options(List.of(source), List.of(), List.of(), "test_main", tmp.resolve(name + ".sfc"),
                Map.of(), Compiler.Backend.MIR));
    }

    private static boolean labelled(Compiler.Result r, String label) throws Exception {
        return Files.readAllLines(r.rom().labels()).stream().anyMatch(l -> l.endsWith(" ." + label));
    }

    @Test
    void theReportKeepsWhatTheRomLeavesOut() throws Exception {
        Compiler.Result r = build(PROGRAM, "inl");
        for (String f : List.of("_clamp", "_step")) {
            assertFalse(labelled(r, f), f + " has no code of its own");
            assertTrue(r.timing().containsKey(f) && r.timing().get(f)[1] > 0, f + " is in the timing report");
        }
        assertTrue(labelled(r, "_big") && labelled(r, "_test_main"), "what is called keeps its code");
        String report = Files.readString(tmp.resolve("inl.timing.txt"));
        assertTrue(report.contains("_clamp   (" + Compiler.INLINED_EVERYWHERE + ")"), report);
        assertFalse(String.join("\n", r.assembly()).contains(".proc _clamp"), "no body in the assembly");
        Compiler.Result kept = Compiler.compile(new Compiler.Options(List.of(PROGRAM), List.of(), List.of(), "test_main",
                tmp.resolve("kept.sfc"), Map.of(), Compiler.Backend.MIR).withInlinedCode(true));
        assertTrue(labelled(kept, "_clamp"), "with the option, as before");
    }

    @Test
    void aBudgetIsCheckedWithoutTheCode() throws Exception {
        String code = Files.readString(PROGRAM).replace("#include <stdint.h>", "#include <stdint.h>\n#include <dampf.h>")
                .replace("static uint16_t step(", "DAMPF_BUDGET(5) static uint16_t step(");
        Path f = tmp.resolve("budget.c");
        Files.writeString(f, code);
        CompileError e = assertThrows(CompileError.class, () -> build(f, "budget"));
        assertTrue(e.diagnostics().getFirst().message().contains("'step' takes up to"), e.getMessage());
    }
}
