package dampf.emu;

/**
 * A 65816 CPU, instruction by instruction, cycle-exact in native mode: every memory access and
 * every internal cycle goes through the {@link Bus}, in the number the W65C816S data sheet gives
 * (validated against the SingleStepTests suite, native mode; see {@code CpuSingleStepTest}).
 *
 * <p>Emulation mode is only modelled as far as starting a program needs it (the CPU starts in
 * emulation mode; dampf's runtime switches to native mode with its third instruction): M and X are
 * forced to 8 bit and the stack stays in page 1. Its special cases (6502 page wrapping, the B flag,
 * emulation-mode interrupt vectors) are not modelled and not validated.
 *
 * <p>Interrupts: {@link #interrupt} takes one in native mode (the bus decides when, see {@link
 * Snes}); it counts how deep the CPU is in interrupt code, and {@link #cyclesAbove} the cycles
 * spent deeper than each level, so a measurement of code that was interrupted can leave the
 * interrupts out. WAI does not wait.
 */
public final class Cpu65816 {
    public int a;
    public int x;
    public int y;
    public int s;
    public int d;
    public int dbr;
    public int pbr;
    public int pc;
    public boolean e;
    public boolean n;
    public boolean v;
    public boolean m;      // 8-bit accumulator
    public boolean xf;     // 8-bit index registers
    public boolean dec;    // decimal mode
    public boolean i;
    public boolean z;
    public boolean c;
    /** STP was executed (the CPU does nothing more until a reset). */
    public boolean stopped;

    /** CPU cycles so far. */
    public long cycles;
    /** Of those, memory accesses (the rest are internal cycles). */
    public long accesses;
    /**
     * How many interrupts the CPU is in: raised when it takes one, lowered by the RTI that leaves
     * it (an RTI outside an interrupt leaves it at 0).
     */
    public int depth;
    /** cyclesAbove[d]: the CPU cycles spent at a depth greater than d, from the interrupt's first cycle to its RTI's last. */
    public final long[] cyclesAbove = new long[8];
    /**
     * The master cycles of the CPU's own cycles so far, each access at the speed of its address
     * ({@link Bus#speed}) and each internal operation 6: what dampf's timing counts. DRAM refresh
     * and DMA, which stop the CPU between its cycles, are not in it.
     */
    public long master;
    /** masterAbove[d]: as {@link #cyclesAbove}, in master cycles. */
    public final long[] masterAbove = new long[8];
    /** The master cycle an interrupt began at (of {@link #master}), for the last one taken. */
    public long interruptMasterStart;
    /** The cycle an interrupt began at, and where it returns to (bank and address), for the last one taken. */
    public long interruptStart;
    public int interruptReturn;
    /** How many interrupts were taken since power-on. */
    public long interrupts;

    private final Bus bus;

    // effective address of the current instruction; ea0: it lies in bank 0 and wraps there (direct page, stack)
    private int ea;
    private boolean ea0;

    public Cpu65816(Bus bus) {
        this.bus = bus;
    }

    /** Power-on or reset: emulation mode, then the reset vector at $00:FFFC. */
    public void reset() {
        e = true;
        m = true;
        xf = true;
        i = true;
        dec = false;
        d = 0;
        dbr = 0;
        pbr = 0;
        s = 0x01FF;
        x &= 0xFF;
        y &= 0xFF;
        stopped = false;
        depth = 0;
        pc = bus.read(0xFFFC) | bus.read(0xFFFD) << 8;
    }

    public int p() {
        return (n ? 0x80 : 0) | (v ? 0x40 : 0) | (m ? 0x20 : 0) | (xf ? 0x10 : 0) | (dec ? 0x08 : 0) | (i ? 0x04 : 0)
                | (z ? 0x02 : 0) | (c ? 0x01 : 0);
    }

    public void setP(int p) {
        n = (p & 0x80) != 0;
        v = (p & 0x40) != 0;
        m = (p & 0x20) != 0;
        xf = (p & 0x10) != 0;
        dec = (p & 0x08) != 0;
        i = (p & 0x04) != 0;
        z = (p & 0x02) != 0;
        c = (p & 0x01) != 0;
        fixWidths();
    }

    private void fixWidths() {
        if (e) {
            m = true;
            xf = true;
            s = 0x0100 | (s & 0xFF);
        }
        if (xf) {
            x &= 0xFF;
            y &= 0xFF;
        }
    }

    // ------------------------------------------------------------------ bus cycles

    private int rd(int address) {
        cycle(bus.speed(address & 0xFFFFFF));
        accesses++;
        return bus.read(address & 0xFFFFFF) & 0xFF;
    }

    private void wr(int address, int value) {
        cycle(bus.speed(address & 0xFFFFFF));
        accesses++;
        bus.write(address & 0xFFFFFF, value & 0xFF);
    }

    private void io() {
        cycle(dampf.target.MasterCycles.IO);
        bus.idle();
    }

    private void cycle(int masterCycles) {
        cycles++;
        master += masterCycles;
        for (int k = 0; k < depth; k++) {
            cyclesAbove[k]++;
            masterAbove[k] += masterCycles;
        }
    }

    /**
     * Takes an interrupt in native mode, as the W65C816S does between two instructions: two
     * internal cycles (the first reads at PC), PBR, PC and P pushed, I set, D cleared, then the
     * 16-bit vector at {@code vector} in bank 0: 8 cycles. (Emulation mode would push no PBR; dampf's
     * programs run in native mode.)
     */
    public void interrupt(int vector) {
        interruptStart = cycles;
        interruptMasterStart = master;
        interruptReturn = pbr << 16 | pc;
        interrupts++;
        depth = Math.min(depth + 1, cyclesAbove.length);
        stopped = false;
        rd(pbr << 16 | pc);
        io();
        if (!e) {
            push(pbr);
        }
        push16(pc);
        push(p());
        i = true;
        dec = false;
        pbr = 0;
        int lo = rd(vector);
        pc = lo | rd(vector + 1) << 8;
    }

    private int fetch() {
        int value = rd(pbr << 16 | pc);
        pc = (pc + 1) & 0xFFFF;
        return value;
    }

    private int fetch16() {
        int lo = fetch();
        return lo | fetch() << 8;
    }

    private int fetch24() {
        int w = fetch16();
        return w | fetch() << 16;
    }

    /** The byte after an address: within bank 0 for direct page and stack, across banks otherwise. */
    private int next(int address) {
        return ea0 ? (address + 1) & 0xFFFF : (address + 1) & 0xFFFFFF;
    }

    private int readData(boolean wide) {
        int lo = rd(ea);
        return wide ? lo | rd(next(ea)) << 8 : lo;
    }

    private void writeData(int value, boolean wide) {
        wr(ea, value);
        if (wide) {
            wr(next(ea), value >> 8);
        }
    }

    // ------------------------------------------------------------------ addressing modes

    private void dp() {
        int op = fetch();
        if ((d & 0xFF) != 0) {
            io();
        }
        ea = (d + op) & 0xFFFF;
        ea0 = true;
    }

    private void dpIndexed(int index) {
        int op = fetch();
        if ((d & 0xFF) != 0) {
            io();
        }
        io();
        ea = (d + op + index) & 0xFFFF;
        ea0 = true;
    }

    /** A 16-bit pointer at the effective address (bank 0). */
    private int pointer16() {
        int lo = rd(ea);
        return lo | rd(next(ea)) << 8;
    }

    private int pointer24() {
        int lo = rd(ea);
        int a1 = next(ea);
        int hi = rd(a1);
        return lo | hi << 8 | rd((a1 + 1) & 0xFFFF) << 16;
    }

    private void dpIndirect() {
        dp();
        ea = dbr << 16 | pointer16();
        ea0 = false;
    }

    private void dpIndirectLong() {
        dp();
        ea = pointer24();
        ea0 = false;
    }

    private void dpIndirectY(boolean write) {
        dp();
        int base = dbr << 16 | pointer16();
        ea0 = false;
        ea = (base + y) & 0xFFFFFF;
        if (write || !xf || ((base ^ ea) & 0xFFFF00) != 0) {
            io();
        }
    }

    private void dpIndirectLongY() {
        dpIndirectLong();
        ea = (ea + y) & 0xFFFFFF;
    }

    private void dpXIndirect() {
        dpIndexed(x);
        ea = dbr << 16 | pointer16();
        ea0 = false;
    }

    private void abs() {
        ea = dbr << 16 | fetch16();
        ea0 = false;
    }

    private void absIndexed(int index, boolean write) {
        int base = dbr << 16 | fetch16();
        ea = (base + index) & 0xFFFFFF;
        ea0 = false;
        if (write || !xf || ((base ^ ea) & 0xFFFF00) != 0) {
            io();
        }
    }

    private void absLong() {
        ea = fetch24();
        ea0 = false;
    }

    private void absLongX() {
        ea = (fetch24() + x) & 0xFFFFFF;
        ea0 = false;
    }

    private void stackRelative() {
        int op = fetch();
        io();
        ea = (s + op) & 0xFFFF;
        ea0 = true;
    }

    private void stackRelativeIndirectY() {
        stackRelative();
        int ptr = pointer16();
        io();
        ea = ((dbr << 16 | ptr) + y) & 0xFFFFFF;
        ea0 = false;
    }

    // ------------------------------------------------------------------ flags and arithmetic

    private void nz(int value, boolean wide) {
        if (wide) {
            z = (value & 0xFFFF) == 0;
            n = (value & 0x8000) != 0;
        } else {
            z = (value & 0xFF) == 0;
            n = (value & 0x80) != 0;
        }
    }

    private void setA(int value) {
        a = m ? (a & 0xFF00) | (value & 0xFF) : value & 0xFFFF;
    }

    private int adc(int acc, int data, boolean wide) {
        int result;
        int cin = c ? 1 : 0;
        if (!wide) {
            acc &= 0xFF;
            if (!dec) {
                result = acc + data + cin;
            } else {
                result = (acc & 0x0F) + (data & 0x0F) + cin;
                if (result > 0x09) {
                    result += 0x06;
                }
                c = result > 0x0F;
                result = (acc & 0xF0) + (data & 0xF0) + (c ? 0x10 : 0) + (result & 0x0F);
            }
            v = (~(acc ^ data) & (acc ^ result) & 0x80) != 0;
            if (dec && result > 0x9F) {
                result += 0x60;
            }
            c = result > 0xFF;
            nz(result, false);
            return result & 0xFF;
        }
        if (!dec) {
            result = acc + data + cin;
        } else {
            result = (acc & 0x000F) + (data & 0x000F) + cin;
            if (result > 0x0009) {
                result += 0x0006;
            }
            c = result > 0x000F;
            result = (acc & 0x00F0) + (data & 0x00F0) + (c ? 0x10 : 0) + (result & 0x000F);
            if (result > 0x009F) {
                result += 0x0060;
            }
            c = result > 0x00FF;
            result = (acc & 0x0F00) + (data & 0x0F00) + (c ? 0x100 : 0) + (result & 0x00FF);
            if (result > 0x09FF) {
                result += 0x0600;
            }
            c = result > 0x0FFF;
            result = (acc & 0xF000) + (data & 0xF000) + (c ? 0x1000 : 0) + (result & 0x0FFF);
        }
        v = (~(acc ^ data) & (acc ^ result) & 0x8000) != 0;
        if (dec && result > 0x9FFF) {
            result += 0x6000;
        }
        c = result > 0xFFFF;
        nz(result, true);
        return result & 0xFFFF;
    }

    private int sbc(int acc, int data, boolean wide) {
        int result;
        int cin = c ? 1 : 0;
        if (!wide) {
            acc &= 0xFF;
            data = ~data & 0xFF;
            if (!dec) {
                result = acc + data + cin;
            } else {
                result = (acc & 0x0F) + (data & 0x0F) + cin;
                if (result <= 0x0F) {
                    result -= 0x06;
                }
                c = result > 0x0F;
                result = (acc & 0xF0) + (data & 0xF0) + (c ? 0x10 : 0) + (result & 0x0F);
            }
            v = (~(acc ^ data) & (acc ^ result) & 0x80) != 0;
            if (dec && result <= 0xFF) {
                result -= 0x60;
            }
            c = result > 0xFF;
            nz(result, false);
            return result & 0xFF;
        }
        data = ~data & 0xFFFF;
        if (!dec) {
            result = acc + data + cin;
        } else {
            result = (acc & 0x000F) + (data & 0x000F) + cin;
            if (result <= 0x000F) {
                result -= 0x0006;
            }
            c = result > 0x000F;
            result = (acc & 0x00F0) + (data & 0x00F0) + (c ? 0x10 : 0) + (result & 0x000F);
            if (result <= 0x00FF) {
                result -= 0x0060;
            }
            c = result > 0x00FF;
            result = (acc & 0x0F00) + (data & 0x0F00) + (c ? 0x100 : 0) + (result & 0x00FF);
            if (result <= 0x0FFF) {
                result -= 0x0600;
            }
            c = result > 0x0FFF;
            result = (acc & 0xF000) + (data & 0xF000) + (c ? 0x1000 : 0) + (result & 0x0FFF);
        }
        v = (~(acc ^ data) & (acc ^ result) & 0x8000) != 0;
        if (dec && result <= 0xFFFF) {
            result -= 0x6000;
        }
        c = result > 0xFFFF;
        nz(result, true);
        return result & 0xFFFF;
    }

    private void compare(int reg, int data, boolean wide) {
        int mask = wide ? 0xFFFF : 0xFF;
        int r = (reg & mask) - (data & mask);
        c = r >= 0;
        nz(r, wide);
    }

    /** ORA AND EOR ADC (STA) LDA CMP SBC with a value, by the opcode's top three bits. */
    private void group1Apply(int g, int value) {
        boolean wide = !m;
        switch (g) {
            case 0 -> {
                setA(a | value);
                nz(a, wide);
            }
            case 1 -> {
                setA(a & (value | (m ? 0xFF00 : 0)));
                nz(a, wide);
            }
            case 2 -> {
                setA(a ^ value);
                nz(a, wide);
            }
            case 3 -> setA(adc(a, value, wide));
            case 5 -> {
                setA(value);
                nz(value, wide);
            }
            case 6 -> compare(a, value, wide);
            case 7 -> setA(sbc(a, value, wide));
            default -> throw new IllegalStateException();
        }
    }

    private boolean group1(int op) {
        int lo = op & 0x0F;
        boolean is = lo == 0x1 || lo == 0x3 || lo == 0x5 || lo == 0x7 || lo == 0x9 || lo == 0xD || lo == 0xF
                || (op & 0x1F) == 0x12;
        if (!is || op == 0x89) {
            return false;
        }
        int g = op >> 5;
        boolean write = g == 4;
        if ((op & 0x1F) == 0x09) {
            group1Apply(g, m ? fetch() : fetch16());
            return true;
        }
        switch (op & 0x1F) {
            case 0x01 -> dpXIndirect();
            case 0x03 -> stackRelative();
            case 0x05 -> dp();
            case 0x07 -> dpIndirectLong();
            case 0x0D -> abs();
            case 0x0F -> absLong();
            case 0x11 -> dpIndirectY(write);
            case 0x12 -> dpIndirect();
            case 0x13 -> stackRelativeIndirectY();
            case 0x15 -> dpIndexed(x);
            case 0x17 -> dpIndirectLongY();
            case 0x19 -> absIndexed(y, write);
            case 0x1D -> absIndexed(x, write);
            case 0x1F -> absLongX();
            default -> throw new IllegalStateException(Integer.toHexString(op));
        }
        if (write) {
            writeData(a, !m);
        } else {
            group1Apply(g, readData(!m));
        }
        return true;
    }

    // ------------------------------------------------------------------ read-modify-write

    private interface Modify {
        int apply(int value, boolean wide);
    }

    private void rmw(Modify f) {
        boolean wide = !m;
        int value = readData(wide);
        io();
        int r = f.apply(value, wide);
        if (wide) {
            wr(next(ea), r >> 8);
        }
        wr(ea, r);
    }

    private int asl(int value, boolean wide) {
        int top = wide ? 0x8000 : 0x80;
        c = (value & top) != 0;
        int r = (value << 1) & (wide ? 0xFFFF : 0xFF);
        nz(r, wide);
        return r;
    }

    private int lsr(int value, boolean wide) {
        c = (value & 1) != 0;
        int r = (value & (wide ? 0xFFFF : 0xFF)) >> 1;
        nz(r, wide);
        return r;
    }

    private int rol(int value, boolean wide) {
        int top = wide ? 0x8000 : 0x80;
        boolean carry = c;
        c = (value & top) != 0;
        int r = ((value << 1) | (carry ? 1 : 0)) & (wide ? 0xFFFF : 0xFF);
        nz(r, wide);
        return r;
    }

    private int ror(int value, boolean wide) {
        int top = wide ? 0x8000 : 0x80;
        boolean carry = c;
        c = (value & 1) != 0;
        int r = ((value & (wide ? 0xFFFF : 0xFF)) >> 1) | (carry ? top : 0);
        nz(r, wide);
        return r;
    }

    private int inc(int value, boolean wide) {
        int r = (value + 1) & (wide ? 0xFFFF : 0xFF);
        nz(r, wide);
        return r;
    }

    private int decr(int value, boolean wide) {
        int r = (value - 1) & (wide ? 0xFFFF : 0xFF);
        nz(r, wide);
        return r;
    }

    private void accumulator(Modify f) {
        io();
        boolean wide = !m;
        setA(f.apply(wide ? a : a & 0xFF, wide));
    }

    // ------------------------------------------------------------------ stack

    private void push(int value) {
        wr(s, value);
        s = e ? 0x0100 | ((s - 1) & 0xFF) : (s - 1) & 0xFFFF;
    }

    private int pull() {
        s = e ? 0x0100 | ((s + 1) & 0xFF) : (s + 1) & 0xFFFF;
        return rd(s);
    }

    private void push16(int value) {
        push(value >> 8);
        push(value);
    }

    private int pull16() {
        int lo = pull();
        return lo | pull() << 8;
    }

    // ------------------------------------------------------------------ the instructions

    private void branch(boolean condition) {
        int offset = (byte) fetch();
        if (condition) {
            io();
            int target = (pc + offset) & 0xFFFF;
            if (e && ((target ^ pc) & 0xFF00) != 0) {
                io();
            }
            pc = target;
        }
    }

    private void loadIndex(boolean isX, int value) {
        if (isX) {
            x = value;
        } else {
            y = value;
        }
        nz(value, !xf);
    }

    /** Executes one instruction. */
    public void step() {
        if (stopped) {
            io();
            return;
        }
        int op = fetch();
        if (group1(op)) {
            return;
        }
        boolean wideA = !m;
        boolean wideX = !xf;
        switch (op) {
            // --- shifts, INC, DEC, TSB, TRB
            case 0x0A -> accumulator(this::asl);
            case 0x06 -> { dp(); rmw(this::asl); }
            case 0x0E -> { abs(); rmw(this::asl); }
            case 0x16 -> { dpIndexed(x); rmw(this::asl); }
            case 0x1E -> { absIndexed(x, true); rmw(this::asl); }
            case 0x2A -> accumulator(this::rol);
            case 0x26 -> { dp(); rmw(this::rol); }
            case 0x2E -> { abs(); rmw(this::rol); }
            case 0x36 -> { dpIndexed(x); rmw(this::rol); }
            case 0x3E -> { absIndexed(x, true); rmw(this::rol); }
            case 0x4A -> accumulator(this::lsr);
            case 0x46 -> { dp(); rmw(this::lsr); }
            case 0x4E -> { abs(); rmw(this::lsr); }
            case 0x56 -> { dpIndexed(x); rmw(this::lsr); }
            case 0x5E -> { absIndexed(x, true); rmw(this::lsr); }
            case 0x6A -> accumulator(this::ror);
            case 0x66 -> { dp(); rmw(this::ror); }
            case 0x6E -> { abs(); rmw(this::ror); }
            case 0x76 -> { dpIndexed(x); rmw(this::ror); }
            case 0x7E -> { absIndexed(x, true); rmw(this::ror); }
            case 0x1A -> accumulator(this::inc);
            case 0xE6 -> { dp(); rmw(this::inc); }
            case 0xEE -> { abs(); rmw(this::inc); }
            case 0xF6 -> { dpIndexed(x); rmw(this::inc); }
            case 0xFE -> { absIndexed(x, true); rmw(this::inc); }
            case 0x3A -> accumulator(this::decr);
            case 0xC6 -> { dp(); rmw(this::decr); }
            case 0xCE -> { abs(); rmw(this::decr); }
            case 0xD6 -> { dpIndexed(x); rmw(this::decr); }
            case 0xDE -> { absIndexed(x, true); rmw(this::decr); }
            case 0x04 -> { dp(); rmw(this::tsb); }
            case 0x0C -> { abs(); rmw(this::tsb); }
            case 0x14 -> { dp(); rmw(this::trb); }
            case 0x1C -> { abs(); rmw(this::trb); }

            // --- BIT, STZ
            case 0x89 -> {
                int value = wideA ? fetch16() : fetch();
                z = (a & value & (wideA ? 0xFFFF : 0xFF)) == 0;
            }
            case 0x24 -> { dp(); bit(readData(wideA)); }
            case 0x2C -> { abs(); bit(readData(wideA)); }
            case 0x34 -> { dpIndexed(x); bit(readData(wideA)); }
            case 0x3C -> { absIndexed(x, false); bit(readData(wideA)); }
            case 0x64 -> { dp(); writeData(0, wideA); }
            case 0x74 -> { dpIndexed(x); writeData(0, wideA); }
            case 0x9C -> { abs(); writeData(0, wideA); }
            case 0x9E -> { absIndexed(x, true); writeData(0, wideA); }

            // --- X and Y
            case 0xA2 -> loadIndex(true, wideX ? fetch16() : fetch());
            case 0xA6 -> { dp(); loadIndex(true, readData(wideX)); }
            case 0xAE -> { abs(); loadIndex(true, readData(wideX)); }
            case 0xB6 -> { dpIndexed(y); loadIndex(true, readData(wideX)); }
            case 0xBE -> { absIndexed(y, false); loadIndex(true, readData(wideX)); }
            case 0xA0 -> loadIndex(false, wideX ? fetch16() : fetch());
            case 0xA4 -> { dp(); loadIndex(false, readData(wideX)); }
            case 0xAC -> { abs(); loadIndex(false, readData(wideX)); }
            case 0xB4 -> { dpIndexed(x); loadIndex(false, readData(wideX)); }
            case 0xBC -> { absIndexed(x, false); loadIndex(false, readData(wideX)); }
            case 0x86 -> { dp(); writeData(x, wideX); }
            case 0x8E -> { abs(); writeData(x, wideX); }
            case 0x96 -> { dpIndexed(y); writeData(x, wideX); }
            case 0x84 -> { dp(); writeData(y, wideX); }
            case 0x8C -> { abs(); writeData(y, wideX); }
            case 0x94 -> { dpIndexed(x); writeData(y, wideX); }
            case 0xE0 -> compare(x, wideX ? fetch16() : fetch(), wideX);
            case 0xE4 -> { dp(); compare(x, readData(wideX), wideX); }
            case 0xEC -> { abs(); compare(x, readData(wideX), wideX); }
            case 0xC0 -> compare(y, wideX ? fetch16() : fetch(), wideX);
            case 0xC4 -> { dp(); compare(y, readData(wideX), wideX); }
            case 0xCC -> { abs(); compare(y, readData(wideX), wideX); }
            case 0xE8 -> { io(); x = (x + 1) & (wideX ? 0xFFFF : 0xFF); nz(x, wideX); }
            case 0xC8 -> { io(); y = (y + 1) & (wideX ? 0xFFFF : 0xFF); nz(y, wideX); }
            case 0xCA -> { io(); x = (x - 1) & (wideX ? 0xFFFF : 0xFF); nz(x, wideX); }
            case 0x88 -> { io(); y = (y - 1) & (wideX ? 0xFFFF : 0xFF); nz(y, wideX); }

            // --- branches and jumps
            case 0x10 -> branch(!n);
            case 0x30 -> branch(n);
            case 0x50 -> branch(!v);
            case 0x70 -> branch(v);
            case 0x90 -> branch(!c);
            case 0xB0 -> branch(c);
            case 0xD0 -> branch(!z);
            case 0xF0 -> branch(z);
            case 0x80 -> branch(true);
            case 0x82 -> {
                int offset = (short) fetch16();
                io();
                pc = (pc + offset) & 0xFFFF;
            }
            case 0x4C -> pc = fetch16();
            case 0x5C -> {
                int target = fetch24();
                pc = target & 0xFFFF;
                pbr = target >> 16;
            }
            case 0x6C -> {
                ea = fetch16();
                ea0 = true;
                pc = pointer16();
            }
            case 0x7C -> {
                int base = fetch16();
                io();
                ea = pbr << 16 | ((base + x) & 0xFFFF);
                ea0 = false;
                int lo = rd(ea);
                pc = lo | rd(pbr << 16 | ((base + x + 1) & 0xFFFF)) << 8;
            }
            case 0xDC -> {
                ea = fetch16();
                ea0 = true;
                int target = pointer24();
                pc = target & 0xFFFF;
                pbr = target >> 16;
            }
            case 0x20 -> {
                int target = fetch16();
                io();
                push16((pc - 1) & 0xFFFF);
                pc = target;
            }
            case 0xFC -> {
                int lo = fetch();
                push16(pc); // pc points at the high byte of the operand
                int hi = fetch();
                io();
                int base = lo | hi << 8;
                int ptr = pbr << 16 | ((base + x) & 0xFFFF);
                int tlo = rd(ptr);
                pc = tlo | rd(pbr << 16 | ((base + x + 1) & 0xFFFF)) << 8;
            }
            case 0x22 -> {
                int lo = fetch();
                int hi = fetch();
                push(pbr);
                io();
                int bank = fetch();
                push16((pc - 1) & 0xFFFF);
                pc = lo | hi << 8;
                pbr = bank;
            }
            case 0x60 -> {
                io();
                io();
                pc = (pull16() + 1) & 0xFFFF;
                io();
            }
            case 0x6B -> {
                io();
                io();
                pc = (pull16() + 1) & 0xFFFF;
                pbr = pull();
            }
            case 0x40 -> {
                io();
                io();
                setP(pull());
                pc = pull16();
                if (!e) {
                    pbr = pull();
                }
                if (depth > 0) {
                    depth--;
                }
            }
            case 0x00, 0x02 -> {
                fetch(); // signature byte
                if (!e) {
                    push(pbr);
                }
                push16(pc);
                push(p());
                i = true;
                dec = false;
                pbr = 0;
                int vector = e ? (op == 0x00 ? 0xFFFE : 0xFFF4) : (op == 0x00 ? 0xFFE6 : 0xFFE4);
                int lo = rd(vector);
                pc = lo | rd(vector + 1) << 8;
            }

            // --- flags and modes
            case 0x18 -> { io(); c = false; }
            case 0x38 -> { io(); c = true; }
            case 0x58 -> { io(); i = false; }
            case 0x78 -> { io(); i = true; }
            case 0xB8 -> { io(); v = false; }
            case 0xD8 -> { io(); dec = false; }
            case 0xF8 -> { io(); dec = true; }
            case 0xC2 -> {
                int mask = fetch();
                io();
                setP(p() & ~mask);
            }
            case 0xE2 -> {
                int mask = fetch();
                io();
                setP(p() | mask);
            }
            case 0xFB -> {
                io();
                boolean carry = c;
                c = e;
                e = carry;
                fixWidths();
            }

            // --- transfers
            case 0xAA -> { io(); x = wideX ? a : a & 0xFF; nz(x, wideX); }
            case 0xA8 -> { io(); y = wideX ? a : a & 0xFF; nz(y, wideX); }
            case 0x8A -> { io(); setA(x); nz(a, wideA); }
            case 0x98 -> { io(); setA(y); nz(a, wideA); }
            case 0x9B -> { io(); y = x; nz(y, wideX); }
            case 0xBB -> { io(); x = y; nz(x, wideX); }
            case 0xBA -> { io(); x = wideX ? s : s & 0xFF; nz(x, wideX); }
            case 0x9A -> { io(); s = e ? 0x0100 | (x & 0xFF) : x; }
            case 0x5B -> { io(); d = a; nz(d, true); }
            case 0x7B -> { io(); a = d; nz(a, true); }
            case 0x1B -> { io(); s = e ? 0x0100 | (a & 0xFF) : a; }
            case 0x3B -> { io(); a = s; nz(a, true); }
            case 0xEB -> {
                io();
                io();
                a = (a >> 8) | ((a & 0xFF) << 8);
                nz(a & 0xFF, false);
            }

            // --- stack
            case 0x48 -> { io(); if (wideA) { push16(a); } else { push(a); } }
            case 0x68 -> {
                io();
                io();
                int value = wideA ? pull16() : pull();
                setA(value);
                nz(value, wideA);
            }
            case 0xDA -> { io(); if (wideX) { push16(x); } else { push(x); } }
            case 0xFA -> { io(); io(); x = wideX ? pull16() : pull(); nz(x, wideX); }
            case 0x5A -> { io(); if (wideX) { push16(y); } else { push(y); } }
            case 0x7A -> { io(); io(); y = wideX ? pull16() : pull(); nz(y, wideX); }
            case 0x08 -> { io(); push(p()); }
            case 0x28 -> { io(); io(); setP(pull()); }
            case 0x8B -> { io(); push(dbr); }
            case 0xAB -> { io(); io(); dbr = pull(); nz(dbr, false); }
            case 0x0B -> { io(); push16(d); }
            case 0x2B -> { io(); io(); d = pull16(); nz(d, true); }
            case 0x4B -> { io(); push(pbr); }
            case 0xF4 -> push16(fetch16());
            case 0xD4 -> {
                dp();
                push16(pointer16());
            }
            case 0x62 -> {
                int offset = fetch16();
                io();
                push16((pc + offset) & 0xFFFF);
            }

            // --- block moves
            case 0x54, 0x44 -> {
                int dest = fetch();
                int src = fetch();
                dbr = dest;
                int value = rd(src << 16 | x);
                wr(dest << 16 | y, value);
                io();
                io();
                int step = op == 0x54 ? 1 : -1;
                int mask = wideX ? 0xFFFF : 0xFF;
                x = (x + step) & mask;
                y = (y + step) & mask;
                a = (a - 1) & 0xFFFF;
                if (a != 0xFFFF) {
                    pc = (pc - 3) & 0xFFFF; // repeat for the next byte
                }
            }

            // --- the rest
            case 0xEA -> io();
            case 0x42 -> {
                // WDM: the second byte is skipped without a program read (VPA inactive)
                io();
                pc = (pc + 1) & 0xFFFF;
            }
            case 0xDB -> { io(); io(); stopped = true; }
            case 0xCB -> { io(); io(); }
            default -> throw new IllegalStateException("opcode " + Integer.toHexString(op) + " not implemented");
        }
    }

    private void bit(int value) {
        boolean wide = !m;
        z = (a & value & (wide ? 0xFFFF : 0xFF)) == 0;
        n = (value & (wide ? 0x8000 : 0x80)) != 0;
        v = (value & (wide ? 0x4000 : 0x40)) != 0;
    }

    private int tsb(int value, boolean wide) {
        int acc = wide ? a : a & 0xFF;
        z = (acc & value) == 0;
        return value | acc;
    }

    private int trb(int value, boolean wide) {
        int acc = wide ? a : a & 0xFF;
        z = (acc & value) == 0;
        return value & ~acc & (wide ? 0xFFFF : 0xFF);
    }
}
