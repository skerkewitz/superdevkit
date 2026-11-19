package superdevkit.project;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A TOML reader of the kit's own (ADR record 31), for the subset superdevkit.toml uses: comments,
 * {@code key = value} with bare keys, {@code [table]} headers (one level), basic strings
 * ({@code "..."}, with \\ \" \n \t \\uXXXX) and literal strings ({@code '...'}), integers (with
 * a sign and underscores), booleans, arrays (over several lines, a comma after the last value
 * allowed) and inline tables ({@code { key = value, ... }}). Tables are maps in the order of the
 * file, arrays lists; strings are String, integers Long, booleans Boolean. Anything else is an
 * error with the line, as is a key given twice.
 */
public final class Toml {
    /** What the reader does not take, with the file and the line. */
    public static final class Error extends Exception {
        public Error(String message) {
            super(message);
        }
    }

    private final String name;
    private final String text;
    private int pos;
    private int line = 1;

    private Toml(String name, String text) {
        this.name = name;
        this.text = text;
    }

    /** Reads {@code text}; {@code name} (the file) goes into the error messages. */
    public static Map<String, Object> parse(String name, String text) throws Error {
        return new Toml(name, text).document();
    }

    private Error error(String message) {
        return new Error(name + ":" + line + ": " + message);
    }

    private Map<String, Object> document() throws Error {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> table = root;
        while (true) {
            skipBlankLines();
            if (pos >= text.length()) {
                return root;
            }
            if (text.charAt(pos) == '[') {
                pos++;
                skipSpaces();
                String key = key();
                skipSpaces();
                expect(']');
                if (root.containsKey(key)) {
                    throw error("the table [" + key + "] is there twice, or a key of that name");
                }
                table = new LinkedHashMap<>();
                root.put(key, table);
            } else {
                String key = key();
                skipSpaces();
                expect('=');
                skipSpaces();
                Object value = value();
                if (table.containsKey(key)) {
                    throw error("the key '" + key + "' is there twice");
                }
                table.put(key, value);
            }
            endOfLine();
        }
    }

    private void skipSpaces() {
        while (pos < text.length() && (text.charAt(pos) == ' ' || text.charAt(pos) == '\t')) {
            pos++;
        }
    }

    /** Spaces, comments and line ends. */
    private void skipBlankLines() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\r') {
                pos++;
            } else if (c == '\n') {
                pos++;
                line++;
            } else if (c == '#') {
                while (pos < text.length() && text.charAt(pos) != '\n') {
                    pos++;
                }
            } else {
                return;
            }
        }
    }

    private void endOfLine() throws Error {
        skipSpaces();
        if (pos < text.length() && text.charAt(pos) == '#') {
            while (pos < text.length() && text.charAt(pos) != '\n') {
                pos++;
            }
        }
        if (pos < text.length() && text.charAt(pos) == '\r') {
            pos++;
        }
        if (pos < text.length() && text.charAt(pos) != '\n') {
            throw error("more after the value: '" + rest() + "'");
        }
    }

    private String rest() {
        int end = text.indexOf('\n', pos);
        return text.substring(pos, end < 0 ? text.length() : end).strip();
    }

    private void expect(char c) throws Error {
        if (pos >= text.length() || text.charAt(pos) != c) {
            throw error("expected '" + c + "'" + (pos < text.length() ? " at '" + rest() + "'" : " at the end"));
        }
        pos++;
    }

    private String key() throws Error {
        int start = pos;
        while (pos < text.length() && (Character.isLetterOrDigit(text.charAt(pos)) || text.charAt(pos) == '_'
                || text.charAt(pos) == '-')) {
            pos++;
        }
        if (start == pos) {
            throw error("expected a key (letters, digits, _ and -)" + (pos < text.length() ? " at '" + rest() + "'" : ""));
        }
        return text.substring(start, pos);
    }

    private Object value() throws Error {
        if (pos >= text.length()) {
            throw error("a value is missing");
        }
        char c = text.charAt(pos);
        if (c == '"') {
            return basicString();
        }
        if (c == '\'') {
            int end = text.indexOf('\'', pos + 1);
            int nl = text.indexOf('\n', pos + 1);
            if (end < 0 || (nl >= 0 && nl < end)) {
                throw error("a string without its closing '");
            }
            String s = text.substring(pos + 1, end);
            pos = end + 1;
            return s;
        }
        if (c == '[') {
            pos++;
            List<Object> list = new ArrayList<>();
            while (true) {
                skipBlankLines();
                if (pos < text.length() && text.charAt(pos) == ']') {
                    pos++;
                    return list;
                }
                list.add(value());
                skipBlankLines();
                if (pos < text.length() && text.charAt(pos) == ',') {
                    pos++;
                } else if (pos < text.length() && text.charAt(pos) == ']') {
                    pos++;
                    return list;
                } else {
                    throw error("expected ',' or ']' in an array" + (pos < text.length() ? " at '" + rest() + "'" : ""));
                }
            }
        }
        if (c == '{') {
            pos++;
            Map<String, Object> table = new LinkedHashMap<>();
            skipSpaces();
            if (pos < text.length() && text.charAt(pos) == '}') {
                pos++;
                return table;
            }
            while (true) {
                skipSpaces();
                String key = key();
                skipSpaces();
                expect('=');
                skipSpaces();
                Object v = value();
                if (table.containsKey(key)) {
                    throw error("the key '" + key + "' is there twice");
                }
                table.put(key, v);
                skipSpaces();
                if (pos < text.length() && text.charAt(pos) == ',') {
                    pos++;
                } else if (pos < text.length() && text.charAt(pos) == '}') {
                    pos++;
                    return table;
                } else {
                    throw error("expected ',' or '}' in an inline table (on one line)");
                }
            }
        }
        int start = pos;
        while (pos < text.length() && " \t\r\n,]}#".indexOf(text.charAt(pos)) < 0) {
            pos++;
        }
        String word = text.substring(start, pos);
        if (word.equals("true") || word.equals("false")) {
            return Boolean.valueOf(word);
        }
        if (word.matches("[+-]?[0-9]+(_[0-9]+)*")) {
            try {
                return Long.parseLong(word.replace("_", "").replace("+", ""));
            } catch (NumberFormatException e) {
                throw error("the number " + word + " is too large");
            }
        }
        throw error(word.isEmpty() ? "a value is missing" : "'" + word + "' is no value superdevkit.toml takes "
                + "(a string in quotes, a whole number, true or false, an array or an inline table)");
    }

    private String basicString() throws Error {
        StringBuilder s = new StringBuilder();
        pos++;
        while (true) {
            if (pos >= text.length() || text.charAt(pos) == '\n') {
                throw error("a string without its closing \"");
            }
            char c = text.charAt(pos++);
            if (c == '"') {
                return s.toString();
            }
            if (c != '\\') {
                s.append(c);
                continue;
            }
            if (pos >= text.length()) {
                throw error("a string without its closing \"");
            }
            char e = text.charAt(pos++);
            switch (e) {
                case '\\' -> s.append('\\');
                case '"' -> s.append('"');
                case 'n' -> s.append('\n');
                case 't' -> s.append('\t');
                case 'u' -> {
                    if (pos + 4 > text.length() || !text.substring(pos, pos + 4).matches("[0-9A-Fa-f]{4}")) {
                        throw error("\\u needs four hex digits");
                    }
                    s.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    pos += 4;
                }
                default -> throw error("the escape \\" + e + " (\\\\, \\\", \\n, \\t, \\uXXXX)");
            }
        }
    }
}
