package dampf.lir;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Removes loads of a value the register holds already, by what {@link RegContents} finds on every
 * way to the load. A load also sets N and Z: where a later instruction reads them (a branch), an
 * {@code LDA} becomes {@code CMP #0}, which sets them the same for the value in A, unless the carry
 * it changes is read too; then the load stays. {@code LDX} and {@code LDY} go only where their
 * flags are not read.
 */
public final class RedundantLoads {
    static final int N = 1;
    static final int Z = 2;
    static final int C = 4;
    static final int V = 8;
    private static final int ALL = N | Z | C | V;

    private final RegContents contents;

    public RedundantLoads() {
        this.contents = new RegContents();
    }

    /** Removes (or turns into CMP #0) the redundant loads; returns how many changed. */
    public int run(LFunction f) {
        int hoisted = hoist(f);
        return hoisted + remove(f);
    }

    /**
     * A load that starts a block (its flags not read) and is redundant on some ways in, not on
     * others that only jump there: it moves to the end of those, so it is redundant on all (a
     * loop head that loads its counter, which the back edge holds in A already and the entry
     * does not). No way gets a load more; the ways that held it lose one.
     */
    int hoist(LFunction f) {
        Map<LBlock, RegContents.State> entries = contents.entries(f);
        Map<LBlock, Integer> flagsOut = flagsLiveOut(f);
        Map<LBlock, List<LBlock>> preds = new IdentityHashMap<>();
        for (LBlock b : f.blocks()) {
            for (LBlock t : b.successors()) {
                preds.computeIfAbsent(t, k -> new ArrayList<>()).add(b);
            }
        }
        int changed = 0;
        for (LBlock b : f.blocks()) {
            List<LBlock> in = preds.getOrDefault(b, List.of());
            List<LInstr> code = b.instrs();
            if (in.size() < 2 || code.isEmpty() || in.contains(b) || b == f.blocks().getFirst()) {
                continue;
            }
            LInstr load = code.getFirst();
            LOperand op = load.op();
            if (op == null || !(load.mnemonic().equals("lda") && load.m16() || load.mnemonic().equals("ldx")
                    || load.mnemonic().equals("ldy"))) {
                continue;
            }
            int live = flagsOut.getOrDefault(b, 0);
            for (int k = code.size() - 1; k > 0; k--) {
                live = (live & ~writes(code.get(k))) | reads(code.get(k));
            }
            if ((live & (N | Z)) != 0) {
                continue;
            }
            List<LBlock> missing = new ArrayList<>();
            boolean someHold = false;
            boolean ok = true;
            for (LBlock p : in) {
                RegContents.State st = entries.get(p);
                if (st == null) {
                    ok = false;
                    break;
                }
                for (LInstr i : p.instrs()) {
                    st = contents.after(i, st);
                }
                boolean held = switch (load.mnemonic()) {
                    case "lda" -> st.a().contains(op);
                    case "ldx" -> op.equals(st.x());
                    default -> op.equals(st.y());
                };
                if (held) {
                    someHold = true;
                    continue;
                }
                LInstr last = p.last();
                boolean jumps = last != null && switch (last.mnemonic()) {
                    case "jmp", "bra", "brl" -> true;
                    default -> false;
                };
                if (p.successors().size() != 1 || longBranch(p, preds)
                        || last != null && !jumps && !last.fallsThrough()
                        || last != null && (last.m16() != load.m16() || last.x16() != load.x16())) {
                    ok = false;
                    break;
                }
                missing.add(p);
            }
            if (!ok || !someHold || missing.isEmpty()) {
                continue;
            }
            for (LBlock p : missing) {
                List<LItem> items = p.items();
                LInstr last = p.last();
                boolean jumps = last != null && !last.fallsThrough();
                int at = jumps ? items.lastIndexOf(last) : items.size();
                items.add(at, new LInstr(load.mnemonic(), op, null, load.m16(), load.x16(), false));
                changed++;
            }
        }
        if (changed > 0) {
            f.rebuild(f.items());
        }
        return changed;
    }

    /**
     * A block that only jumps and that a conditional branch falls through into: the long form of
     * that branch, which BranchRelax folds back into it unless something is put in between.
     */
    private static boolean longBranch(LBlock p, Map<LBlock, List<LBlock>> preds) {
        if (p.instrs().size() != 1 || p.last().fallsThrough()) {
            return false;
        }
        for (LBlock q : preds.getOrDefault(p, List.of())) {
            if (q.last() != null && q.last().isConditionalBranch()) {
                return true;
            }
        }
        return false;
    }

    private int remove(LFunction f) {
        Map<LBlock, RegContents.State> entries = contents.entries(f);
        Map<LBlock, Integer> flagsOut = flagsLiveOut(f);
        int changed = 0;
        for (LBlock b : f.blocks()) {
            RegContents.State s = entries.get(b);
            if (s == null) {
                continue;   // not reached
            }
            List<LItem> items = b.items();
            // the flags live after each instruction of the block
            Map<LInstr, Integer> liveAfter = new IdentityHashMap<>();
            int live = flagsOut.getOrDefault(b, 0);
            for (int k = items.size() - 1; k >= 0; k--) {
                if (items.get(k) instanceof LInstr in) {
                    liveAfter.put(in, live);
                    live = (live & ~writes(in)) | reads(in);
                }
            }
            for (int k = 0; k < items.size(); k++) {
                if (!(items.get(k) instanceof LInstr in)) {
                    continue;
                }
                LOperand op = in.op();
                int flags = liveAfter.get(in);
                boolean held = op != null && switch (in.mnemonic()) {
                    case "lda" -> in.m16() && s.a().contains(op);
                    case "ldx" -> op.equals(s.x());
                    case "ldy" -> op.equals(s.y());
                    default -> false;
                };
                if (held && (flags & (N | Z)) == 0) {
                    items.remove(k--);
                    changed++;
                    continue;   // the registers hold what they held
                }
                if (held && in.mnemonic().equals("lda") && (flags & C) == 0) {
                    LInstr cmp = new LInstr("cmp", "#$0000", in.comment(), true, in.x16(), false);
                    items.set(k, cmp);
                    changed++;
                    s = contents.after(cmp, s);
                    continue;
                }
                // LDA x / ADC y (or AND, ORA, EOR) with A holding y: ADC x (the operation is
                // commutative and sets the flags itself; the carry an ADC reads is not touched by LDA)
                int n = nextInstr(items, k);
                if (op != null && in.mnemonic().equals("lda") && in.m16() && n >= 0
                        && items.get(n) instanceof LInstr next && next.m16() && next.op() != null
                        && COMMUTATIVE.contains(next.mnemonic()) && s.a().contains(next.op())
                        && !next.op().equals(op)) {
                    LInstr fused = next.withOperand(op.text());
                    liveAfter.put(fused, liveAfter.get(next));
                    items.set(n, fused);
                    items.remove(k--);
                    changed++;
                    continue;
                }
                s = contents.after(in, s);
            }
        }
        if (changed > 0) {
            f.rebuild(f.items());
        }
        return changed;
    }

    private static final java.util.Set<String> COMMUTATIVE = java.util.Set.of("adc", "and", "ora", "eor");

    /** The next instruction after k in the block (comments skipped), -1 if none. */
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

    /** The flags live at the end of each block. */
    static Map<LBlock, Integer> flagsLiveOut(LFunction f) {
        Map<LBlock, List<LBlock>> preds = new IdentityHashMap<>();
        for (LBlock b : f.blocks()) {
            for (LBlock s : b.successors()) {
                preds.computeIfAbsent(s, k -> new ArrayList<>()).add(b);
            }
        }
        Map<LBlock, Integer> in = new IdentityHashMap<>();
        Map<LBlock, Integer> out = new IdentityHashMap<>();
        ArrayDeque<LBlock> work = new ArrayDeque<>(f.blocks().reversed());
        while (!work.isEmpty()) {
            LBlock b = work.removeFirst();
            int live = 0;
            for (LBlock s : b.successors()) {
                live |= in.getOrDefault(s, 0);
            }
            out.put(b, live);
            List<LInstr> code = b.instrs();
            for (int k = code.size() - 1; k >= 0; k--) {
                LInstr i = code.get(k);
                live = (live & ~writes(i)) | reads(i);
            }
            Integer was = in.get(b);
            if (was == null || was != live) {
                in.put(b, live);
                for (LBlock p : preds.getOrDefault(b, List.of())) {
                    if (!work.contains(p)) {
                        work.add(p);
                    }
                }
            }
        }
        return out;
    }

    /** The flags an instruction reads. */
    static int reads(LInstr i) {
        return switch (i.mnemonic()) {
            case "beq", "bne" -> Z;
            case "bmi", "bpl" -> N;
            case "bcc", "bcs", "adc", "sbc", "rol", "ror" -> C;
            case "bvc", "bvs" -> V;
            case "php" -> ALL;
            case "lda", "ldx", "ldy", "sta", "stx", "sty", "stz", "and", "ora", "eor", "cmp", "cpx", "cpy", "bit",
                 "inc", "dec", "inx", "iny", "dex", "dey", "asl", "lsr", "tax", "tay", "txa", "tya", "tsx", "txs",
                 "tcd", "tdc", "xba", "pha", "phx", "phy", "pla", "plx", "ply", "clc", "sec", "clv", "rep", "sep",
                 "jmp", "jml", "bra", "brl", "jsl", "jsr", "rtl", "rts", "trb", "tsb", "nop" -> 0;
            default -> ALL;   // not known here: may read anything
        };
    }

    /** The flags an instruction sets for sure. */
    static int writes(LInstr i) {
        return switch (i.mnemonic()) {
            case "lda", "ldx", "ldy", "and", "ora", "eor", "inc", "dec", "inx", "iny", "dex", "dey", "tax", "tay",
                 "txa", "tya", "tsx", "tcd", "tdc", "xba", "pla", "plx", "ply" -> N | Z;
            case "cmp", "cpx", "cpy", "asl", "lsr", "rol", "ror" -> N | Z | C;
            case "adc", "sbc" -> ALL;
            case "bit" -> i.op() instanceof LOperand.Imm ? Z : N | V | Z;
            case "clc", "sec" -> C;
            case "clv" -> V;
            case "trb", "tsb" -> Z;
            case "jsl", "jsr" -> ALL;   // the callee leaves them as it likes; nothing after a call reads them
            default -> 0;
        };
    }
}
