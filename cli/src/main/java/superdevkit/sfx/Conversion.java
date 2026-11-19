package superdevkit.sfx;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A sample's conversion from WAV to BRR, with integers only (docs/sound.md, "The conversion"): the
 * Lab (tools/brr_lab.html) does the same steps in JavaScript and gives the same bytes, which a
 * test checks. Every value that needs sin, cos or pow comes from the tables that
 * dev/sfx_tables.py made once ({@code tables.txt}).
 *
 * <ol>
 * <li>the WAV mixed to one channel of 24 bits ({@link Wav});
 * <li>resampled to the rate the DSP plays at, {@code pitch * 32000 / 4096}: windowed sinc, the
 * cut-off at 0.95 of the lower Nyquist frequency, each output divided by the sum of the weights it
 * used;
 * <li>the treble boost, a high shelf at a quarter of the rate (fixed point, 14 bits);
 * <li>silence trimmed at both ends (below -50 dB);
 * <li>the gain, down to 16 bits, clipped;
 * <li>encoded to BRR ({@link Brr}).
 * </ol>
 */
final class Conversion {
    private Conversion() {
    }

    static final int DSP_RATE = 32000;
    static final int SINC_RES = 128;
    static final int SINC_HALF = 32;

    static final int[] SINC;
    static final int[] SHELF;
    static final int[] GAIN;
    static final int TRIM;

    static {
        int[][] t = new int[4][];
        try (InputStream in = Conversion.class.getResourceAsStream("tables.txt")) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : text.split("\n")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] w = line.trim().split(" ");
                int k = switch (w[0]) {
                    case "SINC" -> 0;
                    case "SHELF" -> 1;
                    case "GAIN" -> 2;
                    case "TRIM" -> 3;
                    default -> throw new IllegalStateException("tables.txt: " + w[0]);
                };
                t[k] = Arrays.stream(w, 1, w.length).mapToInt(Integer::parseInt).toArray();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        SINC = t[0];
        SHELF = t[1];
        GAIN = t[2];
        TRIM = t[3][0];
    }

    /**
     * The settings of one sample (sfx.json). {@code treble} and {@code gain} in steps of half a dB:
     * treble 0..36 (0 to 18 dB), gain -36..24 (-18 to +12 dB).
     */
    record Settings(int rate, int trebleHalfDb, int gainHalfDb, boolean trim) {
    }

    /** What the conversion gives. */
    record Result(byte[] brr, int pitch, int samples, int clipped, int[] filters, int[] input) {
    }

    /** The DSP's pitch register for a rate: rate * 4096 / 32000, rounded, 1..0x3FFF. */
    static int pitch(int rate) {
        long p = Math.floorDiv(rate * 32L + 125, 250);
        return (int) Math.max(1, Math.min(0x3FFF, p));
    }

    static Result convert(Wav wav, Settings s) {
        int pitch = pitch(s.rate());
        int[] x = resample(wav.mono(), wav.rate(), pitch);
        x = treble(x, s.trebleHalfDb());
        if (s.trim()) {
            x = trim(x);
        }
        int[] clipped = new int[1];
        int[] y = gain(x, s.gainHalfDb(), clipped);
        Brr.Encoded e = Brr.encode(y);
        return new Result(e.bytes(), pitch, y.length, clipped[0], e.filters(), y);
    }

    /**
     * Resamples 24-bit samples from {@code inRate} to the DSP's {@code pitch * 125 / 16}. Positions
     * are in units of 2^-20 input samples; the cut-off {@code cut} in units of 2^-16. Every value
     * stays below 2^53, so the Lab's JavaScript numbers hold them exactly.
     */
    static int[] resample(int[] x, int inRate, int pitch) {
        long den = pitch * 125L;     // the output rate, times 16
        long num = inRate * 16L;     // the input rate, times 16
        if (num == den || x.length == 0) {
            return x.clone();
        }
        final long one = 1L << 20;
        long step = Math.floorDiv(num * one * 2 + den, den * 2);                 // input samples per output sample
        int n = (int) Math.max(1, Math.floorDiv(x.length * den * 2 + num, num * 2));
        long cut = Math.min(62259, Math.floorDiv(95 * den * 65536, 100 * num));   // 0.95 * min(1, ratio)
        long width = Math.floorDiv((long) SINC_HALF * one * 65536, cut);         // the kernel's half width
        int[] y = new int[n];
        for (int i = 0; i < n; i++) {
            long t = i * step;
            long kLo = Math.max(0, -Math.floorDiv(-(t - width), one));               // ceil
            long kHi = Math.min(x.length - 1, Math.floorDiv(t + width, one));
            long acc = 0;
            long wsum = 0;
            for (long k = kLo; k <= kHi; k++) {
                long d = Math.abs(t - k * one);
                long j = Math.floorDiv(d * cut + (1L << 28), 1L << 29);              // in steps of 1/128
                if (j < SINC.length) {
                    long w = SINC[(int) j];
                    acc += x[(int) k] * w;
                    wsum += w;
                }
            }
            y[i] = wsum > 0 ? (int) Math.floorDiv(2 * acc + wsum, 2 * wsum) : 0;
        }
        return y;
    }

    /** The high shelf at a quarter of the rate, coefficients in units of 1/16384. */
    static int[] treble(int[] x, int halfDb) {
        if (halfDb <= 0) {
            return x;
        }
        long b0 = SHELF[halfDb * 5];
        long b1 = SHELF[halfDb * 5 + 1];
        long b2 = SHELF[halfDb * 5 + 2];
        long a1 = SHELF[halfDb * 5 + 3];
        long a2 = SHELF[halfDb * 5 + 4];
        int[] y = new int[x.length];
        long x1 = 0;
        long x2 = 0;
        long y1 = 0;
        long y2 = 0;
        for (int i = 0; i < x.length; i++) {
            long v = Math.floorDiv(b0 * x[i] + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2 + 8192, 16384);
            x2 = x1;
            x1 = x[i];
            y2 = y1;
            y1 = v;
            y[i] = (int) v;
        }
        return y;
    }

    /** Without the samples quieter than -50 dB at the start and the end. */
    static int[] trim(int[] x) {
        int a = 0;
        int b = x.length;
        while (a < b && Math.abs(x[a]) < TRIM) {
            a++;
        }
        while (b > a && Math.abs(x[b - 1]) < TRIM) {
            b--;
        }
        return Arrays.copyOfRange(x, a, b);
    }

    /** 24 bits times the gain, to 16 bits; {@code clipped[0]} counts the samples clipped. */
    static int[] gain(int[] x, int halfDb, int[] clipped) {
        long g = GAIN[halfDb + 36];
        int[] y = new int[x.length];
        for (int i = 0; i < x.length; i++) {
            long v = Math.floorDiv(x[i] * g + (1L << 23), 1L << 24);
            if (v > 32767) {
                v = 32767;
                clipped[0]++;
            } else if (v < -32768) {
                v = -32768;
                clipped[0]++;
            }
            y[i] = (int) v;
        }
        return y;
    }

    /** How many output samples at 32 kHz the DSP plays a BRR of {@code blocks} at {@code pitch}. */
    static long played(int blocks, int pitch) {
        return Math.floorDiv(blocks * 16L * 4096 + pitch - 1, pitch);
    }

    /**
     * The length in frames the library counts down for a voice (NTSC: 60.0988 frames a second,
     * 532.46 output samples each), rounded up, plus one frame for the DSP's delay after key on.
     */
    static int frames(int blocks, int pitch) {
        long out = played(blocks, pitch);
        return (int) (Math.floorDiv(out * 600988 + 320_000_000L - 1, 320_000_000L) + 1);
    }
}
