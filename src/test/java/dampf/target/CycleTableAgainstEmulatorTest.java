package dampf.target;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dampf.emu.Bus;
import dampf.emu.Cpu65816;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The cycle table ({@link Cycles65816}, written from the data sheet) against the emulator
 * ({@link Cpu65816}, validated against the SingleStepTests suite): every opcode in dampf's
 * processor state (16-bit index registers, D = 0), with an 8- and a 16-bit accumulator.
 * Conditional branches are checked not taken and taken. Instructions whose time is not fixed
 * (block moves, STP, WAI) are left out.
 */
class CycleTableAgainstEmulatorTest {
    /** 16 MB of zeros, with the instruction at $00:8000. */
    private static final class Ram implements Bus {
        final byte[] mem = new byte[1 << 24];

        @Override
        public int read(int address) {
            return mem[address] & 0xFF;
        }

        @Override
        public void write(int address, int value) {
            mem[address] = (byte) value;
        }

        @Override
        public void idle() {
        }
    }

    private static long run(int op, boolean m16, Boolean taken) {
        Ram ram = new Ram();
        ram.mem[0x8000] = (byte) op;
        Cpu65816 cpu = new Cpu65816(ram);
        cpu.e = false;
        cpu.setP(m16 ? 0x00 : 0x20); // X=0 (16-bit index), M as given, all other flags clear
        cpu.pc = 0x8000;
        cpu.s = 0x1FFF;
        if (taken != null && Cycles65816.isConditionalBranch(op)) {
            // the flag each branch tests is clear; set it where the branch then goes the other way
            boolean branchesOnSet = (op & 0x20) != 0; // BMI BVS BCS BEQ test a set flag
            boolean setFlag = taken == branchesOnSet;
            switch (op & 0xC0) {
                case 0x00 -> cpu.n = setFlag;
                case 0x40 -> cpu.v = setFlag;
                case 0x80 -> cpu.c = setFlag;
                default -> cpu.z = setFlag;
            }
        }
        cpu.step();
        return cpu.cycles;
    }

    @Test
    void tableMatchesEmulator() {
        List<String> wrong = new ArrayList<>();
        for (int op = 0; op < 256; op++) {
            if (op == 0x44 || op == 0x54 || op == 0xCB || op == 0xDB) {
                continue;
            }
            for (boolean m16 : new boolean[] {false, true}) {
                if (Cycles65816.isConditionalBranch(op)) {
                    for (boolean taken : new boolean[] {false, true}) {
                        long emu = run(op, m16, taken);
                        int table = Cycles65816.cycles(op, m16, true, taken);
                        if (emu != table) {
                            wrong.add(String.format("%02X %s m16=%b taken=%b: table %d, emulator %d", op, Cycles65816.name(op), m16, taken, table, emu));
                        }
                    }
                    continue;
                }
                long emu = run(op, m16, null);
                int table = Cycles65816.cycles(op, m16, true, false);
                if (emu != table) {
                    wrong.add(String.format("%02X %s m16=%b: table %d, emulator %d", op, Cycles65816.name(op), m16, table, emu));
                }
            }
        }
        assertTrue(wrong.isEmpty(), wrong.size() + " differences:\n" + String.join("\n", wrong));
    }
}
