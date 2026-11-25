package dampf.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The public headers (lib/include/sdevkit) are the console API's documentation: a game is written
 * from them alone. So everything a game reaches has a Doxygen comment of its own: a header its
 * {@code @file} block, a function, a type or a variable a {@code /** ... *}{@code /} block right
 * before it, a struct's field and a macro a {@code /**<} comment on its line, or a macro the block
 * right before it or the {@code @{ ... @}} group it is in.
 */
class LibDocTest {
    private static final Path PUBLIC = Path.of("lib/include/sdevkit");
    private static final Pattern DECLARATION = Pattern.compile("^(?:extern |typedef |void |uint\\w+ |int\\w+ |const )");

    @Test
    void everythingAGameReachesIsDocumented() throws IOException {
        List<String> missing = new ArrayList<>();
        List<Path> headers;
        try (Stream<Path> s = Files.list(PUBLIC)) {
            headers = s.filter(p -> p.toString().endsWith(".h")).sorted().toList();
        }
        assertTrue(headers.size() >= 7, "the public headers: " + headers);
        for (Path h : headers) {
            List<String> lines = Files.readAllLines(h);
            String name = h.getFileName().toString();
            if (!String.join("\n", lines).contains("@file " + name)) {
                missing.add(name + ": no @file block");
            }
            boolean inComment = false;
            boolean docJustEnded = false;
            int groups = 0;
            boolean inStruct = false;
            String guard = "SDEVKIT_" + name.replace(".h", "").toUpperCase() + "_H";
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String t = line.strip();
                if (inComment) {
                    groups += count(t, "@{") - count(t, "@}");
                    if (t.contains("*/")) {
                        inComment = false;
                    }
                    continue;
                }
                if (t.startsWith("/**") && !t.startsWith("/**<")) {
                    groups += count(t, "@{") - count(t, "@}");
                    inComment = !t.contains("*/");
                    docJustEnded = true;
                    if (!inComment) {
                        continue;
                    }
                    continue;
                }
                if (t.isEmpty() || t.startsWith("/*") || t.startsWith("*") || t.startsWith("#ifndef") || t.startsWith("#endif")
                        || t.startsWith("#include")) {
                    if (!t.isEmpty() && !t.startsWith("/*")) {
                        docJustEnded = docJustEnded && (t.startsWith("#ifndef") || t.startsWith("#endif"));
                    }
                    continue;
                }
                String where = name + ":" + (i + 1) + ": " + t;
                if (t.startsWith("#define")) {
                    if (!t.startsWith("#define " + guard) && !t.contains("/**<") && !docJustEnded && groups == 0
                            && !(i + 1 < lines.size() && lines.get(i + 1).contains("/**<"))) {
                        missing.add(where);
                    }
                    docJustEnded = t.endsWith("\\") && docJustEnded;
                    continue;
                }
                if (inStruct) {
                    if (t.startsWith("}")) {
                        inStruct = false;
                    } else if (!t.contains("/**<")) {
                        missing.add(where);
                    }
                    continue;
                }
                if (DECLARATION.matcher(t).find()) {
                    if (!docJustEnded) {
                        missing.add(where);
                    }
                    if (t.startsWith("typedef struct")) {
                        inStruct = true;
                    }
                }
                docJustEnded = false;
            }
        }
        assertEquals(List.of(), missing, "declarations of lib/include/sdevkit without a Doxygen comment of their own");
    }

    private static int count(String s, String what) {
        int n = 0;
        for (int i = s.indexOf(what); i >= 0; i = s.indexOf(what, i + 1)) {
            n++;
        }
        return n;
    }
}
