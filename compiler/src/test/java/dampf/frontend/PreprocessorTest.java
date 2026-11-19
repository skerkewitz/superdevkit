package dampf.frontend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A failing preprocessor run is an ordinary compile error at gcc's position. */
class PreprocessorTest {
    @TempDir
    Path tmp;

    @Test
    void aMissingHeaderIsACompileErrorAtItsLine() throws Exception {
        Path f = tmp.resolve("inc.c");
        Files.writeString(f, "#include <stdint.h>\n#include <stdio.h>\n");
        Preprocessor pp = new Preprocessor("gcc", SystemHeaders.directory(), List.of(), List.of());
        CompileError e = assertThrows(CompileError.class, () -> pp.run(f));
        Diagnostic d = e.diagnostics().getFirst();
        assertEquals(2, d.pos().line(), d.toString());
        assertTrue(d.message().contains("stdio.h: No such file or directory"), d.toString());
    }

    @Test
    void aMissingGccIsNamedNotAStackTrace() throws Exception {
        Path f = tmp.resolve("a.c");
        Files.writeString(f, "int x;\n");
        Preprocessor pp = new Preprocessor("dampf-no-such-gcc", SystemHeaders.directory(), List.of(), List.of());
        CompileError e = assertThrows(CompileError.class, () -> pp.run(f));
        assertEquals("cannot run dampf-no-such-gcc, the preprocessor dampf needs: is it installed and on the PATH?",
                e.diagnostics().getFirst().message());
    }
}
