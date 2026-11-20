package dampf.lir;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Short 16-bit stretches between 8-bit code run in 8 bits: in {@code SEP #$20 / STA o,Y / REP #$20 /
 * LDA x / SEC / SBC #1 / SEP #$20 / STA o+1,Y} the {@code REP} and the {@code SEP} go, and the
 * stretch computes in 8 bits ({@code SBC #$01}). Only its low byte is stored, and that is the same:
 * every instruction of the stretch gives the same low byte in 8 bits. The pair costs 6 cycles, and
 * every access to A in the stretch is a cycle cheaper.
 *
 * <p>The stretch lies in one block, between {@code REP #$20} (with A 8 bit before it) and
 * {@code SEP #$20}. It may hold loads, {@code AND}/{@code ORA}/{@code EOR}, {@code ADC}/{@code SBC}
 * whose carry does not come from the stretch (an 8-bit carry is another bit), {@code ASL A},
 * {@code INC A}/{@code DEC A}, {@code TXA}/{@code TYA} and instructions that do not touch A. Not
 * stores (8 bits write one byte instead of two), right shifts, compares, {@code XBA},
 * {@code TAX}/{@code TAY} (they copy the high byte too), calls, pushes, nor loads from hardware
 * registers (a 16-bit read touches two of them). After the {@code SEP} no
 * flag the stretch set may be read, and the high byte of A not before A is loaded again: it keeps
 * its old value now. Immediates of A get one byte, and {@code .a8}/{@code .a16} around the stretch
 * tell the assembler.
 */
public final class ByteStretches {
    private static final Set<String> SAME_LOW_BYTE = Set.of("lda", "and", "ora", "eor", "adc", "sbc", "asl", "inc", "dec",
            "txa", "tya", "clc", "sec");
    private static final Set<String> NO_A = Set.of("ldx", "ldy", "stx", "sty", "inx", "iny", "dex", "dey", "cpx", "cpy",
            "clc", "sec", "nop");

    private ByteStretches() {}

    /** Returns how many stretches became 8 bit. */
    public static int run(LFunction f) {
        Map<LBlock, Integer> flagsOut = RedundantLoads.flagsLiveOut(f);
        int changed = 0;
        for (LBlock b : f.blocks()) {
            List<LItem> items = b.items();
            for (int k = 0; k < items.size(); k++) {
                if (!(items.get(k) instanceof LInstr rep) || !isWidth(rep, "rep") || rep.m16()) {
                    continue;
                }
                int end = stretch(items, k);
                if (end < 0 || !afterIsSafe(items, end, flagsOut.getOrDefault(b, RedundantLoadsAll.ALL))) {
                    continue;
                }
                // rewrite: .a8, the stretch in 8 bits, .a16 where the SEP was (the code after it is
                // 8 bit as before; .a16 keeps the assembler's state as the code generator assumes)
                List<LItem> body = new ArrayList<>();
                body.add(directive(LDirective.Kind.A8, ".a8"));
                for (int j = k + 1; j < end; j++) {
                    LItem it = items.get(j);
                    body.add(it instanceof LInstr in ? narrow(in) : it);
                }
                body.add(directive(LDirective.Kind.A16, ".a16"));
                List<LItem> rest = new ArrayList<>(items.subList(end + 1, items.size()));
                items.subList(k, items.size()).clear();
                items.addAll(body);
                items.addAll(rest);
                k += body.size() - 1;
                changed++;
            }
        }
        if (changed > 0) {
            f.rebuild(f.items());
        }
        return changed;
    }

    private static LDirective directive(LDirective.Kind kind, String name) {
        return new LDirective(kind, null, List.of(), null, "        " + name);
    }

    /** The index of the SEP that ends a convertible stretch starting with the REP at k, or -1. */
    private static int stretch(List<LItem> items, int k) {
        boolean carryFromStretch = false;
        for (int j = k + 1; j < items.size(); j++) {
            LItem it = items.get(j);
            if (it instanceof LLabel) {
                return -1;
            }
            if (it instanceof LDirective d && (d.kind() == LDirective.Kind.A8 || d.kind() == LDirective.Kind.A16)) {
                return -1;
            }
            if (!(it instanceof LInstr in)) {
                continue;
            }
            if (isWidth(in, "sep")) {
                return j;
            }
            String m = in.mnemonic();
            LOperand op = in.op();
            boolean ok;
            if (NO_A.contains(m) && !m.startsWith("st") || m.equals("stx") || m.equals("sty")) {
                ok = true;
            } else if (SAME_LOW_BYTE.contains(m)) {
                ok = switch (m) {
                    case "asl", "inc", "dec" -> op instanceof LOperand.Acc;
                    case "adc", "sbc" -> !carryFromStretch;
                    default -> true;
                };
            } else {
                ok = false;
            }
            if (ok && op instanceof LOperand.Mem mem && mem.width() == LOperand.Width.LONG && mem.base().startsWith("$")) {
                ok = false;   // a hardware register: reading 8 bits instead of 16 reads one register less
            }
            if (!ok || op instanceof LOperand.Imm imm && imm.value() == null && !NO_A.contains(m)) {
                return -1;   // an immediate written as an expression: its low byte is not known here
            }
            switch (m) {
                case "adc", "sbc", "asl" -> carryFromStretch = true;
                case "clc", "sec" -> carryFromStretch = false;
                default -> { }
            }
        }
        return -1;
    }

    /** After the SEP at end: no flag of the stretch and not A's high byte are read before they are set again. */
    private static boolean afterIsSafe(List<LItem> items, int end, int flagsLiveAtBlockEnd) {
        int flags = RedundantLoads.N | RedundantLoads.Z | RedundantLoads.C | RedundantLoads.V;
        boolean highByte = true;   // still to be overwritten
        for (int j = end + 1; j < items.size(); j++) {
            if (!(items.get(j) instanceof LInstr in)) {
                continue;
            }
            if ((RedundantLoads.reads(in) & flags) != 0) {
                return false;
            }
            flags &= ~RedundantLoads.writes(in);
            if (highByte) {
                String m = in.mnemonic();
                if (in.m16() && (m.equals("lda") || m.equals("txa") || m.equals("tya") || m.equals("pla") || m.equals("tdc"))) {
                    highByte = false;   // A loaded in full
                } else if (readsWholeA(in)) {
                    return false;
                }
            }
            if (!highByte && flags == 0) {
                return true;
            }
            if (in.endsBlock()) {
                break;
            }
        }
        return !highByte && (flags & flagsLiveAtBlockEnd) == 0;
    }

    /** Whether i reads the high byte of A (16-bit use of A, XBA, the transfers that copy all of C). */
    private static boolean readsWholeA(LInstr i) {
        String m = i.mnemonic();
        return switch (m) {
            case "xba", "tax", "tay", "tcd", "tcs", "pha" -> true;
            case "jsl", "jsr", "rtl", "rts" -> true;   // a call or a return may use A in full
            default -> i.m16() && touchesA(m);
        };
    }

    private static boolean touchesA(String m) {
        return switch (m) {
            case "sta", "adc", "sbc", "and", "ora", "eor", "cmp", "bit", "asl", "lsr", "rol", "ror", "inc", "dec" -> true;
            default -> false;
        };
    }

    /** The instruction as it runs in 8 bits: an immediate of A gets its low byte. */
    private static LInstr narrow(LInstr in) {
        String op = in.operand();
        if (in.op() instanceof LOperand.Imm k && k.value() != null && !NO_A.contains(in.mnemonic())) {
            op = String.format("#$%02X", k.value() & 0xFF);
        }
        return new LInstr(in.mnemonic(), op, in.comment(), false, in.x16(), in.relaxable());
    }

    private static boolean isWidth(LInstr i, String m) {
        return i.mnemonic().equals(m) && i.op() instanceof LOperand.Imm k && k.expr().equals("$20");
    }

    /** All four flags (for blocks without a known live-out). */
    private static final class RedundantLoadsAll {
        static final int ALL = RedundantLoads.N | RedundantLoads.Z | RedundantLoads.C | RedundantLoads.V;
    }
}
