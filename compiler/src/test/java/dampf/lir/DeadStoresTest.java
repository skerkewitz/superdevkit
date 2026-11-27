package dampf.lir;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.BitSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/** DeadStores on small hand-written pieces: a store only a rare way reads moves there. */
class DeadStoresTest {
    private static String ins(String m, String op) {
        return LPrinter.instr(new LInstr(m, op, null, true));
    }

    private static List<String> code(LFunction f) {
        return f.instrs().stream().map(i -> i.mnemonic() + (i.operand() == null ? "" : " " + i.operand())).toList();
    }

    private static LFunction function(String... lines) {
        return LParser.parse(".proc f\n" + String.join("\n", lines) + "\n.endproc\n").functions().getFirst();
    }

    private static BitSet own() {
        BitSet own = new BitSet();
        own.set(0, 4);
        return own;
    }

    @Test
    void aStoreOnlyTheRareWayReadsMovesThere() {
        LFunction f = function(
                ins("lda", "_t"),
                ins("and", "#$000F"),
                ins("sta", "z:dampf_frame+0"),
                ins("cmp", "#$0004"),
                ins("bcs", "rare"),
                "common:",
                ins("lda", "#$0001"),
                ins("sta", "_o"),
                ins("rtl", null),
                "rare:",
                ins("lda", "z:dampf_frame+0"),
                ins("sta", "_o"),
                ins("rtl", null));
        assertEquals(1, new DeadStores(own()).sink(f));
        assertEquals(List.of("lda _t", "and #$000F", "cmp #$0004", "bcs rare", "lda #$0001", "sta _o", "rtl",
                "sta z:dampf_frame+0", "lda z:dampf_frame+0", "sta _o", "rtl"), code(f));
    }

    @Test
    void aStoreStaysWhereAChangeOfTheRegisterFollowsIt() {
        // the AND after the store changes A: at the rare way's start A no longer holds the value
        List<String> before = List.of("lda _t", "sta z:dampf_frame+0", "and #$000F", "cmp #$0004", "bcs rare",
                "lda #$0001", "sta _o", "rtl", "lda z:dampf_frame+0", "sta _o", "rtl");
        LFunction f = function(
                ins("lda", "_t"),
                ins("sta", "z:dampf_frame+0"),
                ins("and", "#$000F"),
                ins("cmp", "#$0004"),
                ins("bcs", "rare"),
                "common:",
                ins("lda", "#$0001"),
                ins("sta", "_o"),
                ins("rtl", null),
                "rare:",
                ins("lda", "z:dampf_frame+0"),
                ins("sta", "_o"),
                ins("rtl", null));
        assertEquals(0, new DeadStores(own()).sink(f));
        assertEquals(before, code(f));
    }

    @Test
    void aStoreGoesThroughTheLongFormOfABranch() {
        // selection writes every branch in its long form: BCC over a JMP to the rare way
        LFunction f = function(
                ins("lda", "_t"),
                ins("sta", "z:dampf_frame+0"),
                ins("cmp", "#$0004"),
                ins("bcc", "common"),
                ins("jmp", "rare"),
                "common:",
                ins("lda", "#$0001"),
                ins("sta", "_o"),
                ins("rtl", null),
                "rare:",
                ins("lda", "z:dampf_frame+0"),
                ins("sta", "_o"),
                ins("rtl", null));
        assertEquals(1, new DeadStores(own()).sink(f));
        assertEquals(List.of("lda _t", "cmp #$0004", "bcc common", "jmp rare", "lda #$0001", "sta _o", "rtl",
                "sta z:dampf_frame+0", "lda z:dampf_frame+0", "sta _o", "rtl"), code(f));
    }
}
