package dampf.backend;

import dampf.lir.LBlock;
import dampf.lir.LFunction;
import dampf.lir.LInstr;
import dampf.lir.LItem;
import dampf.lir.LLabel;
import dampf.target.MasterCycles;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The IRQ's window (ADR-6FRX1GYC, ADR-6FVWK4JE): the game's IRQ handler waits for the horizontal
 * blank before its row ({@code sdevkit_irq_wait_hblank}, a loop that polls HVBJOY) and then writes
 * PPU registers, which must land before the PPU fetches the row's first tiles. This finds, in the
 * handler's code, the master cycles from the end of the wait to the end of the last write to a
 * PPU register ($2100-$213F) on any way, the worst case, calls included.
 *
 * <p>The end of the wait: the instruction after the branch that leaves a loop reading HVBJOY
 * ($4212), wherever dampf inlined the wait, or after a {@code JSL} to the wait, plus the wait's
 * own way from its loop to its {@code RTL}. A second wait on the way starts the count anew (its
 * writes are for the next row). A write is a store to a long address in $2100-$213F, given as a
 * number, with or without an index; a callee's writes count with their own time from its entry, a
 * function written in assembly as if it wrote at its end. Writes through a pointer are not seen. A
 * loop between the wait and a write leaves the window without a bound.
 */
public final class IrqWindow {
    /**
     * The master cycles from the latest return of the wait to the last moment a write still shows
     * cleanly in the row, without HDMA on that line: hClock 1,208 to about 1,366, measured in Mesen
     * (ADR-6FRX1GYC; about 25 CPU cycles).
     */
    public static final int WINDOW = 158;

    /** No write after the wait. */
    public static final long NONE = -1;
    /** A loop between the wait and a write. */
    static final long UNBOUNDED = Long.MAX_VALUE / 4;

    /** What a function does, seen from its entry: its last write and its return, in master cycles. */
    record Reach(long lastWrite, long toReturn) {
        static final Reach NOTHING = new Reach(NONE, NONE);
    }

    /**
     * The worst case for one handler: the master cycles to its last write after a wait, or
     * {@link #NONE} when it writes nothing after one, {@link #UNBOUNDED} with a loop on the way;
     * {@code waits}: whether it waits at all.
     */
    public record Result(boolean waits, long worst, int hdmaChannels, int hdmaBytes) {
        public boolean bounded() {
            return worst < UNBOUNDED;
        }

        /** The same, with the HDMA the handler declares for its line (SDEVKIT_IRQ_HDMA). */
        public Result withHdma(int channels, int bytes) {
            return new Result(waits, worst, channels, bytes);
        }

        /** The master cycles the writes have after the wait: the window less the declared HDMA. */
        public long room() {
            return WINDOW - hdma(hdmaChannels, hdmaBytes);
        }
    }

    /**
     * The master cycles HDMA takes on a line, the worst case for these channels and bytes: 18 once
     * when a channel runs, and per channel 8 and 8 more for a new entry of its table (its line
     * count byte), and 8 for each byte written (fullsnes, "SNES Timings"; ADR-6FVWK4JE).
     */
    public static int hdma(int channels, int bytes) {
        int n = Integer.bitCount(channels & 0xFF);
        return n == 0 ? 0 : HDMA_LINE + n * HDMA_CHANNEL + bytes * HDMA_BYTE;
    }

    static final int HDMA_LINE = 18;
    static final int HDMA_CHANNEL = 16;
    static final int HDMA_BYTE = 8;

    private final dampf.target.MasterCycles.Code code;
    private final boolean fastRom;
    private final String wait;
    private final Function<String, LFunction> codeOf;
    private final Function<String, Timing.Bound> boundOf;
    private final Map<String, Reach> reach = new HashMap<>();

    /**
     * @param wait    the label of the wait function ({@code _sdevkit_irq_wait_hblank})
     * @param codeOf  a function's code by label, null for one without code here (the runtime's,
     *                one written in assembly)
     * @param boundOf a function's best and worst case
     */
    IrqWindow(dampf.target.MasterCycles.Code code, String wait, Function<String, LFunction> codeOf, Function<String, Timing.Bound> boundOf) {
        this.code = code;
        this.fastRom = code.fastRom();
        this.wait = wait;
        this.codeOf = codeOf;
        this.boundOf = boundOf;
    }

    /** The window of the handler with this label. */
    Result of(String handler) {
        Code c = new Code(codeOf.apply(handler));
        long worst = NONE;
        boolean waits = false;
        for (int i = 0; i < c.items.size(); i++) {
            if (!(c.items.get(i) instanceof LInstr in)) {
                continue;
            }
            int start = -1;
            long first = 0;
            if (in.isCall() && isWait(in.operand())) {
                start = i + 1;
                first = waitTail(in.operand());
            } else if (readsHvbjoy(in)) {
                int exit = c.loopExit(i);
                if (exit >= 0) {
                    start = exit;
                }
            }
            if (start < 0) {
                continue;
            }
            waits = true;
            long w = new Walk(c).from(start).lastWrite();
            if (w != NONE) {
                worst = Math.max(worst, w >= UNBOUNDED ? UNBOUNDED : first + w);
            }
        }
        return new Result(waits, worst, 0, 0);
    }

    /** The wait function's own way from the end of its loop to the end of its RTL. */
    private long waitTail(String label) {
        LFunction f = codeOf.apply(label);
        if (f == null) {
            return 0;
        }
        Code c = new Code(f);
        long tail = 0;
        for (int i = 0; i < c.items.size(); i++) {
            if (c.items.get(i) instanceof LInstr in && readsHvbjoy(in)) {
                int exit = c.loopExit(i);
                if (exit >= 0) {
                    long r = new Walk(c).from(exit).toReturn();
                    tail = Math.max(tail, r == NONE ? 0 : r);
                }
            }
        }
        return tail;
    }

    /** What a callee does from its entry; a function without code here writes at its end at the latest. */
    private Reach callee(String label) {
        Reach known = reach.get(label);
        if (known != null) {
            return known;
        }
        LFunction f = codeOf.apply(label);
        Timing.Bound b = boundOf.apply(label);
        long worst = b == null || !b.bounded() ? UNBOUNDED : b.worst();
        Reach r;
        if (f == null) {
            boolean runtime = Timing.runtime(fastRom).containsKey(label) || Timing.runtimeSoft(fastRom).containsKey(label);
            r = new Reach(runtime ? NONE : worst, worst);
        } else {
            reach.put(label, new Reach(UNBOUNDED, UNBOUNDED));   // (no recursion; a guard all the same)
            Reach walked = new Walk(new Code(f)).from(0);
            r = new Reach(walked.lastWrite(), worst);
        }
        reach.put(label, r);
        return r;
    }

    /** The wait, or its copy for the IRQ's context ({@code __irq} in front, dampf.check.ContextCopies). */
    private boolean isWait(String label) {
        return wait.equals(label) || ("__irq" + wait).equals(label);
    }

    /** A store into the PPU's registers, $2100-$213F, at a long address given as a number. */
    static boolean writesPpu(LInstr in) {
        switch (in.mnemonic()) {
            case "sta", "stx", "sty", "stz", "inc", "dec", "asl", "lsr", "rol", "ror", "tsb", "trb" -> { }
            default -> {
                return false;
            }
        }
        String mode = in.mode();
        if (!mode.equals("long") && !mode.equals("long,x")) {
            return false;
        }
        Integer a = MasterCycles.longAddress(in.operand());
        if (a == null) {
            return false;
        }
        int bank = a >> 16;
        int offset = a & 0xFFFF;
        return (bank < 0x40 || bank >= 0x80 && bank < 0xC0) && offset >= 0x2100 && offset <= 0x213F;
    }

    /** A read of HVBJOY ($4212), the horizontal blank's flag. */
    static boolean readsHvbjoy(LInstr in) {
        if (!in.mode().equals("long") || writesPpu(in) || in.mnemonic().startsWith("st")) {
            return false;
        }
        Integer a = MasterCycles.longAddress(in.operand());
        return a != null && (a & 0xFFFF) == 0x4212 && ((a >> 16) < 0x40 || (a >> 16) >= 0x80 && (a >> 16) < 0xC0);
    }

    /** A function's instructions and labels in layout order. */
    private static final class Code {
        final LFunction f;
        final List<LItem> items = new ArrayList<>();
        final Map<String, Integer> labelAt = new HashMap<>();

        Code(LFunction f) {
            this.f = f;
            for (LBlock b : f.blocks()) {
                for (LItem i : b.items()) {
                    if (i instanceof LInstr || i instanceof LLabel) {
                        if (i instanceof LLabel l) {
                            labelAt.put(l.name(), items.size());
                        }
                        items.add(i);
                    }
                }
            }
        }

        /**
         * Where the loop that reads HVBJOY at {@code at} is left: after the first conditional branch
         * behind the read that goes back to it or before it (the loop goes on while the flag is
         * clear), or at the target of one that goes forward; -1 if the read is in no such loop.
         */
        int loopExit(int at) {
            for (int j = at + 1; j < items.size() && j <= at + 8; j++) {
                if (items.get(j) instanceof LInstr b && b.isConditionalBranch()) {
                    Integer t = labelAt.get(b.operand());
                    if (t == null) {
                        return -1;
                    }
                    return t <= at ? j + 1 : t;
                }
            }
            return -1;
        }
    }

    /** The longest ways from one place of a function's code to its PPU writes and its return. */
    private final class Walk {
        private static final int CLEAN = Integer.MAX_VALUE;
        private final Code c;
        private final Map<Integer, Reach> memo = new HashMap<>();
        /** The places on the way being followed, with their depth. */
        private final Map<Integer, Integer> open = new HashMap<>();

        /** A result and the shallowest open place it went back to (a loop), or CLEAN. */
        private record Step(Reach reach, int loop) {}

        Walk(Code c) {
            this.c = c;
        }

        Reach from(int i) {
            return visit(i).reach();
        }

        private Step visit(int i) {
            if (i >= c.items.size()) {
                return new Step(Reach.NOTHING, CLEAN);
            }
            Reach known = memo.get(i);
            if (known != null) {
                return new Step(known, CLEAN);
            }
            Integer back = open.get(i);
            if (back != null) {
                return new Step(Reach.NOTHING, back);   // round a loop: what lies beyond is counted at its head
            }
            int depth = open.size();
            open.put(i, depth);
            Step r = step(i, depth);
            open.remove(i);
            if (r.loop() == CLEAN) {
                memo.put(i, r.reach());
            }
            return r;
        }

        private Step step(int i, int depth) {
            if (!(c.items.get(i) instanceof LInstr in)) {
                return close(visit(i + 1), depth);
            }
            if (readsHvbjoy(in) || in.isCall() && isWait(in.operand())) {
                return new Step(Reach.NOTHING, CLEAN);   // another wait: its writes count from its own end
            }
            long own = in.master(code).worst();
            long write = writesPpu(in) ? own : NONE;
            long ret = NONE;
            List<long[]> next = new ArrayList<>();   // {item, cost to get there}
            if (in.isReturn()) {
                ret = own;
            } else if (in.isCall()) {
                Reach callee = callee(in.operand());
                Timing.Bound b = boundOf.apply(in.operand());
                long worst = b == null || !b.bounded() ? UNBOUNDED : b.worst();
                if (callee.lastWrite() != NONE) {
                    write = Math.max(write, Timing.add(own, callee.lastWrite()));
                }
                next.add(new long[] {i + 1, Timing.add(own, worst)});
            } else if (in.isConditionalBranch()) {
                next.add(new long[] {target(in), in.masterTaken(code).worst()});
                next.add(new long[] {i + 1, own});
            } else if (in.isTableJump()) {
                for (String e : c.f.tableTargets(in.tableLabel())) {
                    Integer t = c.labelAt.get(e);
                    if (t != null) {
                        next.add(new long[] {t, own});
                    }
                }
            } else if (in.isJump()) {
                next.add(new long[] {target(in), own});
            } else {
                next.add(new long[] {i + 1, own});
            }
            int loop = CLEAN;
            for (long[] n : next) {
                if (n[0] < 0) {
                    return new Step(new Reach(UNBOUNDED, UNBOUNDED), CLEAN);
                }
                Step r = visit((int) n[0]);
                loop = Math.min(loop, r.loop());
                if (r.reach().lastWrite() != NONE) {
                    write = Math.max(write, Timing.add(n[1], r.reach().lastWrite()));
                }
                if (r.reach().toReturn() != NONE) {
                    ret = Math.max(ret, Timing.add(n[1], r.reach().toReturn()));
                }
            }
            return close(new Step(new Reach(write, ret), loop), depth);
        }

        /** At the head of a loop: whatever is reached from it may come after any number of rounds. */
        private Step close(Step s, int depth) {
            if (s.loop() != depth) {
                return s;
            }
            Reach r = s.reach();
            return new Step(new Reach(r.lastWrite() == NONE ? NONE : UNBOUNDED, r.toReturn() == NONE ? NONE : UNBOUNDED), CLEAN);
        }

        private int target(LInstr in) {
            Integer t = c.labelAt.get(in.operand());
            return t == null ? -1 : t;
        }
    }
}
