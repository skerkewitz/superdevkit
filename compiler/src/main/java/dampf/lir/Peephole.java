package dampf.lir;

import java.util.ArrayList;
import java.util.List;

/**
 * Peephole rules: a few instructions in a row replaced by cheaper ones that do the same. Each rule
 * looks only at neighbouring instructions in one block (comments, C line headers and debug
 * directives between them do not matter), and
 * only at code with a 16-bit accumulator unless it says otherwise.
 *
 * <ul>
 * <li>{@code STA p / LDX p} becomes {@code STA p / TAX} (and {@code LDY} becomes {@code TAY}): X
 *     gets the value A just stored, 2 cycles instead of 4 (3 in the direct page), with the same N
 *     and Z. Not with an 8-bit A: {@code TAX} would copy the hidden high byte, where {@code LDX}
 *     reads the byte after p. If nothing reads p afterwards, {@link DeadStores} removes the store.</li>
 * <li>k × {@code LSR A} followed by m × {@code ASL A}, m &gt; k: {@code (x >> k) << m} is x with
 *     its low k bits cleared, shifted by m - k: {@code AND #mask} and m - k × {@code ASL A}. N, Z
 *     and the carry (from the last shift) are the same.</li>
 * <li>{@code REP #$20 / SEP #$20} where A was 8 bit before: both go (they change only M).</li>
 * <li>{@code CMP #0} where the instruction before set N and Z from A already, and the carry it
 *     sets is not read.</li>
 * <li>{@code LDA x / XBA / AND #$00FF}, the high byte of the word at x, is the byte at x + 1:
 *     {@code LDA x+1 / AND #$00FF}. For a plain or X- or Y-indexed operand; a place in the
 *     direct page stays there only if x + 1 is still a whole word of it.</li>
 * </ul>
 */
public final class Peephole {
    private final int frameStart;
    private final String absoluteFrame;

    /** {@code frameStart}: where the frame places start (for the direct page). */
    public Peephole(int frameStart) {
        this(frameStart, LOperand.FRAME);
    }

    /**
     * {@code absoluteFrame}: the symbol a frame place outside the direct page is written with
     * ({@code dampf_frame_irq} in an interrupt context, whose direct page is not 0).
     */
    public Peephole(int frameStart, String absoluteFrame) {
        this.frameStart = frameStart;
        this.absoluteFrame = absoluteFrame;
    }

    /** Applies the rules; returns how many places changed. */
    public int run(LFunction f) {
        int changed = 0;
        for (LBlock b : f.blocks()) {
            List<LItem> items = b.items();
            for (int k = 0; k < items.size(); k++) {
                if (items.get(k) instanceof LInstr) {
                    changed += apply(items, k);
                }
            }
        }
        if (changed > 0) {
            f.rebuild(f.items());
        }
        changed += compareWithZero(f);
        return changed;
    }

    /**
     * {@code CMP #$0000} right after an instruction that set N and Z from A (in the same width;
     * stores between them change neither): the flags are those already, only the carry differs
     * ({@code CMP #0} sets it), so it goes where the carry is not read afterwards.
     */
    private static int compareWithZero(LFunction f) {
        java.util.Map<LBlock, Integer> out = RedundantLoads.flagsLiveOut(f);
        int removed = 0;
        for (LBlock b : f.blocks()) {
            List<LItem> items = b.items();
            // the flags live after each instruction
            java.util.Map<LInstr, Integer> liveAfter = new java.util.IdentityHashMap<>();
            int live = out.getOrDefault(b, RedundantLoads.N | RedundantLoads.Z | RedundantLoads.C | RedundantLoads.V);
            for (int k = items.size() - 1; k >= 0; k--) {
                if (items.get(k) instanceof LInstr in) {
                    liveAfter.put(in, live);
                    live = (live & ~RedundantLoads.writes(in)) | RedundantLoads.reads(in);
                }
            }
            for (int k = 0; k < items.size(); k++) {
                if (!(items.get(k) instanceof LInstr in) || !in.mnemonic().equals("cmp")
                        || !(in.op() instanceof LOperand.Imm zero && zero.expr().equals(in.m16() ? "$0000" : "$00"))
                        || (liveAfter.get(in) & RedundantLoads.C) != 0) {
                    continue;
                }
                LInstr before = null;
                for (int j = k - 1; j >= 0; j--) {
                    if (items.get(j) instanceof LLabel) {
                        break;
                    }
                    if (items.get(j) instanceof LInstr p) {
                        if (p.mnemonic().equals("sta") || p.mnemonic().equals("stx") || p.mnemonic().equals("sty")
                                || p.mnemonic().equals("stz")) {
                            continue;   // changes neither A nor the flags
                        }
                        before = p;
                        break;
                    }
                }
                if (before != null && before.m16() == in.m16() && setsNzFromA(before)) {
                    items.remove(k--);
                    removed++;
                }
            }
        }
        if (removed > 0) {
            f.rebuild(f.items());
        }
        return removed;
    }

    /** Whether i leaves N and Z describing A (as a CMP #0 would). */
    private static boolean setsNzFromA(LInstr i) {
        return switch (i.mnemonic()) {
            case "lda", "and", "ora", "eor", "adc", "sbc", "txa", "tya", "pla" -> true;
            case "asl", "lsr", "rol", "ror", "inc", "dec" -> i.op() instanceof LOperand.Acc;
            case "tax", "tay" -> i.m16() && i.x16();   // N and Z of the 16 bits copied, which are A's
            default -> false;
        };
    }

    /** The rules at the instruction at k; returns 1 if one applied. */
    private int apply(List<LItem> items, int k) {
        LInstr in = (LInstr) items.get(k);
        List<Integer> next = nextInstrs(items, k, 3);
        LInstr n1 = next.isEmpty() ? null : (LInstr) items.get(next.get(0));
        // STA p / LDX p
        if (n1 != null && in.mnemonic().equals("sta") && in.m16() && n1.x16() && n1.m16()
                && (n1.mnemonic().equals("ldx") || n1.mnemonic().equals("ldy"))
                && n1.op() != null && n1.op().equals(in.op()) && plain(in.op())) {
            items.set(next.get(0), new LInstr(n1.mnemonic().equals("ldx") ? "tax" : "tay", (String) null, n1.comment(), true, true, false));
            return 1;
        }
        // REP #$20 / SEP #$20 with 8-bit A before
        if (n1 != null && isWidth(in, "rep") && !in.m16() && isWidth(n1, "sep")) {
            items.remove((int) next.get(0));
            items.remove(k);
            return 1;
        }
        // LDA x / XBA / AND #$00FF
        if (next.size() >= 2 && in.mnemonic().equals("lda") && in.m16() && n1.mnemonic().equals("xba")
                && items.get(next.get(1)) instanceof LInstr n2 && n2.mnemonic().equals("and")
                && n2.op() instanceof LOperand.Imm ff && ff.expr().equals("$00FF") && n2.m16()) {
            String high = plusOne(in.op());
            if (high != null) {
                items.remove((int) next.get(0));
                items.set(k, in.withOperand(high));
                return 1;
            }
        }
        // LSR A × k, ASL A × m, m > k
        if (isShift(in, "lsr")) {
            List<Integer> run = new ArrayList<>();
            int j = k;
            while (j >= 0 && items.get(j) instanceof LInstr s && isShift(s, "lsr")) {
                run.add(j);
                j = nextInstr(items, j);
            }
            int lsr = run.size();
            int asl = 0;
            while (j >= 0 && items.get(j) instanceof LInstr s && isShift(s, "asl")) {
                run.add(j);
                asl++;
                j = nextInstr(items, j);
            }
            if (asl > lsr) {
                long mask = 0xFFFF & ~((1L << lsr) - 1);
                LInstr and = new LInstr("and", String.format("#$%04X", mask), in.comment(), true, in.x16(), false);
                // the first instruction becomes AND, the next lsr are dropped, asl - lsr shifts stay
                List<Integer> drop = run.subList(1, 2 * lsr);
                items.set(k, and);
                for (int d = drop.size() - 1; d >= 0; d--) {
                    items.remove((int) drop.get(d));
                }
                return 1;
            }
        }
        return 0;
    }

    private static boolean isShift(LInstr i, String m) {
        return i.mnemonic().equals(m) && i.op() instanceof LOperand.Acc && i.m16();
    }

    private static boolean isWidth(LInstr i, String m) {
        return i.mnemonic().equals(m) && i.op() instanceof LOperand.Imm k && k.expr().equals("$20");
    }

    /** The operand of the byte after x, or null if it cannot be written. */
    private String plusOne(LOperand x) {
        if (!(x instanceof LOperand.Mem m) || m.indirect() || m.base().startsWith("$")) {
            return null;
        }
        LOperand.Width width = m.width();
        int offset = m.offset() + 1;
        if (width == LOperand.Width.DP) {
            if (!m.frame() || m.index() != LOperand.Index.NONE) {
                return null;   // only the frame's direct page places are known to have room
            }
            width = frameStart + offset + 1 < 0x100 ? LOperand.Width.DP : LOperand.Width.ABS;
            if (width == LOperand.Width.ABS) {
                return new LOperand.Mem(width, absoluteFrame, offset, m.index(), false).text();
            }
        }
        return new LOperand.Mem(width, m.base(), offset, m.index(), false).text();
    }

    /** A plain memory operand (not indexed, not indirect, not an immediate). */
    private static boolean plain(LOperand op) {
        return op instanceof LOperand.Mem m ? m.index() == LOperand.Index.NONE && !m.indirect()
                : op instanceof LOperand.Raw r ? !r.text().startsWith("(") && !r.text().contains(",") : op instanceof LOperand.Acc;
    }

    /** The index of the next instruction after k in the list (comments skipped), -1 at a label or the end. */
    private static int nextInstr(List<LItem> items, int k) {
        for (int j = k + 1; j < items.size(); j++) {
            if (items.get(j) instanceof LInstr) {
                return j;
            }
            if (items.get(j) instanceof LLabel) {
                return -1;
            }
        }
        return -1;
    }

    private static List<Integer> nextInstrs(List<LItem> items, int k, int n) {
        List<Integer> out = new ArrayList<>();
        int j = k;
        while (out.size() < n && (j = nextInstr(items, j)) >= 0) {
            out.add(j);
        }
        return out;
    }
}
