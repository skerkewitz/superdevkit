package superdevkit.sfx;

/**
 * BRR, the S-DSP's sample format: blocks of 9 bytes, a header (shift, filter, end and loop flags)
 * and 16 samples of 4 bits. The decoder is the S-DSP's (the four filters, the clamp to 16 bits and
 * the clip to 15, as Anomie's S-DSP notes describe them); the encoder is the kit's: for each block
 * every filter and shift is tried, each nibble chosen against the real decoder, and the pair with
 * the smallest squared error kept. The Lab's {@code encodeBrr} and {@code decodeBrr} are the same
 * code in JavaScript, line for line.
 */
final class Brr {
    private Brr() {
    }

    /** The result of {@link #encode}: the bytes and how many blocks used each filter. */
    record Encoded(byte[] bytes, int[] filters) {
    }

    static int clamp16(int v) {
        return v < -32768 ? -32768 : v > 32767 ? 32767 : v;
    }

    static int wrap16(int v) {
        return (short) v;
    }

    /** One sample from nibble {@code n} (-8..7), as the DSP stores it (clamped, then doubled with wrap). */
    static int decodeOne(int n, int shift, int filter, int out1, int out2) {
        int s = (n << shift) >> 1;
        if (shift >= 13) {
            s = n < 0 ? -0x800 : 0;
        }
        return wrap16(clamp16(s + predict(filter, out1, out2)) * 2);
    }

    /** What a filter adds, from the two previous outputs as stored. */
    static int predict(int filter, int out1, int out2) {
        int p1 = out1 >> 1;
        int p2 = out2 >> 1;
        return switch (filter) {
            case 1 -> p1 + ((-p1) >> 4);
            case 2 -> (p1 << 1) + ((-((p1 << 1) + p1)) >> 5) - p2 + (p2 >> 4);
            case 3 -> (p1 << 1) + ((-(p1 + (p1 << 2) + (p1 << 3))) >> 6) - p2 + (((p2 << 1) + p2) >> 4);
            default -> 0;
        };
    }

    /** Encodes 16-bit samples; the last block carries the end flag. Filter 0 for the first block. */
    static Encoded encode(int[] x) {
        int blocks = Math.max(1, (x.length + 15) / 16);
        byte[] out = new byte[blocks * 9];
        int[] used = new int[4];
        int o1 = 0;
        int o2 = 0;
        int[] nib = new int[16];
        int[] best = new int[16];
        for (int b = 0; b < blocks; b++) {
            long bestErr = Long.MAX_VALUE;
            int bestShift = 0;
            int bestFilter = 0;
            int bo1 = 0;
            int bo2 = 0;
            for (int filter = 0; filter < 4; filter++) {
                if (b == 0 && filter != 0) {
                    continue;
                }
                for (int shift = 0; shift <= 12; shift++) {
                    long e = 0;
                    int a1 = o1;
                    int a2 = o2;
                    for (int i = 0; i < 16; i++) {
                        int t = b * 16 + i < x.length ? x[b * 16 + i] : 0;
                        int pred = predict(filter, a1, a2);
                        int lo = (((t >> 1) - pred) * 2) >> shift;
                        int bestN = 0;
                        int bestV = 0;
                        long bestE = Long.MAX_VALUE;
                        for (int n0 = lo - 1; n0 <= lo + 2; n0++) {
                            int n = n0 < -8 ? -8 : n0 > 7 ? 7 : n0;
                            int v = decodeOne(n, shift, filter, a1, a2);
                            long d = (long) (v - t) * (v - t);
                            if (d < bestE) {
                                bestE = d;
                                bestN = n;
                                bestV = v;
                            }
                        }
                        nib[i] = bestN;
                        e += bestE;
                        a2 = a1;
                        a1 = bestV;
                        if (e >= bestErr) {
                            break;
                        }
                    }
                    if (e < bestErr) {
                        bestErr = e;
                        bestShift = shift;
                        bestFilter = filter;
                        System.arraycopy(nib, 0, best, 0, 16);
                        bo1 = a1;
                        bo2 = a2;
                    }
                }
            }
            used[bestFilter]++;
            int end = b == blocks - 1 ? 1 : 0;
            out[b * 9] = (byte) (bestShift << 4 | bestFilter << 2 | end);
            for (int i = 0; i < 8; i++) {
                out[b * 9 + 1 + i] = (byte) ((best[2 * i] & 15) << 4 | (best[2 * i + 1] & 15));
            }
            o1 = bo1;
            o2 = bo2;
        }
        return new Encoded(out, used);
    }

    /** Decodes BRR to the DSP's stored samples, up to the block with the end flag. */
    static int[] decode(byte[] brr) {
        int blocks = brr.length / 9;
        int[] y = new int[blocks * 16];
        int o1 = 0;
        int o2 = 0;
        int k = 0;
        for (int b = 0; b < blocks; b++) {
            int h = brr[b * 9] & 0xFF;
            int shift = h >> 4;
            int filter = (h >> 2) & 3;
            for (int i = 0; i < 16; i++) {
                int by = brr[b * 9 + 1 + (i >> 1)] & 0xFF;
                int n = (((i & 1) != 0 ? by & 15 : by >> 4) ^ 8) - 8;
                int v = decodeOne(n, shift, filter, o1, o2);
                y[k++] = v;
                o2 = o1;
                o1 = v;
            }
            if ((h & 1) != 0) {
                break;
            }
        }
        return java.util.Arrays.copyOf(y, k);
    }
}
