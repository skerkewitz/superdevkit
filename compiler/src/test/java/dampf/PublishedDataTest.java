package dampf;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The data the tick publishes for interrupt code (lib/include/sdevkit/publish.h, ADR-6FPV1EQV,
 * ADR-6FRDS030, ADR-6FS0T4WB) on the kit's frame rhythm. dev/publish_check plays its check.txt as
 * {@link ExamplesTest} plays an example: on the SNES, where the kit's NMI runs the game's vblank and
 * an IRQ that comes on every line of the vertical blank and the tick in turn, both reading the
 * published copy, and on the desktop and the web, which have no interrupts and publish with the
 * same C. Its tick names for the checksum
 * the published copy it finds and the times the interrupts found torn data, so every tick's
 * checksum is the same on all three only if no interrupt ever found torn data and every platform
 * published the same. exec/published.c does the same in an execution test, and checks that
 * interrupt code during a tick reads the copy of the tick whose game_vblank came last.
 */
class PublishedDataTest {
    private static final Path CHECK = Path.of("dev/publish_check");

    @Test
    void theSnesTheDesktopAndTheWebPublishTheSame() throws Exception {
        ExamplesTest.playsTheSameOnBoth(CHECK, ExamplesTest.desktop(), ExamplesTest.web());
    }

    /**
     * The order of the two bytes in dampf's code (ADR-6FRDS030, ADR-6FS0T4WB): game_tick marks the
     * copy ready as its last store, after the copy (nothing but a change of the registers' width
     * between it and the RTL); the kit's NMI handler that SDEVKIT_PUBLISH defines reads "ready",
     * switches the current copy and then clears "ready"; and game_vblank, on the SNES, switches
     * nothing: it waits until the NMI has cleared "ready". dampf moves and removes no store today;
     * this fails if a later pass moves these. The copy into the free copy, never the current one,
     * is checked by exec/published.c, where the interrupts find no torn data.
     */
    @Test
    void theCopyIsMarkedReadyLastAndTheNmiSwitchesBeforeItClears() throws Exception {
        Path out = CHECK.resolve("build/publish_order.sfc");
        // game_tick's own code: inlined into the main loop, it has none (ADR-6FVMCY48)
        Compiler.compile(ExamplesTest.options(CHECK, out).withBackend(Compiler.Backend.MIR).withInlinedCode(true));
        String s = Files.readString(out.resolveSibling("publish_order.s"));
        List<String> tick = code(s, "_game_tick");
        int ready = -1;
        for (int i = 0; i < tick.size(); i++) {
            if (tick.get(i).matches("sta\\s+_sdevkit_publish_ready\\b.*")) {
                assertTrue(ready < 0, "two stores of the ready byte in _game_tick:\n" + String.join("\n", tick));
                ready = i;
            }
        }
        assertTrue(ready >= 0, "no store of the ready byte in _game_tick:\n" + String.join("\n", tick));
        List<String> after = tick.subList(ready + 1, tick.size()).stream().filter(l -> !l.matches("(rep|sep)\\s.*")).toList();
        assertTrue(!after.isEmpty() && after.getFirst().equals("rtl"), "the ready byte is not game_tick's last store:\n"
                + String.join("\n", tick));

        List<String> nmi = code(s, "_sdevkit_publish_nmi");
        int load = -1, side = -1, clear = -1;
        for (int i = 0; i < nmi.size(); i++) {
            String l = nmi.get(i);
            if (l.matches("lda\\s+_sdevkit_publish_ready\\b.*") && load < 0) {
                load = i;
            } else if (l.matches("sta\\s+_sdevkit_publish_side\\b.*")) {
                side = i;
            } else if (l.matches("stz\\s+_sdevkit_publish_ready\\b.*")) {
                clear = i;
            }
        }
        assertTrue(load >= 0 && load < side && side < clear, "the NMI does not read ready, switch, then clear:\n"
                + String.join("\n", nmi));

        List<String> vblank = code(s, "_game_vblank");
        assertTrue(vblank.stream().noneMatch(l -> l.matches("(sta|stz)\\s+_sdevkit_publish_(side|ready)\\b.*")),
                "game_vblank writes the published bytes on the SNES:\n" + String.join("\n", vblank));
        assertTrue(vblank.stream().anyMatch(l -> l.matches("lda\\s+_sdevkit_publish_ready\\b.*")),
                "game_vblank does not wait for the NMI's switch:\n" + String.join("\n", vblank));
    }

    /** The instructions of one function of a listing, without comments, directives and labels. */
    private static List<String> code(String s, String proc) {
        int start = s.indexOf(".proc " + proc + "\n");
        int end = s.indexOf(".endproc", start);
        assertTrue(start >= 0 && end > start, "no " + proc);
        return s.substring(start, end).lines().map(String::strip)
                .filter(l -> !l.isEmpty() && !l.startsWith(";") && !l.startsWith(".") && !l.endsWith(":"))
                .map(l -> l.replaceAll("\\s*;.*$", "")).toList();
    }
}
