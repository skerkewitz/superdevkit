package dampf.mir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dampf.Compiler;
import dampf.ast.Program;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** MIR text: printed, parsed back and printed again gives the same text; parsed MIR verifies and runs. */
class MirTextTest {
    @TestFactory
    Stream<DynamicTest> everyExecutionProgramRoundTrips() throws IOException, URISyntaxException {
        Path dir = Path.of(MirTextTest.class.getResource("/exec").toURI());
        List<Path> programs;
        try (Stream<Path> s = Files.list(dir)) {
            programs = s.filter(p -> p.toString().endsWith(".c")).sorted().toList();
        }
        return programs.stream().map(src -> DynamicTest.dynamicTest(src.getFileName().toString(), () -> assertTimeoutPreemptively(java.time.Duration.ofMinutes(2), () -> {
            Program p = Compiler.frontEnd(new Compiler.Options(List.of(src), List.of(), List.of(), "test_main", Path.of("x.sfc")));
            roundTrip(Lower.lower(p));
        }, "took too long: hangs?")));
    }

    @Test
    void theWholeGameRoundTrips() throws Exception {
        Path bb = Path.of("examples/blubberblasen");
        List<Path> sources = new ArrayList<>();
        try (Stream<Path> s = Files.list(bb.resolve("src/game"))) {
            s.filter(p -> p.toString().endsWith(".c")).sorted().forEach(sources::add);
        }
        try (Stream<Path> s = Files.list(bb.resolve("generated"))) {
            s.filter(p -> p.toString().endsWith(".c")).sorted().forEach(sources::add);
        }
        sources.add(bb.resolve("bench/bench.c"));
        // as tools/bench_game.sh: every PVSnesLib call goes to bench.c's empty stub
        List<String> defines = new ArrayList<>();
        for (String f : List.of("setMode", "setBrightness", "setScreenOn", "setScreenOff", "setColorEffect",
                "setColorIntensity", "setPaletteColor", "dmaCopyVram", "dmaCopyCGram", "dmaClearVram", "bgSetGfxPtr",
                "bgSetMapPtr", "bgSetScroll", "bgSetEnable", "bgSetDisable", "bgSetEnableSub", "bgSetDisableSub",
                "bgInitTileSet", "bgInitMapSet", "oamInit", "oamInitGfxSet", "oamInitGfxAttr", "oamSet", "oamSetXY",
                "oamSetEx", "oamSetVisible", "oamClear", "snesRegWrite", "snesHdmaSet", "snesHdmaEnable", "snesDmaFits",
                "oamMemory")) {
            defines.add(f + "=bb_" + f);
        }
        Program p = Compiler.frontEnd(new Compiler.Options(sources, List.of(bb.resolve("src"), bb.resolve("generated")),
                defines, "main", Path.of("x.sfc")));
        roundTrip(Lower.lower(p));
    }

    private static void roundTrip(MModule m) {
        String text = Printer.print(m);
        MModule back = MirParser.parse(text);
        List<String> errors = Verifier.check(back);
        assertTrue(errors.isEmpty(), "parsed MIR does not verify: " + errors);
        assertEquals(text, Printer.print(back));
    }

    @Test
    void handWrittenMirRuns() {
        // the sum 1..n with a loop: a phi on the back edge uses %3, defined after its first use
        String text = """
                global @_out : 4

                func @_sum(i16 %n) -> i32 {
                b0_entry:
                    %0 = zext i16 %n to i32
                    jump b1_loop
                b1_loop:
                    %1 = phi i32 [b0_entry, 0], [b2_body, %3]
                    %2 = phi i32 [b0_entry, 1], [b2_body, %4]
                    %5 = cmp ule i32 %2, %0
                    br %5, b2_body, b3_exit
                b2_body:
                    %3 = add i32 %1, %2
                    %4 = add i32 %2, 1
                    jump b1_loop
                b3_exit:
                    store i32 @_out, %1
                    ret i32 %1
                }
                """;
        MModule m = MirParser.parse(text);
        Verifier.verify(m);
        Interpreter in = new Interpreter(m, 100_000);
        assertEquals(5050, in.call("_sum", List.of(100L)));
        assertEquals(5050, in.load(in.addressOf("_out"), 4));
    }

    @Test
    void theVerifierRejectsAUseBeforeItsDefinition() {
        String text = """
                func @_bad(i16 %a) -> i16 {
                b0_entry:
                    %0 = add i16 %1, %a
                    %1 = add i16 %a, 1
                    ret i16 %0
                }
                """;
        List<String> errors = Verifier.check(MirParser.parse(text));
        assertTrue(errors.stream().anyMatch(e -> e.contains("does not dominate")), errors.toString());
    }
}
