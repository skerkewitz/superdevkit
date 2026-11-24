package dampf.emu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The interrupts of dampf's emulator ({@link Snes}, {@link Cpu65816#interrupt}): the NMI at the
 * start of the vertical blank, the timer IRQ at its line or dot, the flags RDNMI and TIMEUP, the I
 * flag, the 8 cycles of the entry and the depth the measurements use. Each test runs a ROM of a
 * few hand-assembled instructions: a main loop that sets NMITIMEN, HTIME and VTIME, an NMI and an
 * IRQ handler that count. The same ROMs' behaviour in Mesen is what the execution test
 * {@code exec/interrupts.c} compares (its handlers check the line they run on).
 */
class InterruptTest {
    private static final int NMI_HANDLER = 0x8100;
    private static final int IRQ_HANDLER = 0x8200;
    private static final long FRAME = 262L * 1364;

    /**
     * A LoROM image: main code at $8000 that sets VTIME, HTIME and NMITIMEN, clears I unless
     * {@code sei}, and loops; an NMI handler that reads RDNMI and counts at $0100; an IRQ handler
     * that counts at $0102 and reads TIMEUP if {@code ack}.
     */
    private static Snes rom(int nmitimen, int vtime, int htime, boolean ack, boolean sei) {
        byte[] rom = new byte[0x8000];
        int[] main = {
            0x18, 0xFB,                         // clc, xce: native mode
            0xC2, 0x30,                         // rep #$30
            0xA2, 0xFF, 0x1F, 0x9A,             // ldx #$1FFF, txs
            0xE2, 0x20,                         // sep #$20
            0xA9, vtime & 0xFF, 0x8F, 0x09, 0x42, 0x00,
            0xA9, vtime >> 8, 0x8F, 0x0A, 0x42, 0x00,
            0xA9, htime & 0xFF, 0x8F, 0x07, 0x42, 0x00,
            0xA9, htime >> 8, 0x8F, 0x08, 0x42, 0x00,
            0xA9, nmitimen, 0x8F, 0x00, 0x42, 0x00,
            0xC2, 0x20,                         // rep #$20
            sei ? 0xEA : 0x58,                  // cli (or nop)
            0x1A, 0x80, 0xFD,                   // loop: inc a, bra loop
        };
        int[] nmi = {0xE2, 0x20, 0xAF, 0x10, 0x42, 0x00, 0xC2, 0x20, 0xEE, 0x00, 0x01, 0x40};
        int[] irq = ack ? new int[] {0xE2, 0x20, 0xAF, 0x11, 0x42, 0x00, 0xC2, 0x20, 0xEE, 0x02, 0x01, 0x40}
                : new int[] {0xEE, 0x02, 0x01, 0x40};
        put(rom, 0x8000, main);
        put(rom, NMI_HANDLER, nmi);
        put(rom, IRQ_HANDLER, irq);
        put(rom, 0xFFEA, NMI_HANDLER & 0xFF, NMI_HANDLER >> 8);
        put(rom, 0xFFEE, IRQ_HANDLER & 0xFF, IRQ_HANDLER >> 8);
        put(rom, 0xFFFC, 0x00, 0x80);
        return new Snes(rom);
    }

    private static void put(byte[] rom, int address, int... bytes) {
        for (int k = 0; k < bytes.length; k++) {
            rom[address - 0x8000 + k] = (byte) bytes[k];
        }
    }

    private static int word(Snes s, int address) {
        return (s.wram()[address] & 0xFF) | (s.wram()[address + 1] & 0xFF) << 8;
    }

    /** Runs {@code frames} frames; returns the line at each entry into {@code handler}. */
    private static List<Integer> linesAt(Snes s, int handler, long frames) {
        List<Integer> lines = new ArrayList<>();
        s.run(frames * FRAME, address -> {
            if (address == handler) {
                lines.add(s.scanline());
            }
        });
        return lines;
    }

    @Test
    void theNmiComesOncePerFrameAtTheStartOfTheVerticalBlank() {
        Snes s = rom(0x80, 0x1FF, 0x1FF, true, false);
        List<Integer> lines = linesAt(s, NMI_HANDLER, 10);
        assertEquals(s.vblanks(), lines.size());
        assertTrue(lines.size() >= 9, "NMIs: " + lines.size());
        lines.forEach(l -> assertEquals(225, l));
        assertEquals(lines.size(), word(s, 0x100));
        assertEquals(0, word(s, 0x102), "no IRQ without a timer");
    }

    @Test
    void withoutItsBitThereIsNoNmiButRdnmiStillTellsTheVerticalBlank() {
        Snes s = rom(0x00, 0x1FF, 0x1FF, true, false);
        assertEquals(List.of(), linesAt(s, NMI_HANDLER, 3));
        // the flag is set at line 225 and cleared by reading it
        Snes bus = rom(0x00, 0x1FF, 0x1FF, true, true);
        bus.run(FRAME);   // ends somewhere in the first frame's lines
        while (bus.scanline() != 230) {
            bus.idle();
        }
        assertEquals(0x80, bus.read(0x004210) & 0x80);
        assertEquals(0, bus.read(0x004210) & 0x80);
    }

    @Test
    void theVerticalTimerFiresAtTheStartOfItsLineOncePerFrame() {
        Snes s = rom(0x20, 100, 0x1FF, true, false);
        List<Integer> lines = linesAt(s, IRQ_HANDLER, 10);
        assertTrue(lines.size() >= 9, "IRQs: " + lines.size());
        lines.forEach(l -> assertEquals(100, l));
        assertEquals(lines.size(), word(s, 0x102));
        assertEquals(0, word(s, 0x100), "no NMI without its bit");
    }

    @Test
    void aWriteThatMakesTheLineVtimesFiresAtOnce() {
        Snes s = rom(0x00, 0x1FF, 0x1FF, true, true);
        s.run(FRAME);
        while (s.scanline() != 100) {
            s.idle();
        }
        s.write(0x004200, 0x20);   // the vertical timer, VTIME still $1FF
        assertEquals(0, s.read(0x004211));
        s.write(0x004209, 100);
        s.write(0x00420A, 0);
        assertEquals(0x80, s.read(0x004211), "V = VTIME from the middle of the line");
        s.write(0x004209, 101);
        s.write(0x004209, 100);
        assertEquals(0x80, s.read(0x004211), "again, after it was false");
        s.write(0x004209, 120);
        assertEquals(0, s.read(0x004211));
    }

    @Test
    void theIrqStaysUntilTimeupIsRead() {
        Snes s = rom(0x20, 100, 0x1FF, false, false);
        List<Integer> lines = linesAt(s, IRQ_HANDLER, 2);
        assertTrue(lines.size() > 100, "a handler that does not read TIMEUP runs again at once: " + lines.size());
    }

    @Test
    void theIFlagHoldsTheIrqBack() {
        Snes s = rom(0x20, 100, 0x1FF, true, true);
        assertEquals(List.of(), linesAt(s, IRQ_HANDLER, 3));
        assertEquals(0x80, s.read(0x004211), "TIMEUP is set all the same");
        assertEquals(0, s.read(0x004211), "and cleared by the read");
    }

    @Test
    void theHorizontalTimerFiresOnEveryLineAtItsDot() {
        Snes s = rom(0x10, 0x1FF, 200, true, false);
        List<Integer> lines = linesAt(s, IRQ_HANDLER, 3);
        assertTrue(lines.size() >= 3 * 262 - 2 && lines.size() <= 3 * 262 + 1, "IRQs in 3 frames: " + lines.size());
        Snes both = rom(0x30, 50, 200, true, false);
        List<Integer> once = linesAt(both, IRQ_HANDLER, 5);
        assertTrue(once.size() >= 4 && once.size() <= 5, "H and V: once a frame, " + once.size());
        once.forEach(l -> assertEquals(50, l));
    }

    @Test
    void theEntryTakesEightCyclesPushesPbrPcAndPAndSetsI() {
        Snes s = rom(0x80, 0x1FF, 0x1FF, true, false);
        Cpu65816 cpu = s.cpu();
        long[] seen = {-1, -1, -1, -1};
        s.run(2 * FRAME, address -> {
            if (address == NMI_HANDLER && seen[0] < 0) {
                seen[0] = cpu.cycles - cpu.interruptStart;
                seen[1] = cpu.i ? 1 : 0;
                seen[2] = cpu.depth;
                // the stack: P, then PC (low, high), then PBR
                seen[3] = s.peek(cpu.s + 2) | s.peek(cpu.s + 3) << 8 | s.peek(cpu.s + 4) << 16;
            }
        });
        assertEquals(8, seen[0], "cycles of the entry");
        assertEquals(1, seen[1], "I is set");
        assertEquals(1, seen[2], "depth inside the handler");
        int loop = 0x8000 + 43;
        assertTrue(seen[3] == loop || seen[3] == loop + 1, "the return address is in the loop: " + Long.toHexString(seen[3]));
        assertEquals(0, cpu.depth, "the RTI left it");
    }

    @Test
    void theCyclesInsideInterruptsAreCountedApart() {
        Snes s = rom(0x30, 50, 200, true, false);
        s.run(3 * FRAME);
        Cpu65816 cpu = s.cpu();
        assertTrue(cpu.interrupts >= 2);
        assertEquals(0, cpu.depth);
        // each IRQ: the entry (8), the handler's SEP 3, LDA long 5, REP 3, INC abs 8 (16 bits), RTI 7
        assertEquals(cpu.interrupts * (8 + 3 + 5 + 3 + 8 + 7), cpu.cyclesAbove[0], "cycles inside the interrupts");
    }
}
