package superdevkit.project;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks that a game's object files reach nothing but superdevkit's console API (ADR-6F3CK2AR):
 * every symbol they leave undefined is declared in the kit's public headers (read from them, so the
 * list cannot fall behind), defined by one of the game's own objects, or one the C compiler calls
 * on its own. Reads the objects itself: ELF (gcc, clang) and COFF (MSVC), so no nm is needed.
 */
public final class Symbols {
    /** What gcc, clang and MSVC call without the source asking. */
    static final Set<String> COMPILER = Set.of(
            // struct and array copies, even with -ffreestanding (dampf's <string.h> has its own)
            "memcpy", "memset", "memmove",
            // gcc where -fstack-protector is on by default (Arch, Debian, Ubuntu, Fedora)
            "__stack_chk_fail",
            // MSVC: the image base /O2 reaches tables with, the buffer checks (/GS), floats
            "__ImageBase", "__report_rangecheckfailure", "__security_check_cookie", "__security_cookie",
            "__GSHandlerCheck", "_fltused");

    private static final Pattern DECLARATION = Pattern.compile("\\b(\\w+)\\s*\\([^(){};]*\\)\\s*;");
    private static final Pattern EXTERN = Pattern.compile("\\bextern\\b[^;(]*?\\b(\\w+)\\s*(?:\\[[^\\]]*\\])*\\s*;");

    private Symbols() {
    }

    /** The names the kit's public headers declare (functions, and variables declared extern). */
    public static Set<String> api(Path headerDir) throws IOException {
        Set<String> names = new HashSet<>();
        List<Path> headers;
        try (var s = Files.list(headerDir)) {
            headers = s.filter(p -> p.toString().endsWith(".h")).sorted().toList();
        }
        for (Path h : headers) {
            String c = Files.readString(h).replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ")
                    .replaceAll("(?m)^\\s*#.*$", " ");
            for (Pattern p : List.of(DECLARATION, EXTERN)) {
                Matcher m = p.matcher(c);
                while (m.find()) {
                    names.add(m.group(1));
                }
            }
        }
        return names;
    }

    /** One message per symbol a game's object uses that is not the kit's; empty when all is well. */
    public static List<String> check(Path headerDir, List<Path> objects) throws IOException {
        Set<String> allowed = new HashSet<>(api(headerDir));
        allowed.addAll(COMPILER);
        List<Table> tables = new ArrayList<>();
        for (Path o : objects) {
            Table t = read(o);
            tables.add(t);
            allowed.addAll(t.defined);
        }
        List<String> bad = new ArrayList<>();
        for (int i = 0; i < objects.size(); i++) {
            for (String u : tables.get(i).undefined) {
                if (!allowed.contains(u)) {
                    bad.add(objects.get(i).getFileName() + ": uses " + u + ", which is not part of superdevkit's console API "
                            + "(lib/include/sdevkit, ADR-6F3CK2AR)");
                }
            }
        }
        return bad;
    }

    /** The global symbols an object defines and the ones it leaves undefined. */
    record Table(Set<String> defined, Set<String> undefined) {}

    static Table read(Path object) throws IOException {
        byte[] d = Files.readAllBytes(object);
        if (d.length >= 4 && d[0] == 0x7F && d[1] == 'E' && d[2] == 'L' && d[3] == 'F') {
            return elf(d, object);
        }
        return coff(d, object);
    }

    /** ELF, 64 bits, little endian: the symbol table, its global and weak entries. */
    private static Table elf(byte[] d, Path object) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        if (d[4] != 2 || d[5] != 1) {
            throw new IOException(object + ": only 64-bit little-endian ELF objects are read");
        }
        long shoff = b.getLong(0x28);
        int shentsize = b.getShort(0x3A) & 0xFFFF;
        int shnum = b.getShort(0x3C) & 0xFFFF;
        Set<String> defined = new HashSet<>(), undefined = new HashSet<>();
        for (int s = 0; s < shnum; s++) {
            int sh = (int) (shoff + (long) s * shentsize);
            if (b.getInt(sh + 4) != 2) {          // SHT_SYMTAB
                continue;
            }
            long off = b.getLong(sh + 0x18), size = b.getLong(sh + 0x20);
            int link = b.getInt(sh + 0x28);
            long entsize = b.getLong(sh + 0x38);
            int str = (int) (shoff + (long) link * shentsize);
            long stroff = b.getLong(str + 0x18);
            for (long e = 0; e + entsize <= size; e += entsize) {
                int at = (int) (off + e);
                int name = b.getInt(at);
                int info = d[at + 4] & 0xFF;
                int shndx = b.getShort(at + 6) & 0xFFFF;
                int bind = info >> 4;
                if (name == 0 || (bind != 1 && bind != 2)) {
                    continue;
                }
                String n = cString(d, (int) (stroff + name));
                (shndx == 0 ? undefined : defined).add(n);
            }
        }
        return new Table(defined, undefined);
    }

    /** COFF (MSVC's .obj): external symbols, undefined when their section number is 0. */
    private static Table coff(byte[] d, Path object) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        if (d.length < 20) {
            throw new IOException(object + ": neither an ELF nor a COFF object");
        }
        int symtab = b.getInt(8);
        int nsyms = b.getInt(12);
        if (symtab <= 0 || symtab + (long) nsyms * 18 > d.length) {
            throw new IOException(object + ": neither an ELF nor a COFF object");
        }
        int strtab = symtab + nsyms * 18;
        Set<String> defined = new HashSet<>(), undefined = new HashSet<>();
        for (int i = 0; i < nsyms; i++) {
            int at = symtab + i * 18;
            String name;
            if (b.getInt(at) == 0) {
                name = cString(d, strtab + b.getInt(at + 4));
            } else {
                int end = 0;
                while (end < 8 && d[at + end] != 0) {
                    end++;
                }
                name = new String(d, at, end, StandardCharsets.ISO_8859_1);
            }
            short section = b.getShort(at + 12);
            int storage = d[at + 16] & 0xFF;
            int aux = d[at + 17] & 0xFF;
            if (storage == 2) {                   // IMAGE_SYM_CLASS_EXTERNAL
                if (section == 0 && b.getInt(at + 8) == 0) {
                    undefined.add(name);
                } else {
                    // in a section, absolute (-1), or common (section 0, the size as its value:
                    // an uninitialised global, which this object defines)
                    defined.add(name);
                }
            }
            i += aux;
        }
        return new Table(defined, undefined);
    }

    private static String cString(byte[] d, int at) {
        int end = at;
        while (end < d.length && d[end] != 0) {
            end++;
        }
        return new String(d, at, end - at, StandardCharsets.ISO_8859_1);
    }
}
