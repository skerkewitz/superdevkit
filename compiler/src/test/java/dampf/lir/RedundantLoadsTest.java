package dampf.lir;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** RedundantLoads on small hand-written pieces. */
class RedundantLoadsTest {
    private static String ins(String m, String op) {
        return LPrinter.instr(new LInstr(m, op, null, true));
    }

    private static List<String> code(LFunction f) {
        return f.instrs().stream().map(i -> i.mnemonic() + (i.operand() == null ? "" : " " + i.operand())).toList();
    }

    private static LFunction function(String... lines) {
        return LParser.parse(".proc f\n" + String.join("\n", lines) + "\n.endproc\n").functions().getFirst();
    }

    @Test
    void aLoopHeadsLoadMovesToTheEntryWhereTheBackEdgeHoldsIt() {
        // the back edge stores the counter from A; the entry jumps in without it: the head's load
        // goes to the entry, and then is redundant in the head (and so is the body's: the
        // comparison leaves A as it is)
        LFunction f = function(
                ins("stz", "z:dampf_frame+0"),
                ins("jmp", "head"),
                "body:",
                ins("lda", "z:dampf_frame+0"),
                ins("clc", null),
                ins("adc", "#$0001"),
                ins("sta", "z:dampf_frame+0"),
                "head:",
                ins("lda", "z:dampf_frame+0"),
                ins("cmp", "#$0010"),
                ins("bcc", "body"),
                ins("rtl", null));
        new RedundantLoads().run(f);
        assertEquals(List.of("stz z:dampf_frame+0", "lda z:dampf_frame+0", "jmp head",
                "clc", "adc #$0001", "sta z:dampf_frame+0",
                "cmp #$0010", "bcc body", "rtl"), code(f));
    }

    @Test
    void aLoadWhoseFlagsAreReadStays() {
        // the head branches on the load's own flags: moving it would need a CMP #0 in the head
        LFunction f = function(
                ins("stz", "z:dampf_frame+0"),
                ins("jmp", "head"),
                "body:",
                ins("lda", "z:dampf_frame+2"),
                ins("sta", "z:dampf_frame+0"),
                "head:",
                ins("lda", "z:dampf_frame+0"),
                ins("bne", "body"),
                ins("rtl", null));
        new RedundantLoads().run(f);
        assertEquals(List.of("stz z:dampf_frame+0", "jmp head", "lda z:dampf_frame+2", "sta z:dampf_frame+0",
                "lda z:dampf_frame+0", "bne body", "rtl"), code(f));
    }

    @Test
    void nothingMovesIntoTheLongFormOfABranch() {
        // the head's load is redundant from the entry, not from the "BRA head" that BNE falls
        // through into: putting the load there would keep BranchRelax from making it one BEQ
        List<String> before = List.of("lda z:dampf_frame+2", "sta z:dampf_frame+0",
                "lda z:dampf_frame+0", "inc a", "sta z:dampf_frame+4", "cmp #$0010", "bne done",
                "bra head", "rtl");
        LFunction f = function(
                ins("lda", "z:dampf_frame+2"),
                ins("sta", "z:dampf_frame+0"),
                "head:",
                ins("lda", "z:dampf_frame+0"),
                ins("inc", "a"),
                ins("sta", "z:dampf_frame+4"),
                ins("cmp", "#$0010"),
                ins("bne", "done"),
                ins("bra", "head"),
                "done:",
                ins("rtl", null));
        new RedundantLoads().run(f);
        assertEquals(before, code(f));
    }

    @Test
    void aConstantTheBodyLoadsMovesBeforeTheLoopsTest() {
        // for (i = 0; i < n; i++) t[i] = c: the body's LDA #c moves to the entry; the test (CPX)
        // leaves A alone, and the function returns nothing, so A is dead after the loop
        LFunction f = function(
                ins("ldx", "#$0000"),
                ins("bra", "test"),
                "body:",
                ins("lda", "#$0C01"),
                ins("sta", "_w,x"),
                ins("inx", null),
                "test:",
                ins("cpx", "#$0040"),
                ins("bcc", "body"),
                ins("rtl", null));
        new RedundantLoads(false).run(f);
        assertEquals(List.of("ldx #$0000", "lda #$0C01", "bra test", "sta _w,x", "inx", "cpx #$0040", "bcc body", "rtl"), code(f));
    }

    @Test
    void notWhereTheFunctionReturnsA() {
        LFunction f = function(
                ins("ldx", "#$0000"),
                ins("bra", "test"),
                "body:",
                ins("lda", "#$0C01"),
                ins("sta", "_w,x"),
                ins("inx", null),
                "test:",
                ins("cpx", "#$0040"),
                ins("bcc", "body"),
                ins("rtl", null));
        new RedundantLoads(true).run(f);
        assertEquals(List.of("ldx #$0000", "bra test", "lda #$0C01", "sta _w,x", "inx", "cpx #$0040", "bcc body", "rtl"), code(f));
    }

    @Test
    void aLoadAfterTheLoopDoesNotMoveIntoIt() {
        // the entry leaves _k in A, the body does not: the load after the loop (also reached only
        // from the test) would move to the end of the body and run every round (catchme's best_load)
        LFunction f = function(
                ins("lda", "_k"),
                ins("ldx", "#$0000"),
                ins("bra", "test"),
                "body:",
                ins("txa", null),
                ins("sta", "_w,x"),
                ins("inx", null),
                "test:",
                ins("cpx", "#$0007"),
                ins("bcc", "body"),
                ins("bra", "done"),
                "done:",
                ins("lda", "_k"),
                ins("sta", "_m"),
                ins("rtl", null));
        List<String> before = code(f);
        new RedundantLoads(false).hoistOverTest(f);
        assertEquals(before, code(f));
    }

    @Test
    void aLoadOfWhatAnotherRegisterHoldsIsATransfer() {
        // X was loaded with p and A stored to q: LDA p is TXA, LDX q is TAX, LDY p is TAY (A holds p too)
        LFunction f = function(
                ins("ldx", "z:dampf_frame+0"),
                ins("lda", "z:dampf_frame+4"),
                ins("sta", "z:dampf_frame+2"),
                ins("lda", "z:dampf_frame+0"),
                ins("sta", "z:dampf_frame+6"),
                ins("ldy", "z:dampf_frame+0"),
                ins("lda", "z:dampf_frame+4"),
                ins("ldx", "z:dampf_frame+4"),
                ins("rtl", null));
        new RedundantLoads().run(f);
        assertEquals(List.of("ldx z:dampf_frame+0", "lda z:dampf_frame+4", "sta z:dampf_frame+2", "txa",
                "sta z:dampf_frame+6", "tay", "lda z:dampf_frame+4", "tax", "rtl"), code(f));
    }
}
