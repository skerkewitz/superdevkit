package dampf.asm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dampf.frontend.CompileError;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The analysis of a function written in assembly (DAMPF_ASM): what it reads from the code (the
 * cycles, the bytes of its locals) and what it rejects, each with the line it names.
 */
class AsmFunctionTest {
    /** uint16_t f(uint16_t a, uint8_t b), in a file that exports _f; another function's parameter _g__x. */
    private static final AsmFunction.Spec SPEC = new AsmFunction.Spec("f", "_f",
            List.of(new AsmFunction.Param("_f__a", 2), new AsmFunction.Param("_f__b", 2)), Set.of("_g__x"));

    private static final String HEAD = """
                    .p816
                    .smart  off
                    .a16
                    .i16
                    .importzp _f__a, _f__b, _f__locals, _g__x, dampf_ptr
                    .import _global
                    .export _f
                    .segment "C_f"
            .proc _f
            """;

    private static AsmFunction.Result analyse(String body) throws CompileError {
        return AsmFunction.analyse("f.s", HEAD + body + ".endproc\n", SPEC);
    }

    /** The error's line (in the body: line 1 is the first line after .proc) and message. */
    private static CompileError fails(String body) {
        return assertThrows(CompileError.class, () -> analyse(body));
    }

    private static int bodyLine(CompileError e) {
        return e.diagnostics().getFirst().pos().line() - HEAD.split("\n").length;
    }

    private static String message(CompileError e) {
        return e.diagnostics().getFirst().message();
    }

    @Test
    void straightCodeHasItsCyclesFromTheTable() throws CompileError {
        AsmFunction.Result r = analyse("""
                        lda     z:_f__a                 ; 4
                        clc                             ; 2
                        adc     _global                 ; 5
                        rtl                             ; 6
                """);
        assertEquals(17, r.best());
        assertEquals(17, r.worst());
        assertEquals(0, r.localsBytes());
    }

    @Test
    void aBranchGivesABestAndAWorstCase() throws CompileError {
        AsmFunction.Result r = analyse("""
                        lda     z:_f__a                 ; 4
                        beq     @zero                   ; 2, taken 3
                        inc     a                       ; 2
                @zero:  rtl                             ; 6
                """);
        assertEquals(4 + 3 + 6, r.best());
        assertEquals(4 + 2 + 2 + 6, r.worst());
    }

    @Test
    void aLoopRunsAsOftenAsItsBoundSays() throws CompileError {
        AsmFunction.Result r = analyse("""
                        ldx     #16                     ; 3
                @loop:  dex                             ; 2
                        bne     @loop                   ; dampf: at most 16
                        rtl                             ; 6
                """);
        // best: the shortest way out (one round), as for C; worst: 15 rounds back (2 + 3) and the last (2 + 2)
        assertEquals(3 + 2 + 2 + 6, r.best());
        assertEquals(3 + 15 * (2 + 3) + 2 + 2 + 6, r.worst());
    }

    @Test
    void nestedLoopsMultiply() throws CompileError {
        AsmFunction.Result r = analyse("""
                        ldy     #4                      ; 3
                @outer: ldx     #8                      ; 3
                @inner: dex                             ; 2
                        bne     @inner                  ; dampf: at most 8
                        dey                             ; 2
                        bne     @outer                  ; dampf: at most 4
                        rtl                             ; 6
                """);
        long inner = 7 * (2 + 3) + 2 + 2;
        long round = 3 + inner + 2;
        assertEquals(3 + 3 * (round + 3) + round + 2 + 6, r.worst());
    }

    @Test
    void theLocalsAreTheBytesTheCodeUses() throws CompileError {
        AsmFunction.Result r = analyse("""
                        lda     z:_f__a
                        sta     z:_f__locals+4
                        sep     #$20
                        .a8
                        sta     z:_f__locals+6
                        rep     #$20
                        .a16
                        lda     [_f__locals],y
                        rtl
                """);
        assertEquals(7, r.localsBytes());
    }

    @Test
    void theScratchRegistersAndAnEightBitParameterAsAWordAreAllowed() throws CompileError {
        analyse("""
                        lda     z:_f__b                 ; the byte and the undefined one after it
                        and     #$00FF
                        sta     z:dampf_ptr+4
                        rtl
                """);
    }

    @Test
    void aLoopWithoutABoundIsAnError() {
        CompileError e = fails("""
                        ldx     #16
                @loop:  dex
                        bne     @loop
                        rtl
                """);
        assertEquals(3, bodyLine(e));
        assertTrue(message(e).contains("a loop without a bound") && message(e).contains("; dampf: at most 16"), message(e));
    }

    @Test
    void aBoundTooLowShowsInTheCheckNotHere() throws CompileError {
        // the analysis believes the comment; the check against C measures it (AsmCheck)
        AsmFunction.Result r = analyse("""
                        ldx     #16
                @loop:  dex
                        bne     @loop                   ; dampf: at most 2
                        rtl
                """);
        assertEquals(3 + 1 * (2 + 3) + 2 + 2 + 6, r.worst());
    }

    @Test
    void writingAnotherFunctionsFrameIsAnError() {
        CompileError other = fails("""
                        lda     z:_f__a
                        sta     z:_g__x
                        rtl
                """);
        assertEquals(2, bodyLine(other));
        assertTrue(message(other).contains("another function's frame"), message(other));
        CompileError frame = fails("""
                        stz     $40
                        rtl
                """);
        assertTrue(message(frame).contains("a fixed address in the frames"), message(frame));
        CompileError beyond = fails("""
                        lda     #0
                        sta     z:_f__b+2
                        rtl
                """);
        assertTrue(message(beyond).contains("beyond the 2 bytes of the parameter _f__b"), message(beyond));
        CompileError indexed = fails("""
                        ldx     #2
                        stz     z:_f__locals,x
                        rtl
                """);
        assertTrue(message(indexed).contains("indexed access to _f__locals"), message(indexed));
    }

    @Test
    void aCallIsAnError() {
        CompileError e = fails("""
                        jsl     _global
                        rtl
                """);
        assertEquals(1, bodyLine(e));
        assertTrue(message(e).contains("calls _global") && message(e).contains("leaf"), message(e));
    }

    @Test
    void returningWithAnEightBitAccumulatorIsAnError() {
        CompileError e = fails("""
                        sep     #$20
                        .a8
                        lda     z:_f__b
                        rtl
                """);
        assertEquals(4, bodyLine(e));
        assertTrue(message(e).contains("8-bit accumulator"), message(e));
    }

    @Test
    void theCpuStateMustBeAsAtTheEntry() throws CompileError {
        assertTrue(message(fails("""
                        pha
                        rtl
                """)).contains("still on the stack"));
        assertTrue(message(fails("""
                        pla
                        rtl
                """)).contains("pulls more than the function pushed"));
        assertTrue(message(fails("""
                        lda     #$0080
                        pha
                        plb
                        plb
                        rtl
                """)).contains("another data bank"));
        assertTrue(message(fails("""
                        sei
                        rtl
                """)).contains("interrupt flag"));
        // PHP ... PLP and PHB ... PLB restore what they saved
        analyse("""
                        php
                        phb
                        sep     #$30
                        .a8
                        .i8
                        lda     #$80
                        pha
                        plb
                        plb
                        plp
                        .a16
                        .i16
                        rtl
                """);
    }

    @Test
    void anImmediateOfTheWrongSizeIsAnError() {
        CompileError e = fails("""
                        sep     #$20
                        lda     #1                      ; assembled with 16 bits: .a8 is missing
                        rep     #$20
                        rtl
                """);
        assertEquals(2, bodyLine(e));
        assertTrue(message(e).contains(".a8/.a16"), message(e));
    }

    @Test
    void widthsThatDifferWhereWaysMeetAreAnError() {
        CompileError e = fails("""
                        lda     z:_f__a
                        beq     @join
                        sep     #$20
                @join:  rep     #$20
                        rtl
                """);
        assertTrue(message(e).contains("different states"), message(e));
    }

    @Test
    void anUnsupportedDirectiveIsAnErrorAtItsLine() {
        CompileError e = fails("""
                        .macro  twice
                        .endmacro
                        rtl
                """);
        assertEquals(1, bodyLine(e));
        assertTrue(message(e).contains(".macro") && message(e).contains("no macros"), message(e));
    }

    @Test
    void aMissingLabelIsAnError() {
        CompileError e = assertThrows(CompileError.class, () -> AsmFunction.analyse("f.s", HEAD.replace(".export _f", "")
                .replace(".proc _f", ".proc _other") + "        rtl\n.endproc\n", SPEC));
        assertTrue(message(e).contains("does not define the label _f"), message(e));
        CompileError unexported = assertThrows(CompileError.class, () -> AsmFunction.analyse("f.s",
                HEAD.replace(".export _f", "") + "        rtl\n.endproc\n", SPEC));
        assertTrue(message(unexported).contains("does not export _f"), message(unexported));
    }

    @Test
    void jumpsOutOfTheFunctionAndOtherReturnsAreErrors() {
        assertTrue(message(fails("""
                        jmp     _global
                """)).contains("jumps out of the function"));
        assertTrue(message(fails("""
                        rts
                """)).contains("returns with RTL"));
        assertTrue(message(fails("""
                        jmp     (_global)
                """)).contains("indirect jump"));
        assertTrue(message(fails("""
                        lda     z:_f__a
                """)).contains("without an RTL"));
    }

    @Test
    void aJumpIntoTheMiddleOfALoopIsAnError() {
        CompileError e = fails("""
                        bra     @in
                @loop:  dex
                @in:    dex
                        bne     @loop                   ; dampf: at most 4
                        rtl
                """);
        assertTrue(message(e).contains("into the middle of the loop"), message(e));
    }
}
