package dampf.frontend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dampf.Compiler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** One run reports every error of every file: one per top-level declaration at most. */
class AllErrorsTest {

    @Test
    void everyDeclarationAndEveryFileIsReported(@TempDir Path dir) throws IOException {
        Path a = Files.writeString(dir.resolve("a.c"), """
                #include <stdint.h>
                int a;
                long b;
                uint8_t fine;
                void f(void) { float x = 1; double y = 2; }
                """);
        Path b = Files.writeString(dir.resolve("b.c"), """
                #include <stdint.h>
                void g(void) { goto out; out: ; }
                void main(void) { }
                """);
        CompileError e = assertThrows(CompileError.class, () -> Compiler.frontEnd(
                new Compiler.Options(List.of(a, b), List.of(), List.of(), "main", dir.resolve("x.sfc"))));
        assertEquals(List.of("a.c:2", "a.c:3", "a.c:5", "b.c:2"),
                e.diagnostics().stream().map(d -> Path.of(d.pos().file()).getFileName() + ":" + d.pos().line()).toList(),
                e.getMessage());
    }
}
