package superdevkit.gfx;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * A small PNG reader of the kit's own, as the converter needs it: 8-bit images of the colour types
 * grey, RGB, palette, grey with alpha and RGBA, without interlacing. Not javax.imageio, so that
 * what the converter sees does not depend on a Java library's colour handling.
 */
public final class Png {
    /** An image: either palette indices ({@code palette} not null) or RGBA pixels. */
    public record Image(int width, int height, int[][] indices, int[][] rgba, int[][] palette) {
        public boolean indexed() {
            return palette != null;
        }
    }

    private Png() {
    }

    public static Image read(Path path) throws IOException, ConvertException {
        byte[] data = Files.readAllBytes(path);
        byte[] signature = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };
        if (data.length < 8 || !java.util.Arrays.equals(java.util.Arrays.copyOf(data, 8), signature)) {
            throw new ConvertException(path + ": not a PNG file");
        }
        int pos = 8;
        int width = -1, height = -1, depth = -1, ctype = -1, interlace = -1;
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        byte[] plte = null, trns = null;
        while (pos + 8 <= data.length) {
            int length = u32(data, pos);
            String kind = new String(data, pos + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            int body = pos + 8;
            pos += 12 + length;
            switch (kind) {
                case "IHDR" -> {
                    width = u32(data, body);
                    height = u32(data, body + 4);
                    depth = data[body + 8] & 0xFF;
                    ctype = data[body + 9] & 0xFF;
                    interlace = data[body + 12] & 0xFF;
                }
                case "PLTE" -> plte = java.util.Arrays.copyOfRange(data, body, body + length);
                case "tRNS" -> trns = java.util.Arrays.copyOfRange(data, body, body + length);
                case "IDAT" -> idat.write(data, body, length);
                default -> { }
            }
            if (kind.equals("IEND")) {
                break;
            }
        }
        if (depth != 8 || interlace != 0 || !(ctype == 0 || ctype == 2 || ctype == 3 || ctype == 4 || ctype == 6)) {
            throw new ConvertException(path + ": only 8-bit PNG images without interlacing are read (this one has depth "
                    + depth + ", colour type " + ctype + ", interlace " + interlace + ")");
        }
        int channels = switch (ctype) {
            case 2 -> 3;
            case 4 -> 2;
            case 6 -> 4;
            default -> 1;
        };
        int stride = width * channels;
        byte[] raw = inflate(idat.toByteArray(), path);
        if (raw.length < (long) height * (stride + 1)) {
            throw new ConvertException(path + ": the image data is shorter than " + width + " x " + height);
        }
        int[][] rows = new int[height][];
        int[] prev = new int[stride];
        for (int y = 0; y < height; y++) {
            int f = raw[y * (stride + 1)] & 0xFF;
            int[] line = new int[stride];
            for (int x = 0; x < stride; x++) {
                line[x] = raw[y * (stride + 1) + 1 + x] & 0xFF;
            }
            for (int x = 0; x < stride; x++) {
                int a = x >= channels ? line[x - channels] : 0;
                int b = prev[x];
                int c = x >= channels ? prev[x - channels] : 0;
                switch (f) {
                    case 1 -> line[x] = (line[x] + a) & 0xFF;
                    case 2 -> line[x] = (line[x] + b) & 0xFF;
                    case 3 -> line[x] = (line[x] + (a + b) / 2) & 0xFF;
                    case 4 -> {
                        int p = a + b - c;
                        int pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
                        line[x] = (line[x] + (pa <= pb && pa <= pc ? a : pb <= pc ? b : c)) & 0xFF;
                    }
                    default -> { }
                }
            }
            rows[y] = line;
            prev = line;
        }
        if (ctype == 3) {
            if (plte == null) {
                throw new ConvertException(path + ": an indexed PNG without a palette");
            }
            int n = plte.length / 3;
            int[][] palette = new int[n][4];
            for (int i = 0; i < n; i++) {
                palette[i][0] = plte[3 * i] & 0xFF;
                palette[i][1] = plte[3 * i + 1] & 0xFF;
                palette[i][2] = plte[3 * i + 2] & 0xFF;
                palette[i][3] = trns != null && i < trns.length ? trns[i] & 0xFF : 255;
            }
            return new Image(width, height, rows, null, palette);
        }
        int[][] rgba = new int[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int[] v = rows[y];
                int k = x * channels;
                int r, g, b, a;
                switch (ctype) {
                    case 0 -> { r = g = b = v[k]; a = 255; }
                    case 2 -> { r = v[k]; g = v[k + 1]; b = v[k + 2]; a = 255; }
                    case 4 -> { r = g = b = v[k]; a = v[k + 1]; }
                    default -> { r = v[k]; g = v[k + 1]; b = v[k + 2]; a = v[k + 3]; }
                }
                rgba[y][x] = r << 24 | g << 16 | b << 8 | a;
            }
        }
        return new Image(width, height, null, rgba, null);
    }

    private static int u32(byte[] d, int p) {
        return (d[p] & 0xFF) << 24 | (d[p + 1] & 0xFF) << 16 | (d[p + 2] & 0xFF) << 8 | (d[p + 3] & 0xFF);
    }

    private static byte[] inflate(byte[] z, Path path) throws ConvertException {
        Inflater inf = new Inflater();
        inf.setInput(z);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        try {
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) {
                    break;
                }
                out.write(buf, 0, n);
            }
        } catch (DataFormatException e) {
            throw new ConvertException(path + ": the image data cannot be unpacked (" + e.getMessage() + ")");
        } finally {
            inf.end();
        }
        return out.toByteArray();
    }
}
