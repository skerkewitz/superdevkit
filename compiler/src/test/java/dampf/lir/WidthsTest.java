package dampf.lir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WidthsTest {
    private static List<String> code(LFunction f) {
        return f.instrs().stream().map(i -> i.mnemonic() + (i.operand() == null ? "" : " " + i.operand())).toList();
    }

    private static LFunction function(String text) {
        return LParser.parse(".proc f\n" + text + "\n.endproc\n").functions().getFirst();
    }

    private static int run(LFunction f) {
        int n = Widths.run(f, false, Map.of(), true);
        LUnit u = new LUnit();
        u.add(f);
        assertEquals(List.of(), LVerifier.verify(u));
        return n;
    }

    @Test
    void byteStoresOnBothWaysOfABranchShareOneSwitch() {
        LFunction f = function("""
                        lda _x
                        sep #$20
                        sta _a
                        rep #$20
                        lda _k
                        cmp #$0003
                        bcc other
                        lda _y
                        sep #$20
                        sta _b
                        rep #$20
                        bra done
                other:
                        lda _z
                        sep #$20
                        sta _b
                        rep #$20
                done:
                        lda #$0105
                        sep #$20
                        sta _c
                        rep #$20
                        rtl
                """);
        assertTrue(run(f) > 0);
        assertEquals(List.of("sep #$20", "lda _x", "sta _a", "rep #$20", "lda _k", "cmp #$0003", "sep #$20", "bcc other",
                "lda _y", "sta _b", "bra done", "lda _z", "sta _b", "lda #$05", "sta _c", "rep #$20", "rtl"), code(f));
    }

    @Test
    void aByteReadInSixteenBitsLaterKeepsItsLoad() {
        // TAX copies the high byte too, in either width: the load before it stays 16 bit, and the
        // TAX and the second load go to 8 bits with the store between them
        LFunction f = function("""
                        lda _x
                        sep #$20
                        sta _a
                        rep #$20
                        tax
                        lda _t,x
                        sep #$20
                        sta _b
                        rep #$20
                        rtl
                """);
        run(f);
        assertEquals(List.of("lda _x", "sep #$20", "sta _a", "tax", "lda _t,x", "sta _b", "rep #$20", "rtl"), code(f));
    }

    @Test
    void theHighByteAfterABranchIsReadInSixteenBits() {
        // the value stored as a byte on one way is added in 16 bits after the join: its load stays 16 bit
        LFunction f = function("""
                        lda _x
                        ldy _k
                        beq skip
                        sep #$20
                        sta _a
                        rep #$20
                skip:
                        clc
                        adc #$012C
                        sta _w
                        rtl
                """);
        run(f);
        assertEquals(List.of("lda _x", "ldy _k", "beq skip", "sep #$20", "sta _a", "rep #$20", "clc", "adc #$012C", "sta _w", "rtl"),
                code(f));
    }

    @Test
    void aLoopWhoseEntryAlsoGoesElsewhereRunsInEightBits() {
        LFunction f = function("""
                        lda _count
                        beq none
                        ldx #$0000
                body:
                        lda _p
                        sep #$20
                        sta _buf,x
                        rep #$20
                        lda _q
                        sep #$20
                        sta _buf+1,x
                        rep #$20
                        inx
                        inx
                        cpx #$0014
                        bcc body
                none:
                        rtl
                """);
        assertTrue(run(f) > 0);
        assertEquals(List.of("lda _count", "sep #$20", "beq none", "ldx #$0000", "lda _p", "sta _buf,x", "lda _q", "sta _buf+1,x",
                "inx", "inx", "cpx #$0014", "bcc body", "rep #$20", "rtl"), code(f));
    }

    @Test
    void flagsReadAfterAnInstructionKeepItsWidth() {
        // the load's N and Z decide the branch: in 8 bits they would be another value's
        LFunction f = function("""
                        sep #$20
                        sta _a
                        rep #$20
                        lda _x
                        bne nz
                        sep #$20
                        stz _a
                        rep #$20
                nz:
                        rtl
                """);
        run(f);
        assertEquals(List.of("sep #$20", "sta _a", "rep #$20", "lda _x", "bne nz", "sep #$20", "stz _a", "rep #$20", "rtl"), code(f));
    }

    @Test
    void callsRunWithSixteenBits() {
        // and A is not narrowed before one: the runtime's routines take an operand in A
        LFunction f = function("""
                        lda _x
                        sep #$20
                        sta _a
                        rep #$20
                        jsl _g
                        lda _y
                        sep #$20
                        sta _b
                        rep #$20
                        rtl
                """);
        run(f);
        assertEquals(List.of("lda _x", "sep #$20", "sta _a", "rep #$20", "jsl _g", "sep #$20", "lda _y", "sta _b", "rep #$20", "rtl"),
                code(f));
    }
}
