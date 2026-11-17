package dampf.book;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The files each generated page of the book is written from: its spec and the files the spec
 * lists under {@code sources}, each with a hash of its content. The lock file records them as
 * they were when the page was last written, so that {@code BookSourcesTest} can tell which
 * pages a change has left behind. Run as a program ({@code ./gradlew stampBook}), it records the
 * current state for the specs it is given, or for {@code all}.
 */
public final class BookSources {

    static final Path SPECS = Path.of("doc-specs");
    static final Path LOCK = SPECS.resolve("_sources.lock");
    private static final String SPEC_SUFFIX = ".spec.md";
    private static final String HEADER = """
            # The files each generated page of the book was last written from, with a hash of each.
            # Written by ./gradlew stampBook, read by BookSourcesTest. Do not edit by hand.
            """;

    private BookSources() {
    }

    public static void main(String[] args) throws IOException {
        Map<String, Map<String, String>> lock = readLock();
        boolean all = args.length == 1 && args[0].equals("all");
        if (all) {
            // Pages whose spec is gone leave the lock as well.
            lock.clear();
        }
        for (Path spec : all ? specs() : Stream.of(args).map(Path::of).toList()) {
            lock.put(page(spec), current(spec));
            System.out.println("stamped " + page(spec));
        }
        writeLock(lock);
    }

    /** Returns every spec below {@code doc-specs} that has a page, in path order. */
    static List<Path> specs() {
        try (Stream<Path> files = Files.walk(SPECS)) {
            return files.filter(f -> f.getFileName().toString().endsWith(SPEC_SUFFIX))
                    .filter(f -> !f.getFileName().toString().startsWith("_"))
                    .filter(f -> !frontmatter(f).contains("status: planned"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Returns the name of a spec's page, such as {@code rules/naming}. */
    static String page(Path spec) {
        String relative = slashes(SPECS.relativize(spec));
        return relative.substring(0, relative.length() - SPEC_SUFFIX.length());
    }

    /** Returns the spec and every file its sources name, each with the hash of its content. */
    static Map<String, String> current(Path spec) {
        Map<String, String> hashes = new TreeMap<>();
        hashes.put(slashes(spec), hash(spec));
        for (String pattern : sources(spec)) {
            for (Path file : resolve(pattern)) {
                hashes.put(slashes(file), hash(file));
            }
        }
        return hashes;
    }

    /** Returns the lock file as page, then file, then hash; empty if there is none. */
    static Map<String, Map<String, String>> readLock() throws IOException {
        Map<String, Map<String, String>> lock = new TreeMap<>();
        if (!Files.exists(LOCK)) {
            return lock;
        }
        for (String line : Files.readAllLines(LOCK)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] fields = line.split(" ");
            lock.computeIfAbsent(fields[0], p -> new TreeMap<>()).put(fields[1], fields[2]);
        }
        return lock;
    }

    private static void writeLock(Map<String, Map<String, String>> lock) throws IOException {
        StringBuilder text = new StringBuilder(HEADER);
        lock.forEach((page, hashes) -> hashes.forEach((file, hash) ->
                text.append(page).append(' ').append(file).append(' ').append(hash).append('\n')));
        Files.writeString(LOCK, text);
    }

    /** The lines of a spec's frontmatter, between the first two {@code ---} lines. */
    private static List<String> frontmatter(Path spec) {
        List<String> lines = read(spec).lines().toList();
        if (lines.isEmpty() || !lines.getFirst().equals("---")) {
            return List.of();
        }
        int end = lines.subList(1, lines.size()).indexOf("---") + 1;
        return end == 0 ? List.of() : lines.subList(1, end);
    }

    /**
     * The items of the {@code sources} list in a spec's frontmatter. An indented line in the list
     * that is not an item fails: it would end the list there and drop every item after it unseen.
     */
    static List<String> sources(Path spec) {
        List<String> sources = new ArrayList<>();
        boolean inSources = false;
        for (String line : frontmatter(spec)) {
            if (line.startsWith("sources:")) {
                inSources = true;
            } else if (inSources && line.startsWith("  - ")) {
                sources.add(line.substring(4).strip());
            } else if (inSources && line.startsWith(" ")) {
                throw new IllegalStateException(
                        spec + ": not an item of sources, indent it as '  - ': " + line);
            } else {
                inSources = false;
            }
        }
        return sources;
    }

    /**
     * Returns the files a source names: the file itself, or for a glob every file it matches, in
     * path order. A pattern that matches nothing gives nothing; the test reports it.
     */
    static List<Path> resolve(String pattern) {
        if (firstGlobCharacter(pattern) == pattern.length()) {
            Path file = Path.of(pattern);
            return Files.isRegularFile(file) ? List.of(file) : List.of();
        }
        // Walk only below the part of the pattern before the first glob character.
        String fixed = pattern.substring(0, pattern.lastIndexOf('/', firstGlobCharacter(pattern)) + 1);
        Path start = Path.of(fixed.isEmpty() ? "." : fixed);
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        try (Stream<Path> files = Files.walk(start)) {
            return files.filter(Files::isRegularFile)
                    .map(f -> fixed.isEmpty() ? start.relativize(f) : f)
                    .filter(matcher::matches)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int firstGlobCharacter(String pattern) {
        int first = pattern.length();
        for (char c : new char[] {'*', '?', '{', '['}) {
            int at = pattern.indexOf(c);
            if (at >= 0) {
                first = Math.min(first, at);
            }
        }
        return first;
    }

    /** The first 12 hex digits of the SHA-256 of a file, read with line ends normalised. */
    private static String hash(Path file) {
        byte[] text = read(file).replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text);
            return HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String slashes(Path path) {
        return path.toString().replace('\\', '/');
    }
}
