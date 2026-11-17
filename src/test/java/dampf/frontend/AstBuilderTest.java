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

    private Program buildFiles(String... codes) throws IOException, CompileError {
        List<Path> files = new java.util.ArrayList<>();
        for (int i = 0; i < codes.length; i++) {
            Path f = tmp.resolve("f" + i + ".c");
            Files.writeString(f, codes[i]);
            files.add(f);
        }
        return Compiler.frontEnd(new Compiler.Options(files, List.of(tmp), List.of(), "test_main", tmp.resolve("t.sfc")));
    }

    @Test
    void rangeHintsAreCheckedWhenTheyAreRead() {
        String head = "#include <stdint.h>\n#include <dampf.h>\nuint8_t g;\n";
        assertTrue(fails(head + "void f(void) { DAMPF_RANGE(g, 0, 3); }\n").diagnostics().getFirst().message()
                .contains("DAMPF_RANGE needs a local variable or parameter of an integer type"));
        assertTrue(fails(head + "void f(uint8_t a) { DAMPF_RANGE(a, 0, g); }\n").diagnostics().getFirst().message()
                .contains("the bounds of DAMPF_RANGE must be constants"));
        assertTrue(fails(head + "void f(uint8_t a) { DAMPF_RANGE(a, 5, 3); }\n").diagnostics().getFirst().message()
                .contains("the lowest value is above the highest"));
        assertTrue(fails(head + "void f(uint8_t a) { DAMPF_RANGE(a, 0, 300); }\n").diagnostics().getFirst().message()
                .contains("300 is outside the range of uint8_t"));
    }

    @Test
    void typedefMayHaveTheNameOfItsStructTag() throws Exception {
        Program p = build("""
                #include <stdint.h>
                typedef struct Bubble { int16_t x; } Bubble;
                typedef struct Pair Pair;
                struct Pair { Bubble a, b; };
                Bubble one; Pair two; struct Bubble three;
                """);
        assertEquals(3, p.globals().size());
    }

    @Test
    void aGlobalDefinedInTwoFilesIsAnError() throws Exception {
        CompileError e = assertThrows(CompileError.class, () -> buildFiles(
                "#include <stdint.h>\nuint8_t phase;\n", "#include <stdint.h>\nuint8_t phase;\n"));
        String msg = e.diagnostics().getFirst().message();
        assertTrue(msg.contains("redefinition of 'phase' (first defined at"), msg);
        // the same through a header both files include
        Files.writeString(tmp.resolve("g.h"), "#include <stdint.h>\nuint8_t shared;\n");
        assertThrows(CompileError.class, () -> buildFiles("#include \"g.h\"\n", "#include \"g.h\"\n"));
        // fine: repeated in one file, extern in the other, static in both
        buildFiles("#include <stdint.h>\nuint8_t a; uint8_t a = 1; static uint8_t s;\n",
                "#include <stdint.h>\nextern uint8_t a; static uint8_t s;\n");
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

    // sizeof must give the PC's value (gcc on x86-64): pointers are 8 bytes there, structs padded

    @Test
    void sizeofOfAPointerIsRejected() {
        CompileError e = fails("""
                #include <stdint.h>
                uint32_t s;
                void test_main(void) { s = sizeof(uint8_t *); }
                """);
        String msg = e.diagnostics().getFirst().message();
        assertTrue(msg.contains("is 4 in dampf but 8 on the PC"), msg);
        assertEquals(3, e.diagnostics().getFirst().pos().line());
    }

    @Test
    void sizeofOfAPaddedStructIsRejected() {
        CompileError e = fails("""
                #include <stdint.h>
                typedef struct { uint8_t a; int16_t b; } Pair;
                Pair p;
                uint32_t s;
                void test_main(void) { s = sizeof p; }
                """);
        assertTrue(e.diagnostics().getFirst().message().contains("is 3 in dampf but 4 on the PC"), e.toString());
    }

    @Test
    void theElementCountOfPointersAndPaddedStructsIsAllowed() throws Exception {
        Program p = build("""
                #include <stdint.h>
                typedef struct { uint8_t a; int16_t b; } Pair;
                Pair pairs[5];
                const char *names[3] = { "a", "b", "c" };
                uint32_t n1;
                uint32_t n2;
                void test_main(void) { n1 = sizeof names / sizeof names[0]; n2 = sizeof(pairs) / sizeof(Pair); }
                """);
        assertTrue(p.function("test_main").isDefined());
        // anything else with such a sizeof is still rejected
        assertEquals(4, fails("""
                #include <stdint.h>
                const char *names[3] = { "a", "b", "c" };
                uint32_t n;
                void test_main(void) { n = sizeof names / 2; }
                """).diagnostics().getFirst().pos().line());
    }

    @Test
    void sizeofOfAStructWithoutPaddingIsAllowed() throws Exception {
        build("""
                #include <stdint.h>
                typedef struct { uint8_t a; uint8_t b; int16_t c; } Quad;
                uint32_t s;
                void test_main(void) { s = sizeof(Quad); }
                """);
    }
}

