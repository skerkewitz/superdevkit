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

    /** Short test programs: at most about 600 frames. */
    EmulatorRunner() {
        this(600L * 357_366);
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
            if (base == null) {
                throw new IOException("no address for " + v.label());
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
