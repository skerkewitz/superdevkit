package dampf.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The architecture decision records: {@code docs/ADR.md} holds the table, {@code docs/adr/} one
 * file per record. Nothing else keeps the two in step, so these tests do: every file has its row
 * and every row its file, the numbers have no gaps, the titles agree, the statuses name records
 * that exist, and so does every "record N" cited in the documents. A record of another project
 * is cited with its name in front ("gosu-flint's record 32") and is not checked.
 */
class AdrTest {

    private static final Path TABLE = Path.of("docs/ADR.md");
    private static final Path RECORDS = Path.of("docs/adr");

    private static final Pattern FILE_NAME = Pattern.compile("(\\d{4})-([a-z0-9]+(?:-[a-z0-9]+)*)\\.md");
    private static final Pattern ROW = Pattern.compile(
            "\\| (\\d+) \\| \\[(.+)\\]\\(adr/([^)]+)\\) \\| ([^|]+) \\| (\\d{4}-\\d{2}-\\d{2}) \\|");
    private static final Pattern HEADING = Pattern.compile("# (\\d+)\\. (.+)");
    private static final Pattern STATUS = Pattern.compile(
            "(Accepted|Superseded)((; (amends|supersedes|amended by|superseded by|superseded in part by)"
            + " \\d+(, \\d+)*)*)");
    private static final Pattern CITED = Pattern.compile("\\b[Rr]ecords? (\\d+)");
    private static final Pattern OTHER_PROJECT = Pattern.compile("[\\w-]+'s\\s+$");

    record Row(int number, String title, String file, String status) {}

    private static List<Row> rows() throws IOException {
        List<Row> rows = new ArrayList<>();
        for (String line : Files.readAllLines(TABLE)) {
            if (line.startsWith("| ") && Character.isDigit(line.charAt(2))) {
                Matcher m = ROW.matcher(line);
                assertTrue(m.matches(), "a row of docs/ADR.md that is not | No. | [Title](adr/file) | Status | Date |: " + line);
                rows.add(new Row(Integer.parseInt(m.group(1)), m.group(2), m.group(3), m.group(4).strip()));
            }
        }
        return rows;
    }

    private static Map<Integer, Path> files() throws IOException {
        Map<Integer, Path> files = new TreeMap<>();
        try (Stream<Path> list = Files.list(RECORDS)) {
            for (Path file : list.toList()) {
                Matcher m = FILE_NAME.matcher(file.getFileName().toString());
                assertTrue(m.matches() && m.group(2).length() <= 50,
                        "not NNNN-<start-of-title>.md, with at most 50 characters after the number: " + file);
                assertTrue(files.put(Integer.parseInt(m.group(1)), file) == null, "two files for one number: " + file);
            }
        }
        return files;
    }

    @Test
    void everyRecordHasARowAndEveryRowARecord() throws IOException {
        List<Row> rows = rows();
        Map<Integer, Path> files = files();
        List<Integer> numbers = rows.stream().map(Row::number).toList();
        List<Integer> expected = Stream.iterate(1, n -> n + 1).limit(rows.size()).toList();
        assertEquals(expected, numbers, "the rows of docs/ADR.md are numbered 1, 2, 3, ... in order");
        assertEquals(List.copyOf(files.keySet()), numbers, "the files in docs/adr/ and the rows of docs/ADR.md");
        for (Row row : rows) {
            assertEquals(files.get(row.number()).getFileName().toString(), row.file(),
                    "the row of record " + row.number() + " links to another file");
        }
    }

    @Test
    void theHeadingOfARecordIsItsNumberAndTheTitleOfItsRow() throws IOException {
        for (Row row : rows()) {
            String first = Files.readAllLines(RECORDS.resolve(row.file())).getFirst();
            Matcher m = HEADING.matcher(first);
            assertTrue(m.matches(), row.file() + " starts with \"# N. Title\", not: " + first);
            assertEquals(row.number(), Integer.parseInt(m.group(1)), row.file());
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
        for (Row row : rows) {
            Matcher m = STATUS.matcher(row.status());
            assertTrue(m.matches(), "record " + row.number() + ": a status such as \"Accepted; amended by 12;"
                    + " superseded in part by 15\", not: " + row.status());
            Matcher n = Pattern.compile("\\d+").matcher(m.group(2));
            while (n.find()) {
                int other = Integer.parseInt(n.group());
                assertTrue(other >= 1 && other <= rows.size() && other != row.number(),
                        "record " + row.number() + " names record " + other + " in its status");
            }
        }
    }

    @Test
    void everyCitedRecordExists() throws IOException {
        int count = rows().size();
        List<String> wrong = new ArrayList<>();
        List<Path> documents = new ArrayList<>(List.of(Path.of("README.md"), Path.of("CLAUDE.md")));
        for (Path dir : List.of(Path.of("docs"), Path.of("doc-specs"))) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(p -> p.toString().endsWith(".md") && !p.toString().contains("node_modules"))
                        .forEach(documents::add);
            }
        }
        for (Path document : documents) {
            String text = Files.readString(document);
            Matcher m = CITED.matcher(text);
            while (m.find()) {
                if (OTHER_PROJECT.matcher(text.substring(Math.max(0, m.start() - 40), m.start())).find()) {
                    continue;
                }
                int cited = Integer.parseInt(m.group(1));
                if (cited < 1 || cited > count) {
                    wrong.add(document + ": record " + cited);
                }
            }
        }
        assertEquals(List.of(), wrong, "records cited that docs/ADR.md does not have");
    }
}
