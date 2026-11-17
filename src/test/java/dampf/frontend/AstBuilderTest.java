package dampf.frontend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dampf.Compiler;
import dampf.ast.Program;
import dampf.ast.Type;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AstBuilderTest {
    @TempDir
    Path tmp;

    private Program build(String code) throws IOException, CompileError {
        Path f = tmp.resolve("t.c");
        Files.writeString(f, code);
        return Compiler.frontEnd(new Compiler.Options(List.of(f), List.of(), List.of(), "test_main", tmp.resolve("t.sfc")));
    }

    private CompileError fails(String code) {
        return assertThrows(CompileError.class, () -> build(code));
    }

    @Test
    void stdintTypesMeanWhatTheyMeanOnThePc() throws Exception {
        Program p = build("""
                #include <stdint.h>
                int8_t a; uint8_t b; int16_t c; uint16_t d;
                """);
        assertEquals(List.of(Type.I8, Type.U8, Type.I16, Type.U16), p.globals().stream().map(v -> v.type()).toList());
    }

    @Test
    void rejectsPlainIntWithTheFixedWidthReplacement() {
        CompileError e = fails("unsigned short x;\n");
        String msg = e.diagnostics().getFirst().message();
        assertTrue(msg.contains("'unsigned short' is not allowed: use uint16_t"), msg);
        assertEquals(1, e.diagnostics().getFirst().pos().line());
    }

    @Test
    void rejectsLong() {
        assertTrue(fails("long x;\n").getMessage().contains("64 bit on the PC"));
    }

    @Test
    void rejectsLargeDecimalConstantsThatAreLongOnThePc() {
        assertTrue(fails("#include <stdint.h>\nuint16_t x = 3000000000;\n").getMessage().contains("64-bit long"));
    }

    @Test
    void namesTheCycleOfARecursion() {
        CompileError e = fails("""
                #include <stdint.h>
                static void pop_chain(void);
                static void update_bubble(void) { pop_chain(); }
                static void pop_chain(void) { update_bubble(); }
                void test_main(void) { update_bubble(); }
                """);
        assertTrue(e.getMessage().contains("recursion is not supported: pop_chain → update_bubble → pop_chain"), e.getMessage());
    }

    @Test
    void reportsCallsToFunctionsThatAreNeverDefined() {
        CompileError e = fails("""
                void missing(void);
                void test_main(void) { missing(); }
                """);
        assertTrue(e.getMessage().contains("'missing' is called but never defined"), e.getMessage());
        assertEquals(2, e.diagnostics().getFirst().pos().line());
    }

    @Test
    void constantsGetTheirCTypes() throws Exception {
        // 0xFFFFFFFF is unsigned int, 2147483647 is int, -1 converted to uint16_t is 65535
        Program p = build("""
                #include <stdint.h>
                uint16_t a = 0xFFFFFFFF;
                uint16_t b = -1;
                int8_t c = 200;
                uint8_t d = 'A';
                """);
        assertEquals(65535L, p.initialScalar(p.global("a")));
        assertEquals(65535L, p.initialScalar(p.global("b")));
        assertEquals(-56L, p.initialScalar(p.global("c")));
        assertEquals(65L, p.initialScalar(p.global("d")));
    }

    @Test
    void adjacentStringLiteralsAreJoined() {
        // the lexer makes one token of adjacent literals; the pieces must be joined, not kept with quotes
        assertEquals("VERSION 1.4.0", AstBuilder.unescape("\"VERSION \" \"1.4.0\""));
        assertEquals("A\"B\n", AstBuilder.unescape("\"A\\\"\"\n   \"B\\n\""));
        assertEquals("\u0041", AstBuilder.unescape("\"\\x41\""));
    }

    @Test
    void unsupportedConstructsSaySo() {
        assertTrue(fails("#include <stdint.h>\nunion u { uint8_t a; };\n").getMessage().contains("unions are not supported"));
        assertTrue(fails("#include <stdint.h>\nvoid f(uint8_t x) { again: goto again; }\n").getMessage().contains("not supported yet: labels and goto"));
    }
}
