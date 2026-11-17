package dampf.target;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Spot checks against the W65C816S data sheet, table 5-7 (native mode, D low byte 0). */
class Cycles65816Test {
    private static int c(int op, boolean m16, boolean x16) {
        return Cycles65816.cycles(op, m16, x16, false);
    }

    @Test
    void loadsAndStoresDependOnTheAccumulatorWidth() {
        assertEquals(4, c(0xAD, false, true));   // lda abs, 8-bit A
        assertEquals(5, c(0xAD, true, true));    // lda abs, 16-bit A
        assertEquals(6, c(0xAF, true, true));    // lda long
        assertEquals(6, c(0x8F, true, true));    // sta long
        assertEquals(3, c(0xA9, true, true));    // lda #imm (16 bit)
        assertEquals(4, c(0x85, true, true));    // sta dp
    }

    @Test
    void indexedReadsPayTheIndexCycleWith16BitIndexRegisters() {
        assertEquals(6, c(0xBD, true, true));    // lda abs,x: 4 + m + x=0
        assertEquals(4, c(0xBD, false, false));  // 8-bit A and X: 4 (+1 only on a page crossing, not modelled)
        assertEquals(7, c(0xB1, true, true));    // lda (dp),y: 5 + m + x=0
        assertEquals(7, c(0xB7, true, true));    // lda [dp],y: 6 + m, no index cycle
        assertEquals(6, c(0x9D, true, true));    // sta abs,x: 5 + m (stores always pay)
    }

    @Test
    void readModifyWriteCostsTwoMoreWith16BitAccumulator() {
        assertEquals(8, c(0xEE, true, true));    // inc abs
        assertEquals(6, c(0xEE, false, true));
        assertEquals(2, c(0x0A, true, true));    // asl a
    }

    @Test
    void controlFlow() {
        assertEquals(8, c(0x22, true, true));    // jsl
        assertEquals(6, c(0x6B, true, true));    // rtl
        assertEquals(3, c(0x4C, true, true));    // jmp abs
        assertEquals(2, Cycles65816.cycles(0xD0, true, true, false)); // bne not taken
        assertEquals(3, Cycles65816.cycles(0xD0, true, true, true));  // bne taken
        assertEquals(3, c(0x80, true, true));    // bra
    }

    @Test
    void indexRegisterInstructions() {
        assertEquals(5, c(0xAE, true, true));    // ldx abs, 16-bit X
        assertEquals(4, c(0xAE, true, false));   // ldx abs, 8-bit X
        assertEquals(3, c(0xA2, true, true));    // ldx #imm (16 bit)
    }

    @Test
    void lengths() {
        assertEquals(3, Cycles65816.length(0xA9, true, true));   // lda #$1234
        assertEquals(2, Cycles65816.length(0xA9, false, true));  // lda #$12
        assertEquals(4, Cycles65816.length(0x22, true, true));   // jsl
        assertEquals(2, Cycles65816.length(0xD0, true, true));   // bne
        assertEquals(2, Cycles65816.length(0xE2, true, true));   // sep #$20
        assertEquals(2, Cycles65816.length(0xB7, true, true));   // lda [dp],y
        assertEquals(1, Cycles65816.length(0x6B, true, true));   // rtl
        assertEquals(3, Cycles65816.length(0x8D, true, true));   // sta abs
        assertEquals(4, Cycles65816.length(0x9F, true, true));   // sta long,x
    }
}
