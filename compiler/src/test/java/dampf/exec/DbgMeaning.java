package dampf.exec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What a debug file in ld65's format says, independent of its ids and order: which bytes (CPU
 * addresses, as maximal ranges) every line of every file covers, C lines apart from lines of assembly; which symbol
 * has which value; the segments with their place in the ROM file; the scopes of functions; the C
 * symbols. Two files that say the same give equal objects (ADR record 20).
 */
record DbgMeaning(Map<String, TreeSet<String>> lines, TreeSet<String> symbols, TreeSet<String> segments,
                  TreeSet<String> scopes, TreeSet<String> csyms) {

    static DbgMeaning read(Path file) throws IOException {
        Map<String, Map<String, String>> byKindId = new HashMap<>();
        Map<String, List<Map<String, String>>> all = new HashMap<>();
        for (String l : Files.readAllLines(file)) {
            int tab = l.indexOf('\t');
            if (tab < 0) {
                continue;
            }
            String kind = l.substring(0, tab);
            Map<String, String> f = fields(l.substring(tab + 1));
            all.computeIfAbsent(kind, k -> new ArrayList<>()).add(f);
            if (f.containsKey("id")) {
                byKindId.put(kind + f.get("id"), f);
            }
        }
        Map<String, TreeMap<Long, Long>> ranges = new TreeMap<>();
        for (Map<String, String> l : all.getOrDefault("line", List.of())) {
            if (!l.containsKey("span")) {
                continue;
            }
            String name = byKindId.get("file" + l.get("file")).get("name");
            String key = ("1".equals(l.get("type")) ? "C " : "asm ") + name + ":" + l.get("line");
            for (String span : l.get("span").split("\\+")) {
                Map<String, String> s = byKindId.get("span" + span);
                Map<String, String> seg = byKindId.get("seg" + s.get("seg"));
                long start = Long.decode(seg.get("start")) + Long.parseLong(s.get("start"));
                ranges.computeIfAbsent(key, k -> new TreeMap<>()).merge(start, start + Long.parseLong(s.get("size")), Math::max);
            }
        }
        // the bytes each line covers, as maximal ranges (how they are cut into spans does not matter)
        Map<String, TreeSet<String>> lines = new TreeMap<>();
        ranges.forEach((key, r) -> {
            TreeSet<String> out = new TreeSet<>();
            long from = -1;
            long to = -1;
            for (Map.Entry<Long, Long> e : r.entrySet()) {
                if (from >= 0 && e.getKey() <= to) {
                    to = Math.max(to, e.getValue());
                    continue;
                }
                if (from >= 0) {
                    out.add(String.format("%06X-%06X", from, to));
                }
                from = e.getKey();
                to = e.getValue();
            }
            out.add(String.format("%06X-%06X", from, to));
            lines.put(key, out);
        });
        TreeSet<String> symbols = new TreeSet<>();
        for (Map<String, String> s : all.getOrDefault("sym", List.of())) {
            if (!"imp".equals(s.get("type"))) {
                symbols.add(s.get("name") + "=" + Long.decode(s.get("val")) + " " + s.get("type"));
            }
        }
        TreeSet<String> segments = new TreeSet<>();
        for (Map<String, String> s : all.getOrDefault("seg", List.of())) {
            if (Long.decode(s.get("size")) > 0) {
                segments.add(s.get("name") + " " + s.get("start") + " " + s.get("size") + " " + s.get("addrsize") + " "
                        + s.getOrDefault("ooffs", "-"));
            }
        }
        TreeSet<String> scopes = new TreeSet<>();
        for (Map<String, String> s : all.getOrDefault("scope", List.of())) {
            if ("scope".equals(s.get("type"))) {
                scopes.add(s.get("name") + " " + s.get("size"));
            }
        }
        TreeSet<String> csyms = new TreeSet<>();
        for (Map<String, String> c : all.getOrDefault("csym", List.of())) {
            String scope = byKindId.get("scope" + c.get("scope")).get("name");
            String sym = c.containsKey("sym") ? byKindId.get("sym" + c.get("sym")).get("name") : "?";
            csyms.add(c.get("name") + " in " + scope + " " + c.get("sc") + " " + sym);
        }
        return new DbgMeaning(lines, symbols, segments, scopes, csyms);
    }

    private static Map<String, String> fields(String s) {
        Map<String, String> m = new HashMap<>();
        int k = 0;
        while (k < s.length()) {
            int eq = s.indexOf('=', k);
            String key = s.substring(k, eq);
            int end;
            String value;
            if (s.charAt(eq + 1) == '"') {
                end = s.indexOf('"', eq + 2) + 1;
                value = s.substring(eq + 2, end - 1);
            } else {
                end = s.indexOf(',', eq);
                end = end < 0 ? s.length() : end;
                value = s.substring(eq + 1, end);
            }
            m.put(key, value);
            k = end + 1;
        }
        return m;
    }
}
