package dampf.exec;

import dampf.snes.RomBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs a ROM headless in Mesen 2 ({@code --testrunner} with a Lua script). The dampf runtime
 * writes to $21FB when the entry function has returned; the script then reads the globals and
 * writes them to a file. Works with the Windows Mesen from WSL: paths are converted with wslpath.
 */
final class MesenRunner {
    private final Path mesen;
    private final int timeoutFrames;
    private final int timeoutSeconds;

    /** For short test programs: at most 600 frames, one minute. */
    MesenRunner(Path mesen) {
        this(mesen, 600, 60);
    }

    MesenRunner(Path mesen, int timeoutFrames, int timeoutSeconds) {
        this.mesen = mesen;
        this.timeoutFrames = timeoutFrames;
        this.timeoutSeconds = timeoutSeconds;
    }

    Map<String, Long> run(RomBuilder.Rom rom, List<Observed> globals) throws IOException, InterruptedException {
        Map<String, Long> addresses = labels(rom.labels());
        Path dir = rom.rom().getParent();
        Path result = dir.resolve("mesen_result.txt");
        Files.deleteIfExists(result);
        String resultPath = hostPath(result).replace("\\", "\\\\");

        StringBuilder lua = new StringBuilder();
        lua.append("local out = \"").append(resultPath).append("\"\n");
        lua.append("""
                local function finish(text, code)
                  local f = io.open(out, "w"); f:write(text); f:close(); emu.stop(code)
                end
                local function rd(a) return emu.read(0x7E0000 + a, emu.memType.snesMemory) end   -- variables are in bank $7E
                local ok, err = pcall(function()
                  local frames = 0
                  emu.addEventCallback(function()
                    frames = frames + 1
                """);
        lua.append("    if frames > ").append(timeoutFrames).append(" then finish(\"timeout\\n\", 3) end\n");
        lua.append("""
                  end, emu.eventType.endFrame)
                  emu.addMemoryCallback(function(addr, value)
                    local s = ""
                """);
        for (Observed v : globals) {
            Long base = addresses.get(v.label());
            if (base == null) {
                throw new IOException("no address for " + v.label() + " in " + rom.labels());
            }
            long addr = base + v.offset();
            // little endian: rd(a) + rd(a+1) * 256 + ... (Lua 5.4 integers are 64 bit)
            StringBuilder expr = new StringBuilder();
            for (int i = 0; i < v.type().size(); i++) {
                expr.append(i == 0 ? "" : " + ").append(String.format("rd(0x%06X)", addr + i)).append(i == 0 ? "" : " * " + (1L << (8 * i)));
            }
            lua.append("    s = s .. \"").append(v.path()).append(" \" .. (").append(expr).append(") .. \"\\n\"\n");
        }
        lua.append("""
                    finish(s, 0)
                  end, emu.callbackType.write, 0x21FB, 0x21FB)
                end)
                if not ok then finish("lua error " .. tostring(err) .. "\\n", 2) end
                """);
        Path script = dir.resolve("mesen_run.lua");
        Files.writeString(script, lua);

        Process p = new ProcessBuilder(mesen.toString(), "--testrunner", "--timeout=" + timeoutSeconds, hostPath(rom.rom()), hostPath(script))
                .directory(mesen.getParent().toFile()).redirectErrorStream(true).start();
        p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
        if (!p.waitFor(timeoutSeconds + 30L, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("Mesen did not finish");
        }
        if (!Files.exists(result)) {
            throw new IOException("Mesen wrote no result (exit code " + p.exitValue() + ")");
        }
        String text = Files.readString(result, StandardCharsets.UTF_8);
        if (text.startsWith("timeout") || text.startsWith("lua error")) {
            throw new IOException("Mesen: " + text.strip());
        }
        Map<String, Long> raw = new LinkedHashMap<>();
        for (String line : text.strip().split("\\R")) {   // Lua on Windows writes \r\n
            String l = line.strip();
            int sp = l.lastIndexOf(' ');
            raw.put(l.substring(0, sp), Long.parseLong(l.substring(sp + 1)));
        }
        Map<String, Long> values = new LinkedHashMap<>();
        for (Observed v : globals) {
            values.put(v.path(), v.type().wrap(raw.get(v.path())));
        }
        return values;
    }

    /** ld65 -Ln format: "al 000100 ._result". */
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

    /** The path as Mesen sees it: a Windows path when we run in WSL. */
    private static String hostPath(Path p) throws IOException {
        if (!isWsl()) {
            return p.toAbsolutePath().toString();
        }
        try {
            Process w = new ProcessBuilder("wslpath", "-w", p.toAbsolutePath().toString()).start();
            String s = new String(w.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (w.waitFor() != 0 || s.isEmpty()) {
                throw new IOException("wslpath failed for " + p);
            }
            return s;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    private static boolean isWsl() {
        return System.getenv("WSL_DISTRO_NAME") != null;
    }
}
