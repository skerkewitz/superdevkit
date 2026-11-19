package dampf.asm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Links object files into a LoROM image, with the memory map of dampf's runtime
 * (docs/memory-map.md): bank $80 holds STARTUP, DAMPF_CODE and the initial values of DATA, then
 * banked segments, and HEADER and VECTORS at $FFC0 and $FFE0; the other banked segments fill
 * banks $81 and up in the order given; ZEROPAGE lies at $0000, BSS from $2000 and DATA after it
 * (where the start-up copies it). Segments of the same name are joined in the order of the files.
 * The symbols {@code __BSS_RUN__}, {@code __BSS_SIZE__}, {@code __DATA_LOAD__} and so on are
 * defined as ld65 defines them. The image is padded with $FF to a power of two banks.
 */
public final class Linker {
    public static final int FIRST_BANK = 0x80;
    public static final int BANK = 0x8000;
    private static final int RAM = 0x2000;
    private static final List<String> FIXED = List.of("STARTUP", "DAMPF_CODE", "DATA");

    /** Where a piece of a segment lies: run address (labels), load address (bytes). */
    public record Placed(ObjectFile obj, ObjectFile.Section section, int run, int load) {}

    /** The result: the image, every symbol's address (full names per file, and the globals), the placed parts. */
    public record Image(byte[] rom, Map<String, Long> globals, Map<ObjectFile, Map<String, Long>> locals, List<Placed> parts) {}

    private final List<ObjectFile> objects;
    private final Map<String, Long> globals = new LinkedHashMap<>();
    private final Map<String, ObjectFile> exporter = new HashMap<>();
    private final Map<ObjectFile, Map<String, Long>> locals = new HashMap<>();
    private final Map<ObjectFile.Section, Placed> placed = new HashMap<>();

    private Linker(List<ObjectFile> objects) {
        this.objects = objects;
    }

    /** {@code banks}: the banked segments per ROM bank, the first one being $80 (see {@link dampf.snes.RomBuilder#pack}). */
    public static Image link(List<ObjectFile> objects, List<List<String>> banks) {
        return new Linker(objects).run(banks);
    }

    /** The size of every segment, all files together. */
    public static Map<String, Integer> sizes(List<ObjectFile> objects) {
        Map<String, Integer> sizes = new LinkedHashMap<>();
        for (ObjectFile o : objects) {
            for (ObjectFile.Section s : o.sections.values()) {
                sizes.merge(s.name, s.size(), Integer::sum);
            }
        }
        return sizes;
    }

    private Image run(List<List<String>> banks) {
        Map<String, Integer> sizes = sizes(objects);
        Set<String> known = new HashSet<>(FIXED);
        known.addAll(List.of("HEADER", "VECTORS", "ZEROPAGE", "BSS"));
        banks.forEach(known::addAll);
        for (String s : sizes.keySet()) {
            if (!known.contains(s)) {
                throw new IllegalArgumentException("the segment " + s + " has no place in the memory map");
            }
        }
        int bss = sizes.getOrDefault("BSS", 0);
        int data = sizes.getOrDefault("DATA", 0);
        int count = Integer.highestOneBit(Math.max(1, banks.size()));
        if (count < banks.size()) {
            count <<= 1;
        }
        byte[] rom = new byte[count * BANK];
        Arrays.fill(rom, (byte) 0xFF);

        // bank $80: the fixed segments, then its banked ones
        int at = address(0, 0);
        int dataLoad = 0;
        for (String s : FIXED) {
            if (s.equals("DATA")) {
                dataLoad = at;
                place(s, RAM + bss, at);
            } else {
                place(s, at, at);
            }
            at += sizes.getOrDefault(s, 0);
        }
        for (int b = 0; b < banks.size(); b++) {
            if (b > 0) {
                at = address(b, 0);
            }
            for (String s : banks.get(b)) {
                place(s, at, at);
                at += sizes.getOrDefault(s, 0);
            }
        }
        place("HEADER", address(0, 0x7FC0), address(0, 0x7FC0));
        place("VECTORS", address(0, 0x7FE0), address(0, 0x7FE0));
        place("ZEROPAGE", 0, -1);
        place("BSS", RAM, -1);

        globals.put("__BSS_LOAD__", (long) RAM);
        globals.put("__BSS_RUN__", (long) RAM);
        globals.put("__BSS_SIZE__", (long) bss);
        globals.put("__DATA_LOAD__", (long) dataLoad);
        globals.put("__DATA_RUN__", (long) RAM + bss);
        globals.put("__DATA_SIZE__", (long) data);

        // symbols: labels first, then equates (which may refer to anything)
        for (ObjectFile o : objects) {
            for (String e : o.exports) {
                if (exporter.put(e, o) != null) {
                    throw new IllegalArgumentException("'" + e + "' is exported twice");
                }
            }
            Map<String, Long> m = new LinkedHashMap<>();
            for (ObjectFile.Symbol s : o.symbols.values()) {
                if (s.isLabel()) {
                    Placed p = placed.get(o.sections.get(s.section()));
                    m.put(s.name(), (long) p.run() + s.offset());
                }
            }
            locals.put(o, m);
        }
        for (ObjectFile o : objects) {
            for (ObjectFile.Symbol s : o.symbols.values()) {
                if (!s.isLabel()) {
                    value(o, s.name(), new HashSet<>());
                }
            }
        }
        for (Map.Entry<String, ObjectFile> e : exporter.entrySet()) {
            globals.put(e.getKey(), value(e.getValue(), e.getKey(), new HashSet<>()));
        }

        // the bytes, with the values filled in
        List<Placed> parts = new ArrayList<>();
        for (ObjectFile o : objects) {
            for (ObjectFile.Section s : o.sections.values()) {
                Placed p = placed.get(s);
                parts.add(p);
                byte[] bytes = s.bytes();
                for (ObjectFile.Fixup f : s.fixups) {
                    long v = f.expr().eval(n -> lookup(o, n));
                    if (f.kind() == ObjectFile.Fixup.Kind.RELATIVE) {
                        long d = v - (p.run() + f.offset() + f.size());
                        long lim = 1L << (8 * f.size() - 1);
                        if (d < -lim || d >= lim) {
                            throw new IllegalArgumentException(f.where() + ": the branch does not reach (" + d + " bytes)");
                        }
                        v = d;
                    } else if (f.size() == 1 && !f.expr().truncates() && (v < -128 || v > 0xFF)) {
                        throw new IllegalArgumentException(f.where() + ": the value $" + Long.toHexString(v) + " does not fit into a byte");
                    }
                    for (int k = 0; k < f.size(); k++) {
                        bytes[f.offset() + k] = (byte) (v >> (8 * k));
                    }
                }
                if (p.load() >= 0) {
                    if (s.reserved > 0) {
                        throw new IllegalArgumentException("reserved bytes without contents in " + s.name);
                    }
                    System.arraycopy(bytes, 0, rom, offset(p.load()), bytes.length);
                } else if (bytes.length > 0) {
                    throw new IllegalArgumentException("contents in " + s.name + ", which is not in the ROM");
                }
            }
        }
        return new Image(rom, globals, locals, parts);
    }

    private static int address(int bank, int offset) {
        return (FIRST_BANK + bank) << 16 | 0x8000 | offset;
    }

    private static int offset(int address) {
        return ((address >> 16) - FIRST_BANK) * BANK + (address & 0x7FFF);
    }

    /** Places the parts of a segment one after the other from run (and load, -1 if not in the ROM). */
    private void place(String segment, int run, int load) {
        for (ObjectFile o : objects) {
            ObjectFile.Section s = o.sections.get(segment);
            if (s != null) {
                placed.put(s, new Placed(o, s, run, load));
                run += s.size();
                if (load >= 0) {
                    load += s.size();
                }
            }
        }
    }

    private Long lookup(ObjectFile o, String name) {
        if (o.symbols.containsKey(name)) {
            return value(o, name, new HashSet<>());
        }
        Long g = globals.get(name);
        if (g != null) {
            return g;
        }
        ObjectFile e = exporter.get(name);
        if (e != null) {
            return value(e, name, new HashSet<>());
        }
        throw new IllegalArgumentException(o.name + ": the symbol '" + name + "' is not defined");
    }

    private long value(ObjectFile o, String name, Set<String> path) {
        Map<String, Long> m = locals.get(o);
        Long known = m.get(name);
        if (known != null) {
            return known;
        }
        if (!path.add(o.name + ":" + name)) {
            throw new IllegalArgumentException("'" + name + "' is defined by itself");
        }
        ObjectFile.Symbol s = o.symbols.get(name);
        long v = s.value().eval(n -> o.symbols.containsKey(n) ? value(o, n, path) : lookup(o, n));
        m.put(name, v);
        return v;
    }
}
