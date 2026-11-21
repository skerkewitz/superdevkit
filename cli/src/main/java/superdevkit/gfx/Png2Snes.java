package superdevkit.gfx;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import superdevkit.AtomicWrite;

/**
 * PNG to SNES graphics data, as C for dampf ({@code superdevkit png}, the converter of
 * tools/README.md): tiles of 4 or 2 bits per pixel and their palette, a palette alone, or a picture
 * for mode 7.
 *
 * <ul>
 *   <li>{@code tiles}: the image has at most 16 colours: an indexed PNG uses its palette as it is
 *       (index 0 is the transparent colour of sprites and the backdrop of backgrounds); an RGB or
 *       RGBA image gets the colours in the order they first appear, with transparent pixels (alpha
 *       0) as colour 0, which is then black. Cut into 8 x 8 tiles in reading order; a sheet 128
 *       pixels wide has the order of VRAM, so a 16 x 16 sprite at tile n is tiles n, n + 1, n + 16
 *       and n + 17. NAME_tiles (32 bytes a tile, the SNES's planar 4-bit format), NAME_pal (16
 *       colours, BGR 5:5:5, low byte first), NAME_TILE_COUNT, NAME_TILES_SIZE, NAME_PAL_SIZE.</li>
 *   <li>{@code tiles2}: the same with 2 bits a pixel (4 colours, indices 0 to 3), for BG3 of
 *       mode 1: 16 bytes a tile, a palette of 4 colours.</li>
 *   <li>{@code palette}: the colours of the first row of pixels, left to right: NAME_pal only.</li>
 *   <li>{@code mode7}: an indexed PNG 1024 wide and up to 1024 high, one byte a pixel: index i &gt; 0
 *       becomes FIRST - 1 + i (where its colours go in CGRAM), 0 stays 0. Identical 8 x 8 blocks
 *       once, at most 256 (mode 7 cannot flip). NAME_map (128 a row), NAME_tiles (64 bytes a
 *       tile), NAME_pal (from index 1 on), NAME_MAP_SIZE, NAME_TILE_COUNT, NAME_TILES_SIZE,
 *       NAME_PAL_SIZE, NAME_PAL_FIRST.</li>
 * </ul>
 *
 * The output is the bytes tools/png2snes.py made, which this replaces (ADR record 41: the kit's
 * converters in Java, so building needs nothing but Java).
 */
public final class Png2Snes {
    public enum Mode { TILES, TILES2, PALETTE, MODE7 }

    private Png2Snes() {
    }

    /** Converts {@code png} and writes NAME.c and NAME.h into {@code outDir}. */
    public static void convert(Mode mode, Path png, String name, Path outDir, int first) throws IOException, ConvertException {
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new ConvertException("'" + name + "' is no C name");
        }
        String source = png.getFileName().toString();
        if (mode == Mode.MODE7) {
            mode7(png, name, outDir, first, source);
            return;
        }
        Indexed img = indexed(png);
        if (mode == Mode.PALETTE) {
            for (int x = 16; x < img.width; x++) {
                if (img.pixels[0][x] != 0) {
                    throw new ConvertException(png + ": a palette strip has at most 16 colours in its first row");
                }
            }
            List<int[]> strip = new ArrayList<>();
            for (int x = 0; x < Math.min(16, img.width); x++) {
                strip.add(img.colours.get(img.pixels[0][x]));
            }
            while (strip.size() < 16) {
                strip.add(new int[] { 0, 0, 0 });
            }
            write(outDir, name, source, null, bgr555(strip), 0);
            return;
        }
        if (img.width % 8 != 0 || img.height % 8 != 0) {
            throw new ConvertException(png + ": " + img.width + " x " + img.height + " is not a multiple of 8 x 8");
        }
        int planes = mode == Mode.TILES2 ? 2 : 4;
        List<int[]> colours = img.colours;
        if (planes == 2) {
            int used = maxIndex(img.pixels);
            if (used > 3) {
                throw new ConvertException(png + ": uses colour " + used + "; a 2-bit tile has 4 colours");
            }
            colours = colours.subList(0, 4);
        }
        byte[] tiles = new byte[(img.width / 8) * (img.height / 8) * 8 * planes];
        int at = 0;
        for (int ty = 0; ty < img.height / 8; ty++) {
            for (int tx = 0; tx < img.width / 8; tx++) {
                for (int y = 0; y < 8; y++) {
                    for (int plane = 0; plane < planes; plane++) {
                        int bits = 0;
                        for (int x = 0; x < 8; x++) {
                            bits |= ((img.pixels[ty * 8 + y][tx * 8 + x] >> plane) & 1) << (7 - x);
                        }
                        tiles[at + (plane / 2) * 16 + y * 2 + (plane & 1)] = (byte) bits;
                    }
                }
                at += 8 * planes;
            }
        }
        write(outDir, name, source, tiles, bgr555(colours), (img.width / 8) * (img.height / 8));
    }

    /** An image as indices 0..15 and its 16 colours (r, g, b). */
    private record Indexed(int width, int height, int[][] pixels, List<int[]> colours) {}

    private static Indexed indexed(Path png) throws IOException, ConvertException {
        Png.Image img = Png.read(png);
        List<int[]> colours = new ArrayList<>();
        int[][] pixels;
        if (img.indexed()) {
            int used = maxIndex(img.indices());
            if (used > 15) {
                throw new ConvertException(png + ": uses palette index " + used + "; a 4-bit tile has 16 colours");
            }
            for (int i = 0; i < Math.min(16, img.palette().length); i++) {
                int[] p = img.palette()[i];
                colours.add(new int[] { p[0], p[1], p[2] });
            }
            pixels = img.indices();
        } else {
            colours.add(new int[] { 0, 0, 0 });
            Map<Integer, Integer> index = new HashMap<>();
            pixels = new int[img.height()][img.width()];
            for (int y = 0; y < img.height(); y++) {
                for (int x = 0; x < img.width(); x++) {
                    int v = img.rgba()[y][x];
                    if ((v & 0xFF) == 0) {
                        pixels[y][x] = 0;
                        continue;
                    }
                    int rgb = v >>> 8;
                    Integer k = index.get(rgb);
                    if (k == null) {
                        colours.add(new int[] { rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF });
                        if (colours.size() > 16) {
                            throw new ConvertException(png + ": more than 15 colours besides the transparent one");
                        }
                        k = colours.size() - 1;
                        index.put(rgb, k);
                    }
                    pixels[y][x] = k;
                }
            }
        }
        while (colours.size() < 16) {
            colours.add(new int[] { 0, 0, 0 });
        }
        return new Indexed(img.width(), img.height(), pixels, colours);
    }

    private static int maxIndex(int[][] pixels) {
        int used = 0;
        for (int[] row : pixels) {
            for (int v : row) {
                used = Math.max(used, v);
            }
        }
        return used;
    }

    private static byte[] bgr555(List<int[]> colours) {
        byte[] out = new byte[colours.size() * 2];
        for (int i = 0; i < colours.size(); i++) {
            int[] c = colours.get(i);
            int v = (c[0] >> 3) | ((c[1] >> 3) << 5) | ((c[2] >> 3) << 10);
            out[2 * i] = (byte) v;
            out[2 * i + 1] = (byte) (v >> 8);
        }
        return out;
    }

    private static String cArray(String name, byte[] data) {
        StringBuilder s = new StringBuilder("const uint8_t " + name + "[" + data.length + "] = {\n    ");
        for (int i = 0; i < data.length; i++) {
            if (i > 0) {
                s.append(i % 16 == 0 ? ",\n    " : ", ");
            }
            s.append(String.format("0x%02X", data[i] & 0xFF));
        }
        return s.append("\n};\n").toString();
    }

    private static String header(String source) {
        return "/* Generated by superdevkit png from " + source + ". Do not edit. */";
    }

    private static void write(Path outDir, String name, String source, byte[] tiles, byte[] pal, int count) throws IOException {
        Files.createDirectories(outDir);
        String up = name.toUpperCase(java.util.Locale.ROOT);
        List<String> h = new ArrayList<>(List.of(header(source), "#ifndef " + up + "_H", "#define " + up + "_H",
                "#include <stdint.h>"));
        List<String> c = new ArrayList<>(List.of(header(source), "#include \"" + name + ".h\"", ""));
        if (tiles != null) {
            h.add("#define " + up + "_TILE_COUNT " + count);
            h.add("#define " + up + "_TILES_SIZE " + tiles.length);
            h.add("extern const uint8_t " + name + "_tiles[" + tiles.length + "];");
            c.add(cArray(name + "_tiles", tiles));
        }
        h.add("#define " + up + "_PAL_SIZE " + pal.length);
        h.add("extern const uint8_t " + name + "_pal[" + pal.length + "];");
        h.add("#endif");
        h.add("");
        c.add(cArray(name + "_pal", pal));
        AtomicWrite.writeString(outDir.resolve(name + ".h"), String.join("\n", h));
        AtomicWrite.writeString(outDir.resolve(name + ".c"), String.join("\n", c));
    }

    private static void mode7(Path png, String name, Path outDir, int first, String source) throws IOException, ConvertException {
        Png.Image img = Png.read(png);
        if (!img.indexed()) {
            throw new ConvertException(png + ": a mode 7 picture is an indexed PNG (one byte a pixel)");
        }
        int width = img.width(), height = img.height();
        if (width != 1024 || height % 8 != 0 || height > 1024) {
            throw new ConvertException(png + ": " + width + " x " + height
                    + "; a mode 7 picture is 1024 wide and up to 1024 high, in whole tiles of 8 x 8");
        }
        int used = maxIndex(img.indices());
        if (first < 1 || first - 1 + used > 255) {
            throw new ConvertException(png + ": palette index " + used + " from CGRAM colour " + first + " on goes past colour 255");
        }
        if (used >= img.palette().length) {
            throw new ConvertException(png + ": uses palette index " + used + ", but the palette has " + img.palette().length + " colours");
        }
        java.io.ByteArrayOutputStream tiles = new java.io.ByteArrayOutputStream();
        Map<String, Integer> index = new HashMap<>();
        byte[] map = new byte[(height / 8) * 128];
        int m = 0;
        for (int ty = 0; ty < height / 8; ty++) {
            for (int tx = 0; tx < 128; tx++) {
                byte[] block = new byte[64];
                for (int y = 0; y < 8; y++) {
                    for (int x = 0; x < 8; x++) {
                        int v = img.indices()[ty * 8 + y][tx * 8 + x];
                        block[y * 8 + x] = (byte) (v != 0 ? first - 1 + v : 0);
                    }
                }
                String key = new String(block, StandardCharsets.ISO_8859_1);
                Integer k = index.get(key);
                if (k == null) {
                    if (index.size() == 256) {
                        throw new ConvertException(png + ": more than 256 different tiles of 8 x 8 (at tile " + tx + ", " + ty
                                + "); mode 7 has 256 and cannot flip them");
                    }
                    k = index.size();
                    index.put(key, k);
                    tiles.write(block);
                }
                map[m++] = (byte) (int) k;
            }
        }
        List<int[]> colours = new ArrayList<>();
        for (int i = 1; i <= used; i++) {
            int[] p = img.palette()[i];
            colours.add(new int[] { p[0], p[1], p[2] });
        }
        byte[] pal = bgr555(colours);
        byte[] t = tiles.toByteArray();
        Files.createDirectories(outDir);
        String up = name.toUpperCase(java.util.Locale.ROOT);
        List<String> h = List.of(header(source), "#ifndef " + up + "_H", "#define " + up + "_H", "#include <stdint.h>",
                "#define " + up + "_MAP_SIZE " + map.length, "#define " + up + "_TILE_COUNT " + index.size(),
                "#define " + up + "_TILES_SIZE " + t.length, "#define " + up + "_PAL_SIZE " + pal.length,
                "#define " + up + "_PAL_FIRST " + first,
                "extern const uint8_t " + name + "_map[" + map.length + "];",
                "extern const uint8_t " + name + "_tiles[" + t.length + "];",
                "extern const uint8_t " + name + "_pal[" + pal.length + "];", "#endif", "");
        List<String> c = List.of(header(source), "#include \"" + name + ".h\"", "", cArray(name + "_map", map),
                cArray(name + "_tiles", t), cArray(name + "_pal", pal));
        AtomicWrite.writeString(outDir.resolve(name + ".h"), String.join("\n", h));
        AtomicWrite.writeString(outDir.resolve(name + ".c"), String.join("\n", c));
    }
}
