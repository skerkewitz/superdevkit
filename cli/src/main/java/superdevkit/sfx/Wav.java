package superdevkit.sfx;

/**
 * A WAV file as the converter reads it: PCM of 8, 16, 24 or 32 bits, or 32-bit float, any number
 * of channels, mixed to one channel of 24-bit integers. The Lab reads WAV the same way
 * ({@code readWav} in tools/brr_lab.html), not with the browser's decoder, which resamples to the
 * rate of the audio device.
 *
 * <p>The rules, the same in both: 8 bits are unsigned ({@code (b - 128) << 16}), 16 bits are shifted
 * up by 8, 24 bits are taken as they are, 32-bit PCM is shifted down by 8 (rounding towards minus
 * infinity), a float {@code f} becomes {@code floor(f * 8388608 + 0.5)} clamped to 24 bits; the
 * channels of a frame are added and divided by their number, rounding towards minus infinity.
 *
 * @param rate   frames per second
 * @param mono   the frames, mixed to one channel, 24 bits each
 * @param channels how many channels the file has
 * @param bits   bits per sample in the file
 * @param isFloat whether the samples are floats
 */
record Wav(int rate, int[] mono, int channels, int bits, boolean isFloat) {

    /** A file that is not a WAV the converter can read; the message says why. */
    static final class FormatError extends Exception {
        private static final long serialVersionUID = 1L;

        FormatError(String message) {
            super(message);
        }
    }

    static Wav read(byte[] f) throws FormatError {
        if (f.length < 12 || !tag(f, 0, "RIFF") || !tag(f, 8, "WAVE")) {
            throw new FormatError("not a WAV file (no RIFF/WAVE header)");
        }
        int format = -1;
        int channels = 0;
        int rate = 0;
        int bits = 0;
        int dataAt = -1;
        int dataLen = 0;
        int p = 12;
        while (p + 8 <= f.length) {
            int len = u32(f, p + 4);
            int body = p + 8;
            if (len < 0 || body + len > f.length) {
                len = f.length - body; // a truncated last chunk: take what is there
            }
            if (tag(f, p, "fmt ")) {
                if (len < 16) {
                    throw new FormatError("the fmt chunk is too short");
                }
                format = u16(f, body);
                channels = u16(f, body + 2);
                rate = u32(f, body + 4);
                bits = u16(f, body + 14);
                if (format == 0xFFFE && len >= 26) {
                    format = u16(f, body + 24); // WAVE_FORMAT_EXTENSIBLE: the sub-format's first two bytes
                }
            } else if (tag(f, p, "data")) {
                dataAt = body;
                dataLen = len;
            }
            p = body + len + (len & 1);
        }
        if (format < 0) {
            throw new FormatError("no fmt chunk");
        }
        if (dataAt < 0) {
            throw new FormatError("no data chunk");
        }
        boolean isFloat = format == 3;
        if (format != 1 && !isFloat) {
            throw new FormatError("format " + format + " is not PCM or float (compressed WAV is not read)");
        }
        if (isFloat ? bits != 32 : bits != 8 && bits != 16 && bits != 24 && bits != 32) {
            throw new FormatError((isFloat ? "float" : "PCM") + " with " + bits + " bits is not read");
        }
        if (channels < 1) {
            throw new FormatError("no channels");
        }
        if (rate < 1000 || rate > 192000) {
            throw new FormatError("a rate of " + rate + " Hz (1000 to 192000 are read)");
        }
        int bytes = bits / 8;
        int frames = dataLen / (bytes * channels);
        int[] mono = new int[frames];
        for (int i = 0; i < frames; i++) {
            long sum = 0;
            for (int c = 0; c < channels; c++) {
                int at = dataAt + (i * channels + c) * bytes;
                sum += sample(f, at, bits, isFloat);
            }
            mono[i] = (int) Math.floorDiv(sum, (long) channels);
        }
        return new Wav(rate, mono, channels, bits, isFloat);
    }

    /** One sample as 24 bits. */
    static int sample(byte[] f, int at, int bits, boolean isFloat) {
        if (isFloat) {
            double v = Float.intBitsToFloat(u32(f, at));
            if (Double.isNaN(v)) {
                return 0;
            }
            double s = Math.floor(v * 8388608.0 + 0.5);
            return (int) Math.max(-8388608, Math.min(8388607, s));
        }
        return switch (bits) {
            case 8 -> ((f[at] & 0xFF) - 128) << 16;
            case 16 -> ((f[at] & 0xFF) | f[at + 1] << 8) << 8;
            case 24 -> (f[at] & 0xFF) | (f[at + 1] & 0xFF) << 8 | f[at + 2] << 16;
            default -> u32(f, at) >> 8;
        };
    }

    private static boolean tag(byte[] f, int at, String t) {
        for (int k = 0; k < 4; k++) {
            if (f[at + k] != t.charAt(k)) {
                return false;
            }
        }
        return true;
    }

    private static int u16(byte[] f, int at) {
        return (f[at] & 0xFF) | (f[at + 1] & 0xFF) << 8;
    }

    private static int u32(byte[] f, int at) {
        return (f[at] & 0xFF) | (f[at + 1] & 0xFF) << 8 | (f[at + 2] & 0xFF) << 16 | f[at + 3] << 24;
    }
}
