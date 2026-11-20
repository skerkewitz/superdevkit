package dampf.lir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Every operand form dampf writes reads as a typed operand and prints back the same (ADR record 47). */
class LOperandTest {
    @Test
    void everyFormDampfWritesIsTyped() {
        String[][] cases = {
            {"lda", "z:dampf_frame+0"}, {"sta", "a:dampf_frame+270"}, {"lda", "z:dampf_frame+0,x"}, {"lda", "#$0001"},
            {"lda", "_sum"}, {"lda", "_keep+2"}, {"lda", "_arr,x"}, {"lda", "_oam+2,x"}, {"lda", "_xs,y"},
            {"lda", "_cats+2,y"}, {"sta", "f:$002105"}, {"lda", "f:_SIN,x"}, {"lda", "f:_CAVE_DEFS+11,x"},
            {"lda", "f:$00210B,x"}, {"lda", "f:_version+2"}, {"lda", "f:_version"}, {"lda", "z:R0"}, {"lda", "z:R14"},
            {"lda", "[dampf_frame+0],y"}, {"lda", "[dampf_ptr],y"}, {"lda", "[dampf_ptr+4],y"}, {"lda", "z:dampf_ptr"},
            {"lda", "z:dampf_ptr+2"}, {"lda", "#.loword(_arr)"}, {"lda", "#^_rom"}, {"lda", "#.loword(dampf_frame+0)"},
            {"asl", "a"}, {"bra", "b1_for"}, {"jsl", "dampf_mul16"}, {"bcc", "@L52"},
        };
        for (String[] c : cases) {
            LOperand o = LOperand.of(c[0], c[1]);
            assertEquals(c[1], o.text(), c[1]);
            assertFalse(o instanceof LOperand.Raw, c[1] + " is " + o);
        }
        assertNull(LOperand.of("rts", null));
        // the one form that stays text: two offsets
        assertInstanceOf(LOperand.Raw.class, LOperand.of("lda", "z:dampf_ptr+4+2"));
    }

    @Test
    void aFramePlaceSaysWhereItIs() {
        assertEquals(6, ((LOperand.Mem) LOperand.of("lda", "z:dampf_frame+6")).frameOffset());
        assertEquals(270, ((LOperand.Mem) LOperand.of("lda", "a:dampf_frame+270")).frameOffset());
        assertEquals(-1, ((LOperand.Mem) LOperand.of("lda", "z:dampf_frame+0,x")).frameOffset());   // indexed
        assertEquals(-1, ((LOperand.Mem) LOperand.of("lda", "[dampf_frame+0],y")).frameOffset());   // a pointer there
        assertTrue(((LOperand.Mem) LOperand.of("lda", "[dampf_frame+0],y")).frame());
        assertEquals(-1, ((LOperand.Mem) LOperand.of("lda", "_sum")).frameOffset());
        assertEquals(0x00FFL, ((LOperand.Imm) LOperand.of("and", "#$00FF")).value());
        assertNull(((LOperand.Imm) LOperand.of("lda", "#^_rom")).value());
    }
}
