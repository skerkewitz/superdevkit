package dampf.frontend.pp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dampf.frontend.SourceMap;
import dampf.frontend.SourcePos;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * dampf's preprocessor on its own: the examples of the C standard (C99 6.10.3.5), the arithmetic of
 * {@code #if}, includes and the line markers, and the features the subset rejects. That it agrees
 * with clang's preprocessor on real code is {@link OracleTest}.
 */
class PreprocessTest {
    @TempDir
    Path tmp;

    private String run(String source, String... defines) throws Exception {
        Path f = tmp.resolve("t.c");
        Files.writeString(f, source);
        return new Preprocess(tmp.resolve("include"), List.of(), List.of(defines)).run(f);
    }

    /** The tokens of preprocessed text, one space between two, markers left out. */
    static String tokens(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (line.startsWith("#")) {
                continue;
            }
            for (Token t : Lexer.lexLine(line, "x", 1, 1)) {
                out.add(t.text());
            }
        }
        return String.join(" ", out);
    }

    private void same(String expected, String source, String... defines) throws Exception {
        assertEquals(tokens(expected), tokens(run(source, defines)));
    }

    @Test
    void theStandardsExample3() throws Exception {
        same("""
                f(2 * (y+1)) + f(2 * (f(2 * (z[0])))) % f(2 * (0)) + t(1);
                f(2 * (2+(3,4)-0,1)) | f(2 * (~ 5)) & f(2 * (0,1))^m(0,1);
                int i[] = { 1, 23, 4, 5, };
                char c[2][6] = { "hello", "" };
                """, """
                #define x 3
                #define f(a) f(x * (a))
                #undef x
                #define x 2
                #define g f
                #define z z[0]
                #define h g(~
                #define m(a) a(w)
                #define w 0,1
                #define t(a) a
                #define p() int
                #define q(x) x
                #define r(x,y) x ## y
                #define str(x) # x
                f(y+1) + f(f(z)) % t(t(g)(0) + t)(1);
                g(x+(3,4)-w) | h 5) & m
                (f)^m(m);
                p() i[q()] = { q(1), r(2,3), r(4,), r(,5), r(,) };
                char c[2][6] = { str(hello), str() };
                """);
    }

    @Test
    void theStandardsExample4() throws Exception {
        same("""
                printf("x" "1" "= %d, x" "2" "= %s", x1, x2);
                fputs("strncmp(\\"abc\\\\0d\\", \\"abc\\", '\\\\4') == 0" ": @\\n", s);
                "hello";
                "hello" ", world"
                """, """
                #define str(s) # s
                #define xstr(s) str(s)
                #define debug(s, t) printf("x" # s "= %d, x" # t "= %s", \\
                 x ## s, x ## t)
                #define glue(a, b) a ## b
                #define xglue(a, b) glue(a, b)
                #define HIGHLOW "hello"
                #define LOW LOW ", world"
                debug(1, 2);
                fputs(str(strncmp("abc\\0d", "abc", '\\4') // this goes away
                 == 0) str(: @\\n), s);
                glue(HIGH, LOW);
                xglue(HIGH, LOW)
                """);
    }

    @Test
    void theStandardsExample5Placemarkers() throws Exception {
        same("int j[] = { 123, 45, 67, 89, 10, 11, 12, };", """
                #define t(x,y,z) x ## y ## z
                int j[] = { t(1,2,3), t(,4,5), t(6,,7), t(8,9,),
                 t(10,,), t(,11,), t(,,12), t(,,) };
                """);
    }

    @Test
    void theStandardsExample7Variadic() throws Exception {
        same("""
                fprintf(stderr, "Flag" );
                fprintf(stderr, "X = %d\\n", x );
                puts( "The first, second, and third items." );
                ((x>y)?puts("x>y"): printf("x is %d but y is %d", x, y));
                """, """
                #define debug(...) fprintf(stderr, __VA_ARGS__)
                #define showlist(...) puts(#__VA_ARGS__)
                #define report(test, ...) ((test)?puts(#test): printf(__VA_ARGS__))
                debug("Flag");
                debug("X = %d\\n", x);
                showlist(The first, second, and third items.);
                report(x>y, "x is %d but y is %d", x, y);
                """);
    }

    @Test
    void aMacroDoesNotExpandItselfAgain() throws Exception {
        same("x + 1 ; a b a ; f ( 2 )", """
                #define x x + 1
                #define a b a
                #define b a b
                #define f(n) f(n)
                x; a; f(2)
                """);
    }

    @Test
    void anEmptyArgumentLeavesItsWhiteSpaceAsGccAndClangDo() throws Exception {
        // found by RandomMacrosTest, seed 2714: the white space before a parameter that gets an
        // empty argument goes to the next token, also after the end of the replacement
        assertEquals("\"( , a)\" \"(a , a)\" \"(, a)\" \"[a b]\" \"[ b]\" 1", tokens(run("""
                #define S(x) #x
                #define T(x) S(x)
                #define f13(p0) p0 ## p0 p0
                #define h(p0) a p0
                #define k(p0)  p0
                T((f13(), a)) T((h(), a)) T((k(), a)) T([h()b]) T([f13()b])
                #if f13() 1
                1
                #endif
                """)));
    }

    @Test
    void conditionsWithTheArithmeticOfIntmax() throws Exception {
        same("yes1 yes2 yes3 yes4 yes5 yes6 yes7", """
                #define A 3
                #if A * 2 == 6 && defined A && !defined(B)
                yes1
                #endif
                #if -1 < 0u
                no
                #else
                yes2
                #endif
                #if 0 && (1 / 0)
                no
                #elif 1 ? 2 : (1 / 0)
                yes3
                #endif
                #if UNDEFINED_NAME == 0 && 0x10 == 16 && 010 == 8 && 'a' == 97 && '\\xff' < 0
                yes4
                #endif
                #ifdef A
                # ifndef A
                no
                # elif 1
                yes5
                # endif
                #endif
                #if (1 << 62) > 0 && (-8 >> 1) == -4 && 18446744073709551615 == -1
                yes6
                #endif
                #if 0
                #if 1
                no
                #else
                no
                #endif
                #elif 2 > 1
                yes7
                #endif
                """);
    }

    @Test
    void definesFromTheCommandLineAndPredefinedMacros() throws Exception {
        same("1 7 199901L 1", "__DAMPF__ N __STDC_VERSION__ F(3)\n", "N=7", "F(x)=1");
    }

    @Test
    void everyTokenMapsBackToItsFileLineAndColumn() throws Exception {
        Path inc = Files.createDirectories(tmp.resolve("include"));
        Files.writeString(inc.resolve("h.h"), "#pragma once\nint in_h;\n");
        String text = run("""
                #include <h.h>
                #include "include/h.h"
                #define TWO 2
                  int a = TWO;  /* a comment
                over two lines */ int b;
                int c = \\
                  3;





                int d;
                """);
        SourceMap map = SourceMap.of(text, "t.c");
        String[] lines = text.split("\n", -1);
        assertEquals(pos("include/h.h", 2), find(map, lines, "in_h"));
        assertEquals(pos("t.c", 4), find(map, lines, "a"));
        assertEquals(pos("t.c", 5), find(map, lines, "b"));
        assertEquals(pos("t.c", 7), find(map, lines, "3"));
        assertEquals(pos("t.c", 13), find(map, lines, "d"));
        assertEquals(1, text.split("in_h", -1).length - 1, "#pragma once: h.h is read once\n" + text);
        // a token not out of a macro is in its column
        int row = 0;
        while (!lines[row].contains(" a ")) {
            row++;
        }
        assertEquals(6, lines[row].indexOf(" a ") + 1, lines[row]);
    }

    private static String pos(String file, int line) {
        return file + ":" + line;
    }

    private static String find(SourceMap map, String[] lines, String word) {
        for (int k = 0; k < lines.length; k++) {
            if (!lines[k].startsWith("#") && java.util.Arrays.asList(lines[k].split("[^A-Za-z0-9_]+")).contains(word)) {
                SourcePos p = map.map(k + 1, 0);
                String f = p.file();
                return (f.contains("/include/") ? "include/" + f.substring(f.lastIndexOf('/') + 1) : Path.of(f).getFileName().toString())
                        + ":" + p.line();
            }
        }
        return "not found";
    }

    @Test
    void featuresOutsideTheSubsetAreRejected() {
        for (String src : List.of("#line 3\n", "#include_next <x.h>\n", "#warning x\n", "#define M <x.h>\n#include M\n",
                "#define v(...) __VA_OPT__(,)\n", "__COUNTER__\n", "#if __has_include(<x.h>)\n#endif\n",
                "#define n(a...) a\n")) {
            PpException e = assertThrows(PpException.class, () -> run(src), src);
            assertTrue(e.getMessage().contains("not supported by dampf's preprocessor"), src + ": " + e.getMessage());
        }
    }

    @Test
    void errorsNameTheirPlace() {
        PpException e = assertThrows(PpException.class, () -> run("int x;\n#if 1\n"));
        assertEquals("unterminated #if", e.getMessage());
        assertEquals(2, e.line());
        e = assertThrows(PpException.class, () -> run("#define f(a, b) a\nf(1)\n"));
        assertEquals("macro \"f\" passed 1 arguments, but takes 2", e.getMessage());
        e = assertThrows(PpException.class, () -> run("\n#error stop here\n"));
        assertEquals("#error stop here", e.getMessage());
        assertEquals(2, e.line());
        e = assertThrows(PpException.class, () -> run("#if 1 / 0\n#endif\n"));
        assertEquals("division by zero in #if", e.getMessage());
    }
}
