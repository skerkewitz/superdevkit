package dampf.lir;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class ByteLoopsTest {
    private static List<String> code(LFunction f) {
        return f.instrs().stream().map(i -> i.mnemonic() + (i.operand() == null ? "" : " " + i.operand())).toList();
    }

    private static LFunction function(String text) {
        return LParser.parse(".proc f\n" + text + "\n.endproc\n").functions().getFirst();
    }

    @Test
    void aByteCopyRunsInEightBits() {
        LFunction f = function("""
                        ldx #$0000
                        bra test
                body:
                        lda _src,x
                        sep #$20
                        sta _dst,x
                        rep #$20
                        inx
                test:
                        cpx #$0064
                        bcc body
                        rtl
                """);
        ByteLoops.run(f, false);
        assertEquals(List.of("ldx #$0000", "sep #$20", "bra test", "lda _src,x", "sta _dst,x", "inx", "cpx #$0064", "bcc body",
                "rep #$20", "rtl"), code(f));
    }

    @Test
    void aFillMovesItsSwitchesOutOfTheLoop() {
        // Ertüp's memset: nothing in 16 bits but the counter; the REP / LDX / SEP at the entry go too
        LFunction f = function("""
                        sep #$20
                        stz _n
                        rep #$20
                        ldx #$0200
                        bra test
                body:
                        sep #$20
                        stz _oam,x
                        rep #$20
                        inx
                test:
                        cpx #$0220
                        bcc body
                done:
                        lda _k
                        sta _m
                        rtl
                """);
        ByteLoops.run(f, false);
        assertEquals(List.of("sep #$20", "stz _n", "ldx #$0200", "bra test", "stz _oam,x", "inx", "cpx #$0220", "bcc body",
                "rep #$20", "lda _k", "sta _m", "rtl"), code(f));
    }

    private static final String COPY = """
                    ldx #$0000
                    bra test
            body:
                    lda _src,x
                    sep #$20
                    sta _dst,x
                    rep #$20
                    inx
            test:
                    cpx #$0064
                    bcc body
                    rtl
            """;

    @Test
    void notWhereTheFunctionReturnsA() {
        // the high byte of A after the loop is another in 8 bits
        LFunction f = function(COPY);
        List<String> before = code(f);
        ByteLoops.run(f, true);
        assertEquals(before, code(f));
    }

    @Test
    void notWhereABranchReadsTheFlagsOfALoad() {
        LFunction f = function(COPY.replace("        inx\n", "        inx\n        lda _src,x\n        beq done\n")
                .replace("        rtl", "done:\n        rtl"));
        List<String> before = code(f);
        ByteLoops.run(f, false);
        assertEquals(before, code(f));
    }

    @Test
    void notWhereTheHighByteIsRead() {
        // TAX copies all of A whatever the width
        LFunction f = function(COPY.replace("        inx\n", "        tax\n        inx\n"));
        List<String> before = code(f);
        ByteLoops.run(f, false);
        assertEquals(before, code(f));
    }

    @Test
    void notAnAdditionWhoseCarryComesFromTheLoop() {
        // the carry of an 8-bit ASL is another bit than that of a 16-bit one
        LFunction f = function(COPY.replace("        lda _src,x\n", "        lda _src,x\n        asl a\n        adc #$0003\n"));
        List<String> before = code(f);
        ByteLoops.run(f, false);
        assertEquals(before, code(f));
    }

    @Test
    void anAdditionAfterClcGetsItsLowByte() {
        LFunction f = function(COPY.replace("        lda _src,x\n", "        lda _src,x\n        clc\n        adc #$0103\n"));
        ByteLoops.run(f, false);
        assertEquals(List.of("ldx #$0000", "sep #$20", "bra test", "lda _src,x", "clc", "adc #$03", "sta _dst,x", "inx",
                "cpx #$0064", "bcc body", "rep #$20", "rtl"), code(f));
    }

    @Test
    void notALoopEnteredInTheMiddle() {
        LFunction f = function("""
                        lda _k
                        bne body
                        ldx #$0000
                        bra test
                body:
                        sep #$20
                        stz _dst,x
                        rep #$20
                        inx
                test:
                        cpx #$0064
                        bcc body
                        rtl
                """);
        List<String> before = code(f);
        ByteLoops.run(f, false);
        assertEquals(before, code(f));
    }

    @Test
    void notWhereTheEntryComesAfterTheLoopInTheText() {
        // the width follows REP / SEP in the order of the text: the body would read back as 16 bits
        LFunction f = function("""
                        jmp pre
                body:
                        sep #$20
                        stz _dst,x
                        rep #$20
                        inx
                test:
                        cpx #$0064
                        bcc body
                        rtl
                pre:
                        ldx #$0000
                        jmp test
                """);
        List<String> before = code(f);
        ByteLoops.run(f, false);
        assertEquals(before, code(f));
    }
}
