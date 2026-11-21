package dampf.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The bookkeeping of the generated pages: the lock, the specs' sources and the pages' markers.
 * Whether the pages are current is not checked here, since the book is brought in step before a
 * release, not with every change ({@code ./gradlew checkBook}, ADR-6F60ZB4N).
 */
class BookSourcesTest {

    private static final Path BOOK = Path.of("docs/book");

    @Test
    void aPageIsStaleWhenASourceChangedOrIsNew() {
        Path spec = BookSources.specs().getFirst();
        String page = BookSources.page(spec);
        Map<String, String> recorded = new TreeMap<>(BookSources.current(spec));
        String source = recorded.keySet().iterator().next();
        recorded.put(source, "000000000000");
        recorded.put("gone.md", "000000000000");
        List<String> stale = BookSources.stale(Map.of(page, recorded));
        assertEquals(page + ": " + String.join(", ", new TreeSet<>(List.of(source, "gone.md"))),
                stale.getFirst());
        // Every other page is not in the lock given, so it is not stamped yet.
        assertEquals(BookSources.specs().size(), stale.size());
    }

    @Test
    void theLockNamesOnlyPagesThatHaveASpec() throws IOException {
        TreeSet<String> pages = new TreeSet<>(BookSources.readLock().keySet());
        BookSources.specs().forEach(spec -> pages.remove(BookSources.page(spec)));
        assertEquals(new TreeSet<String>(), pages, "remove them with ./gradlew stampBook");
    }

    @Test
    void aSourceIndentedWronglyFailsInsteadOfEndingTheList(@TempDir Path dir) throws IOException {
        Path spec = dir.resolve("page.spec.md");
        Files.writeString(spec, """
                ---
                title: Page
                sources:
                  - README.md
                   - docs/roadmap.md
                  - docs/subset.md
                ---
                """);
        assertThrows(IllegalStateException.class, () -> BookSources.sources(spec));
    }

    @Test
    void everySourceOfASpecMatchesAFile() {
        List<String> empty = new ArrayList<>();
        for (Path spec : BookSources.specs()) {
            for (String pattern : BookSources.sources(spec)) {
                if (BookSources.resolve(pattern).isEmpty()) {
                    empty.add(BookSources.page(spec) + ": " + pattern);
                }
            }
        }
        assertEquals(List.of(), empty);
    }

    @Test
    void everySpecHasAPageThatSaysItIsGeneratedFromIt() throws IOException {
        List<String> missing = new ArrayList<>();
        for (Path spec : BookSources.specs()) {
            Path page = BOOK.resolve(BookSources.page(spec) + ".md");
            String marker = "<!-- GENERATED from " + spec.toString().replace('\\', '/') + " ";
            if (!Files.exists(page) || !Files.readString(page).startsWith(marker)) {
                missing.add(page.toString());
            }
        }
        assertEquals(List.of(), missing);
    }
}
