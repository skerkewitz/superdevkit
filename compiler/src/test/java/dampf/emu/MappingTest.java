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
    void loRomSaveRamIsAtBank70WhenTheHeaderAnnouncesIt() {
        byte[] r = rom(0x20000);
        r[0x7FD5] = 0x30;
        r[0x7FD6] = 0x02;                                    // ROM, RAM and battery
        r[0x7FD8] = 0x01;                                    // 2 KB
        Snes s = new Snes(r);
        assertEquals(2048, s.sram().length);
        s.write(0x700000, 0x12);
        s.write(0x7007FF, 0x34);
        assertEquals(0x12, s.read(0x700000));
        assertEquals(0x12, s.read(0x700800));                // 2 KB repeat through the bank
        assertEquals(0x34, s.read(0xF007FF));                // $F0-$FD mirror $70-$7D
        assertEquals(0x12, s.peek(0x700000));
        assertEquals(0x12, s.sram()[0]);
        assertEquals(r[0x0000] & 0xFF, s.read(0x708000));    // the upper half of bank $70 is ROM

        r[0x7FD6] = 0x00;                                    // ROM only: nothing at $70:0000
        Snes none = new Snes(r);
        assertEquals(0, none.sram().length);
        none.write(0x700000, 0x12);
        assertEquals(0, none.read(0x700000));
    }

    /** A LoROM of dampf's whose data has $21 at $FFD5: its header at $7FC0 is valid, so it stays LoROM. */
    @Test
    void aLoRomWithHiRomsModeByteInItsDataStaysLoRom() {
        byte[] r = rom(0x20000);
        r[0x7FD5] = 0x30;
        r[0x7FDC] = 0x01;
        r[0x7FDD] = 0x5C;                                    // the complement ...
        r[0x7FDE] = (byte) 0xFE;
        r[0x7FDF] = (byte) 0xA3;                             // ... and the checksum agree
        r[0xFFD5] = 0x21;                                    // a byte of the game's data
        assertFalse(Snes.isHiRom(r));
        assertEquals(r[0x0000] & 0xFF, new Snes(r).read(0x008000));
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
