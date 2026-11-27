package dampf.emu;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Finds the global that a miscompilation changes first: plays two builds of the same game (the
 * one to check and one known to be right, such as {@code --backend naive}) with the same input in
 * dampf's emulator and compares their globals, by name, after every tick:
 * <pre>
 *   java -cp 'build/install/superdevkit/java/*' dampf.emu.RomDiff good.sfc bad.sfc recording.txt [ticks]
 * </pre>
 * The globals are the labels of the label file (.lbl, beside each ROM) from $2000 to $7FFF, in WRAM; a
 * global ends where the next label begins, in the build where that comes first. Each tick prints the globals that differ and did not
 * before, with the bytes of both, so the first lines name what went wrong first. Globals that hold
 * addresses differ from the start, since the two builds place things differently; they are
 * printed at tick 0 and not again.
 */
public final class RomDiff {
    private RomDiff() {
    }

    private record Global(String name, int address, int size) {}

    public static void main(String[] args) throws IOException {
        if (args.length < 3 || args.length > 4) {
            System.err.println("usage: RomDiff good.sfc bad.sfc recording.txt [ticks]");
            System.exit(2);
        }
        ExampleCheck.Input input = ExampleCheck.parse(Path.of(args[2]));
        long ticks = args.length == 4 ? Long.parseLong(args[3]) : input.ticks();
        List<Global> goodGlobals = globals(Path.of(args[0]));
        List<Global> badGlobals = globals(Path.of(args[1]));
        Map<String, Integer> sizes = new java.util.HashMap<>();
        goodGlobals.forEach(g -> sizes.put(g.name(), g.size()));
        badGlobals.forEach(g -> sizes.merge(g.name(), g.size(), Math::min));
        List<Map<String, byte[]>> good = play(Path.of(args[0]), sized(goodGlobals, sizes), input, ticks);
        List<Map<String, byte[]>> bad = play(Path.of(args[1]), sized(badGlobals, sizes), input, ticks);
        System.out.printf("%d and %d ticks, %d globals%n", good.size(), bad.size(), goodGlobals.size());
        java.util.Set<String> known = new java.util.HashSet<>();
        int shown = 0;
        for (int t = 0; t < Math.min(good.size(), bad.size()) && shown < 400; t++) {
            for (Map.Entry<String, byte[]> e : good.get(t).entrySet()) {
                byte[] b = bad.get(t).get(e.getKey());
                if (b == null || known.contains(e.getKey()) || java.util.Arrays.equals(e.getValue(), b)) {
                    continue;
                }
                known.add(e.getKey());
                shown++;
                System.out.printf("tick %d %s%n  good %s%n  bad  %s%n", t, e.getKey(), hex(e.getValue()), hex(b));
            }
        }
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            if (i == 64) {
                return s + "... (" + b.length + " bytes)";
            }
            s.append(String.format("%02x ", b[i] & 0xFF));
        }
        return s.toString();
    }

    private static List<Global> globals(Path rom) throws IOException {
        String name = rom.getFileName().toString().replaceFirst("\\.sfc$", ".lbl");
        TreeMap<Integer, String> byAddress = new TreeMap<>();
        for (String line : Files.readAllLines(rom.resolveSibling(name))) {
            String[] w = line.split("\\s+");
            if (w.length == 3 && w[0].equals("al") && w[2].startsWith("._") && !w[2].startsWith(".__")) {
                int a = Integer.parseInt(w[1], 16);
                // the globals: in bank $7E without the bank (bank 0 is ROM from $8000), in bank $7F with it
                if (a >= 0x2000 && a < 0x8000 || a >> 16 == dampf.target.SnesTarget.SECOND_VARIABLE_BANK) {
                    byAddress.putIfAbsent(dampf.target.SnesTarget.wramIndex(a), w[2].substring(2));
                }
            }
        }
        List<Global> g = new ArrayList<>();
        Map.Entry<Integer, String> e = byAddress.firstEntry();
        while (e != null) {
            Map.Entry<Integer, String> next = byAddress.higherEntry(e.getKey());
            int size = Math.min(next == null ? 256 : next.getKey() - e.getKey(), 4096);
            g.add(new Global(e.getValue(), e.getKey(), size));
            e = next;
        }
        return g;
    }

    /** The globals with the smaller of the two builds' sizes: a gap to the next label may hold more than the global. */
    private static List<Global> sized(List<Global> globals, Map<String, Integer> sizes) {
        return globals.stream().map(g -> new Global(g.name(), g.address(), sizes.get(g.name()))).toList();
    }

    private static List<Map<String, byte[]>> play(Path rom, List<Global> globals, ExampleCheck.Input input, long ticks)
            throws IOException {
        Snes snes = new Snes(Files.readAllBytes(rom));
        List<Map<String, byte[]>> states = new ArrayList<>();
        boolean[] started = { false };
        snes.onPortWrite((port, value, clock) -> {
            if (port != 0x21FD) {
                return;
            }
            if (value == 1) {
                started[0] = true;
            } else if (started[0]) {
                Map<String, byte[]> s = new LinkedHashMap<>();
                for (Global g : globals) {
                    byte[] b = new byte[g.size()];
                    for (int i = 0; i < b.length; i++) {
                        b[i] = snes.wram()[g.address() + i];
                    }
                    s.put(g.name(), b);
                }
                states.add(s);
                if (states.size() >= ticks) {
                    snes.stop();
                }
            }
            snes.setPads(started[0] ? input.pad(states.size()) : 0, 0);
        });
        snes.run((40 * ticks + 2000) * ExampleCheck.FRAME);
        return states;
    }
}
