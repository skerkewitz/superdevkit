package dampf.lir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** What RegContents says the registers hold after an instruction. */
class RegContentsTest {
    private static final LOperand P = LOperand.of("ldx", "z:dampf_frame+0");
    private static final LOperand Q = LOperand.of("ldy", "z:dampf_frame+2");

    private static RegContents.State after(RegContents.State s, String mnemonic, String operand) {
        return new RegContents().after(new LInstr(mnemonic, operand, null, true), s);
    }

    @Test
    void transfersBetweenXAndYCopyWhatTheyHold() {
        RegContents.State s = after(after(RegContents.State.NONE, "ldx", "z:dampf_frame+0"), "ldy", "z:dampf_frame+2");
        assertEquals(P, s.x());
        assertEquals(Q, s.y());
        RegContents.State txy = after(s, "txy", null);
        assertEquals(P, txy.x());
        assertEquals(P, txy.y());    // Y holds X's now, not what it was loaded with
        RegContents.State tyx = after(s, "tyx", null);
        assertEquals(Q, tyx.x());
        assertEquals(Q, tyx.y());
    }

    @Test
    void aTransferFromALeavesA() {
        RegContents.State s = after(RegContents.State.NONE, "lda", "z:dampf_frame+0");
        assertEquals(java.util.Set.of(P), after(s, "tax", null).a());
        assertEquals(java.util.Set.of(P), after(s, "tay", null).a());
    }

    @Test
    void aCallLeavesNothingKnown() {
        RegContents.State s = after(after(RegContents.State.NONE, "ldx", "z:dampf_frame+0"), "ldy", "z:dampf_frame+2");
        RegContents.State jsr = after(s, "jsr", "f");
        assertNull(jsr.x());
        assertNull(jsr.y());
    }
}
