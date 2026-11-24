package dampf.frontend;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dampf.Compiler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Plain char is text (docs/subset.md, decision 1; ADR-6FM063YZ): its value may be copied, converted
 * to another 8-bit type, tested for zero and compared with == and != against a char or a
 * constant from 0 to 127, and nothing else, so its signedness never shows.
 */
class PlainCharTest {
    private static final String HEAD = """
            #include <stdint.h>
            char c, d, buf[8];
            const char *s = "TEXT";
            uint8_t u, table[256];
            int8_t i8;
            int16_t i16;
            uint16_t u16;
            int32_t i32;
            static char pass(char x) { return x; }
            static void take16(int16_t v) { i16 = v; }
            """;

    @TempDir
    Path tmp;

    private void build(String body) throws IOException, CompileError {
        build("", body);
    }

    private void build(String top, String body) throws IOException, CompileError {
        Path f = tmp.resolve("t.c");
        Files.writeString(f, HEAD + top + "void test_main(void) {\n" + body + "\n}\n");
        Compiler.frontEnd(new Compiler.Options(List.of(f), List.of(), List.of(), "test_main", tmp.resolve("t.sfc")));
    }

    private String fails(String body) {
        CompileError e = assertThrows(CompileError.class, () -> build(body), body);
        return e.diagnostics().getFirst().message();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // copying, also through parameters, results and arrays
        "d = c;", "buf[0] = c;", "buf[1] = *s;", "c = pass(d);", "c = s[2];", "char e = c; d = e;",
        // stored from a number: the same byte on every PC
        "c = 'a';", "c = (char)('0' + u % 10);", "c = 200;", "c = u;", "c = (char)i16;", "buf[2] = 0;",
        // converted to another 8-bit type
        "u = c;", "i8 = c;", "u = (uint8_t)c;", "i8 = (int8_t)*s;",
        // == and != against a char or a constant from 0 to 127
        "u = c == 'a';", "u = c != '\\0';", "u = c == 0;", "u = 'x' == c;", "u = c == d;", "u = *s != buf[0];",
        "u = c == 127;",
        // tests for zero
        "if (c) { u = 1; }", "while (*s) { s++; }", "u = !c;", "u = c && d;", "u = c || u;", "u = c ? 1 : 2;",
        "for (; *s != 0; s++) { }",
        // switch with case values from 0 to 127
        "switch (c) { case 'a': u = 1; break; case '\\n': u = 2; break; default: break; }",
        // the pointer, not the char
        "s = s + 1;", "u = (uint8_t)(s - (const char *)buf);", "s++;", "c = *(s + 2);",
        // the number through a cast to an 8-bit type
        "u = (uint8_t)((uint8_t)c - '0');", "i16 = (int16_t)(uint8_t)c;", "u = table[(uint8_t)c];",
        "u = (uint8_t)c >= '0' && (uint8_t)c <= '9';", "i16 = i8 = c;",
        // no value is read
        "u = sizeof c;", "u = sizeof(char);", "char *p = &c; *p = 'b';",
        // int8_t is a number, not text
        "i8 = (int8_t)(i8 + 1); u = (uint8_t)(i8 >> 1); i16 = i8;",
    })
    void allowed(String body) {
        assertDoesNotThrow(() -> build(body), body);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // arithmetic
        "i16 = c + 1;", "u = (uint8_t)(c - '0');", "u = (uint8_t)(c - ' ');", "i16 = c * 2;", "i16 = c / 2;",
        "i16 = c % 10;", "i16 = 1 + c;", "i16 = -c;", "i16 = +c;",
        // shifts and bit operations
        "i16 = c << 1;", "i16 = c >> 1;", "u = (uint8_t)(c & 0x7f);", "i16 = c | 1;", "i16 = c ^ d;", "i16 = ~c;",
        // relational comparisons
        "u = c < 'a';", "u = c <= 'z';", "u = c > 0;", "u = c >= ' ';", "u = d < c;",
        // == and != against a number that is not a char or a constant from 0 to 127
        "u = c == -1;", "u = c == 200;", "u = c != u;", "u = i8 == c;",
        // changing a char in place
        "c++;", "++c;", "c--;", "--c;", "c += 1;", "c -= 'a';", "c |= 0x20;", "d = c++;",
        // as an index or a pointer offset
        "u = table[c];", "u = table[*s];", "s = s + c;",
        // conversions to a wider type
        "i16 = c;", "u16 = *s;", "i32 = c;", "take16(c);",
        // ?: with a char branch has the type int
        "c = u ? c : ' ';",
    })
    void rejected(String body) {
        String msg = fails(body);
        assertTrue(msg.contains("plain char is text") && msg.contains("use uint8_t or int8_t"), body + ": " + msg);
    }

    @Test
    void theMessageNamesTheVariable() {
        assertTrue(fails("i16 = d + 1;").endsWith("cast the char: (uint8_t)d"), fails("i16 = d + 1;"));
    }

    @Test
    void aCastToAWiderTypeIsRejected() {
        for (String body : List.of("i16 = (int16_t)c;", "u16 = (uint16_t)*s;", "i32 = (int32_t)buf[0];", "s = (const char *)c;")) {
            String msg = fails(body);
            assertTrue(msg.contains("a cast of a plain char to") && msg.contains("cast to uint8_t or int8_t first"),
                    body + ": " + msg);
        }
    }

    @Test
    void aSwitchOnACharNeedsCaseValuesFrom0To127() {
        String msg = fails("switch (c) { case 'a': break; case -1: break; }");
        assertTrue(msg.contains("case -1 on a plain char") && msg.contains("or switch on (uint8_t)"), msg);
        assertTrue(fails("switch (*s) { case 200: break; }").contains("case 200 on a plain char"));
        // the conversion of the controlling value is fine
        assertDoesNotThrow(() -> build("switch ((uint8_t)c) { case 200: break; }"));
    }

    @Test
    void aTypedefOfCharIsText() {
        CompileError e = assertThrows(CompileError.class, () -> build("typedef char letter;\nletter l = 'a';\n", "i16 = l + 1;"));
        assertTrue(e.diagnostics().getFirst().message().contains("plain char is text"), e.getMessage());
    }

    @Test
    void signedAndUnsignedCharStayRejectedInGameCode() {
        assertTrue(fails("unsigned char x = 1; u = x;").contains("is not allowed: use uint8_t"));
        assertTrue(fails("signed char x = 1; i8 = x;").contains("is not allowed: use int8_t"));
    }
}
