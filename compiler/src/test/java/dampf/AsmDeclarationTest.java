package dampf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dampf.frontend.CompileError;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * DAMPF_ASM as a whole: the declaration, the analysis' bounds in the timing report, and the check
 * that runs the assembly against the C body in every build from MIR (docs/hints.md).
 */
class AsmDeclarationTest {
    @TempDir
    Path tmp;

    private static final String PROGRAM = """
            #include <stdint.h>
            #include <dampf.h>
            uint16_t out[4];
            uint16_t twice_plus(uint16_t x, uint8_t k) DAMPF_ASM("asm/f.s")
            {
                return (uint16_t)(x + x + k);
            }
            void test_main(void)
            {
                for (uint8_t i = 0; i < 4; i++) {
                    out[i] = twice_plus((uint16_t)(i * 1000u), i);
                }
            }
            """;

    private static final String ASM = """
                    .p816
                    .smart  off
                    .a16
                    .i16
                    .importzp _twice_plus__x, _twice_plus__k
                    .export _twice_plus
                    .segment "C_twice_plus"
            .proc _twice_plus
                    lda     z:_twice_plus__k
                    and     #$00FF
                    clc
                    adc     z:_twice_plus__x
                    clc
                    adc     z:_twice_plus__x
                    rtl
            .endproc
            """;

    private Compiler.Result build(String c, String asm, Compiler.Backend backend, Map<String, Path> replace) throws Exception {
        Path f = tmp.resolve("p.c");
        Files.writeString(f, c);
        Files.createDirectories(tmp.resolve("asm"));
        Files.writeString(tmp.resolve("asm/f.s"), asm);
        return Compiler.compile(new Compiler.Options(List.of(f), List.of(), List.of(), "test_main", tmp.resolve("out/p.sfc"),
                replace, backend));
    }

    private static String message(CompileError e) {
        return e.diagnostics().getFirst().toString();
    }

    @Test
    void theAssemblyIsLinkedTimedAndCheckedAgainstItsBody() throws Exception {
        Compiler.Result r = build(PROGRAM, ASM, Compiler.Backend.MIR, Map.of());
        // lda dp 4, and #imm 3, clc 2, adc dp 4, clc 2, adc dp 4, rtl 6 CPU cycles: in master cycles
        // the code's reads and internal operations 6, the direct page and the stack 8
        assertEquals(168, r.timing().get("_twice_plus")[0]);
        assertEquals(168, r.timing().get("_twice_plus")[1]);
        assertEquals(1, r.asmChecks().size());
        assertEquals(dampf.emu.AsmCheck.INPUTS, r.asmChecks().getFirst().inputs());
        assertEquals(168, r.asmChecks().getFirst().asmWorst());
        String report = Files.readString(tmp.resolve("out/p.timing.txt"));
        assertTrue(report.contains("_twice_plus   (assembly, "), report);
    }

    @Test
    void theCheckFindsAnAssemblyThatComputesSomethingElse() {
        CompileError e = assertThrows(CompileError.class, () -> build(PROGRAM,
                ASM.replace("        and     #$00FF\n", ""), Compiler.Backend.MIR, Map.of()));
        // the high byte of an 8-bit parameter is undefined: the check fills it with something else than 0
        assertTrue(message(e).contains("p.c:4:10: error: the assembly does not compute what the C body of 'twice_plus' computes: "
                + "for x = "), message(e));
        assertTrue(message(e).contains("the C body returns") && message(e).contains("asm/f.s, DAMPF_ASM)"), message(e));
    }

    @Test
    void theCheckFindsADifferenceInMemory() {
        String c = """
                #include <stdint.h>
                #include <dampf.h>
                uint16_t total;
                void add(uint16_t x) DAMPF_ASM("asm/f.s") { total = (uint16_t)(total + x); }
                void test_main(void) { add(3); }
                """;
        String asm = """
                        .p816
                        .smart  off
                        .a16
                        .i16
                        .importzp _add__x
                        .import _total
                        .export _add
                        .segment "C_add"
                .proc _add
                        lda     z:_add__x
                        sta     _total                  ; forgets the old total
                        rtl
                .endproc
                """;
        CompileError e = assertThrows(CompileError.class, () -> build(c, asm, Compiler.Backend.MIR, Map.of()));
        assertTrue(message(e).contains("the C body leaves total"), message(e));
    }

    @Test
    void theCheckFindsABoundCommentTooLow() throws Exception {
        String c = """
                #include <stdint.h>
                #include <dampf.h>
                uint16_t r;
                uint16_t bits(uint16_t x) DAMPF_ASM("asm/f.s")
                {
                    uint16_t n = 0;
                    for (uint8_t i = 0; i < 16; i++) {
                        n = (uint16_t)(n + (x & 1));
                        x = (uint16_t)(x >> 1);
                    }
                    return n;
                }
                void test_main(void) { r = bits(0x1234); }
                """;
        String asm = """
                        .p816
                        .smart  off
                        .a16
                        .i16
                        .importzp _bits__x
                        .export _bits
                        .segment "C_bits"
                .proc _bits
                        ldy     #0
                        ldx     #16
                @loop:  lsr     z:_bits__x
                        bcc     @zero
                        iny
                @zero:  dex
                        bne     @loop                   ; dampf: at most 8
                        tya
                        rtl
                .endproc
                """;
        CompileError e = assertThrows(CompileError.class, () -> build(c, asm, Compiler.Backend.MIR, Map.of()));
        assertTrue(message(e).contains("outside the bounds dampf computed") && message(e).contains("dampf: at most"), message(e));
        // the true bound builds
        build(c, asm.replace("at most 8", "at most 16"), Compiler.Backend.MIR, Map.of());
    }

    @Test
    void theNaiveCodeGeneratorLinksTheSameFile() throws Exception {
        Compiler.Result r = build(PROGRAM, ASM, Compiler.Backend.NAIVE, Map.of());
        assertTrue(String.join("\n", r.assembly()).contains("_twice_plus__x"), "the parameters' equates");
        assertTrue(r.asmChecks().isEmpty(), "the check runs in the build from MIR");
    }

    @Test
    void replaceOverridesTheDeclaration() throws Exception {
        Path other = tmp.resolve("other.s");
        Files.writeString(other, ASM.replace("        clc\n        adc     z:_twice_plus__x\n        rtl",
                "        clc\n        adc     z:_twice_plus__x\n        nop\n        rtl"));
        Compiler.Result r = build(PROGRAM, ASM, Compiler.Backend.MIR, Map.of("twice_plus", other));
        assertEquals(180, r.timing().get("_twice_plus")[1]);
    }

    @Test
    void misplacedOrMissingDeclarationsAreErrors() {
        CompileError proto = assertThrows(CompileError.class, () -> build("""
                #include <stdint.h>
                #include <dampf.h>
                uint16_t f(uint16_t x) DAMPF_ASM("asm/f.s");
                void test_main(void) { }
                """, ASM, Compiler.Backend.MIR, Map.of()));
        assertTrue(message(proto).contains("DAMPF_ASM belongs on a function's definition"), message(proto));
        CompileError missing = assertThrows(CompileError.class, () -> build(PROGRAM.replace("asm/f.s", "asm/g.s"), ASM,
                Compiler.Backend.MIR, Map.of()));
        assertTrue(message(missing).contains("asm/g.s, does not exist"), message(missing));
        CompileError entry = assertThrows(CompileError.class, () -> build("""
                #include <dampf.h>
                void test_main(void) DAMPF_ASM("asm/f.s") { }
                """, ASM, Compiler.Backend.MIR, Map.of()));
        assertTrue(message(entry).contains("cannot be written in assembly"), message(entry));
        CompileError analysis = assertThrows(CompileError.class, () -> build(PROGRAM, ASM.replace("rtl", "rts"),
                Compiler.Backend.MIR, Map.of()));
        assertTrue(message(analysis).contains("f.s:15:1: error: RTS"), message(analysis));
    }
}
