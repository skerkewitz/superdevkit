package dampf;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The data the tick publishes for interrupt code (lib/include/sdevkit/publish.h, ADR-6FPV1EQV) on
 * the kit's frame rhythm. dev/publish_check plays its check.txt as {@link ExamplesTest} plays an
 * example: on the SNES, where the NMI and an IRQ that comes on every line of the tick in turn
 * read the published copy, and on the desktop and the web, which have no interrupts and publish
 * with the same C. Its tick names for the checksum the published copy it finds and the times the
 * interrupts found torn data, so every tick's checksum is the same on all three only if no
 * interrupt ever found torn data and every platform published the same. exec/published.c does
 * the same in an execution test, with counts of the interrupts that came while the kit copied.
 */
class PublishedDataTest {
    private static final Path CHECK = Path.of("dev/publish_check");

    @Test
    void theSnesTheDesktopAndTheWebPublishTheSame() throws Exception {
        ExamplesTest.playsTheSameOnBoth(CHECK, ExamplesTest.desktop(), ExamplesTest.web());
    }

    /**
     * The barrier: in dampf's code for game_tick the flag is set after the tick's last store and
     * before the copy's first, and cleared after the copy's last. dampf moves and removes no store
     * today; this fails if a later pass does it to the flag (gcc drops the first write when it is
     * not volatile, since the second overwrites it with nothing between them that reads it).
     */
    @Test
    void theFlagIsSetBeforeTheCopyAndClearedAfterIt() throws Exception {
        Path out = CHECK.resolve("build/publish_order.sfc");
        ExamplesTest.compile(CHECK, out);
        String s = Files.readString(out.resolveSibling("publish_order.s"));
        int start = s.indexOf(".proc _game_tick");
        int end = s.indexOf(".endproc", start);
        assertTrue(start >= 0 && end > start, "no _game_tick in " + out);
        List<String> code = s.substring(start, end).lines().map(String::strip)
                .filter(l -> !l.isEmpty() && !l.startsWith(";") && !l.startsWith(".")).toList();
        int set = -1, firstCopy = -1, lastCopy = -1, clear = -1, lastFrame = -1;
        for (int i = 0; i < code.size(); i++) {
            String l = code.get(i);
            if (l.matches("sta\\s+_sdevkit_publishing\\b.*") && set < 0) {
                set = i;
            } else if (l.matches("stz\\s+_sdevkit_publishing\\b.*")) {
                clear = i;
            } else if (l.matches("sta\\s+\\[dampf_ptr\\+4\\],y.*")) {
                firstCopy = firstCopy < 0 ? i : firstCopy;
                lastCopy = i;
            } else if (l.matches("st[az]\\s+_frame\\b.*")) {
                lastFrame = i;
            }
        }
        String listing = String.join("\n", code);
        assertTrue(set >= 0 && firstCopy >= 0 && clear >= 0, "the flag's writes and the copy in _game_tick:\n" + listing);
        assertTrue(lastFrame < set, "a store into the tick's struct after the flag was set:\n" + listing);
        assertTrue(set < firstCopy && lastCopy < clear, "the copy is not between the flag's two writes:\n" + listing);
    }
}
