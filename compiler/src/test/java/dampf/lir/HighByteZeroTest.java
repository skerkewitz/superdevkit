package dampf.lir;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class HighByteZeroTest {
    private static List<String> code(LFunction f) {
        return f.instrs().stream().map(i -> i.mnemonic() + (i.operand() == null ? "" : " " + i.operand())).toList();
    }

    private static LFunction run(String text) {
        LUnit u = LParser.parse(".proc f\n" + text + "\n.endproc\n");
        LFunction f = u.functions().getFirst();
        HighByteZero.run(f, o -> false);
        assertEquals(List.of(), LVerifier.verify(u));
        return f;
    }

    @Test
    void aByteLoadNeedsNoMaskWhereBIsZero() {
        // LDA #$0005 leaves B at 0, the 8-bit store keeps it: the 16-bit stretch with the mask goes
        // to 8 bits, and TAX copies the 0
        LFunction f = run("""
                        lda #$0005
                        sep #$20
                        sta _a
                        rep #$20
                        lda _b
                        and #$00FF
                        tax
                        sep #$20
                        lda _t,x
                        sta _c
                        rep #$20
                        rtl
                """);
        assertEquals(List.of("lda #$0005", "sep #$20", "sta _a", "lda _b", "tax", "lda _t,x", "sta _c", "rep #$20", "rtl"), code(f));
    }

    @Test
    void notWhereBIsNotKnown() {
        LFunction f = run("""
                        lda _w
                        sep #$20
                        sta _a
                        rep #$20
                        lda _b
                        and #$00FF
                        tax
                        sep #$20
                        lda _t,x
                        sta _c
                        rep #$20
                        rtl
                """);
        assertEquals(List.of("lda _w", "sep #$20", "sta _a", "rep #$20", "lda _b", "and #$00FF", "tax", "sep #$20", "lda _t,x",
                "sta _c", "rep #$20", "rtl"), code(f));
    }

    @Test
    void theSwitchMovesDownToAStoreOfTheWord() {
        // the mask goes, the REP moves to the 16-bit store, which stores B (0) as the high byte
        LFunction f = run("""
                        ldx _i
                        txa
                        and #$000F
                        sep #$20
                        sta _a
                        rep #$20
                        lda _b
                        and #$00FF
                        sta _w
                        rtl
                """);
        assertEquals(List.of("ldx _i", "txa", "and #$000F", "sep #$20", "sta _a", "lda _b", "rep #$20", "sta _w", "rtl"), code(f));
    }

    @Test
    void aCompareWithAByteConstantGoesToEightBits() {
        // C and Z of a byte against 3 are the same in 8 bits; the branch reads them
        LFunction f = run("""
                        lda #$0000
                        sep #$20
                        sta _a
                        rep #$20
                        lda _b
                        and #$00FF
                        cmp #$0003
                        sep #$20
                        bcc low
                        sta _c
                low:
                        rep #$20
                        rtl
                """);
        assertEquals(List.of("lda #$0000", "sep #$20", "sta _a", "lda _b", "cmp #$03", "bcc low", "sta _c", "rep #$20", "rtl"),
                code(f));
    }
}
