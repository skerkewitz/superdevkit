package superdevkit.project;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * raylib for a target of Rachael, ready-built and pinned to one version (ADR-6F3CJZBY): found in the
 * kit (raylib/&lt;system&gt;/ in a release, build/raylib/&lt;system&gt;/ in the repository), or
 * fetched from raylib's releases on GitHub into the kit's folder once. Unpacked in Java, so it
 * needs no curl, tar or unzip.
 */
public final class Raylib {
    public static final String VERSION = "5.5";

    private Raylib() {
    }

    /** "linux", "windows" or "web": raylib's package for the target. */
    static String system(String target) {
        return switch (target) {
            case "win64" -> "windows";
            case "web" -> "web";
            default -> "linux";
        };
    }

    static String packageName(String target) {
        return switch (target) {
            case "win64" -> "raylib-" + VERSION + "_win64_msvc16.zip";
            case "web" -> "raylib-" + VERSION + "_webassembly.zip";   // built by emcc: lib/libraylib.a of wasm objects
            default -> "raylib-" + VERSION + "_linux_amd64.tar.gz";
        };
    }

    /** Where the kit keeps raylib for the target (whether it is there yet or not). */
    public static Path folder(Kit kit, String target) {
        Path release = kit.home().resolve("raylib").resolve(system(target));
        if (Files.isRegularFile(release.resolve("include/raylib.h"))) {
            return release;
        }
        boolean repository = Files.isRegularFile(kit.home().resolve("settings.gradle.kts"));
        return repository ? kit.home().resolve("build/raylib").resolve(system(target)) : release;
    }

    public static boolean present(Kit kit, String target) {
        return Files.isRegularFile(folder(kit, target).resolve("include/raylib.h"));
    }

    /** Fetches and unpacks raylib for the target when it is not there. */
    public static void ensure(Kit kit, String target, java.io.PrintStream out) throws IOException, InterruptedException {
        if (present(kit, target)) {
            return;
        }
        Path dest = folder(kit, target);
        String pkg = packageName(target);
        URI uri = URI.create("https://github.com/raysan5/raylib/releases/download/" + VERSION + "/" + pkg);
        out.println("    fetching " + uri);
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpResponse<byte[]> r = client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() != 200) {
            throw new IOException("raylib " + VERSION + ": " + uri + " answered " + r.statusCode());
        }
        Path tmp = dest.resolveSibling(dest.getFileName() + ".tmp");
        deleteTree(tmp);
        Files.createDirectories(tmp);
        if (pkg.endsWith(".zip")) {
            unzip(r.body(), tmp);
        } else {
            untar(new GZIPInputStream(new ByteArrayInputStream(r.body())), tmp);
        }
        deleteTree(dest);
        Files.createDirectories(dest.getParent());
        Files.move(tmp, dest);
        if (!present(kit, target)) {
            throw new IOException("raylib " + VERSION + ": the package " + pkg + " has no include/raylib.h");
        }
    }

    /** For the release build: puts raylib for the targets into a kit's folder (folder, targets...). */
    public static void main(String[] args) throws IOException, InterruptedException {
        Kit kit = new Kit(Path.of(args[0]));
        for (int i = 1; i < args.length; i++) {
            ensure(kit, args[i], System.out);
        }
    }

    /** The path inside the package without its first folder (raylib-5.5_.../include/raylib.h). */
    private static String strip(String name) {
        int slash = name.indexOf('/');
        return slash < 0 ? "" : name.substring(slash + 1);
    }

    private static Path target(Path root, String name) throws IOException {
        Path t = root.resolve(name).normalize();
        if (!t.startsWith(root)) {
            throw new IOException("raylib's package has a file outside its folder: " + name);
        }
        return t;
    }

    private static void unzip(byte[] zip, Path root) throws IOException {
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                String name = strip(e.getName().replace('\\', '/'));
                if (name.isEmpty()) {
                    continue;
                }
                Path t = target(root, name);
                if (e.isDirectory()) {
                    Files.createDirectories(t);
                } else {
                    Files.createDirectories(t.getParent());
                    Files.copy(z, t);
                }
            }
        }
    }

    /** A tar stream (ustar): files and folders; links and the rest are skipped. */
    private static void untar(InputStream in, Path root) throws IOException {
        byte[] header = new byte[512];
        String longName = null;
        while (in.readNBytes(header, 0, 512) == 512) {
            if (header[0] == 0) {
                break;
            }
            String name = field(header, 0, 100);
            String prefix = field(header, 345, 155);
            if (!prefix.isEmpty()) {
                name = prefix + "/" + name;
            }
            if (longName != null) {
                name = longName;
                longName = null;
            }
            long size = Long.parseLong(field(header, 124, 12).trim().isEmpty() ? "0" : field(header, 124, 12).trim(), 8);
            char type = (char) header[156];
            byte[] body = in.readNBytes((int) size);
            in.skipNBytes((512 - size % 512) % 512);
            if (type == 'L') {
                longName = new String(body, java.nio.charset.StandardCharsets.UTF_8).replace("\0", "");
                continue;
            }
            String rel = strip(name);
            if (rel.isEmpty()) {
                continue;
            }
            Path t = target(root, rel);
            if (type == '5') {
                Files.createDirectories(t);
            } else if (type == '0' || type == 0) {
                Files.createDirectories(t.getParent());
                Files.write(t, body);
            }
        }
    }

    private static String field(byte[] h, int at, int len) {
        int end = at;
        while (end < at + len && h[end] != 0) {
            end++;
        }
        return new String(h, at, end - at, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (var s = Files.walk(p)) {
            for (Path x : s.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(x);
            }
        }
    }
}
