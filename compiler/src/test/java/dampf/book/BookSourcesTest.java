package dampf.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A generated page of the book is only as current as the last run of {@code /docs-gen} on it.
 * These tests fail when a page's spec or one of its sources has changed since then, so that a
 * change to a document or the code cannot leave the book behind unnoticed.
 */
class BookSourcesTest {

    private static final Path BOOK = Path.of("docs/book");

    @Test
    void everyGeneratedPageWasWrittenFromItsCurrentSources() throws IOException {
        Map<String, Map<String, String>> lock = BookSources.readLock();
        List<String> stale = new ArrayList<>();
        for (Path spec : BookSources.specs()) {
            String page = BookSources.page(spec);
            Map<String, String> recorded = lock.get(page);
            if (recorded == null) {
                stale.add(page + ": not stamped yet");
                continue;
            }
            Map<String, String> current = BookSources.current(spec);
            TreeSet<String> changed = new TreeSet<>();
            current.forEach((file, hash) -> {
                if (!hash.equals(recorded.get(file))) {
                    changed.add(file);
                }
            });
            recorded.keySet().stream().filter(f -> !current.containsKey(f)).forEach(changed::add);
            if (!changed.isEmpty()) {
                stale.add(page + ": " + String.join(", ", changed));
            }
        }
        assertEquals(List.of(), stale, """
                These pages of the book were written from older versions of the files named.
                Run /docs-gen for each spec, which also stamps it. If a change does not touch what
                a page says, stamp it without regenerating:
                    ./gradlew stampBook -Pspecs=doc-specs/<page>.spec.md
                """);
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
