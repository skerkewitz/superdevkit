package dampf.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The architecture decision records: {@code docs/ADR.md} holds the table, {@code docs/adr/} one
 * file per record. Nothing else keeps the two in step, so these tests do: every file has its row
 * and every row its file, each ID is well formed and decodes to the date of its row, the table is
 * in the order of the IDs, the titles agree, the statuses and the table of former numbers name
 * records that exist, and so does every "ADR-ID" cited in the repository. Outside the book, which
 * is brought in step at a release, no document cites a record by its former number any more. A
 * record of another project is cited with its name in front ("gosu-flint's record 32") and is not
 * checked. The IDs are explained in docs/ADR.md and made by {@code dev/new_adr.sh}.
 */
class AdrTest {

    private static final Path TABLE = Path.of("docs/ADR.md");
    private static final Path RECORDS = Path.of("docs/adr");

    /** Crockford's Base32: the digits and the capital letters without I, L, O and U. */
    static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final String ID = "[0-9A-HJKMNP-TV-Z]{8}";
    private static final Instant EPOCH = Instant.parse("2020-01-01T00:00:00Z");

    private static final Pattern FILE_NAME = Pattern.compile("(" + ID + ")-([a-z0-9]+(?:-[a-z0-9]+)*)\\.md");
    private static final Pattern ROW = Pattern.compile(
            "\\| ADR-(" + ID + ") \\| \\[(.+)\\]\\(adr/([^)]+)\\) \\| ([^|]+) \\| (\\d{4}-\\d{2}-\\d{2}) \\|");
    private static final Pattern FORMER_ROW = Pattern.compile("\\| \\d+ \\| .*");
    private static final Pattern FORMER_CELL = Pattern.compile(
            "(\\d+) \\| \\[ADR-(" + ID + ")\\]\\(adr/([^)]+)\\)");
    private static final Pattern HEADING = Pattern.compile("# ADR-(" + ID + "): (.+)");
    private static final Pattern STATUS = Pattern.compile(
            "(Accepted|Superseded)((; (amends|supersedes|amended by|superseded by|superseded in part by)"
            + " ADR-" + ID + "(, ADR-" + ID + ")*)*)");
    private static final Pattern CITED = Pattern.compile("\\bADR-([0-9A-Za-z]{8})\\b");
    private static final Pattern NUMBERED = Pattern.compile("\\b[Rr]ecords? \\d+");
    private static final Pattern OTHER_PROJECT = Pattern.compile("[\\w-]+'s\\s+(ADR\\s+)?$");
    private static final Set<String> TEXT = Set.of(".md", ".java", ".kts", ".c", ".h", ".sh", ".py", ".lua");

    record Row(String id, String title, String file, String status, LocalDate date) {}

    private static List<Row> rows() throws IOException {
        List<Row> rows = new ArrayList<>();
        for (String line : Files.readAllLines(TABLE)) {
            if (line.startsWith("| ADR-")) {
                Matcher m = ROW.matcher(line);
                assertTrue(m.matches(), "a row of docs/ADR.md that is not | ADR-ID | [Title](adr/file) | Status | Date |: " + line);
                rows.add(new Row(m.group(1), m.group(2), m.group(3), m.group(4).strip(), LocalDate.parse(m.group(5))));
            }
        }
        return rows;
    }

    private static Map<String, Path> files() throws IOException {
        Map<String, Path> files = new TreeMap<>();
        try (Stream<Path> list = Files.list(RECORDS)) {
            for (Path file : list.toList()) {
                Matcher m = FILE_NAME.matcher(file.getFileName().toString());
                assertTrue(m.matches() && m.group(2).length() <= 50,
                        "not <ID>-<start-of-title>.md, with at most 50 characters after the ID: " + file);
                assertTrue(files.put(m.group(1), file) == null, "two files for one ID: " + file);
            }
        }
        return files;
    }

    /** The moment an ID was made: its first 6 characters, in seconds since {@link #EPOCH}. */
    static Instant made(String id) {
        long seconds = 0;
        for (char c : id.substring(0, 6).toCharArray()) {
            seconds = seconds * 32 + ALPHABET.indexOf(c);
        }
        return EPOCH.plusSeconds(seconds);
    }

    /** The ID of a record made at {@code when} with {@code title}, as dev/new_adr.sh makes it. */
    static String id(Instant when, String title) throws NoSuchAlgorithmException {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(title.getBytes(StandardCharsets.UTF_8));
        long seconds = when.getEpochSecond() - EPOCH.getEpochSecond();
        int bits = ((hash[0] & 0xff) << 8 | (hash[1] & 0xff)) >> 6;
        return encode(seconds, 6) + encode(bits, 2);
    }

    private static String encode(long value, int width) {
        char[] out = new char[width];
        for (int k = width - 1; k >= 0; k--) {
            out[k] = ALPHABET.charAt((int) (value % 32));
            value /= 32;
        }
        return new String(out);
    }

    @Test
    void anIdIsTheTimeAndTheHashOfTheTitle() throws NoSuchAlgorithmException {
        // The record that introduced the IDs, as dev/new_adr.sh made it.
        Instant when = made("6FCBBNSH");
        assertEquals("6FCBBNSH", id(when, "Records get an ID made from the time and the title instead of a number"));
        assertTrue(id(when.plusSeconds(1), "x").compareTo("6FCBBNSH") > 0, "a later ID sorts after an earlier one");
        assertEquals(Instant.parse("2020-01-01T00:00:00Z"), made("00000000"));
        assertEquals(LocalDate.of(2054, 1, 9), made("ZZZZZZ00").atZone(ZoneOffset.UTC).toLocalDate());
    }

    @Test
    void everyRecordHasARowAndEveryRowARecord() throws IOException {
        List<Row> rows = rows();
        Map<String, Path> files = files();
        List<String> ids = rows.stream().map(Row::id).toList();
        assertEquals(List.copyOf(new TreeSet<>(ids)), ids, "the rows of docs/ADR.md are in the order of their IDs, each once");
        assertEquals(List.copyOf(files.keySet()), ids, "the files in docs/adr/ and the rows of docs/ADR.md");
        for (Row row : rows) {
            assertEquals(files.get(row.id()).getFileName().toString(), row.file(),
                    "the row of ADR-" + row.id() + " links to another file");
        }
    }

    @Test
    void theIdOfARecordDecodesToTheDateOfItsRow() throws IOException {
        for (Row row : rows()) {
            assertEquals(row.date(), made(row.id()).atZone(ZoneOffset.UTC).toLocalDate(),
                    "the day of ADR-" + row.id() + " in UTC and the date of its row");
        }
    }

    @Test
    void theHeadingOfARecordIsItsIdAndTheTitleOfItsRow() throws IOException {
        for (Row row : rows()) {
            String first = Files.readAllLines(RECORDS.resolve(row.file())).getFirst();
            Matcher m = HEADING.matcher(first);
            assertTrue(m.matches(), row.file() + " starts with \"# ADR-ID: Title\", not: " + first);
            assertEquals(row.id(), m.group(1), row.file());
            assertEquals(row.title(), m.group(2), "the title of " + row.file() + " and of its row in docs/ADR.md");
        }
    }

    @Test
    void aRecordHasContextDecisionAndConsequencesAndNoStatus() throws IOException {
        for (Row row : rows()) {
            String text = Files.readString(RECORDS.resolve(row.file()));
            for (String part : List.of("**Context.**", "**Decision.**", "**Consequences.**")) {
                assertTrue(text.contains(part), row.file() + " has no " + part);
            }
            assertTrue(!text.contains("Status:"), row.file() + ": the status is kept in docs/ADR.md only");
        }
    }

    @Test
    void aStatusNamesOnlyRecordsThatExist() throws IOException {
        List<Row> rows = rows();
        Set<String> ids = new TreeSet<>(rows.stream().map(Row::id).toList());
        for (Row row : rows) {
            Matcher m = STATUS.matcher(row.status());
            assertTrue(m.matches(), "ADR-" + row.id() + ": a status such as \"Accepted; amended by ADR-6F3CJEQA;"
                    + " superseded in part by ADR-6F0R6FT6\", not: " + row.status());
            Matcher n = Pattern.compile("ADR-(" + ID + ")").matcher(m.group(2));
            while (n.find()) {
                assertTrue(ids.contains(n.group(1)) && !n.group(1).equals(row.id()),
                        "ADR-" + row.id() + " names ADR-" + n.group(1) + " in its status");
            }
        }
    }

    @Test
    void everyFormerNumberNamesOneRecord() throws IOException {
        Map<String, String> files = new TreeMap<>();
        for (Row row : rows()) {
            files.put(row.id(), row.file());
        }
        Map<Integer, String> former = new TreeMap<>();
        for (String line : Files.readAllLines(TABLE)) {
            if (FORMER_ROW.matcher(line).matches()) {
                Matcher m = FORMER_CELL.matcher(line);
                while (m.find()) {
                    assertTrue(former.put(Integer.parseInt(m.group(1)), m.group(2)) == null, "former number twice: " + m.group(1));
                    assertEquals(files.get(m.group(2)), m.group(3), "former number " + m.group(1) + " links to another file");
                }
            }
        }
        assertEquals(Stream.iterate(1, n -> n + 1).limit(51).toList(), List.copyOf(former.keySet()),
                "the table of former numbers has 1 to 51");
        assertEquals(51, new TreeSet<>(former.values()).size(), "two former numbers name one record");
    }

    @Test
    void everyCitedRecordExistsAndNoneIsCitedByNumber() throws IOException {
        Set<String> ids = new TreeSet<>(rows().stream().map(Row::id).toList());
        List<String> wrong = new ArrayList<>();
        List<String> numbered = new ArrayList<>();
        for (Path document : documents()) {
            String text = Files.readString(document);
            Matcher m = CITED.matcher(text);
            while (m.find()) {
                if (!ids.contains(m.group(1))) {
                    wrong.add(document + ": ADR-" + m.group(1));
                }
            }
            if (document.startsWith("docs/book") || document.equals(TABLE)) {
                continue;
            }
            Matcher n = NUMBERED.matcher(text);
            while (n.find()) {
                if (!OTHER_PROJECT.matcher(text.substring(Math.max(0, n.start() - 40), n.start())).find()) {
                    numbered.add(document + ": " + n.group());
                }
            }
        }
        assertEquals(List.of(), wrong, "records cited that docs/ADR.md does not have");
        assertEquals(List.of(), numbered, "records cited by their former number; cite them as ADR-<ID>");
    }

    /** The text files of the repository, without what is built, fetched or installed. */
    private static List<Path> documents() throws IOException {
        Set<String> skip = Set.of(".git", "build", ".gradle", "node_modules", ".mesen", ".vitepress");
        try (Stream<Path> walk = Files.walk(Path.of("."))) {
            return walk.filter(Files::isRegularFile)
                    .map(p -> Path.of(".").relativize(p))
                    .filter(p -> {
                        for (Path part : p) {
                            if (skip.contains(part.toString())) {
                                return false;
                            }
                        }
                        String name = p.getFileName().toString();
                        int dot = name.lastIndexOf('.');
                        return dot >= 0 && TEXT.contains(name.substring(dot));
                    })
                    .sorted()
                    .toList();
        }
    }
}
