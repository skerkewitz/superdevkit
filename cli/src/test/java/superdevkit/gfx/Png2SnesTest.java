package superdevkit.gfx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The converter (superdevkit png): tiles and colours survive the round trip for indexed and RGBA
 * images, the files say what they hold, and what does not fit is an error. (The cases of
 * tools/png2snes.py's tests, which this converter replaces.)
 */
class Png2SnesTest {
    @TempDir
    Path dir;

    /** Writes a PNG: rows of pixels, each an index (colour type 3) or {r, g, b, a} (type 6). */
    private static void png(Path path, int width, int[][][] rows, int ctype, int[][] palette, boolean filterUp) throws IOException {
        int channels = ctype == 3 ? 1 : 4;
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        byte[] prev = new byte[width * channels];
        for (int[][] r : rows) {
            byte[] line = new byte[width * channels];
            for (int x = 0; x < width; x++) {
                for (int c = 0; c < channels; c++) {
                    line[x * channels + c] = (byte) r[x][c];
                }
            }
            if (filterUp) {
                raw.write(2);
                for (int i = 0; i < line.length; i++) {
                    raw.write((line[i] - prev[i]) & 0xFF);
                }
                prev = line;
            } else {
                raw.write(0);
                raw.write(line);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[] { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' });
        chunk(out, "IHDR", ByteBuffer.allocate(13).putInt(width).putInt(rows.length).put((byte) 8).put((byte) ctype)
                .put((byte) 0).put((byte) 0).put((byte) 0).array());
        if (palette != null) {
            byte[] p = new byte[palette.length * 3];
            for (int i = 0; i < palette.length; i++) {
                p[3 * i] = (byte) palette[i][0];
                p[3 * i + 1] = (byte) palette[i][1];
                p[3 * i + 2] = (byte) palette[i][2];
            }
            chunk(out, "PLTE", p);
            chunk(out, "tRNS", new byte[] { 0 });
        }
        Deflater d = new Deflater();
        d.setInput(raw.toByteArray());
        d.finish();
        byte[] buf = new byte[raw.size() + 1024];
        int n = d.deflate(buf);
        chunk(out, "IDAT", java.util.Arrays.copyOf(buf, n));
        chunk(out, "IEND", new byte[0]);
        Files.write(path, out.toByteArray());
    }

    private static void chunk(ByteArrayOutputStream out, String kind, byte[] data) throws IOException {
        out.write(ByteBuffer.allocate(4).putInt(data.length).array());
        byte[] k = kind.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        out.write(k);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(k);
        crc.update(data);
        out.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    private static int[][][] indexRows(int[][] idx) {
        int[][][] rows = new int[idx.length][][];
        for (int y = 0; y < idx.length; y++) {
            rows[y] = new int[idx[y].length][];
            for (int x = 0; x < idx[y].length; x++) {
                rows[y][x] = new int[] { idx[y][x] };
            }
        }
        return rows;
    }

    /** The bytes of {@code const uint8_t NAME[...] = { ... }} in a generated .c file. */
    private static byte[] array(Path c, String name) throws IOException {
        Matcher m = Pattern.compile(name + "\\[\\d+\\] = \\{([^}]*)\\}").matcher(Files.readString(c));
        assertTrue(m.find(), name + " in " + c);
        List<Byte> out = new ArrayList<>();
        for (String s : m.group(1).split(",")) {
            if (!s.isBlank()) {
                out.add((byte) Integer.parseInt(s.strip().substring(2), 16));
            }
        }
        byte[] b = new byte[out.size()];
        for (int i = 0; i < b.length; i++) {
            b[i] = out.get(i);
        }
        return b;
    }

    /** The colour index of pixel (x, y) of the tile at {@code at} in the SNES's 4-bit format. */
    private static int decode(byte[] t, int at, int x, int y, int planes) {
        int v = 0;
        for (int p = 0; p < planes; p++) {
            v |= ((t[at + (p / 2) * 16 + y * 2 + (p & 1)] >> (7 - x)) & 1) << p;
        }
        return v;
    }

    @Test
    void indexedTilesRoundTrip() throws Exception {
        Random r = new Random(1);
        int[][] idx = new int[16][16];
        for (int[] row : idx) {
            for (int x = 0; x < 16; x++) {
                row[x] = r.nextInt(16);
            }
        }
        int[][] pal = new int[16][];
        for (int i = 0; i < 16; i++) {
            pal[i] = new int[] { i * 16, 255 - i * 16, (i * 37) % 256 };
        }
        Path p = dir.resolve("a.png");
        png(p, 16, indexRows(idx), 3, pal, true);
        Png2Snes.convert(Png2Snes.Mode.TILES, p, "a", dir, 1);
        byte[] t = array(dir.resolve("a.c"), "a_tiles");
        assertEquals(4 * 32, t.length);
        for (int ty = 0; ty < 2; ty++) {
            for (int tx = 0; tx < 2; tx++) {
                for (int y = 0; y < 8; y++) {
                    for (int x = 0; x < 8; x++) {
                        assertEquals(idx[ty * 8 + y][tx * 8 + x], decode(t, (ty * 2 + tx) * 32, x, y, 4));
                    }
                }
            }
        }
        byte[] colours = array(dir.resolve("a.c"), "a_pal");
        for (int i = 0; i < 16; i++) {
            int v = (pal[i][0] >> 3) | (pal[i][1] >> 3) << 5 | (pal[i][2] >> 3) << 10;
            assertEquals(v, (colours[2 * i] & 0xFF) | (colours[2 * i + 1] & 0xFF) << 8);
        }
    }

    @Test
    void twoBitTiles() throws Exception {
        Random r = new Random(2);
        int[][] idx = new int[8][8];
        for (int[] row : idx) {
            for (int x = 0; x < 8; x++) {
                row[x] = r.nextInt(4);
            }
        }
        int[][] pal = new int[16][];
        for (int i = 0; i < 16; i++) {
            pal[i] = new int[] { i < 4 ? i * 60 : 0, 0, 0 };
        }
        Path p = dir.resolve("t2.png");
        png(p, 8, indexRows(idx), 3, pal, false);
        Png2Snes.convert(Png2Snes.Mode.TILES2, p, "t2", dir, 1);
        byte[] t = array(dir.resolve("t2.c"), "t2_tiles");
        assertEquals(16, t.length);
        assertEquals(8, array(dir.resolve("t2.c"), "t2_pal").length);
        byte[] four = java.util.Arrays.copyOf(t, 32);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                assertEquals(idx[y][x], decode(four, 0, x, y, 4));
            }
        }
        idx[3][3] = 4;
        png(p, 8, indexRows(idx), 3, pal, false);
        assertThrows(ConvertException.class, () -> Png2Snes.convert(Png2Snes.Mode.TILES2, p, "t2", dir, 1));
    }

    @Test
    void eightBitTiles() throws Exception {
        Random r = new Random(3);
        int[][] idx = new int[8][16];
        for (int[] row : idx) {
            for (int x = 0; x < 16; x++) {
                row[x] = r.nextInt(256);
            }
        }
        int[][] pal = new int[256][];
        for (int i = 0; i < 256; i++) {
            pal[i] = new int[] { i, 255 - i, (i * 7) & 0xFF };
        }
        Path p = dir.resolve("t8.png");
        png(p, 16, indexRows(idx), 3, pal, false);
        Png2Snes.convert(Png2Snes.Mode.TILES8, p, "t8", dir, 1);
        byte[] t = array(dir.resolve("t8.c"), "t8_tiles");
        assertEquals(2 * 64, t.length);
        assertEquals(512, array(dir.resolve("t8.c"), "t8_pal").length);
        for (int tx = 0; tx < 2; tx++) {
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    assertEquals(idx[y][tx * 8 + x], decode(t, tx * 64, x, y, 8));
                }
            }
        }
    }

    @Test
    void rgbaGetsItsColoursInOrderOfAppearance() throws Exception {
        int[] red = { 255, 0, 0, 255 }, blue = { 0, 0, 255, 255 }, clear = { 9, 9, 9, 0 };
        int[][][] rows = new int[8][][];
        for (int y = 0; y < 8; y++) {
            rows[y] = new int[][] { clear, red, blue, red, clear, clear, clear, clear };
        }
        Path p = dir.resolve("b.png");
        png(p, 8, rows, 6, null, false);
        Png2Snes.convert(Png2Snes.Mode.TILES, p, "b", dir, 1);
        byte[] t = array(dir.resolve("b.c"), "b_tiles");
        int[] first = new int[8];
        for (int x = 0; x < 8; x++) {
            first[x] = decode(t, 0, x, 0, 4);
        }
        assertArrayEquals(new int[] { 0, 1, 2, 1, 0, 0, 0, 0 }, first);
        byte[] pal = array(dir.resolve("b.c"), "b_pal");
        assertEquals(0x001F, (pal[2] & 0xFF) | (pal[3] & 0xFF) << 8);   // red
        assertEquals(0x7C00, (pal[4] & 0xFF) | (pal[5] & 0xFF) << 8);   // blue
    }

    @Test
    void outputFilesAndBgr555() throws Exception {
        int[][] idx = new int[8][8];
        for (int[] row : idx) {
            java.util.Arrays.fill(row, 1);
        }
        int[][] pal = new int[16][];
        pal[0] = new int[] { 0, 0, 0 };
        pal[1] = new int[] { 248, 128, 8 };
        for (int i = 2; i < 16; i++) {
            pal[i] = new int[] { 0, 0, 0 };
        }
        Path p = dir.resolve("c.png");
        png(p, 8, indexRows(idx), 3, pal, false);
        Png2Snes.convert(Png2Snes.Mode.TILES, p, "thing", dir, 1);
        String h = Files.readString(dir.resolve("thing.h"));
        String c = Files.readString(dir.resolve("thing.c"));
        assertTrue(h.contains("#define THING_TILE_COUNT 1"), h);
        assertTrue(h.contains("#define THING_TILES_SIZE 32"), h);
        assertTrue(c.contains("const uint8_t thing_pal[32]"), c);
        assertTrue(c.contains("0x00, 0x00, 0x1F, 0x06"), c);   // r 31, g 16, b 1: 0x061F, low byte first
    }

    @Test
    void moreThan16ColoursIsAnError() throws Exception {
        int[][][] rows = new int[8][16][];
        for (int y = 0; y < 8; y++) {
            for (int i = 0; i < 8; i++) {
                rows[y][i] = new int[] { i * 10, 0, 0, 255 };
                rows[y][8 + i] = new int[] { 0, i * 10 + 5, 0, 255 };
            }
        }
        Path p = dir.resolve("d.png");
        png(p, 16, rows, 6, null, false);
        assertThrows(ConvertException.class, () -> Png2Snes.convert(Png2Snes.Mode.TILES, p, "d", dir, 1));
    }

    @Test
    void aSizeNotAMultipleOf8IsAnError() throws Exception {
        int[][] idx = new int[8][12];
        for (int[] row : idx) {
            java.util.Arrays.fill(row, 1);
        }
        int[][] pal = new int[16][];
        for (int i = 0; i < 16; i++) {
            pal[i] = new int[] { 0, 0, 0 };
        }
        Path p = dir.resolve("e.png");
        png(p, 12, indexRows(idx), 3, pal, false);
        assertThrows(ConvertException.class, () -> Png2Snes.convert(Png2Snes.Mode.TILES, p, "e", dir, 1));
    }

    @Test
    void mode7MapTilesAndFirstColour() throws Exception {
        // 1024 x 16: tile row 0 has a pattern in tiles 0 and 2 (the same) and 1 (another), the rest
        // colour 1; row 1 all colour 2
        int[][] idx = new int[16][1024];
        for (int y = 0; y < 16; y++) {
            java.util.Arrays.fill(idx[y], y < 8 ? 1 : 2);
        }
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                idx[y][x] = idx[y][16 + x] = (x + y) % 3 + 1;
                idx[y][8 + x] = x == y ? 3 : 1;
            }
        }
        int[][] pal = { { 0, 0, 0 }, { 248, 0, 0 }, { 0, 248, 0 }, { 0, 0, 248 } };
        Path p = dir.resolve("m7.png");
        png(p, 1024, indexRows(idx), 3, pal, false);
        Png2Snes.convert(Png2Snes.Mode.MODE7, p, "m7", dir, 33);
        byte[] map = array(dir.resolve("m7.c"), "m7_map");
        byte[] tiles = array(dir.resolve("m7.c"), "m7_tiles");
        assertEquals(256, map.length);
        assertArrayEquals(new byte[] { 0, 1, 0, 2 }, java.util.Arrays.copyOf(map, 4));
        HashSet<Byte> second = new HashSet<>();
        for (int i = 128; i < 256; i++) {
            second.add(map[i]);
        }
        assertEquals(java.util.Set.of((byte) 3), second);
        assertEquals(4 * 64, tiles.length);                  // pattern, diagonal, all 1, all 2
        assertEquals(33, tiles[0] & 0xFF);                   // index 1 -> CGRAM 33
        assertEquals(35, tiles[64 + 9] & 0xFF);              // the diagonal: index 3 at (1, 1)
        assertEquals(6, array(dir.resolve("m7.c"), "m7_pal").length);   // indices 1..3
        String h = Files.readString(dir.resolve("m7.h"));
        assertTrue(h.contains("#define M7_PAL_FIRST 33") && h.contains("#define M7_MAP_SIZE 256")
                && h.contains("#define M7_TILES_SIZE 256"), h);
    }

    @Test
    void mode7Limits() throws Exception {
        int[][] pal = new int[256][];
        for (int i = 0; i < 256; i++) {
            pal[i] = new int[] { i, i, i };
        }
        Path p = dir.resolve("m7b.png");
        int[][] idx = new int[8][1024];
        for (int[] row : idx) {
            java.util.Arrays.fill(row, 200);
        }
        png(p, 1024, indexRows(idx), 3, pal, false);
        assertThrows(ConvertException.class, () -> Png2Snes.convert(Png2Snes.Mode.MODE7, p, "m", dir, 100));   // past 255
        idx = new int[24][1024];
        for (int[] row : idx) {
            java.util.Arrays.fill(row, 1);
        }
        for (int k = 0; k < 384; k++) {                      // 384 tiles, all different
            idx[(k / 128) * 8][(k % 128) * 8] = k / 32 + 1;
            idx[(k / 128) * 8][(k % 128) * 8 + 1] = k % 32 + 1;
        }
        png(p, 1024, indexRows(idx), 3, pal, false);
        assertThrows(ConvertException.class, () -> Png2Snes.convert(Png2Snes.Mode.MODE7, p, "m", dir, 1));   // > 256 tiles
        idx = new int[8][512];
        for (int[] row : idx) {
            java.util.Arrays.fill(row, 1);
        }
        png(p, 512, indexRows(idx), 3, pal, false);
        assertThrows(ConvertException.class, () -> Png2Snes.convert(Png2Snes.Mode.MODE7, p, "m", dir, 1));   // not 1024 wide
    }
}
