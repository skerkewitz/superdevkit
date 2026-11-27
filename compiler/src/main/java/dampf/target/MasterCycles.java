package dampf.target;

/**
 * What an instruction costs in master cycles (21.477 MHz on NTSC), the unit of dampf's timing
 * (docs/timing.md, ADR-6FVMDV79). Each CPU cycle of the 65816 is a memory access or an internal
 * operation, and the SNES makes the CPU wait for slow memory: an access costs 6, 8 or 12 master
 * cycles by its address ({@link #speed}), an internal operation 6. So an instruction costs its
 * accesses, sorted by where they go ({@link #accesses}), at the speed of each place.
 *
 * <p>The places, in dampf's code (docs/memory-map.md): the code is in ROM banks from $80, read at
 * FastROM speed (6) or, with {@code --slowrom}, at 8; the direct page and the stack lie in low RAM
 * (8); DBR is $7E, so an absolute address is WRAM (8); a long address is an I/O register at its
 * own speed or constant data in ROM; a pointer may point to RAM, ROM, save RAM or an I/O register,
 * 6 to 8, except the old joypad registers at $4000-$41FF (12), which no pointer of dampf's code
 * reaches (they are read through the automatic joypad read at $4218).
 *
 * <p>Not counted here: DRAM refresh (40 master cycles a scanline), DMA and HDMA, which stop the
 * CPU whatever it runs (level 3 of docs/timing.md).
 */
public final class MasterCycles {
    /** An internal operation of the CPU. */
    public static final int IO = 6;
    /** WRAM, SlowROM, save RAM and most of the address space. */
    public static final int SLOW = 8;
    /** FastROM, and the I/O registers at $2000-$3FFF and $4200-$5FFF. */
    public static final int FAST = 6;
    /** The old joypad registers at $4000-$41FF. */
    public static final int JOYPAD = 12;

    private MasterCycles() {}

    /** A cost interval in master cycles. */
    public record Range(int best, int worst) {
        public static Range of(int c) {
            return new Range(c, c);
        }

        Range join(Range o) {
            return new Range(Math.min(best, o.best), Math.max(worst, o.worst));
        }

        @Override
        public String toString() {
            return best == worst ? Integer.toString(best) : best + "-" + worst;
        }
    }

    /**
     * Master cycles of one access at a 24-bit address (fullsnes, "SNES Memory Map"; dampf's
     * emulator uses this too): 8 for WRAM, SlowROM and most of the space, 6 for the I/O registers
     * at $2000-$3FFF and $4200-$5FFF and for ROM in the banks from $80 when {@code fastRom}
     * (MEMSEL set), 12 for the old joypad registers at $4000-$41FF.
     */
    public static int speed(int address, boolean fastRom) {
        int bank = address >> 16 & 0xFF;
        int offset = address & 0xFFFF;
        if ((bank & 0x40) != 0) {
            // $40-$7F and $C0-$FF: ROM or WRAM all through
            return bank >= 0xC0 && fastRom ? FAST : SLOW;
        }
        if (offset < 0x2000) {
            return SLOW;
        }
        if (offset < 0x4000) {
            return FAST;
        }
        if (offset < 0x4200) {
            return JOYPAD;
        }
        if (offset < 0x6000) {
            return FAST;
        }
        if (offset < 0x8000) {
            return SLOW;
        }
        return bank >= 0x80 && fastRom ? FAST : SLOW;
    }

    /** The speeds of the addresses from {@code from} to {@code to} within one bank. */
    public static Range speeds(int from, int to, boolean fastRom) {
        Range r = Range.of(speed(from, fastRom));
        int bank = from & 0xFF0000;
        for (int edge : new int[] {0x2000, 0x4000, 0x4200, 0x6000, 0x8000}) {
            int a = bank | edge;
            if (a > from && a <= to) {
                r = r.join(Range.of(speed(a, fastRom)));
            }
        }
        return r;
    }

    /** The speed of the code: ROM from bank $80, FastROM or SlowROM. */
    public static int code(boolean fastRom) {
        return fastRom ? FAST : SLOW;
    }

    /**
     * The CPU cycles of one execution of an instruction, by where they go: {@code program} reads
     * at the program counter (the opcode and its operand), {@code stack} pushes and pulls,
     * {@code direct} the direct page (an operand there, or a pointer read from there),
     * {@code data} the effective address of an absolute, long or indirect operand, {@code table}
     * a jump table or pointer read from the program bank ({@code JMP (abs,X)}, {@code JSR (abs,X)})
     * or bank 0 ({@code JMP (abs)}, {@code JML [abs]}, the vector of BRK and COP), and {@code io}
     * the internal operations. They add up to {@link Cycles65816#cycles}.
     */
    public record Accesses(int program, int stack, int direct, int data, int table, int io) {}

    /** The accesses of one execution ({@link Accesses}); a block move counts one byte. */
    public static Accesses accesses(int opcode, boolean m16, boolean x16, boolean taken) {
        String name = Cycles65816.name(opcode);
        int sp = name.indexOf(' ');
        String mnemonic = sp < 0 ? name : name.substring(0, sp);
        String mode = sp < 0 ? "" : name.substring(sp + 1);
        int cycles = Cycles65816.cycles(opcode, m16, x16, taken);
        int program = Cycles65816.length(opcode, m16, x16);
        boolean index = switch (mnemonic) {
            case "ldx", "ldy", "stx", "sty", "cpx", "cpy", "phx", "phy", "plx", "ply" -> true;
            default -> false;
        };
        int w = (index ? x16 : m16) ? 2 : 1;
        boolean rmw = switch (mnemonic) {
            case "asl", "rol", "lsr", "ror", "inc", "dec", "tsb", "trb" -> !mode.equals("a") && !mode.isEmpty();
            default -> false;
        };
        int operand = rmw ? 2 * w : w;
        int stack = 0;
        int direct = 0;
        int data = 0;
        int table = 0;
        switch (mnemonic) {
            case "pha", "pla", "phx", "plx", "phy", "ply" -> stack = w;
            case "php", "plp", "phb", "plb", "phk" -> stack = 1;
            case "phd", "pld", "pea", "per", "jsr", "rts" -> stack = 2;
            case "pei" -> {
                stack = 2;
                direct = 2;
            }
            case "jsl", "rtl" -> stack = 3;
            case "rti" -> stack = 4;
            case "brk", "cop" -> {
                stack = 4;
                table = 2;
            }
            case "mvn", "mvp" -> data = 2;
            case "jmp", "jml" -> table = switch (mode) {
                case "(abs)", "(abs,x)" -> 2;
                case "[abs]" -> 3;
                default -> 0;
            };
            default -> {
                switch (mode) {
                    case "dp", "dp,x", "dp,y" -> direct = operand;
                    case "sr,s" -> stack = operand;
                    case "(dp)", "(dp),y", "(dp,x)" -> {
                        direct = 2;
                        data = operand;
                    }
                    case "[dp]", "[dp],y" -> {
                        direct = 3;
                        data = operand;
                    }
                    case "(sr,s),y" -> {
                        stack = 2;
                        data = operand;
                    }
                    case "abs", "abs,x", "abs,y", "long", "long,x" -> data = operand;
                    default -> { }
                }
            }
        }
        if (mnemonic.equals("jsr") && mode.equals("(abs,x)")) {
            table = 2;
        }
        int io = cycles - program - stack - direct - data - table;
        if (io < 0) {
            throw new IllegalStateException("more accesses than cycles: " + name);
        }
        return new Accesses(program, stack, direct, data, table, io);
    }

    /**
     * The master cycles of one execution, with the speed of the code, of the direct page, of the
     * data the operand reaches and of a table read (each a range where the address is not known).
     * The stack is low RAM.
     */
    public static Range cost(int opcode, boolean m16, boolean x16, boolean taken, int code, Range direct, Range data,
                             Range table) {
        Accesses a = accesses(opcode, m16, x16, taken);
        int fixed = a.program() * code + a.stack() * SLOW + a.io() * IO;
        return new Range(fixed + a.direct() * direct.best() + a.data() * data.best() + a.table() * table.best(),
                fixed + a.direct() * direct.worst() + a.data() * data.worst() + a.table() * table.worst());
    }

    /** Any address a pointer of dampf's code may hold: RAM, ROM, save RAM, an I/O register. */
    public static final Range POINTER = new Range(FAST, SLOW);
    /** Low RAM, where the direct page and the stack are. */
    public static final Range LOW_RAM = Range.of(SLOW);

    /**
     * The master cycles of an instruction of dampf's generated code, from its operand as written
     * ({@code z:x} direct page, {@code x} or {@code x,x} absolute in DBR's bank $7E, {@code f:x}
     * long, {@code [x],y} through a pointer): an I/O register given as a number at its own speed,
     * the CPU's multiplier ({@code WRMPYA}, {@code WRMPYB}, {@code RDMPY}) at 6, any other symbol
     * reached long is constant data in ROM, at the code's speed. A long address with an index
     * counts every address from it to the end of its bank.
     */
    public static Range of(int opcode, String mode, String operand, boolean m16, boolean x16, boolean taken,
                           boolean fastRom) {
        int code = code(fastRom);
        Range data = switch (mode) {
            case "long", "long,x" -> longOperand(operand, mode.equals("long,x"), fastRom);
            case "[dp]", "[dp],y" -> POINTER;
            default -> speeds(SnesTarget.DATA_BANK << 16, SnesTarget.DATA_BANK << 16 | 0xFFFF, fastRom);
        };
        Range table = mode.equals("(abs,x)") ? Range.of(code) : Range.of(SLOW);
        return cost(opcode, m16, x16, taken, code, LOW_RAM, data, table);
    }

    private static Range longOperand(String operand, boolean indexed, boolean fastRom) {
        if (operand == null) {
            return Range.of(SLOW);
        }
        String o = operand.strip();
        if (o.startsWith("f:")) {
            o = o.substring(2);
        }
        if (indexed && o.endsWith(",x")) {
            o = o.substring(0, o.length() - 2);
        }
        Integer address = switch (o) {
            case "WRMPYA" -> SnesTarget.WRMPYA;
            case "WRMPYB" -> SnesTarget.WRMPYB;
            case "RDMPY" -> SnesTarget.RDMPY;
            default -> number(o);
        };
        if (address == null) {
            return Range.of(code(fastRom));   // constant data in ROM, from bank $80
        }
        return indexed ? speeds(address, address | 0xFFFF, fastRom) : Range.of(speed(address, fastRom));
    }

    /**
     * The address of a long operand given as a number ({@code f:$00210D}, {@code f:$002100,x}: the
     * base), or of the multiplier's names; null for a symbol.
     */
    public static Integer longAddress(String operand) {
        if (operand == null) {
            return null;
        }
        String o = operand.strip();
        if (o.startsWith("f:")) {
            o = o.substring(2);
        }
        if (o.endsWith(",x")) {
            o = o.substring(0, o.length() - 2);
        }
        return switch (o) {
            case "WRMPYA" -> SnesTarget.WRMPYA;
            case "WRMPYB" -> SnesTarget.WRMPYB;
            case "RDMPY" -> SnesTarget.RDMPY;
            default -> number(o);
        };
    }

    private static Integer number(String s) {
        try {
            if (s.startsWith("$")) {
                return Integer.parseInt(s.substring(1), 16);
            }
            if (!s.isEmpty() && Character.isDigit(s.charAt(0))) {
                return Integer.decode(s);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }
}
