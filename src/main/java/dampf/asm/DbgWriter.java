package dampf.asm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the debug file of a linked image in ld65's format (version 2.0), which Mesen reads: the
 * source files (the assembly and, from {@code .dbg file}, the C files), the segments with their
 * place in the ROM file, the spans of bytes every line of assembly and every C line made, the
 * scopes of the files and functions, the symbols, and the C functions and their static variables
 * ({@code .dbg func}, {@code .dbg sym}). It says what ld65's file says; ids and order differ
 * (docs/transition-to-lir.md, the decision on the debug file).
 */
public final class DbgWriter {
    private final StringBuilder files = new StringBuilder();
    private final StringBuilder lines = new StringBuilder();
    private final StringBuilder mods = new StringBuilder();
    private final StringBuilder segs = new StringBuilder();
    private final StringBuilder spans = new StringBuilder();
    private final StringBuilder scopes = new StringBuilder();
    private final StringBuilder syms = new StringBuilder();
    private final StringBuilder types = new StringBuilder();
    private final StringBuilder csyms = new StringBuilder();
    private final Map<String, Integer> typeId = new HashMap<>();
    private final List<Object[]> pending = new ArrayList<>();
    private final Map<String, Integer> exportedSym = new HashMap<>();
    private int fileCount;
    private int lineCount;
    private int spanCount;
    private int scopeCount;
    private int symCount;
    private int typeCount;
    private int csymCount;

    private DbgWriter() {}

    /**
     * @param asmFiles size and modification time (seconds) of each object's assembly file, by its name
     * @param romName  the name of the ROM file as the debug file names it
     */
    public static String write(List<ObjectFile> objects, Linker.Image image, Map<String, long[]> asmFiles, String romName) {
        return new DbgWriter().run(objects, image, asmFiles, romName);
    }

    private record Seg(int id, String name, int start, int load) {}

    private String run(List<ObjectFile> objects, Linker.Image image, Map<String, long[]> asmFiles, String romName) {
        // segments, in the order of their addresses
        Map<String, Seg> seg = new LinkedHashMap<>();
        Map<String, Integer> segSize = new HashMap<>();
        Map<ObjectFile.Section, Linker.Placed> placed = new IdentityHashMap<>();
        Map<ObjectFile.Section, Integer> partOffset = new IdentityHashMap<>();
        List<Linker.Placed> parts = new ArrayList<>(image.parts());
        parts.sort((a, b) -> Integer.compare(a.run(), b.run()));
        for (Linker.Placed p : parts) {
            placed.put(p.section(), p);
            Seg s = seg.get(p.section().name);
            if (s == null) {
                s = new Seg(seg.size(), p.section().name, p.run(), p.load());
                seg.put(s.name(), s);
            }
            partOffset.put(p.section(), p.run() - s.start());
            segSize.merge(s.name(), p.section().size(), Integer::sum);
        }
        for (Seg s : seg.values()) {
            boolean zp = s.name().equals("ZEROPAGE");
            boolean rw = zp || s.name().equals("BSS") || s.name().equals("DATA");
            segs.append(String.format("seg\tid=%d,name=\"%s\",start=0x%06X,size=0x%04X,addrsize=%s,type=%s", s.id(), s.name(),
                    s.start(), segSize.get(s.name()), zp ? "zeropage" : "absolute", rw ? "rw" : "ro"));
            if (s.load() >= 0) {
                segs.append(String.format(",oname=\"%s\",ooffs=%d", romName,
                        ((s.load() >> 16) - Linker.FIRST_BANK) * Linker.BANK + (s.load() & 0x7FFF)));
            }
            segs.append('\n');
        }

        // files: the assembly of each module, then the C files
        Map<String, Integer> fileId = new LinkedHashMap<>();
        Map<String, List<Integer>> fileMods = new LinkedHashMap<>();
        Map<String, long[]> fileInfo = new HashMap<>();
        for (int m = 0; m < objects.size(); m++) {
            ObjectFile o = objects.get(m);
            fileId.put(o.name, fileId.size());
            fileMods.computeIfAbsent(o.name, k -> new ArrayList<>()).add(m);
            fileInfo.put(o.name, asmFiles.getOrDefault(o.name, new long[2]));
        }
        for (int m = 0; m < objects.size(); m++) {
            for (Map.Entry<String, long[]> f : objects.get(m).dbgFiles.entrySet()) {
                if (!fileId.containsKey(f.getKey())) {
                    fileId.put(f.getKey(), fileId.size());
                    fileInfo.put(f.getKey(), f.getValue());
                }
                List<Integer> ms = fileMods.computeIfAbsent(f.getKey(), k -> new ArrayList<>());
                if (!ms.contains(m)) {
                    ms.add(m);
                }
            }
        }
        for (Map.Entry<String, Integer> f : fileId.entrySet()) {
            long[] info = fileInfo.get(f.getKey());
            files.append(String.format("file\tid=%d,name=\"%s\",size=%d,mtime=0x%08X,mod=%s%n", f.getValue(), f.getKey(), info[0],
                    info[1], join(fileMods.get(f.getKey()))));
            fileCount++;
        }

        for (int m = 0; m < objects.size(); m++) {
            ObjectFile o = objects.get(m);
            mods.append(String.format("mod\tid=%d,name=\"%s\",file=%d%n", m, o.name.replaceFirst("\\.s$", ".o"), fileId.get(o.name)));
            Map<String, Long> values = image.locals().get(o);

            // the assembly's lines with their bytes
            Map<Integer, Integer> asmLineId = new HashMap<>();
            for (ObjectFile.AsmLine l : o.asmLines) {
                ObjectFile.Section s = o.sections.get(l.section());
                int span = span(seg.get(l.section()).id(), partOffset.get(s) + l.offset(), l.size());
                asmLineId.put(l.line(), line(fileId.get(o.name), l.line(), null, List.of(span)));
            }
            // the C lines: a span for every run of code under one .dbg line
            Map<String, List<Integer>> cSpans = new LinkedHashMap<>();
            ObjectFile.Line run = null;
            for (ObjectFile.Line l : o.lines) {
                if (run != null && run.file().equals(l.file()) && run.line() == l.line() && run.section().equals(l.section())
                        && run.offset() + run.size() == l.offset()) {
                    run = new ObjectFile.Line(run.section(), run.offset(), run.size() + l.size(), run.file(), run.line());
                    continue;
                }
                cSpan(run, o, seg, partOffset, cSpans);
                run = l;
            }
            cSpan(run, o, seg, partOffset, cSpans);
            for (Map.Entry<String, List<Integer>> e : cSpans.entrySet()) {
                int bar = e.getKey().lastIndexOf('|');
                line(fileId.get(e.getKey().substring(0, bar)), Integer.parseInt(e.getKey().substring(bar + 1)), 1, e.getValue());
            }

            // scopes: the file, then every .proc
            int fileScope = scopeCount++;
            List<Integer> fileSpans = new ArrayList<>();
            int fileSize = 0;
            for (ObjectFile.Section s : o.sections.values()) {
                if (s.size() > 0) {
                    fileSpans.add(span(seg.get(s.name).id(), partOffset.get(s), s.size()));
                    fileSize += s.size();
                }
            }
            scopes.append(String.format("scope\tid=%d,name=\"\",mod=%d,size=%d%s%n", fileScope, m, fileSize,
                    fileSpans.isEmpty() ? "" : ",span=" + join(fileSpans)));
            Map<String, Integer> symId = new HashMap<>();
            for (ObjectFile.Symbol s : o.symbols.values()) {
                if (!s.name().startsWith(":")) {
                    symId.put(s.name(), symCount++);
                }
            }
            Map<String, Integer> procScope = new HashMap<>();
            for (ObjectFile.Proc p : o.procs) {
                int id = scopeCount++;
                procScope.put(p.name(), id);
                ObjectFile.Section s = o.sections.get(p.section());
                int parent = p.name().contains("::") ? procScope.get(p.name().substring(0, p.name().lastIndexOf("::"))) : fileScope;
                String span = p.size() > 0 ? ",span=" + span(seg.get(p.section()).id(), partOffset.get(s) + p.offset(), p.size()) : "";
                scopes.append(String.format("scope\tid=%d,name=\"%s\",mod=%d,type=scope,size=%d,parent=%d,sym=%d%s%n", id,
                        shortName(p.name()), m, p.size(), parent, symId.get(p.name()), span));
            }

            // symbols
            Map<String, Integer> procSize = new HashMap<>();
            o.procs.forEach(p -> procSize.put(p.name(), p.size()));
            for (ObjectFile.Symbol s : o.symbols.values()) {
                if (s.name().startsWith(":")) {
                    continue;   // unnamed labels have no name
                }
                String name = s.name();
                String where;
                int cheap = name.lastIndexOf("::@");
                if (cheap >= 0) {
                    where = "parent=" + symId.get(name.substring(0, cheap));
                } else {
                    int k = name.lastIndexOf("::");
                    where = "scope=" + (k < 0 ? fileScope : procScope.get(name.substring(0, k)));
                }
                Integer def = o.defLine.get(name);
                int defId = asmLineId.computeIfAbsent(def, d -> line(fileId.get(o.name), d, null, List.of()));
                long v = values.get(name);
                String size = procSize.containsKey(name) ? ",size=" + procSize.get(name) : "";
                syms.append(String.format("sym\tid=%d,name=\"%s\",addrsize=%s%s,%s,def=%d,val=0x%X%s,type=%s%n", symId.get(name),
                        shortName(name), s.zeropage() ? "zeropage" : "absolute", size, where, defId, v,
                        s.isLabel() ? ",seg=" + seg.get(s.section()).id() : "", s.isLabel() ? "lab" : "equ"));
            }

            // C functions and their static variables (a variable's label may be in another module)
            for (ObjectFile.DbgFunc fn : o.dbgFuncs) {
                pending.add(new Object[] {fn.name(), procScope.get(fn.label()), type(typeId, fn.type()), "ext", symId, fn.label()});
            }
            for (ObjectFile.DbgSym v : o.dbgSyms) {
                pending.add(new Object[] {v.name(), procScope.get(v.function()), type(typeId, v.type()), "static", symId, v.label()});
            }
            for (String e : o.exports) {
                exportedSym.put(e, symId.get(e));
            }
        }
        for (Object[] c : pending) {
            @SuppressWarnings("unchecked")
            Map<String, Integer> local = (Map<String, Integer>) c[4];
            Integer sym = local.containsKey((String) c[5]) ? local.get((String) c[5]) : exportedSym.get((String) c[5]);
            csyms.append(String.format("csym\tid=%d,name=\"%s\",scope=%d,type=%d,sc=%s,sym=%d%n", csymCount++, c[0], c[1], c[2],
                    c[3], sym));
        }

        StringBuilder out = new StringBuilder();
        out.append("version\tmajor=2,minor=0\n");
        out.append(String.format("info\tcsym=%d,file=%d,lib=0,line=%d,mod=%d,scope=%d,seg=%d,span=%d,sym=%d,type=%d%n", csymCount,
                fileCount, lineCount, objects.size(), scopeCount, seg.size(), spanCount, symCount, typeCount));
        return out.append(files).append(lines).append(mods).append(segs).append(spans).append(scopes).append(syms)
                .append(types).append(csyms).toString();
    }

    private void cSpan(ObjectFile.Line run, ObjectFile o, Map<String, Seg> seg, Map<ObjectFile.Section, Integer> partOffset,
                       Map<String, List<Integer>> cSpans) {
        if (run == null || run.size() == 0) {
            return;
        }
        ObjectFile.Section s = o.sections.get(run.section());
        int span = span(seg.get(run.section()).id(), partOffset.get(s) + run.offset(), run.size());
        cSpans.computeIfAbsent(run.file() + "|" + run.line(), k -> new ArrayList<>()).add(span);
    }

    private int type(Map<String, Integer> ids, String val) {
        return ids.computeIfAbsent(val, v -> {
            types.append(String.format("type\tid=%d,val=\"%s\"%n", typeCount, v));
            return typeCount++;
        });
    }

    private int span(int seg, int start, int size) {
        spans.append(String.format("span\tid=%d,seg=%d,start=%d,size=%d%n", spanCount, seg, start, size));
        return spanCount++;
    }

    private int line(int file, int line, Integer type, List<Integer> spanIds) {
        lines.append(String.format("line\tid=%d,file=%d,line=%d%s%s%n", lineCount, file, line, type == null ? "" : ",type=" + type,
                spanIds.isEmpty() ? "" : ",span=" + join(spanIds)));
        return lineCount++;
    }

    private static String shortName(String name) {
        int k = name.lastIndexOf("::");
        return k < 0 ? name : name.substring(k + 2);
    }

    private static String join(List<Integer> ids) {
        StringBuilder b = new StringBuilder();
        for (int i : ids) {
            b.append(b.isEmpty() ? "" : "+").append(i);
        }
        return b.toString();
    }
}
