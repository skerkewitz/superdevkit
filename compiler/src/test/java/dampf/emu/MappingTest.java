package dampf.emu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Where the cartridge's ROM appears: LoROM (dampf's ROMs) and HiROM (the Calypsi benchmark ROM). */
class MappingTest {
    private static byte[] rom(int size) {
        byte[] r = new byte[size];
        for (int i = 0; i < size; i++) {
            r[i] = (byte) (i * 7 + (i >> 8) * 13 + (i >> 16) * 29);
        }
        return r;
    }

    @Test
    void loRomMapsHalfBanks() {
        byte[] r = rom(0x20000);
        r[0x7FD5] = 0x20;
        r[0xFFD5] = 0x00;
        Snes s = new Snes(r);
        assertFalse(Snes.isHiRom(r));
        assertEquals(r[0x0000] & 0xFF, s.read(0x008000));
        assertEquals(r[0x8123] & 0xFF, s.read(0x818123));   // bank $81 = the second 32 KB
    }

    @Test
    void hiRomMapsWholeBanks() {
        byte[] r = rom(0x20000);
        r[0xFFD5] = 0x21;
        Snes s = new Snes(r);
        assertTrue(Snes.isHiRom(r));
        assertEquals(r[0x0123] & 0xFF, s.read(0xC00123));    // $C0-$FF: all 64 KB
        assertEquals(r[0x10123] & 0xFF, s.read(0xC10123));
        assertEquals(r[0x18123] & 0xFF, s.read(0x018123));   // $00-$3F: the upper half
        assertEquals(r[0x18123] & 0xFF, s.read(0x818123));   // $80-$BF: the same
        assertEquals(r[0x10123] & 0xFF, s.read(0x410123));   // $40-$7D: all 64 KB
        s.write(0x000010, 0x5A);
        assertEquals(0x5A, s.read(0x7E0010));                // the low WRAM stays where it is
    }
}
