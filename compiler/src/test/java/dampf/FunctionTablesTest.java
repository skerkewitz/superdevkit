package dampf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dampf.ast.Function;
import dampf.ast.Program;
import dampf.check.CallGraph;
import dampf.frontend.CompileError;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Function pointers in constant tables (ADR-6F0R668G, docs/subset.md): what the front end
 * accepts and rejects, the call graph through a table, and the timing of a table call. The
 * calls themselves run in the execution test {@code exec/function_tables.c}.
 */
class FunctionTablesTest {
    @TempDir
    Path tmp;

    private static final String HEAD = """
            #include <stdint.h>
            static void a(void) { }
            static void b(void) { }
            static void g(uint8_t x) { (void)x; }
            """;

    private Program frontEnd(String code) throws Exception {
        Path f = tmp.resolve("t.c");
        Files.writeString(f, code);
        return Compiler.frontEnd(new Compiler.Options(List.of(f), List.of(), List.of(), "test_main", tmp.resolve("t.sfc")));
    }

    private String error(String code) {
        CompileError e = assertThrows(CompileError.class, () -> frontEnd(code));
        return e.diagnostics().getFirst().message();
    }

    private void rejects(String body, String message) {
        String m = error(HEAD + body);
        assertTrue(m.contains(message), m);
    }

    @Test
    void acceptsTheFormsOfAConstantTable() throws Exception {
        Program p = frontEnd(HEAD + """
                typedef void (*Handler)(void);
                static const Handler typed[2] = { a, &b };
                static void (*const plain[])(void) = { b, a };
                static void (*const one)(void) = a;
                static void (*const grid[2][2])(void) = { { a, b }, { b, a } };
                typedef struct { uint8_t cost; void (*run)(uint8_t); } Rule;
                static const Rule rules[2] = { { 1, g }, { 2, g } };
                void test_main(void)
                {
                    uint8_t i;
                    static void (*const local[1])(void) = { b };
                    for (i = 0; i < 2; i++) {
                        typed[i]();
                        (*plain[i])();
                        grid[i][1 - i]();
                        rules[i].run(rules[i].cost);
                    }
                    one();
                    local[0]();
                }
                """);
        Function main = p.function("test_main");
        List<String> callees = CallGraph.of(p).callsFrom(main).stream().map(e -> e.callee().name()).toList();
        assertEquals(List.of("a", "b", "b", "a", "a", "b", "g", "a", "b"), callees);
    }

    @Test
    void aCallReachesOnlyTheEntriesItsPlaceCanBe() throws Exception {
        Program p = frontEnd(HEAD + """
                typedef struct { void (*first)(void); void (*second)(void); } Pair;
                static const Pair pairs[2] = { { a, b }, { a, b } };
                static void (*const row[3])(void) = { a, b, a };
                void test_main(void)
                {
                    uint8_t i;
                    for (i = 0; i < 2; i++) {
                        pairs[i].second();
                    }
                    row[1]();
                }
                """);
        List<String> callees = CallGraph.of(p).callsFrom(p.function("test_main")).stream().map(e -> e.callee().name()).toList();
        assertEquals(List.of("b", "b"), callees);
    }

    @Test
    void rejectsFunctionPointersOutsideConstantTables() {
        rejects("static void (*h[2])(void) = { a, b };\n", "'h' holds function pointers, which are only allowed in constant tables");
        rejects("void (*p)(void);\n", "'p' holds function pointers");
        rejects("typedef struct { void (*f)(void); } S;\nS s;\n", "'s' holds function pointers");
        rejects("void test_main(void) { void (*const p)(void) = a; p(); }\n", "the function table 'p' must be static const");
        rejects("void f(void (*cb)(void)) { cb(); }\n", "function pointers cannot be passed");
        rejects("typedef void (*H)(void);\nH get(void);\n", "functions cannot return function pointers");
        rejects("static void (*const h[2])(void);\n", "the function table 'h' needs an initializer");
    }

    @Test
    void rejectsFunctionPointersAsValues() {
        String table = "static void (*const h[2])(void) = { a, b };\n";
        rejects(table + "uint8_t same;\nvoid test_main(void) { same = h[0] == h[1]; }\n", "a function pointer can only be called");
        rejects(table + "uint32_t x;\nvoid test_main(void) { x = (uint32_t)h[1]; }\n", "a function pointer can only be called");
        rejects("uint32_t x;\nvoid test_main(void) { x = (uint32_t)&a; }\n", "a function's name can only be called");
        rejects("void test_main(void) { (1 ? a : b)(); }\n", "a function's name can only be called");
        rejects("typedef struct { void (*f)(void); } S;\nstatic const S s[1] = { { a } };\n"
                + "void test_main(void) { const S *p = &s[0]; p->f(); }\n", "can only be called straight from its constant table");
    }

    @Test
    void rejectsTablesThatDoNotSetEveryEntry() {
        rejects("static void (*const h[2])(void) = { a, 0 };\n", "must name a function (and cannot be null)");
        rejects("static void (*const h[3])(void) = { a, b };\n", "leaves the entry at byte 8 unset");
        rejects("static void (*const h[2])(void) = { a, g };\n", "'g' is void (uint8_t), but the table holds void (void)");
    }

    @Test
    void limitsTheArgumentsOfATableCall() {
        String types = String.join(", ", java.util.Collections.nCopies(10, "uint16_t"));
        StringBuilder params = new StringBuilder();
        for (int k = 0; k < 10; k++) {
            params.append(k > 0 ? ", " : "").append("uint16_t p").append(k);
        }
        String m = error("""
                #include <stdint.h>
                static void f(%s) { }
                static void (*const h[1])(%s) = { f };
                void test_main(void) { h[0](1, 2, 3, 4, 5, 6, 7, 8, 9, 10); }
                """.formatted(params, types));
        assertTrue(m.contains("passes at most 18 bytes of arguments"), m);
    }

    @Test
    void namesARecursionThroughATable() {
        String m = error("""
                #include <stdint.h>
                static void tick(void);
                static void (*const states[1])(void) = { tick };
                static void tick(void) { states[0](); }
                void test_main(void) { tick(); }
                """);
        assertTrue(m.contains("recursion is not supported: tick → tick"), m);
    }

    // ------------------------------------------------------------------ timing

    private static final String TIMED = """
            #include <stdint.h>
            #include <dampf.h>
            uint8_t t[32];
            uint16_t s;
            static void cheap(uint8_t k) { s = k; }
            static void costly(uint8_t k)
            {
                uint8_t i;
                for (i = 0; i < 32; i++) {
                    s = (uint16_t)(s + t[i] + k);
                }
            }
            static void (*const run[2])(uint8_t) = { cheap, costly };
            BUDGET void dispatch(uint8_t which) { run[which & 1](3); }
            void test_main(void) { dispatch(0); dispatch(1); }
            """;

    private Compiler.Result build(String code) throws Exception {
        Path f = tmp.resolve("b.c");
        Files.writeString(f, code);
        return Compiler.compile(new Compiler.Options(List.of(f), List.of(), List.of(), "test_main", tmp.resolve("b.sfc"),
                Map.of(), Compiler.Backend.MIR));
    }

    @Test
    void aTableCallCostsItsDispatchAndTheCostliestEntry() throws Exception {
        Compiler.Result r = build(TIMED.replace("BUDGET ", ""));
        long[] d = r.timing().get("_dispatch");
        long[] cheap = r.timing().get("_cheap");
        long[] costly = r.timing().get("_costly");
        // the dispatch: JSL dampf_icall (8) and its JML [dampf_fptr] (6), the entry from the table
        // into dampf_fptr, the argument into dampf_regs and from there into the parameter
        assertTrue(d[0] >= cheap[0] + 14 && d[0] <= cheap[0] + 80, d[0] + " vs cheap " + cheap[0]);
        assertTrue(d[1] >= costly[1] + 14 && d[1] <= costly[1] + 80, d[1] + " vs costly " + costly[1]);
        assertEquals(d[1] - costly[1], d[0] - cheap[0], "the same dispatch on both ways");
    }

    @Test
    void aBudgetCountsTheCostliestEntry() throws Exception {
        long worst = build(TIMED.replace("BUDGET ", "")).timing().get("_dispatch")[1];
        build(TIMED.replace("BUDGET", "DAMPF_BUDGET(" + (2 * worst) + ")"));
        CompileError e = assertThrows(CompileError.class,
                () -> build(TIMED.replace("BUDGET", "DAMPF_BUDGET(" + (worst - 1) + ")")));
        String msg = e.diagnostics().getFirst().message();
        assertTrue(msg.contains("'dispatch' takes up to " + worst + " CPU cycles"), msg);
    }
}
