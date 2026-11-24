package dampf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * An argument file for {@code dampf build}, {@code check} and {@code parse} (ADR-6F3CJW9N):
 * {@code --config <file>} or {@code @file}, once per call. One option with its value per line, or
 * a source file or folder; {@code #} starts a comment, blank lines are ignored, a value may be
 * quoted. Relative paths are resolved against the file's folder. The file's arguments go in front
 * of those of the command line, so that the command line adds to them and wins for an option with
 * one value. A file may not name another.
 */
public final class ConfigFile {
    private ConfigFile() {}

    /** Options followed by a value. */
    private static final Set<String> TAKES_VALUE = Set.of("-I", "-D", "-o", "--entry", "--backend", "--assembler",
            "--block-counts", "--replace", "--sram", "--lib");
    /** Options whose value is a path. */
    private static final Set<String> PATH_VALUE = Set.of("-I", "-o", "--block-counts", "--lib");

    /** A mistake in an argument file or in how it was named, with the place. */
    public static final class Error extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Error(String message) {
            super(message);
        }
    }

    /**
     * The arguments of a call with the argument file, if one is named, read in: the command, the
     * file's arguments, then the rest of the command line.
     */
    public static List<String> expand(List<String> args) throws IOException {
        List<String> rest = new ArrayList<>();
        Path config = null;
        for (int i = 1; i < args.size(); i++) {
            String a = args.get(i);
            Path named = null;
            if (a.equals("--config")) {
                if (i + 1 == args.size()) {
                    throw new Error("--config needs a file");
                }
                named = Path.of(args.get(++i));
            } else if (a.startsWith("@") && a.length() > 1) {
                named = Path.of(a.substring(1));
            }
            if (named == null) {
                rest.add(a);
            } else if (config != null) {
                throw new Error("only one argument file per call (" + config + " and " + named + ")");
            } else {
                config = named;
            }
        }
        List<String> out = new ArrayList<>();
        out.add(args.getFirst());
        if (config != null) {
            out.addAll(read(config));
        }
        out.addAll(rest);
        return out;
    }

    /** The arguments an argument file holds, its relative paths resolved against its folder. */
    public static List<String> read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new Error("no argument file " + file);
        }
        Path folder = file.getParent();
        List<String> lines = Files.readAllLines(file);
        List<String> out = new ArrayList<>();
        for (int n = 0; n < lines.size(); n++) {
            String where = file + ":" + (n + 1) + ": ";
            List<String> words = words(lines.get(n), where);
            if (words.isEmpty()) {
                continue;
            }
            String first = words.getFirst();
            if (first.equals("--config") || first.startsWith("@")) {
                throw new Error(where + "an argument file may not name another");
            }
            if (TAKES_VALUE.contains(first)) {
                if (words.size() != 2) {
                    throw new Error(where + first + " needs one value");
                }
                String value = words.get(1);
                out.add(first);
                if (PATH_VALUE.contains(first)) {
                    out.add(resolve(folder, value));
                } else if (first.equals("--replace") && value.contains("=")) {
                    int eq = value.indexOf('=');
                    out.add(value.substring(0, eq + 1) + resolve(folder, value.substring(eq + 1)));
                } else {
                    out.add(value);
                }
                continue;
            }
            if (words.size() != 1) {
                throw new Error(where + "one option with its value per line, or one file or folder");
            }
            if (first.startsWith("-I") && first.length() > 2) {
                out.add("-I" + resolve(folder, first.substring(2)));
            } else if (first.startsWith("-")) {
                out.add(first);
            } else {
                out.add(resolve(folder, first));
            }
        }
        return out;
    }

    /** The source files an argument names: a folder stands for the .c files directly in it. */
    public static List<Path> sources(Path p) throws IOException {
        if (!Files.exists(p)) {
            // say so here, with the path as the user gave it
            throw new Error("no such file or folder: " + p);
        }
        if (!Files.isDirectory(p)) {
            return List.of(p);
        }
        try (Stream<Path> s = Files.list(p)) {
            return s.filter(f -> f.toString().endsWith(".c") && Files.isRegularFile(f)).sorted().toList();
        }
    }

    /** A path of the file, relative to the file's folder; given as the file was named (relative to where dampf runs, or absolute). */
    private static String resolve(Path folder, String value) {
        Path p = Path.of(value);
        if (p.isAbsolute() || folder == null) {
            return value;
        }
        return folder.resolve(p).normalize().toString();
    }

    /** The words of a line: split at blanks, a double-quoted value as one word, # to the end. */
    private static List<String> words(String line, String where) {
        List<String> words = new ArrayList<>();
        StringBuilder w = null;
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    quoted = false;
                } else {
                    w.append(c);
                }
            } else if (c == '"') {
                quoted = true;
                if (w == null) {
                    w = new StringBuilder();
                }
            } else if (c == '#' && w == null) {
                break;
            } else if (Character.isWhitespace(c)) {
                if (w != null) {
                    words.add(w.toString());
                    w = null;
                }
            } else {
                if (w == null) {
                    w = new StringBuilder();
                }
                w.append(c);
            }
        }
        if (quoted) {
            throw new Error(where + "a quote is not closed");
        }
        if (w != null) {
            words.add(w.toString());
        }
        return words;
    }
}
