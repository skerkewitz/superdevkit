package dampf.snes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Assembles and links generated code with the dampf SNES runtime into a ROM, using ca65 and
 * ld65. Besides the ROM it writes ld65's debug file (.dbg, which Mesen reads), a label file and
 * a map file.
 */
public final class RomBuilder {
    /** What a build produced. */
    public record Rom(Path rom, Path dbg, Path labels, Path map) {}

    private static final String RES = "/dampf/snes/";

    private RomBuilder() {}

    /**
     * @param asm      the generated assembly
     * @param outDir   where all files go
     * @param baseName name of the ROM without extension
     */
    public static Rom build(String asm, Path outDir, String baseName) throws IOException {
        Files.createDirectories(outDir);
        Path source = outDir.resolve(baseName + ".s");
        Files.writeString(source, asm, StandardCharsets.UTF_8);
        Path runtime = copyResource("runtime.s", outDir.resolve("dampf_runtime.s"));
        Path config = copyResource("lorom32k.cfg", outDir.resolve("dampf_lorom32k.cfg"));

        // All tools run in outDir with plain file names, so the debug file holds relative paths.
        String obj = baseName + ".o";
        String runtimeObj = "dampf_runtime.o";
        run(outDir, "ca65", "-g", "-o", obj, source.getFileName().toString());
        run(outDir, "ca65", "-g", "-o", runtimeObj, runtime.getFileName().toString());

        Rom rom = new Rom(outDir.resolve(baseName + ".sfc"), outDir.resolve(baseName + ".dbg"),
                outDir.resolve(baseName + ".lbl"), outDir.resolve(baseName + ".map"));
        run(outDir, "ld65", "-C", config.getFileName().toString(), "--dbgfile", baseName + ".dbg", "-Ln", baseName + ".lbl",
                "-m", baseName + ".map", "-o", baseName + ".sfc", runtimeObj, obj);
        fixChecksum(rom.rom());
        return rom;
    }

    /** Writes the header checksum and its complement (LoROM header at $7FC0 in the file). */
    static void fixChecksum(Path rom) throws IOException {
        byte[] data = Files.readAllBytes(rom);
        int header = 0x7FC0;
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
        List<String> cmd = new ArrayList<>(List.of(command));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (p.waitFor() != 0) {
                throw new IOException(String.join(" ", cmd) + " failed:\n" + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted: " + String.join(" ", cmd), e);
        }
    }
}
