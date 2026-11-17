package dampf.emu;

/**
 * What the CPU is connected to. Addresses are 24 bit. Every call is one CPU cycle: a memory
 * access ({@link #read}, {@link #write}) or an internal operation ({@link #idle}), so a bus can
 * count time exactly (on the SNES, an access costs 6 or 8 master cycles depending on the address,
 * an internal operation 6).
 */
public interface Bus {
    int read(int address);

    void write(int address, int value);

    /** A CPU cycle without a memory access. */
    void idle();
}
