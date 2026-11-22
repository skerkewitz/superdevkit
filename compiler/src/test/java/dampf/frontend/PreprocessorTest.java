package dampf.frontend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A failing preprocessor run is an ordinary compile error at its place. */
class PreprocessorTest {
    @TempDir
    Path tmp;

    @Test
    void aMissingHeaderIsACompileErrorAtItsLine() throws Exception {
        Path f = tmp.resolve("inc.c");
        Files.writeString(f, "#include <stdint.h>\n#include <stdio.h>\n");
        Preprocessor pp = new Preprocessor(SystemHeaders.directory(), List.of(), List.of());
        CompileError e = assertThrows(CompileError.class, () -> pp.run(f));
        Diagnostic d = e.diagnostics().getFirst();
        assertEquals(2, d.pos().line(), d.toString());
        assertTrue(d.message().contains("stdio.h: No such file or directory"), d.toString());
    }

    @Test
    void aFeatureOutsideTheSubsetIsNamed() throws Exception {
        Path f = tmp.resolve("a.c");
        Files.writeString(f, "int x;\n#line 7\n");
        Preprocessor pp = new Preprocessor(SystemHeaders.directory(), List.of(), List.of());
        CompileError e = assertThrows(CompileError.class, () -> pp.run(f));
        Diagnostic d = e.diagnostics().getFirst();
        assertEquals("#line is not supported by dampf's preprocessor", d.message());
        assertEquals(2, d.pos().line());
    }
}
