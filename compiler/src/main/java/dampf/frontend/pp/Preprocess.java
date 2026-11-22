package dampf.frontend.pp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * dampf's preprocessor (ADR-6FD4GDN5): C99's directives and macros as far as the subset needs them, on
 * one source file and the headers it includes, written out as text with line markers
 * ({@code # 12 "file.c"}) as {@code gcc -E} writes it, so that the parser maps every token back to
 * its file and line.
 *
 * <p>Built: {@code #include "..."} and {@code <...>}, {@code #define} (object-like, function-like,
 * variadic with {@code __VA_ARGS__}), {@code #}, {@code ##}, {@code #undef}, {@code #if},
 * {@code #ifdef}, {@code #ifndef}, {@code #elif}, {@code #else}, {@code #endif}, {@code defined},
 * {@code #error}, {@code #pragma} (passed on; {@code #pragma once} is kept), {@code __FILE__},
 * {@code __LINE__}, {@code __STDC__}, {@code __STDC_VERSION__}, {@code __STDC_HOSTED__}.
 * Rejected with an error: {@code #line}, {@code #include} of a macro, {@code #include_next},
 * {@code #warning} and gcc's other directives, and the names in {@link Expander#UNSUPPORTED}.
 *
 * <p>An include is searched like gcc with {@code -nostdinc -I} does it: {@code "..."} in the
 * directory of the including file first, then both kinds in dampf's include directory and the
 * given directories, in that order. A file's name in the markers is the directory as given and the
 * name, as gcc writes it.
 */
public final class Preprocess {
    private static final int MAX_DEPTH = 200;

    private final List<Path> searchDirs = new ArrayList<>();
    private final Map<String, Macro> macros = new HashMap<>();
    private final Expander expander = new Expander(macros);
    private final Set<Path> once = new HashSet<>();
    private final Emitter out = new Emitter();

    /** A group of an #if: whether its text is used, whether a branch was taken, whether #else came. */
    private static final class Cond {
        final Token at;
        final boolean parentActive;
        boolean active;
        boolean taken;
        boolean seenElse;

        Cond(Token at, boolean parentActive, boolean active) {
            this.at = at;
            this.parentActive = parentActive;
            this.active = active;
            this.taken = active || !parentActive;
        }
    }

    public Preprocess(Path dampfInclude, List<Path> includeDirs, List<String> defines) {
        searchDirs.add(dampfInclude);
        searchDirs.addAll(includeDirs);
        for (String d : List.of("__STDC__ 1", "__STDC_VERSION__ 199901L", "__STDC_HOSTED__ 1", "__DAMPF__ 1")) {
            define(Lexer.lexLine(d, "<built-in>", 1, 1), null);
        }
        for (String d : defines) {
            int eq = d.indexOf('=');
            String text = eq < 0 ? d + " 1" : d.substring(0, eq) + " " + d.substring(eq + 1);
            define(Lexer.lexLine(text, "<command-line>", 1, 1), null);
        }
    }

    /** The preprocessed text of {@code source}. */
    public String run(Path source) throws IOException {
        file(source.toString(), source, 0, "");
        return out.text();
    }

    private void file(String name, Path path, int depth, String flag) throws IOException {
        if (once.contains(path.toAbsolutePath().normalize())) {
            return;
        }
        String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        List<Token> tokens = Lexer.lex(text, name);
        out.enter(name, flag);
        Deque<Cond> conds = new ArrayDeque<>();
        List<Token> pending = new ArrayList<>();
        int i = 0;
        while (i < tokens.size()) {
            int end = i;
            while (tokens.get(end).kind() != Token.Kind.NEWLINE) {
                end++;
            }
            List<Token> line = tokens.subList(i, end);
            boolean active = conds.isEmpty() || conds.peek().active;
            if (!line.isEmpty() && line.getFirst().is("#")) {
                flush(pending);
                directive(line, tokens.get(end), conds, name, path, depth);
            } else if (active) {
                pending.addAll(line);
                pending.add(tokens.get(end));
            }
            i = end + 1;
        }
        flush(pending);
        if (!conds.isEmpty()) {
            Token at = conds.peek().at;
            throw new PpException("unterminated #" + at.text(), at);
        }
    }

    private void flush(List<Token> pending) {
        if (pending.isEmpty()) {
            return;
        }
        // a token from the file stands at its own line, also after an invocation or a comment over
        // several lines, as gcc writes it (clang keeps it on the line where that began)
        for (Token t : expander.expand(pending)) {
            if (t.kind() != Token.Kind.NEWLINE && t.kind() != Token.Kind.PLACEMARKER) {
                out.emit(t);
            }
        }
        pending.clear();
    }

    private void directive(List<Token> line, Token newline, Deque<Cond> conds, String name, Path path, int depth)
            throws IOException {
        boolean active = conds.isEmpty() || conds.peek().active;
        if (line.size() == 1) {
            return;   // the null directive
        }
        Token d = line.get(1);
        List<Token> rest = line.subList(2, line.size());
        String dn = d.kind() == Token.Kind.IDENT ? d.text() : "";
        switch (dn) {
            case "if" -> {
                conds.push(new Cond(d, active, active && condition(rest, d)));
                return;
            }
            case "ifdef", "ifndef" -> {
                boolean value = false;
                if (active) {
                    Token m = macroName(rest, d);
                    value = macros.containsKey(m.text()) == dn.equals("ifdef");
                }
                conds.push(new Cond(d, active, value));
                return;
            }
            case "elif" -> {
                Cond c = conds.peek();
                if (c == null) {
                    throw new PpException("#elif without #if", d);
                }
                if (c.seenElse) {
                    throw new PpException("#elif after #else", d);
                }
                c.active = c.parentActive && !c.taken && condition(rest, d);
                c.taken |= c.active;
                return;
            }
            case "else" -> {
                Cond c = conds.peek();
                if (c == null) {
                    throw new PpException("#else without #if", d);
                }
                if (c.seenElse) {
                    throw new PpException("#else after #else", d);
                }
                c.seenElse = true;
                c.active = c.parentActive && !c.taken;
                c.taken = true;
                return;
            }
            case "endif" -> {
                if (conds.isEmpty()) {
                    throw new PpException("#endif without #if", d);
                }
                conds.pop();
                return;
            }
            default -> {
            }
        }
        if (!active) {
            return;   // other directives in a group that is skipped are not looked at
        }
        switch (dn) {
            case "define" -> define(rest, d);
            case "undef" -> macros.remove(macroName(rest, d).text());
            case "include" -> include(rest, d, newline, name, depth);
            case "error" -> throw new PpException("#error " + spell(rest), line.getFirst());
            case "pragma" -> {
                if (!rest.isEmpty() && rest.getFirst().isIdent("once")) {
                    once.add(path.toAbsolutePath().normalize());
                } else {
                    out.pragma(line.getFirst().line(), spell(rest));
                }
            }
            case "line", "include_next", "warning", "ident", "sccs", "assert", "unassert", "import" ->
                    throw new PpException("#" + dn + " is not supported by dampf's preprocessor", d);
            default -> throw new PpException("invalid preprocessing directive #" + d.text(), d);
        }
    }

    private static Token macroName(List<Token> rest, Token d) {
        if (rest.isEmpty()) {
            throw new PpException("no macro name given in #" + d.text() + " directive", d);
        }
        Token m = rest.getFirst();
        if (m.kind() != Token.Kind.IDENT) {
            throw new PpException("macro names must be identifiers", m);
        }
        return m;
    }

    /** The tokens as text, one space where there was white space. */
    private static String spell(List<Token> ts) {
        StringBuilder s = new StringBuilder();
        for (Token t : ts) {
            if (t.space() && !s.isEmpty()) {
                s.append(' ');
            }
            s.append(t.text());
        }
        return s.toString();
    }

    /** #define: the name, its parameters if a "(" follows it without white space, the body. */
    private void define(List<Token> rest, Token d) {
        Token nameTok = macroName(rest, d == null ? rest.getFirst() : d);
        String name = nameTok.text();
        if (name.equals("defined")) {
            throw new PpException("\"defined\" cannot be used as a macro name", nameTok);
        }
        List<String> params = null;
        boolean variadic = false;
        int k = 1;
        if (rest.size() > 1 && rest.get(1).is("(") && !rest.get(1).space()) {
            params = new ArrayList<>();
            k = 2;
            if (k < rest.size() && rest.get(k).is(")")) {
                k++;
            } else {
                while (true) {
                    if (k >= rest.size()) {
                        throw new PpException("missing ')' in macro parameter list", nameTok);
                    }
                    Token p = rest.get(k);
                    if (p.is("...")) {
                        variadic = true;
                        params.add("__VA_ARGS__");
                        k++;
                        if (k >= rest.size() || !rest.get(k).is(")")) {
                            throw new PpException("missing ')' after \"...\"", p);
                        }
                        k++;
                        break;
                    }
                    if (p.kind() != Token.Kind.IDENT || p.text().equals("__VA_ARGS__")) {
                        throw new PpException("expected parameter name, found \"" + p.text() + "\"", p);
                    }
                    if (params.contains(p.text())) {
                        throw new PpException("duplicate macro parameter \"" + p.text() + "\"", p);
                    }
                    params.add(p.text());
                    k++;
                    if (k < rest.size() && rest.get(k).is(",")) {
                        k++;
                        continue;
                    }
                    if (k < rest.size() && rest.get(k).is(")")) {
                        k++;
                        break;
                    }
                    if (k < rest.size() && rest.get(k).is("...")) {
                        throw new PpException("named variadic macros are not supported by dampf's preprocessor", rest.get(k));
                    }
                    throw new PpException("expected ',' or ')' in macro parameter list", nameTok);
                }
            }
        }
        List<Token> body = new ArrayList<>(rest.subList(k, rest.size()));
        if (!body.isEmpty()) {
            body.set(0, body.getFirst().withSpace(false));
        }
        for (int b = 0; b < body.size(); b++) {
            Token t = body.get(b);
            if (t.isIdent("__VA_OPT__")) {
                throw new PpException("__VA_OPT__ is not supported by dampf's preprocessor", t);
            }
            if (t.isIdent("__VA_ARGS__") && !variadic) {
                throw new PpException("__VA_ARGS__ can only appear in the expansion of a variadic macro", t);
            }
            if (t.is("##") && (b == 0 || b == body.size() - 1)) {
                throw new PpException("'##' cannot appear at either end of a macro expansion", t);
            }
            if (params != null && t.is("#") && (b + 1 >= body.size() || !params.contains(body.get(b + 1).text())
                    || body.get(b + 1).kind() != Token.Kind.IDENT)) {
                throw new PpException("'#' is not followed by a macro parameter", t);
            }
        }
        macros.put(name, new Macro(name, params == null ? null : List.copyOf(params), variadic, List.copyOf(body)));
    }

    /** The value of an #if or #elif: defined replaced, then the macros, then the expression. */
    private boolean condition(List<Token> rest, Token d) {
        List<Token> ts = new ArrayList<>(expander.expand(defined(rest, d)));
        ts.removeIf(t -> t.kind() == Token.Kind.PLACEMARKER);
        return Condition.evaluate(defined(ts, d), d);
    }

    private List<Token> defined(List<Token> ts, Token d) {
        List<Token> out = new ArrayList<>(ts.size());
        for (int k = 0; k < ts.size(); k++) {
            Token t = ts.get(k);
            if (!t.isIdent("defined")) {
                out.add(t);
                continue;
            }
            boolean paren = k + 1 < ts.size() && ts.get(k + 1).is("(");
            int n = k + (paren ? 2 : 1);
            if (n >= ts.size() || ts.get(n).kind() != Token.Kind.IDENT || paren && (n + 1 >= ts.size() || !ts.get(n + 1).is(")"))) {
                throw new PpException("operator \"defined\" requires an identifier", d);
            }
            boolean value = macros.containsKey(ts.get(n).text());
            out.add(new Token(Token.Kind.NUMBER, value ? "1" : "0", t.space(), t.file(), t.line(), t.col(), null, false));
            k = paren ? n + 1 : n;
        }
        return out;
    }

    private void include(List<Token> rest, Token d, Token newline, String current, int depth) throws IOException {
        if (rest.isEmpty()) {
            throw new PpException("#include expects \"FILENAME\" or <FILENAME>", d);
        }
        Token first = rest.getFirst();
        String header;
        boolean quoted;
        if (first.kind() == Token.Kind.STRING && first.text().startsWith("\"")) {
            header = first.text().substring(1, first.text().length() - 1);
            quoted = true;
        } else if (first.is("<")) {
            StringBuilder s = new StringBuilder();
            int k = 1;
            for (; k < rest.size() && !rest.get(k).is(">"); k++) {
                if (rest.get(k).space() && !s.isEmpty()) {
                    s.append(' ');
                }
                s.append(rest.get(k).text());
            }
            if (k >= rest.size()) {
                throw new PpException("missing terminating > character", first);
            }
            header = s.toString();
            quoted = false;
        } else if (first.kind() == Token.Kind.IDENT) {
            throw new PpException("#include of a macro is not supported by dampf's preprocessor", first);
        } else {
            throw new PpException("#include expects \"FILENAME\" or <FILENAME>", first);
        }
        if (depth + 1 > MAX_DEPTH) {
            throw new PpException("#include nested depth " + MAX_DEPTH + " exceeds maximum", d);
        }
        String found = find(header, quoted, current);
        if (found == null) {
            throw new PpException(header + ": No such file or directory", first);
        }
        file(found, Path.of(found), depth + 1, " 1");
        out.resume(current, newline.line() + 1);
    }

    /** Where an include is: its name as gcc writes it in the markers, or null. */
    private String find(String header, boolean quoted, String current) {
        if (Path.of(header).isAbsolute()) {
            return Files.isRegularFile(Path.of(header)) ? header : null;
        }
        List<String> candidates = new ArrayList<>();
        if (quoted) {
            int slash = Math.max(current.lastIndexOf('/'), current.lastIndexOf('\\'));
            candidates.add(slash < 0 ? header : current.substring(0, slash + 1) + header);
        }
        for (Path dir : searchDirs) {
            candidates.add(dir + "/" + header);
        }
        for (String c : candidates) {
            if (Files.isRegularFile(Path.of(c))) {
                return c;
            }
        }
        return null;
    }

    /**
     * The text written out: every token on the line it came from (a token out of a macro on the
     * line of the invocation), a token that did not come out of a macro in its column, and a line
     * marker where the file changes or more than 8 lines are left out.
     */
    private static final class Emitter {
        private final StringBuilder sb = new StringBuilder();
        private String file;
        private int line;
        private int col = 1;
        private Token prev;

        void enter(String name, String flag) {
            endLine();
            sb.append("# 1 ").append(Expander.quote(name)).append(flag).append('\n');
            file = name;
            line = 1;
            col = 1;
            prev = null;
        }

        void resume(String name, int at) {
            endLine();
            sb.append("# ").append(at).append(' ').append(Expander.quote(name)).append(" 2\n");
            file = name;
            line = at;
            col = 1;
            prev = null;
        }

        private void endLine() {
            if (col > 1) {
                sb.append('\n');
                line++;
                col = 1;
            }
        }

        private void moveTo(int target) {
            if (target <= line) {
                return;
            }
            if (target - line <= 8) {
                sb.append("\n".repeat(target - line));
            } else {
                if (col > 1) {
                    sb.append('\n');
                }
                sb.append("# ").append(target).append(' ').append(Expander.quote(file)).append('\n');
            }
            line = target;
            col = 1;
            prev = null;
        }

        void emit(Token t) {
            moveTo(t.line());
            if (col < t.col() && (prev == null || !t.expanded())) {
                sb.append(" ".repeat(t.col() - col));
                col = t.col();
            } else if (prev != null && (t.space() || needsSpace(prev, t))) {
                sb.append(' ');
                col++;
            }
            sb.append(t.text());
            col += t.text().length();
            prev = t;
        }

        void pragma(int at, String text) {
            moveTo(at);
            endLine();
            sb.append("#pragma ").append(text).append('\n');
            line++;
            prev = null;
        }

        String text() {
            endLine();
            return sb.toString();
        }

        /** Whether two tokens written next to each other would read as other tokens. */
        private static boolean needsSpace(Token a, Token b) {
            String x = a.text();
            String y = b.text();
            if (x.isEmpty() || y.isEmpty()) {
                return false;
            }
            char last = x.charAt(x.length() - 1);
            char first = y.charAt(0);
            boolean wordA = Character.isLetterOrDigit(last) || last == '_' || last == '$';
            boolean wordB = Character.isLetterOrDigit(first) || first == '_' || first == '$';
            if (wordA && wordB) {
                return true;
            }
            if (a.kind() == Token.Kind.NUMBER && (first == '.' || (first == '+' || first == '-') && "eEpP".indexOf(last) >= 0)) {
                return true;
            }
            if (first == '.' && Character.isDigit(y.length() > 1 ? y.charAt(1) : ' ') && wordA) {
                return true;
            }
            if (a.kind() == Token.Kind.PUNCT || a.kind() == Token.Kind.OTHER) {
                if (x.equals("/") && (first == '/' || first == '*')) {
                    return true;
                }
                if (x.equals(".") && Character.isDigit(first)) {
                    return true;
                }
                for (int n = 1; n <= Math.min(2, y.length()); n++) {
                    if (Lexer.PUNCTUATOR_SET.contains(x + y.substring(0, n))) {
                        return true;
                    }
                }
                if (x.equals("..") || x.equals(".") && y.startsWith("..")) {
                    return true;
                }
            }
            return false;
        }
    }
}
