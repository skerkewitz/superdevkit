package dampf.snes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Assembles and links generated code with the dampf SNES runtime into a LoROM image, using ca65
 * and ld65. Besides the ROM it writes ld65's debug file (.dbg, which Mesen reads), a label file,
 * a map file and the linker configuration it used.
 *
 * <p>ROM banks: code and constant data live in banks $80 and up, 32 KB each ($8000-$FFFF). The
 * code generator puts every function and every constant object into its own segment; ld65 cannot
 * split a segment, so none of them crosses a bank boundary. The builder links twice: once to
 * measure the exact size of every segment, then with a configuration that packs the segments into
 * banks (largest first, first fit). The runtime's fixed parts stay in bank $80, where the reset
 * vector points. The image is padded to a power of two, and the header gets the ROM size and the
 * checksum.
 */
public final class RomBuilder {
    /** What a build produced. */
    public record Rom(Path rom, Path dbg, Path labels, Path map) {}

    private static final String RES = "/dampf/snes/";
    private static final int BANK = 0x8000;
    private static final int FIRST_BANK = 0x80;
    private static final int MAX_BANKS = 0x80;          // banks $80-$FF: 4 MB
    private static final int HEADER_AND_VECTORS = 0x40; // $FFC0-$FFFF of bank $80

    /** Segments the runtime puts into bank $80 (besides HEADER and VECTORS at fixed addresses). */
    private static final List<String> FIXED = List.of("STARTUP", "DAMPF_CODE", "DATA");

    private RomBuilder() {}

    /**
     * @param modules  the generated assembly, one or more modules
     * @param outDir   where all files go
     * @param baseName name of the ROM without extension
     */
    public static Rom build(List<String> modules, Path outDir, String baseName) throws IOException {
        return build(modules, outDir, baseName, true);
    }

    /**
     * {@code fastRom}: the start-up switches the ROM in banks $80-$FF, where all of the code and
     * the constant data run, to 3.58 MHz (MEMSEL), and the header says FastROM; a SlowROM
     * cartridge needs false.
     */
    public static Rom build(List<String> modules, Path outDir, String baseName, boolean fastRom) throws IOException {
        Files.createDirectories(outDir);
        Path runtime = copyResource("runtime.s", outDir.resolve("dampf_runtime.s"));

        // All tools run in outDir with plain file names, so the debug file holds relative paths.
        List<String> objects = new ArrayList<>();
        String runtimeObj = "dampf_runtime.o";
        run(outDir, "ca65", "-g", "-D", "DAMPF_FASTROM=" + (fastRom ? 1 : 0), "-o", runtimeObj, runtime.getFileName().toString());
        objects.add(runtimeObj);
        List<String> banked = new ArrayList<>();
        for (int i = 0; i < modules.size(); i++) {
            String name = modules.size() == 1 ? baseName : baseName + "_" + (i + 1);
            Files.writeString(outDir.resolve(name + ".s"), modules.get(i), StandardCharsets.UTF_8);
            run(outDir, "ca65", "-g", "-o", name + ".o", name + ".s");
            objects.add(name + ".o");
            banked.addAll(bankedSegments(modules.get(i)));
        }

        // pass 1: everything in one large area, only to learn the segment sizes
        String measureCfg = baseName + "_measure.cfg";
        Files.writeString(outDir.resolve(measureCfg), measureConfig(banked));
        String measureMap = baseName + "_measure.map";
        run(outDir, concat(List.of("ld65", "-C", measureCfg, "-m", measureMap, "-o", baseName + "_measure.bin"), objects));
        Map<String, Integer> sizes = segmentSizes(outDir.resolve(measureMap));
        Files.deleteIfExists(outDir.resolve(baseName + "_measure.bin"));

        // pass 2: pack into banks and link for real
        List<List<String>> banks = pack(banked, sizes);
        String cfg = baseName + ".cfg";
        Files.writeString(outDir.resolve(cfg), finalConfig(banks));
        Rom rom = new Rom(outDir.resolve(baseName + ".sfc"), outDir.resolve(baseName + ".dbg"),
                outDir.resolve(baseName + ".lbl"), outDir.resolve(baseName + ".map"));
        run(outDir, concat(List.of("ld65", "-C", cfg, "--dbgfile", baseName + ".dbg", "-Ln", baseName + ".lbl",
                "-m", baseName + ".map", "-o", baseName + ".sfc"), objects));
        fixHeader(rom.rom());
        return rom;
    }

    /** The segments the code generator made for functions (C...) and constant objects (R...). */
    static List<String> bankedSegments(String asm) {
        List<String> names = new ArrayList<>();
        Matcher m = Pattern.compile("^\\s*\\.segment\\s+\"([CR]_[A-Za-z0-9_]*)\"", Pattern.MULTILINE).matcher(asm);
        while (m.find()) {
            if (!names.contains(m.group(1))) {
                names.add(m.group(1));
            }
        }
        return names;
    }

    private static String ramPart() {
        return """
                    DP:    start = $0000, size = $0100, type = rw;   # direct page (D = 0)
                    #      $0100-$1DFF free; $1E00-$1FFF stack
                    RAM:   start = $2000, size = $DFFF, type = rw;   # bank $7E (DBR = $7E), see docs/memory-map.md; $FFFF stays free (one past the end)
                """;
    }

    private static String measureConfig(List<String> banked) {
        StringBuilder c = new StringBuilder("# dampf: first link, only to measure segment sizes\nMEMORY {\n");
        c.append(ramPart());
        c.append("    ALL:   start = $000000, size = $1000000, type = ro, file = %O;\n}\nSEGMENTS {\n");
        c.append("    ZEROPAGE: load = DP, type = zp, optional = yes;\n");
        c.append("    BSS:      load = RAM, type = bss, define = yes, optional = yes;\n");
        c.append("    DATA:     load = ALL, run = RAM, type = rw, define = yes, optional = yes;\n");
        for (String s : List.of("STARTUP", "DAMPF_CODE", "HEADER", "VECTORS")) {
            c.append("    ").append(s).append(": load = ALL, type = ro;\n");
        }
        for (String s : banked) {
            c.append("    ").append(s).append(": load = ALL, type = ro;\n");
        }
        return c.append("}\n").toString();
    }

    /** ld65's map file: "Segment list" lines are "NAME  START  END  SIZE  ALIGN". */
    static Map<String, Integer> segmentSizes(Path map) throws IOException {
        Map<String, Integer> sizes = new LinkedHashMap<>();
        boolean inList = false;
        for (String line : Files.readAllLines(map)) {
            if (line.startsWith("Segment list:")) {
                inList = true;
                continue;
            }
            if (inList) {
                String[] p = line.trim().split("\\s+");
                if (p.length >= 4 && p[1].matches("[0-9A-Fa-f]{6}") && p[3].matches("[0-9A-Fa-f]{6}")) {
                    sizes.put(p[0], Integer.parseInt(p[3], 16));
                } else if (line.isBlank() && !sizes.isEmpty()) {
                    break;
                }
            }
        }
        return sizes;
    }

    /** Packs the segments into banks: largest first, into the first bank with room. Bank 0 here is $80. */
    static List<List<String>> pack(List<String> segments, Map<String, Integer> sizes) throws IOException {
        int fixed = HEADER_AND_VECTORS;
        for (String f : FIXED) {
            fixed += sizes.getOrDefault(f, 0);
        }
        if (fixed > BANK) {
            throw new IOException("the runtime and the initial values of variables (" + fixed + " bytes) do not fit into bank $80");
        }
        List<List<String>> banks = new ArrayList<>();
        List<Integer> free = new ArrayList<>();
        banks.add(new ArrayList<>());
        free.add(BANK - fixed);
        List<String> order = new ArrayList<>(segments);
        order.sort(Comparator.comparing((String s) -> sizes.getOrDefault(s, 0)).reversed().thenComparing(s -> s));
        for (String s : order) {
            int size = sizes.getOrDefault(s, 0);
            if (size > BANK) {
                throw new IOException(describe(s) + " is " + size + " bytes, more than a ROM bank (32 KB)");
            }
            int b = 0;
            while (b < banks.size() && free.get(b) < size) {
                b++;
            }
            if (b == banks.size()) {
                if (banks.size() == MAX_BANKS) {
                    throw new IOException("the program does not fit into 4 MB of ROM");
                }
                banks.add(new ArrayList<>());
                // the last byte stays free: a pointer just past an object's end must not wrap to
                // the start of the bank, because address arithmetic stays within the bank
                free.add(BANK - 1);
            }
            banks.get(b).add(s);
            free.set(b, free.get(b) - size);
        }
        return banks;
    }

    private static String describe(String segment) {
        return (segment.startsWith("C") ? "the code of " : "the constant object ") + segment.substring(1);
    }

    private static String finalConfig(List<List<String>> banks) {
        int count = Integer.highestOneBit(banks.size());
        if (count < banks.size()) {
            count <<= 1; // pad to a power of two
        }
        StringBuilder c = new StringBuilder("# dampf: LoROM, " + count + " bank(s) of 32 KB from $80, packed by dampf.snes.RomBuilder\nMEMORY {\n");
        c.append(ramPart());
        for (int i = 0; i < count; i++) {
            c.append(String.format("    ROM%02X: start = $%02X8000, size = $8000, type = ro, fill = yes, fillval = $FF, file = %%O;%n",
                    FIRST_BANK + i, FIRST_BANK + i));
        }
        c.append("}\nSEGMENTS {\n");
        c.append("    ZEROPAGE:   load = DP, type = zp, optional = yes;\n");
        c.append("    BSS:        load = RAM, type = bss, define = yes, optional = yes;\n");
        c.append("    STARTUP:    load = ROM80, type = ro;\n");
        c.append("    DAMPF_CODE: load = ROM80, type = ro;\n");
        c.append("    DATA:       load = ROM80, run = RAM, type = rw, define = yes, optional = yes;\n");
        for (int b = 0; b < banks.size(); b++) {
            for (String s : banks.get(b)) {
                c.append(String.format("    %s: load = ROM%02X, type = ro;%n", s, FIRST_BANK + b));
            }
        }
        c.append("    HEADER:     load = ROM80, type = ro, start = $80FFC0;\n");
        c.append("    VECTORS:    load = ROM80, type = ro, start = $80FFE0;\n");
        return c.append("}\n").toString();
    }

    /** Writes the ROM size, the checksum and its complement into the LoROM header ($7FC0 in the file). */
    static void fixHeader(Path rom) throws IOException {
        byte[] data = Files.readAllBytes(rom);
        int header = 0x7FC0;
        int kb = data.length / 1024;
        data[header + 0x17] = (byte) (31 - Integer.numberOfLeadingZeros(kb)); // 2^n KB
        data[header + 0x1C] = (byte) 0xFF;
        data[header + 0x1D] = (byte) 0xFF;
        data[header + 0x1E] = 0;
        data[header + 0x1F] = 0;
        int sum = 0;
        for (byte b : data) {
            sum = (sum + (b & 0xFF)) & 0xFFFF;
        }
        int complement = sum ^ 0xFFFF;
        data[header + 0x1C] = (byte) complement;
        data[header + 0x1D] = (byte) (complement >> 8);
        data[header + 0x1E] = (byte) sum;
        data[header + 0x1F] = (byte) (sum >> 8);
        Files.write(rom, data);
    }

    private static String[] concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return all.toArray(String[]::new);
    }

    private static Path copyResource(String name, Path target) throws IOException {
        try (InputStream in = RomBuilder.class.getResourceAsStream(RES + name)) {
            if (in == null) {
                throw new IOException("missing resource " + RES + name);
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private static void run(Path dir, String... command) throws IOException {
        dampf.util.Processes.check(dir, java.time.Duration.ofMinutes(5), List.of(command));
    }
}
