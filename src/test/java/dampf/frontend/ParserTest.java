package dampf.frontend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ParserTest {
    @TempDir
    Path tmp;

    private Parser.Result preprocessAndParse(String name, String code) throws IOException {
        Path f = tmp.resolve(name);
        Files.writeString(f, code);
        Preprocessor pp = new Preprocessor("gcc", SystemHeaders.directory(), List.of(tmp), List.of());
        return Parser.parse(pp.run(f), f.toString());
    }

    @Test
    void parsesTypedefsFromDampfStdint() throws IOException {
        Parser.Result r = preprocessAndParse("ok.c", """
                #include <stdint.h>
                typedef int16_t fx8_8;
                static uint8_t counter;
                fx8_8 scale(fx8_8 v, uint8_t s) { return (fx8_8)((v * s) >> 8); }
                """);
        assertTrue(r.ok(), () -> r.diagnostics().toString());
    }

    @Test
    void reportsSyntaxErrorsAtTheOriginalLine() throws IOException {
        Files.writeString(tmp.resolve("defs.h"), "#define ONE 1\n#define TWO 2\n");
        Parser.Result r = preprocessAndParse("bad.c", """
                #include "defs.h"
                #include <stdint.h>

                uint8_t f(void) {
                    return ONE + ;
                }
                """);
        assertFalse(r.ok());
        SourcePos pos = r.diagnostics().getFirst().pos();
        assertTrue(pos.file().endsWith("bad.c"), pos.toString());
        assertEquals(5, pos.line());
    }

    @Test
    void hostMacrosAreNotDefined() throws IOException {
        Parser.Result r = preprocessAndParse("host.c", """
                #if defined(__x86_64__) || defined(__GNUC__) || !defined(__DAMPF__)
                #error host leaked
                #endif
                """);
        assertTrue(r.ok());
    }
}
