package dampf.target;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dampf.emu.Bus;
import dampf.emu.Cpu65816;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The accesses of {@link MasterCycles} against the emulator ({@link Cpu65816}, validated against
 * the SingleStepTests suite): every opcode with 8- and 16-bit accumulator and index registers,
 * conditional branches taken and not, run on a bus that sorts each cycle by the address it goes
 * to. The instruction is at $80:C000, its operand bytes and every other byte are $7E: D = 0 and
 * X = Y = $0200 put the direct page below $1000, S = $1F00 the stack at $1000-$1FFF, DBR = $7E and
 * long operands and pointers the data in bank $7E, a jump table in bank $80 below the code, and a
 * pointer read in bank 0 ({@code JMP (abs)}, the vectors) at $2000 and up. STP and WAI are left
 * out, and WDM, which dampf never writes (the emulator spends its second byte as an internal
 * cycle).
 */
class MasterCyclesAgainstEmulatorTest {
    private static final int PC = 0x80C000;

    private static final class Sorting implements Bus {
        final int[] code = new int[4];
        int program;
        int stack;
        int direct;
        int data;
        int table;
        int io;

        private void sort(int address) {
            int bank = address >> 16;
            int offset = address & 0xFFFF;
            if (address >= PC && address < PC + 4) {
                program++;
            } else if (bank == 0x80) {
                table++;
            } else if (bank == 0 && offset < 0x1000) {
                direct++;
            } else if (bank == 0 && offset < 0x2000) {
                stack++;
            } else if (bank == 0) {
                table++;
            } else {
                data++;
            }
        }

        @Override
        public int read(int address) {
            sort(address);
            return address >= PC && address < PC + 4 ? code[address - PC] : 0x7E;
        }

        @Override
        public void write(int address, int value) {
            sort(address);
        }

        @Override
        public void idle() {
            io++;
        }
    }

    private static MasterCycles.Accesses run(int op, boolean m16, boolean x16, boolean taken) {
        Sorting bus = new Sorting();
        bus.code[0] = op;
        bus.code[1] = 0x7E;
        bus.code[2] = 0x7E;
        bus.code[3] = 0x7E;
        Cpu65816 cpu = new Cpu65816(bus);
        cpu.e = false;
        cpu.setP((m16 ? 0 : 0x20) | (x16 ? 0 : 0x10));
        cpu.pbr = PC >> 16;
        cpu.pc = PC & 0xFFFF;
        cpu.dbr = 0x7E;
        cpu.s = 0x1F00;
        cpu.x = x16 ? 0x0200 : 0x20;
        cpu.y = cpu.x;
        if (Cycles65816.isConditionalBranch(op)) {
            boolean branchesOnSet = (op & 0x20) != 0;
            boolean setFlag = taken == branchesOnSet;
            switch (op & 0xC0) {
                case 0x00 -> cpu.n = setFlag;
                case 0x40 -> cpu.v = setFlag;
                case 0x80 -> cpu.c = setFlag;
                default -> cpu.z = setFlag;
            }
        }
        cpu.step();
        return new MasterCycles.Accesses(bus.program, bus.stack, bus.direct, bus.data, bus.table, bus.io);
    }

    @Test
    void accessesMatchTheEmulator() {
        List<String> wrong = new ArrayList<>();
        int checked = 0;
        for (int op = 0; op < 256; op++) {
            if (op == 0xCB || op == 0xDB || op == 0x42) {
                continue;
            }
            for (boolean m16 : new boolean[] {false, true}) {
                for (boolean x16 : new boolean[] {false, true}) {
                    for (boolean taken : Cycles65816.isConditionalBranch(op) ? new boolean[] {false, true} : new boolean[] {false}) {
                        MasterCycles.Accesses emu = run(op, m16, x16, taken);
                        MasterCycles.Accesses model = MasterCycles.accesses(op, m16, x16, taken);
                        if (!emu.equals(model)) {
                            wrong.add(String.format("%02X %s m16=%b x16=%b taken=%b: model %s, emulator %s", op,
                                    Cycles65816.name(op), m16, x16, taken, model, emu));
                        }
                        checked++;
                    }
                }
            }
        }
        assertTrue(wrong.isEmpty(), wrong.size() + " differences:\n" + String.join("\n", wrong));
        assertTrue(checked > 1000);
    }

    @Test
    void speedsOfTheMemory() {
        assertEquals(6, MasterCycles.speed(0x002100, true));
        assertEquals(12, MasterCycles.speed(0x004016, true));
        assertEquals(6, MasterCycles.speed(0x004218, false));
        assertEquals(8, MasterCycles.speed(0x7E2000, true));
        assertEquals(6, MasterCycles.speed(0x808000, true));
        assertEquals(8, MasterCycles.speed(0x808000, false));
        assertEquals(8, MasterCycles.speed(0x008000, true));
        assertEquals(new MasterCycles.Range(6, 12), MasterCycles.speeds(0x002100, 0x00FFFF, true));
        assertEquals(MasterCycles.Range.of(6), MasterCycles.speeds(0x004300, 0x0043FF, true));
    }

    @Test
    void anInstructionOfTheGeneratedCode() {
        // LDA [dampf_ptr],y, 16 bit: 2 program reads, 3 of the pointer, 2 through it (RAM, ROM or I/O)
        int lda = Cycles65816.opcode("lda", "[dp],y");
        assertEquals(new MasterCycles.Range(2 * 6 + 3 * 8 + 2 * 6, 2 * 6 + 3 * 8 + 2 * 8),
                MasterCycles.of(lda, "[dp],y", "[dampf_ptr],y", true, true, false, true));
        // STA f:$002118 in 8 bits: 4 program reads and an I/O register, all at 6
        int sta = Cycles65816.opcode("sta", "long");
        assertEquals(MasterCycles.Range.of(30), MasterCycles.of(sta, "long", "f:$002118", false, true, false, true));
        assertEquals(MasterCycles.Range.of(38), MasterCycles.of(sta, "long", "f:$002118", false, true, false, false));
        // LDA long of a symbol: constant data in ROM, or a variable in bank $7F (ADR-6FVM9QWS)
        int ldaLong = Cycles65816.opcode("lda", "long");
        assertEquals(MasterCycles.Range.of(4 * 6 + 2 * 6), MasterCycles.of(ldaLong, "long", "f:_table+2", true, true, false, true));
        assertEquals(MasterCycles.Range.of(4 * 6 + 2 * 8), MasterCycles.of(ldaLong, "long", "f:_map+2", true, true, false,
                new MasterCycles.Code(true, java.util.Set.of("_map"))));
        // LDA abs, 16 bit: WRAM through DBR
        int ldaAbs = Cycles65816.opcode("lda", "abs");
        assertEquals(MasterCycles.Range.of(3 * 6 + 2 * 8), MasterCycles.of(ldaAbs, "abs", "_x", true, true, false, true));
    }
}
