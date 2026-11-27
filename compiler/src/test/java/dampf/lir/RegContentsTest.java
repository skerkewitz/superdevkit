package dampf.lir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

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
        assertEquals(Set.of(P), s.x());
        assertEquals(Set.of(Q), s.y());
        RegContents.State txy = after(s, "txy", null);
        assertEquals(Set.of(P), txy.x());
        assertEquals(Set.of(P), txy.y());    // Y holds X's now, not what it was loaded with
        RegContents.State tyx = after(s, "tyx", null);
        assertEquals(Set.of(Q), tyx.x());
        assertEquals(Set.of(Q), tyx.y());
    }

    @Test
    void aTransferFromALeavesA() {
        RegContents.State s = after(RegContents.State.NONE, "lda", "z:dampf_frame+0");
        assertEquals(Set.of(P), after(s, "tax", null).a());
        assertEquals(Set.of(P), after(s, "tay", null).a());
    }

    @Test
    void aCallLeavesNothingKnown() {
        RegContents.State s = after(after(RegContents.State.NONE, "ldx", "z:dampf_frame+0"), "ldy", "z:dampf_frame+2");
        RegContents.State jsr = after(s, "jsr", "f");
        assertTrue(jsr.x().isEmpty());
        assertTrue(jsr.y().isEmpty());
    }

    @Test
    void transfersAndStoresOfXAndYAreFollowed() {
        RegContents.State s = after(RegContents.State.NONE, "lda", "z:dampf_frame+0");
        RegContents.State tax = after(s, "tax", null);
        assertEquals(Set.of(P), tax.x());
        RegContents.State stx = after(after(RegContents.State.NONE, "ldx", "z:dampf_frame+0"), "stx", "z:dampf_frame+2");
        assertEquals(Set.of(P, Q), stx.x());
        assertEquals(Set.of(P, Q), after(stx, "txa", null).a());
        // a byte written next to a place it holds: X no longer equals that place
        RegContents.State inc = after(stx, "inc", "z:dampf_frame+3");
        assertEquals(Set.of(P), inc.x());
    }
}
