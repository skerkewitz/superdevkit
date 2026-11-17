package dampf.emu;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The emulator against the SingleStepTests 65816 suite (https://github.com/SingleStepTests/65816,
 * MIT licence): per opcode, random starting states, one instruction each, with the state after it
 * and every bus cycle. Native mode only (see {@link Cpu65816} on emulation mode).
 *
 * <p>Checked: registers, flags and memory after the instruction, the number of CPU cycles, and the
 * number of memory accesses (cycles with VDA or VPA active), which the SNES timing needs.
 *
 * <p>Runs with {@code ./gradlew test -PcpuTests} (or {@code -PcpuTests=N}: N tests per opcode,
 * default 300). The first ~200 KB of each opcode's file are downloaded once into
 * ~/.cache/dampf/65816-tests.
 */
class CpuSingleStepTest {
    private static final String URL = "https://raw.githubusercontent.com/SingleStepTests/65816/main/v1/";
    private static final Path CACHE = Path.of(System.getProperty("user.home"), ".cache", "dampf", "65816-tests");

    /** 16 MB of RAM, as the suite assumes; only the bytes a test touches are kept. */
    private static final class TestBus implements Bus {
        final Map<Integer, Integer> ram = new HashMap<>();

        @Override
        public int read(int address) {
            return ram.getOrDefault(address, 0);
        }

        @Override
        public void write(int address, int value) {
            ram.put(address, value);
        }

        @Override
        public void idle() {
        }
    }

    @Test
    void nativeModeMatchesTheSuite() throws Exception {
        String count = System.getProperty("dampf.cpuTests");
        assumeTrue(count != null, "set -PcpuTests to run");
        int perOpcode = Integer.parseInt(count);
        ObjectMapper json = new ObjectMapper();
        List<String> failures = new ArrayList<>();
        int run = 0;
        for (int op = 0; op < 256; op++) {
            List<String> lines = tests(op);
            int failed = 0;
            String first = null;
            for (int t = 0; t < Math.min(perOpcode, lines.size()); t++) {
                JsonNode test = json.readTree(lines.get(t));
                String problem = runOne(test);
                run++;
                if (problem != null) {
                    failed++;
                    if (first == null) {
                        first = test.get("name").asText() + ": " + problem;
                    }
                }
            }
            if (failed > 0) {
                failures.add(String.format("%02X: %d failed, first %s", op, failed, first));
            }
        }
        System.out.println("CPU tests run: " + run + ", opcodes failing: " + failures.size());
        failures.forEach(System.out::println);
        assertTrue(failures.isEmpty(), failures.size() + " opcode(s) fail:\n" + String.join("\n", failures));
    }

    /** null if the test passes, otherwise what differs. */
    static String runOne(JsonNode test) {
        JsonNode in = test.get("initial");
        JsonNode out = test.get("final");
        TestBus bus = new TestBus();
        for (JsonNode cell : in.get("ram")) {
            bus.ram.put(cell.get(0).asInt(), cell.get(1).asInt());
        }
        Cpu65816 cpu = new Cpu65816(bus);
        cpu.e = in.get("e").asInt() != 0;
        cpu.a = in.get("a").asInt();
        cpu.x = in.get("x").asInt();
        cpu.y = in.get("y").asInt();
        cpu.s = in.get("s").asInt();
        cpu.d = in.get("d").asInt();
        cpu.dbr = in.get("dbr").asInt();
        cpu.pbr = in.get("pbr").asInt();
        cpu.pc = in.get("pc").asInt();
        cpu.setP(in.get("p").asInt());
        try {
            int start = cpu.pc;
            cpu.step();
            // MVN/MVP move one byte per execution and repeat themselves; a test covers the whole move
            int op = in.get("ram").isEmpty() ? -1 : bus.ram.getOrDefault(cpu.pbr << 16 | start, -1);
            while ((op == 0x44 || op == 0x54) && cpu.pc == start) {
                cpu.step();
            }
        } catch (RuntimeException e) {
            return e.toString();
        }
        StringBuilder diff = new StringBuilder();
        check(diff, "pc", cpu.pc, out.get("pc").asInt());
        check(diff, "a", cpu.a, out.get("a").asInt());
        check(diff, "x", cpu.x, out.get("x").asInt());
        check(diff, "y", cpu.y, out.get("y").asInt());
        check(diff, "s", cpu.s, out.get("s").asInt());
        check(diff, "d", cpu.d, out.get("d").asInt());
        check(diff, "dbr", cpu.dbr, out.get("dbr").asInt());
        check(diff, "pbr", cpu.pbr, out.get("pbr").asInt());
        check(diff, "p", cpu.p(), out.get("p").asInt());
        check(diff, "e", cpu.e ? 1 : 0, out.get("e").asInt());
        for (JsonNode cell : out.get("ram")) {
            int address = cell.get(0).asInt();
            check(diff, String.format("ram[%06X]", address), bus.read(address), cell.get(1).asInt());
        }
        // Cycles without an address are the halted CPU after WAI/STP, which the suite records once;
        // the emulator counts the instruction itself (3 cycles, as the data sheet gives).
        long cycleCount = 0;
        long accesses = 0;
        for (JsonNode cyc : test.get("cycles")) {
            if (cyc.get(0).isNull()) {
                continue;
            }
            cycleCount++;
            String flags = cyc.get(2).asText();
            if (flags.charAt(0) == 'd' || flags.charAt(1) == 'p') {
                accesses++;
            }
        }
        check(diff, "cycles", (int) cpu.cycles, (int) cycleCount);
        check(diff, "accesses", (int) cpu.accesses, (int) accesses);
        return diff.isEmpty() ? null : diff.toString().strip();
    }

    private static void check(StringBuilder diff, String what, int got, int expected) {
        if (got != expected) {
            diff.append(String.format(" %s=%X (expected %X)", what, got, expected));
        }
    }

    /** The complete test lines from the cached start of the opcode's native-mode file. */
    private static List<String> tests(int op) throws IOException, InterruptedException {
        Files.createDirectories(CACHE);
        Path file = CACHE.resolve(String.format("%02x.n.part.json", op));
        if (!Files.exists(file)) {
            HttpRequest req = HttpRequest.newBuilder(URI.create(URL + String.format("%02x.n.json", op)))
                    .header("Range", "bytes=0-199999").build();
            HttpResponse<InputStream> r = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = r.body()) {
                Files.write(file, body.readAllBytes());
            }
        }
        List<String> lines = new ArrayList<>();
        String[] raw = Files.readString(file, StandardCharsets.UTF_8).split("\n");
        for (int i = 0; i < raw.length - 1; i++) { // the last line is cut off
            String l = raw[i].strip();
            if (l.startsWith("[")) {
                l = l.substring(1);
            }
            if (l.endsWith(",")) {
                l = l.substring(0, l.length() - 1);
            }
            if (l.startsWith("{")) {
                lines.add(l);
            }
        }
        return lines;
    }
}
