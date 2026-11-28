package dampf.lir;

import dampf.target.SnesTarget;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

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
 * <li>{@code CMP #0}, {@code CPX #0} and {@code CPY #0} where N and Z describe that register
 *     already on every way there, and the carry the comparison sets is not read; X and Y loads
 *     between the instruction that set A's flags and a {@code CMP #0} move above it first.</li>
 * <li>{@code LDA x / XBA / AND #$00FF}, the high byte of the word at x, is the byte at x + 1:
 *     {@code LDA x+1 / AND #$00FF}. For a plain or X- or Y-indexed operand; a place in the
 *     direct page stays there only if x + 1 is still a whole word of it.</li>
 * </ul>
 */
public final class Peephole {
    private final String absoluteFrame;

    /** For the main code: frame places outside the direct page are written with {@code dampf_frame}. */
    public Peephole() {
        this(LOperand.FRAME);
    }

    /**
     * {@code absoluteFrame}: the symbol a frame place outside the direct page is written with
     * ({@code dampf_frame_irq} in an interrupt context, whose direct page is not 0). Which places
     * lie in the direct page the target says ({@link SnesTarget#inDirectPage}).
     */
    public Peephole(String absoluteFrame) {
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

    // what N and Z describe: a register in a width (several when they hold the same value)
    private static final int A8 = 1;
    private static final int A16 = 2;
    private static final int X8 = 4;
    private static final int X16 = 8;
    private static final int Y8 = 16;
    private static final int Y16 = 32;
    private static final int EVERY = 63;

    private static int a(boolean m16) {
        return m16 ? A16 : A8;
    }

    private static int x(boolean x16) {
        return x16 ? X16 : X8;
    }

    private static int y(boolean x16) {
        return x16 ? Y16 : Y8;
    }

    /**
     * {@code CMP #0} (and {@code CPX #0}, {@code CPY #0}) where N and Z describe that register in
     * the same width already on every way there, and the carry the comparison sets is not read
     * afterwards. Which register N and Z describe is followed over the blocks ({@link #describes}).
     * Before that, X and Y loads and transfers between the instruction that set A's flags and a
     * {@code CMP #0} move above it ({@link #indexOpsAbove}), so that the flags are A's again.
     */
    private static int compareWithZero(LFunction f) {
        int moved = indexOpsAbove(f);
        if (moved > 0) {
            f.rebuild(f.items());
        }
        Map<LBlock, Integer> in = describedAtEntry(f);
        Map<LBlock, Integer> out = RedundantLoads.flagsLiveOut(f);
        int removed = 0;
        for (LBlock b : f.blocks()) {
            List<LItem> items = b.items();
            // the flags live after each instruction
            Map<LInstr, Integer> liveAfter = new IdentityHashMap<>();
            int live = out.getOrDefault(b, RedundantLoads.N | RedundantLoads.Z | RedundantLoads.C | RedundantLoads.V);
            for (int k = items.size() - 1; k >= 0; k--) {
                if (items.get(k) instanceof LInstr i) {
                    liveAfter.put(i, live);
                    live = (live & ~RedundantLoads.writes(i)) | RedundantLoads.reads(i);
                }
            }
            int s = in.getOrDefault(b, 0);
            for (int k = 0; k < items.size(); k++) {
                if (!(items.get(k) instanceof LInstr i)) {
                    continue;
                }
                int need = withZero(i);
                if (need != 0 && (s & need) != 0 && (liveAfter.get(i) & RedundantLoads.C) == 0) {
                    items.remove(k--);
                    removed++;
                    continue;   // N and Z describe what they did, and more than the comparison's
                }
                s = describes(i, s);
            }
        }
        if (removed > 0) {
            f.rebuild(f.items());
        }
        return moved + removed;
    }

    /** For {@code CMP #0}, {@code CPX #0} and {@code CPY #0}: the register and width it tests; else 0. */
    private static int withZero(LInstr i) {
        if (!(i.op() instanceof LOperand.Imm k) || k.value() == null || k.value() != 0) {
            return 0;
        }
        return switch (i.mnemonic()) {
            case "cmp" -> a(i.m16());
            case "cpx" -> x(i.x16());
            case "cpy" -> y(i.x16());
            default -> 0;
        };
    }

    /**
     * What N and Z describe after i, from what they described before ({@code s}). An instruction
     * that writes a register sets N and Z from it (in its width; a transfer from both registers
     * where the widths agree); stores, pushes, the carry, jumps and branches keep them, and so does
     * {@code REP}/{@code SEP} without N and Z (the width a later test asks for must still be the
     * same); anything else, a call or a read-modify-write of memory, leaves nothing known.
     */
    static int describes(LInstr i, int s) {
        boolean m = i.m16();
        boolean w = i.x16();
        boolean same = m == w;   // A and the index registers of the same width
        return switch (i.mnemonic()) {
            case "lda", "and", "ora", "eor", "adc", "sbc", "pla" -> a(m);
            case "asl", "lsr", "rol", "ror", "inc", "dec" -> i.op() instanceof LOperand.Acc ? a(m) : 0;
            case "xba" -> A8;   // the new low byte, in any width
            case "tdc", "tcd" -> A16;
            case "cmp", "cpx", "cpy" -> withZero(i);
            case "txa" -> a(m) | (same ? x(w) : 0);
            case "tya" -> a(m) | (same ? y(w) : 0);
            case "tax" -> x(w) | (same ? a(m) : 0);
            case "tay" -> y(w) | (same ? a(m) : 0);
            case "ldx", "inx", "dex", "plx", "tsx" -> x(w);
            case "ldy", "iny", "dey", "ply" -> y(w);
            case "tyx", "txy" -> x(w) | y(w);
            case "sta", "stx", "sty", "stz", "pha", "phx", "phy", "phb", "phd", "phk", "php", "clc", "sec", "clv",
                 "nop", "txs", "tcs", "bra", "brl", "jmp", "jml", "beq", "bne", "bmi", "bpl", "bcc", "bcs", "bvc",
                 "bvs" -> s;
            case "rep", "sep" -> i.op() instanceof LOperand.Imm k && k.value() != null && (k.value() & 0x82) == 0 ? s : 0;
            default -> 0;
        };
    }

    /** What N and Z describe at the start of each block, on every way there (nothing at the entry). */
    private static Map<LBlock, Integer> describedAtEntry(LFunction f) {
        Map<LBlock, Integer> in = new IdentityHashMap<>();
        if (f.blocks().isEmpty()) {
            return in;
        }
        LBlock first = f.blocks().getFirst();
        in.put(first, 0);
        ArrayDeque<LBlock> work = new ArrayDeque<>(List.of(first));
        while (!work.isEmpty()) {
            LBlock b = work.removeFirst();
            int s = in.get(b);
            for (LInstr i : b.instrs()) {
                s = describes(i, s);
            }
            for (LBlock t : b.successors()) {
                Integer was = in.get(t);
                int now = t == first ? 0 : was == null ? s : was & s;
                if (was == null || now != was) {
                    in.put(t, now);
                    if (!work.contains(t)) {
                        work.add(t);
                    }
                }
            }
        }
        return in;
    }

    /**
     * {@code LDA x / LDX #k / CMP #0}: the flags are X's, though A's were set just before. Where
     * the loads and transfers of X and Y between them (stores may stand between too) read neither
     * A nor what the instruction that set A's flags or the stores write, and neither reads an X
     * or Y they write, they go above it: {@code LDX #k / LDA x}, and N and Z describe A at the
     * {@code CMP #0}. Only loads
     * of constants and of frame places move (no I/O register, no volatile variable is read in
     * another order). Returns how many groups moved.
     */
    private static int indexOpsAbove(LFunction f) {
        int moved = 0;
        for (LBlock b : f.blocks()) {
            List<LItem> items = b.items();
            for (int k = 0; k < items.size(); k++) {
                if (!(items.get(k) instanceof LInstr cmp) || !cmp.mnemonic().equals("cmp") || withZero(cmp) == 0) {
                    continue;
                }
                List<Integer> between = new ArrayList<>();
                List<LInstr> stores = new ArrayList<>();
                int writes = 0;   // X and Y, as Index.X / Index.Y bits
                int j = k - 1;
                for (; j >= 0; j--) {
                    if (items.get(j) instanceof LLabel) {
                        j = -1;
                        break;
                    }
                    if (!(items.get(j) instanceof LInstr p)) {
                        continue;
                    }
                    int wr = indexOnly(p);
                    if (wr != 0) {
                        between.add(j);
                        writes |= wr;
                    } else if (isStore(p)) {
                        stores.add(p);
                    } else {
                        break;
                    }
                }
                if (j < 0 || between.isEmpty() || !(items.get(j) instanceof LInstr setter)
                        || setter.m16() != cmp.m16() || describes(setter, 0) != a(cmp.m16())
                        || !onlyA(setter) || (indexReads(setter) & writes) != 0 || !crosses(between, items, stores, writes)) {
                    continue;
                }
                List<LItem> group = new ArrayList<>();
                for (int at : between) {   // from the last up: the indexes before stay
                    group.addFirst(items.remove(at));
                }
                items.addAll(j, group);
                moved++;
            }
        }
        return moved;
    }

    private static boolean isStore(LInstr i) {
        return switch (i.mnemonic()) {
            case "sta", "stz", "stx", "sty" -> true;
            default -> false;
        };
    }

    /**
     * Whether the index loads and transfers at {@code moved} may go above the stores: no store
     * reads an index register they write (as its index, or {@code STX}/{@code STY}), and none
     * writes a frame place they read (a store through an index or a pointer may write any).
     */
    private static boolean crosses(List<Integer> moved, List<LItem> items, List<LInstr> stores, int writes) {
        for (LInstr st : stores) {
            int reads = indexReads(st) | (st.mnemonic().equals("stx") ? 1 : 0) | (st.mnemonic().equals("sty") ? 2 : 0);
            if ((reads & writes) != 0) {
                return false;
            }
            for (int at : moved) {
                if (((LInstr) items.get(at)).op() instanceof LOperand.Mem load
                        && (!(st.op() instanceof LOperand.Mem to) || to.index() != LOperand.Index.NONE || to.indirect()
                            || !to.base().equals(load.base()) || Math.abs(to.offset() - load.offset()) < 2)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** For a load or transfer that writes only X or Y (and N and Z) and reads no A: which (1 X, 2 Y); else 0. */
    private static int indexOnly(LInstr i) {
        boolean source = i.op() == null || i.op() instanceof LOperand.Imm
                || i.op() instanceof LOperand.Mem m && m.frame() && !m.indirect();
        return switch (i.mnemonic()) {
            case "ldx" -> source ? 1 : 0;
            case "ldy" -> source ? 2 : 0;
            case "inx", "dex", "tyx" -> 1;
            case "iny", "dey", "txy" -> 2;
            default -> 0;
        };
    }

    /** Whether i writes only A (and flags), not memory, the stack or the other registers. */
    private static boolean onlyA(LInstr i) {
        return switch (i.mnemonic()) {
            case "lda", "and", "ora", "eor", "adc", "sbc" -> true;
            case "asl", "lsr", "rol", "ror", "inc", "dec" -> i.op() instanceof LOperand.Acc;
            default -> false;
        };
    }

    /** The index registers an instruction's operand reads (1 X, 2 Y). */
    private static int indexReads(LInstr i) {
        String t = i.operand() == null ? "" : i.operand().toLowerCase(java.util.Locale.ROOT).replace(" ", "");
        return (t.contains(",x") ? 1 : 0) | (t.contains(",y") ? 2 : 0);
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
            width = SnesTarget.inDirectPage(offset, 2) ? LOperand.Width.DP : LOperand.Width.ABS;
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
