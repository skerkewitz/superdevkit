package dampf.emu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The CPU's 8 x 8 multiplier in the emulator: one bit of the second factor per CPU cycle, lowest
 * first, so that code reading the product too early fails here as it would on a console. Where
 * the line lies was compared with Mesen: the runtime's multiplication with one cycle less of
 * waiting gives wrong products in both.
 */
class MultiplierTest {
    private static final int WRMPYA = 0x004202;
    private static final int WRMPYB = 0x004203;
    private static final int RDMPYL = 0x004216;

    private static Snes snes() {
        return new Snes(new byte[0x20000]);
    }

    /** The low byte of the product, read after {@code cycles} CPU cycles between the write and the read. */
    private static int lowAfter(int a, int b, int cycles) {
        Snes s = snes();
        s.write(WRMPYA, a);
        s.write(WRMPYB, b);
        for (int k = 0; k < cycles; k++) {
            s.idle();
        }
        return s.read(RDMPYL);
    }

    @Test
    void afterEightCyclesTheProductIsThere() {
        for (int a : new int[] {0, 1, 3, 0x5A, 0x80, 0xFF}) {
            for (int b : new int[] {0, 1, 2, 0x7F, 0x80, 0xA5, 0xFF}) {
                assertEquals((a * b) & 0xFF, lowAfter(a, b, 8), a + " * " + b);
            }
        }
    }

    @Test
    void beforeThatOnlyTheLowBitsOfTheSecondFactorHaveCounted() {
        for (int cycles = 0; cycles < 8; cycles++) {
            int b = 0xFF;
            int partial = 0xFF * (b & ((1 << cycles) - 1));
            assertEquals(partial & 0xFF, lowAfter(0xFF, b, cycles), cycles + " cycles");
        }
    }

    @Test
    void aSecondMultiplicationStartsAfresh() {
        Snes s = snes();
        s.write(WRMPYA, 200);
        s.write(WRMPYB, 100);
        for (int k = 0; k < 8; k++) {
            s.idle();
        }
        assertEquals((200 * 100) & 0xFF, s.read(RDMPYL));
        assertEquals((200 * 100) >> 8, s.read(RDMPYL + 1));
        s.write(WRMPYB, 3);                                  // the first factor stays
        for (int k = 0; k < 8; k++) {
            s.idle();
        }
        assertEquals(600 & 0xFF, s.read(RDMPYL));
        assertEquals(600 >> 8, s.read(RDMPYL + 1));
    }
}
