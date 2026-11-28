package dampf.lir;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Peephole's comparisons with 0 on small hand-written pieces (the lists leave out the labels). */
class PeepholeTest {
    private static List<String> code(LFunction f) {
        return f.instrs().stream().map(i -> i.mnemonic() + (i.operand() == null ? "" : " " + i.operand())).toList();
    }

    private static List<String> peephole(String... lines) {
        StringBuilder text = new StringBuilder(".proc f\n");
        for (String l : lines) {
            text.append(l.endsWith(":") ? l : "        " + l).append('\n');
        }
        LFunction f = LParser.parse(text + ".endproc\n").functions().getFirst();
        new Peephole().run(f);
        return code(f);
    }

    @Test
    void anIndexLoadMovesAboveTheMaskAndTheCompareGoes() {
        // the mask set N and Z from A, the constant into X set them again: X goes first
        assertEquals(List.of("lda _t,x", "ldx #$0000", "and #$00FF", "beq done", "inx", "rtl"),
                peephole("lda _t,x", "and #$00FF", "ldx #$0000", "cmp #$0000", "beq done", "inx", "done:", "rtl"));
    }

    @Test
    void indexLoadsMovePastStoresOfA() {
        // the store of A stays where it is; the loads of X and Y go above the mask, in their order
        assertEquals(List.of("lda _t,x", "ldx #$0001", "ldy z:dampf_frame+4", "and #$00FF", "sta z:dampf_frame+0",
                "bne done", "inx", "rtl"),
                peephole("lda _t,x", "and #$00FF", "sta z:dampf_frame+0", "ldx #$0001", "ldy z:dampf_frame+4",
                        "cmp #$0000", "bne done", "inx", "done:", "rtl"));
        // not past a store indexed by X, nor a load past a store to its place
        assertEquals(List.of("lda _t,y", "and #$00FF", "sta _u,x", "ldx #$0001", "cmp #$0000", "bne done", "inx", "rtl"),
                peephole("lda _t,y", "and #$00FF", "sta _u,x", "ldx #$0001", "cmp #$0000", "bne done", "inx", "done:", "rtl"));
        assertEquals(List.of("lda _t", "and #$00FF", "sta z:dampf_frame+0", "ldx z:dampf_frame+1", "cmp #$0000", "bne done",
                "inx", "rtl"),
                peephole("lda _t", "and #$00FF", "sta z:dampf_frame+0", "ldx z:dampf_frame+1", "cmp #$0000", "bne done",
                        "inx", "done:", "rtl"));
    }

    @Test
    void anIndexLoadStaysWhereTheInstructionReadsX() {
        assertEquals(List.of("lda _t,x", "ldx #$0000", "cmp #$0000", "beq done", "inx", "rtl"),
                peephole("lda _t,x", "ldx #$0000", "cmp #$0000", "beq done", "inx", "done:", "rtl"));
    }

    @Test
    void theFlagsOfEveryWayInCount() {
        // both ways into join leave N and Z describing A
        assertEquals(List.of("lda z:dampf_frame+0", "beq join", "sta z:dampf_frame+2", "bpl done", "inx",
                "rtl"),
                peephole("lda z:dampf_frame+0", "beq join", "sta z:dampf_frame+2", "join:", "cmp #$0000", "bpl done",
                        "inx", "done:", "rtl"));
        // one way loads X after A: the comparison stays
        assertEquals(List.of("lda z:dampf_frame+0", "beq join", "ldx z:dampf_frame+2", "cmp #$0000",
                "bpl done", "inx", "rtl"),
                peephole("lda z:dampf_frame+0", "beq join", "ldx z:dampf_frame+2", "join:", "cmp #$0000", "bpl done",
                        "inx", "done:", "rtl"));
    }

    @Test
    void indexRegistersToo() {
        assertEquals(List.of("ldx z:dampf_frame+0", "sta z:dampf_frame+2", "beq done", "inx", "rtl"),
                peephole("ldx z:dampf_frame+0", "sta z:dampf_frame+2", "cpx #$0000", "beq done", "inx", "done:", "rtl"));
        assertEquals(List.of("tay", "bne done", "inx", "rtl"),
                peephole("tay", "cpy #$0000", "bne done", "inx", "done:", "rtl"));
        // TAY sets the flags from Y, not X
        assertEquals(List.of("tay", "cpx #$0000", "bne done", "inx", "rtl"),
                peephole("tay", "cpx #$0000", "bne done", "inx", "done:", "rtl"));
    }

    @Test
    void aCarryReadAfterwardsKeepsTheCompare() {
        assertEquals(List.of("lda z:dampf_frame+0", "cmp #$0000", "bcs done", "inx", "rtl"),
                peephole("lda z:dampf_frame+0", "cmp #$0000", "bcs done", "inx", "done:", "rtl"));
    }

    @Test
    void anotherWidthKeepsTheCompare() {
        // the flags of the 16-bit load do not describe the byte an 8-bit compare tests
        assertEquals(List.of("lda z:dampf_frame+0", "sep #$20", "cmp #$00", "rep #$20", "beq done", "inx", "rtl"),
                peephole("lda z:dampf_frame+0", "sep #$20", "cmp #$00", "rep #$20", "beq done", "inx", "done:", "rtl"));
        // XBA sets them from the new low byte only
        assertEquals(List.of("xba", "cmp #$0000", "beq done", "inx", "rtl"),
                peephole("xba", "cmp #$0000", "beq done", "inx", "done:", "rtl"));
    }

    @Test
    void aCallLeavesNothingKnown() {
        assertEquals(List.of("lda z:dampf_frame+0", "jsl _g", "cmp #$0000", "beq done", "inx", "rtl"),
                peephole("lda z:dampf_frame+0", "jsl _g", "cmp #$0000", "beq done", "inx", "done:", "rtl"));
    }
}
