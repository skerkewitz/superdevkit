package dampf.exec;

import dampf.emu.Snes;
import dampf.snes.RomBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a ROM in dampf's own emulator ({@link Snes}) until the runtime writes $21FB, then reads the
 * observed globals from WRAM. The same job as {@link MesenRunner}, inside the JVM and much faster.
 */
final class EmulatorRunner {
    private final long limitMasterCycles;

    /**
     * Short test programs: at most about 600 frames; 6,000 with a pass limit
     * ({@link dampf.util.PassLimit}), since code without the passes is up to ten times slower.
     */
    EmulatorRunner() {
        this((dampf.util.PassLimit.configured() >= 0 ? 6_000L : 600L) * 357_366);
    }

    EmulatorRunner(long limitMasterCycles) {
        this.limitMasterCycles = limitMasterCycles;
    }

    /** The values, and the master cycles the program took. */
    record Result(Map<String, Long> values, long masterCycles) {}

    Result run(RomBuilder.Rom rom, List<Observed> globals) throws IOException {
        Snes snes = Snes.load(rom.rom());
        if (!snes.run(limitMasterCycles)) {
            throw new IOException("the program did not finish within " + limitMasterCycles + " master cycles");
        }
        Map<String, Long> addresses = labels(rom.labels());
        byte[] wram = snes.wram();
        Map<String, Long> values = new LinkedHashMap<>();
        for (Observed v : globals) {
            Long base = addresses.get(v.label());
            if (base == null) {   // left out: no reachable code touches it
                values.put(v.path(), v.initial());
                continue;
            }
            int addr = (int) (base + v.offset()); // a bank $7E address without the bank
            long raw = 0;
            for (int i = 0; i < v.type().size(); i++) {
                raw |= (long) (wram[addr + i] & 0xFF) << (8 * i);
            }
            values.put(v.path(), v.type().wrap(raw));
        }
        return new Result(values, snes.masterClock());
    }

    /**
     * The CPU cycles the entry function takes, from its first instruction until control is back
     * in the runtime (at dampf_done, right after the JSL that called it). The cycles of interrupts
     * that came meanwhile are left out.
     */
    long cyclesOf(RomBuilder.Rom rom, String function) throws IOException {
        return measure(rom, function, false);
    }

    /** The same in master cycles ({@link dampf.emu.Cpu65816#master}): what the static timing bounds. */
    long masterCyclesOf(RomBuilder.Rom rom, String function) throws IOException {
        return measure(rom, function, true);
    }

    private long measure(RomBuilder.Rom rom, String function, boolean master) throws IOException {
        Map<String, Long> addresses = labels(rom.labels());
        long start = addresses.get(function);
        long stop = addresses.get("dampf_done");
        Snes snes = Snes.load(rom.rom());
        long[] at = {-1, -1};
        snes.run(limitMasterCycles, address -> {
            dampf.emu.Cpu65816 cpu = snes.cpu();
            long own = master ? cpu.master - cpu.masterAbove[0] : cpu.cycles - cpu.cyclesAbove[0];
            if (address == start && at[0] < 0) {
                at[0] = own;
            } else if (address == stop && at[0] >= 0 && at[1] < 0) {
                at[1] = own;
            }
        });
        if (at[0] < 0 || at[1] < 0) {
            throw new IOException(function + " did not run to its end");
        }
        return at[1] - at[0];
    }

    /** How many calls were measured, and the first that broke their static bounds. */
    record CallCheck(long calls, List<String> wrong) {}

    /**
     * Runs the ROM and measures every call of every function (and runtime routine) with static
     * bounds, in master cycles: from its first instruction to the instruction after the JSL that called it. Each
     * must lie within the bounds (best, worst; worst -1: unbounded). The cycles of interrupts that
     * came during a call are not the call's. An interrupt whose entry stub has bounds
     * ({@code dampf_irq}, {@code dampf_nmi}) is measured too, from its first cycle to the end of
     * its RTI, without an interrupt that came during it.
     */
    CallCheck checkCalls(RomBuilder.Rom rom, Map<String, long[]> timing) throws IOException {
        Map<Long, String> starts = new HashMap<>();
        for (Map.Entry<String, Long> e : labels(rom.labels()).entrySet()) {
            if (timing.containsKey(e.getKey())) {
                starts.put(e.getValue(), e.getKey());
            }
        }
        byte[] image = Files.readAllBytes(rom.rom());
        Snes snes = Snes.load(rom.rom());
        record Call(String function, long start, long returnTo, int depth, long above) {}
        java.util.Deque<Call> stack = new java.util.ArrayDeque<>();
        long[] previous = {-1};
        long[] calls = {0};
        long[] interrupts = {0};
        List<String> wrong = new java.util.ArrayList<>();
        dampf.emu.Cpu65816 cpu = snes.cpu();
        snes.run(limitMasterCycles, address -> {
            long cycles = cpu.master;
            while (!stack.isEmpty() && stack.peek().returnTo() == address && stack.peek().depth() >= cpu.depth) {
                Call c = stack.pop();
                long took = cycles - c.start() - (cpu.masterAbove[c.depth()] - c.above());
                long[] b = timing.get(c.function());
                if ((took < b[0] || b[1] >= 0 && took > b[1]) && wrong.size() < 10) {
                    wrong.add(c.function() + " took " + took + " master cycles, bounds " + b[0] + ".." + (b[1] < 0 ? "unbounded" : b[1]));
                }
                calls[0]++;
            }
            if (cpu.interrupts != interrupts[0]) {
                // an interrupt was taken right before this instruction, the first of its stub
                interrupts[0] = cpu.interrupts;
                String stub = starts.get((long) address | 0x800000);   // its label is in bank $80, the vector leads to $00
                if (stub != null) {
                    stack.push(new Call(stub, cpu.interruptMasterStart, cpu.interruptReturn, cpu.depth, cpu.masterAbove[cpu.depth]));
                }
            } else {
                String f = starts.get((long) address);
                if (f != null && previous[0] >= 0 && byteAt(image, previous[0]) == 0x22) {   // after a JSL
                    stack.push(new Call(f, cycles, previous[0] + 4, cpu.depth, cpu.masterAbove[cpu.depth]));
                }
            }
            previous[0] = address;
        });
        return new CallCheck(calls[0], wrong);
    }

    /** The byte at a CPU address in a LoROM image (banks $80 and up, $8000-$FFFF). */
    private static int byteAt(byte[] rom, long address) {
        if ((address & 0xFFFF) < 0x8000) {
            return -1;
        }
        int offset = (int) (((address >> 16) & 0x7F) * 0x8000 + (address & 0x7FFF));
        return offset < rom.length ? rom[offset] & 0xFF : -1;
    }

    private static Map<String, Long> labels(Path file) throws IOException {
        Map<String, Long> m = new HashMap<>();
        for (String line : Files.readAllLines(file)) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 3 && parts[0].equals("al")) {
                m.put(parts[2].substring(1), Long.parseLong(parts[1], 16));
            }
        }
        return m;
    }
}
