package dampf.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dampf.Compiler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A 16-bit multiplication by a constant is shifts and adds in A; eight shifts or more in a row are
 * {@code XBA} and a mask, as {@code x << 8} is (exec/mul_by_256.c checks the results against the PC).
 */
class MultiplyByConstantTest {
    @TempDir
    Path tmp;

    /** The instructions of the function {@code f}, without comments and debug lines. */
    private List<String> code(String body) throws Exception {
        Path src = tmp.resolve("t.c");
        Files.writeString(src, "#include <stdint.h>\nuint16_t x, r;\n"
                + "void f(void) { r = " + body + "; }\nvoid test_main(void) { x = 3; f(); }\n");
        Compiler.compile(new Compiler.Options(List.of(src), List.of(), List.of(), "test_main", tmp.resolve("t.sfc"))
                .withBackend(Compiler.Backend.MIR).withInlinedCode(true));
        List<String> out = new ArrayList<>();
        boolean in = false;
        for (String line : Files.readAllLines(tmp.resolve("t.s"))) {
            String t = line.replaceAll(";.*", "").strip().replaceAll("\\s+", " ");
            if (t.equals(".proc _f")) {
                in = true;
            } else if (t.equals(".endproc")) {
                in = false;
            } else if (in && !t.isEmpty() && !t.startsWith(".") && !t.endsWith(":")) {
                out.add(t);
            }
        }
        return out;
    }

    @Test
    void times256IsTheBytesSwapped() throws Exception {
        assertEquals(List.of("lda _x", "xba", "and #$FF00", "sta _r", "rtl"), code("(uint16_t)(x * 256u)"));
    }

    @Test
    void moreShiftsAfterTheSwap() throws Exception {
        assertEquals(List.of("lda _x", "xba", "and #$FF00", "asl a", "asl a", "sta _r", "rtl"),
                code("(uint16_t)(x * 1024u)"));
    }

    @Test
    void eightShiftsBetweenTwoDigits() throws Exception {
        // 257 = 2^8 + 1: x shifted by 8, then x added (from the frame, where its load went)
        List<String> c = code("(uint16_t)(x * 257u)");
        assertEquals(List.of("xba", "and #$FF00", "clc"), c.subList(c.indexOf("xba"), c.indexOf("xba") + 3), c::toString);
        assertFalse(c.contains("asl a"), c::toString);
    }

    @Test
    void fewerThanEightStayShifts() throws Exception {
        assertEquals(List.of("lda _x", "asl a", "asl a", "asl a", "asl a", "asl a", "asl a", "asl a", "sta _r", "rtl"),
                code("(uint16_t)(x * 128u)"));
    }
}
