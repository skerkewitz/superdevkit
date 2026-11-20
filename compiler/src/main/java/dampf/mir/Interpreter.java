package dampf.mir;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs an MIR module, with C's meaning of every operation. It is an independent definition of
 * what a program does, next to gcc: the tests compare both, so a wrong lowering from the AST shows
 * up here, before any 65816 code exists for it.
 *
 * <p>Memory: global objects lie one after the other from $10000; every call gets fresh frame
 * slots from $400000; addresses below $10000 are I/O, as on the SNES: stores there go to a
 * {@link PortListener}, loads read 0. Pointers are plain addresses.
 */
public final class Interpreter {
    /** Notified of stores to addresses below $10000 (dampf's runtime and the benchmark mark events there). */
    public interface PortListener {
        void write(int address, int value);
    }

    private static final int GLOBALS = 0x10000;
    private static final int FRAMES = 0x400000;
    private static final int SIZE = 0x800000;

    private final MModule module;
    private final byte[] mem = new byte[SIZE];
    private final Map<String, Integer> globalAddress = new HashMap<>();
    private final Map<String, Integer> globalSize = new HashMap<>();
    private int frameTop = FRAMES;
    private long steps;
    private final long maxSteps;
    private PortListener ports = (a, v) -> { };

    public Interpreter(MModule module, long maxSteps) {
        this.module = module;
        this.maxSteps = maxSteps;
        int next = GLOBALS;
        for (MModule.Global g : module.globals()) {
            globalAddress.put(g.symbol(), next);
            globalSize.put(g.symbol(), g.size());
            next += Math.max(1, g.size());
            if (next >= FRAMES) {
                throw new IllegalStateException("the globals do not fit the interpreter's memory");
            }
        }
        for (MModule.Global g : module.globals()) {
            int base = globalAddress.get(g.symbol());
            for (MModule.Datum d : g.init()) {
                long v = d.symbol() == null ? d.value() : globalAddress.get(d.symbol()) + d.value();
                store(base + d.offset(), d.size(), v);
            }
        }
    }

    /** Sees every value an instruction produces (tests check the range analysis with it). */
    public interface Observer {
        void value(Instr i, long bits);

        /** A parameter's value on entry to its function. */
        default void param(Value.Param p, long bits) {}
    }

    private Observer observer;

    public void observe(Observer o) {
        this.observer = o;
    }

    public void onPortWrite(PortListener listener) {
        this.ports = listener;
    }

    /** The address of a global object. */
    public int addressOf(String symbol) {
        return globalAddress.get(symbol);
    }

    /** {@code size} bytes at an address, little endian. */
    public long load(int address, int size) {
        long v = 0;
        for (int i = 0; i < size; i++) {
            int a = address + i;
            v |= (long) (a < GLOBALS ? 0 : mem[a] & 0xFF) << (8 * i);
        }
        return v;
    }

    private void store(int address, int size, long value) {
        for (int i = 0; i < size; i++) {
            int a = address + i;
            int b = (int) (value >> (8 * i)) & 0xFF;
            if (a < GLOBALS) {
                ports.write(a, b);
            } else {
                mem[a] = (byte) b;
            }
        }
    }

    /** Calls a function and returns its result (0 for void). */
    public long call(String function, List<Long> args) {
        MFunction f = module.function(function);
        if (f == null) {
            throw new IllegalArgumentException("no function " + function);
        }
        return run(f, args);
    }

    /** Per function, computed once: how many value slots a call needs, and each frame slot's position. */
    private record Layout(int values, Map<Value.Slot, Integer> slotOffsets, int frameSize) {}

    private final Map<MFunction, Layout> layouts = new HashMap<>();

    private Layout layout(MFunction f) {
        return layouts.computeIfAbsent(f, k -> {
            int n = 0;
            for (Value.Param p : f.params()) {
                p.index = n++;
            }
            for (Block b : f.blocks()) {
                for (Instr i : b.instrs()) {
                    i.index = n++;
                }
            }
            Map<Value.Slot, Integer> offsets = new HashMap<>();
            int size = 0;
            for (Value.Slot s : f.slots()) {
                offsets.put(s, size);
                size += Math.max(1, s.size());
            }
            return new Layout(n, offsets, size);
        });
    }

    /** One activation: its values and where its frame slots are. */
    private static final class Frame {
        final long[] values;
        final int slotBase;
        final Map<Value.Slot, Integer> slotOffsets;

        Frame(Layout l, int slotBase) {
            this.values = new long[l.values()];
            this.slotBase = slotBase;
            this.slotOffsets = l.slotOffsets();
        }
    }

    private long run(MFunction f, List<Long> args) {
        Layout l = layout(f);
        Frame frame = new Frame(l, frameTop);
        for (int i = 0; i < f.params().size(); i++) {
            Value.Param p = f.params().get(i);
            frame.values[p.index] = p.type().wrap(args.get(i));
            if (observer != null) {
                observer.param(p, frame.values[p.index]);
            }
        }
        int frameStart = frameTop;
        frameTop += l.frameSize();
        if (frameTop >= SIZE) {
            throw new IllegalStateException("out of frame memory");
        }
        try {
            Block prev = null;
            Block b = f.entry();
            long[] phiValues = new long[8];
            while (true) {
                // all φs of the block read their operands before any of them is set
                List<Instr> is = b.instrs();
                int nPhi = 0;
                while (nPhi < is.size() && is.get(nPhi) instanceof Instr.Phi p) {
                    int k = p.from.indexOf(prev);
                    if (k < 0) {
                        throw new IllegalStateException("phi in " + b + " has no operand for " + prev);
                    }
                    if (nPhi == phiValues.length) {
                        phiValues = java.util.Arrays.copyOf(phiValues, nPhi * 2);
                    }
                    phiValues[nPhi++] = eval(p.op(k), frame);
                }
                for (int k = 0; k < nPhi; k++) {
                    frame.values[is.get(k).index] = phiValues[k];
                    if (observer != null) {
                        observer.value(is.get(k), phiValues[k]);
                    }
                }
                Block next = null;
                for (int k = nPhi; k < is.size(); k++) {
                    Instr i = is.get(k);
                    if (++steps > maxSteps) {
                        throw new IllegalStateException("more than " + maxSteps + " MIR instructions: endless loop?");
                    }
                    switch (i) {
                        case Instr.Jump j -> next = j.target;
                        case Instr.Branch br -> next = eval(br.op(0), frame) != 0 ? br.ifTrue : br.ifFalse;
                        case Instr.Switch s -> {
                            long v = eval(s.op(0), frame);
                            next = s.otherwise;
                            MType t = s.op(0).type();
                            for (int c = 0; c < s.cases.size(); c++) {
                                if (t.wrap(s.cases.get(c)) == v) {
                                    next = s.targets.get(c);
                                    break;
                                }
                            }
                        }
                        case Instr.Ret r -> {
                            return r.operands().isEmpty() ? 0 : eval(r.op(0), frame);
                        }
                        case Instr.Unreachable u -> throw new IllegalStateException("a hint does not hold: "
                                + "DAMPF_RANGE" + (u.pos() != null ? " at " + u.pos() : "") + " in " + f.name());
                        default -> {
                            long v = execute(i, frame);
                            frame.values[i.index] = v;
                            if (observer != null && i.type() != MType.VOID) {
                                observer.value(i, v);
                            }
                        }
                    }
                }
                prev = b;
                b = next;
            }
        } finally {
            frameTop = frameStart;
        }
    }

    private long eval(Value v, Frame frame) {
        return switch (v) {
            case Value.Const k -> k.bits();
            case Value.Param p -> frame.values[p.index];
            case Value.GlobalAddr g -> globalAddress.get(g.symbol());
            case Value.SlotAddr s -> frame.slotBase + frame.slotOffsets.get(s.slot());
            case Value.Undef u -> 0;
            case Instr i -> frame.values[i.index];
        };
    }

    /**
     * The address of an access, checked: one based on a global or a frame slot must lie within
     * that object (C makes any other access undefined).
     */
    private int inObject(Instr access, int address, int size, Frame frame) {
        Gvn.Location at = Gvn.locate(access.op(0));
        if (at.named()) {
            long base = eval(at.base(), frame);
            int length = at.base() instanceof Value.GlobalAddr g ? globalSize.get(g.symbol()) : ((Value.SlotAddr) at.base()).slot().size();
            if (address < base || address + size > base + length) {
                throw new IllegalStateException("an access outside its object: " + Printer.instr(access) + " at offset "
                        + (address - base) + " of " + length + " bytes" + (access.pos() != null ? " (" + access.pos() + ")" : ""));
            }
        }
        return address;
    }

    private long execute(Instr i, Frame frame) {
        MType t = i.type();
        return switch (i) {
            case Instr.Bin b -> bin(b.op, t, eval(b.op(0), frame), eval(b.op(1), frame));
            case Instr.Cmp c -> cmp(c.pred, c.op(0).type(), eval(c.op(0), frame), eval(c.op(1), frame)) ? 1 : 0;
            case Instr.Cast c -> {
                long x = eval(c.op(0), frame);
                yield switch (c.kind) {
                    case ZEXT, TRUNC, PTRTOINT, INTTOPTR -> t.wrap(x);
                    case SEXT -> t.wrap(c.op(0).type().signed(x));
                };
            }
            case Instr.PtrAdd p -> MType.PTR.wrap(eval(p.op(0), frame) + p.op(1).type().signed(eval(p.op(1), frame)));
            case Instr.Load l -> load(inObject(l, address(l.op(0), frame), t.size, frame), t.size);
            case Instr.Store s -> {
                store(inObject(s, address(s.op(0), frame), s.op(1).type().size, frame), s.op(1).type().size, eval(s.op(1), frame));
                yield 0;
            }
            case Instr.Copy c -> {
                int to = address(c.op(0), frame);
                int from = address(c.op(1), frame);
                byte[] tmp = new byte[c.size];
                for (int k = 0; k < c.size; k++) {
                    tmp[k] = (byte) load(from + k, 1);
                }
                for (int k = 0; k < c.size; k++) {
                    store(to + k, 1, tmp[k]);
                }
                yield 0;
            }
            case Instr.Call c -> {
                List<Long> args = c.operands().stream().map(a -> eval(a, frame)).toList();
                yield t.wrap(run(module.function(c.callee), args));
            }
            default -> throw new IllegalStateException("cannot execute " + Printer.instr(i));
        };
    }

    /** {@code x op y} at type t, both already wrapped to t; division by zero throws ArithmeticException. */
    static long bin(Instr.BinOp op, MType t, long x, long y) {
        int bits = t.bits();
        return t.wrap(switch (op) {
            case ADD -> x + y;
            case SUB -> x - y;
            case MUL -> x * y;
            case UDIV -> {
                if (y == 0) {
                    throw new ArithmeticException("division by zero");
                }
                yield Long.divideUnsigned(x, y);
            }
            case UREM -> {
                if (y == 0) {
                    throw new ArithmeticException("division by zero");
                }
                yield Long.remainderUnsigned(x, y);
            }
            case SDIV -> {
                if (y == 0) {
                    throw new ArithmeticException("division by zero");
                }
                yield t.signed(x) / t.signed(y); // Java truncates towards zero, as C does
            }
            case SREM -> {
                if (y == 0) {
                    throw new ArithmeticException("division by zero");
                }
                yield t.signed(x) % t.signed(y);
            }
            case AND -> x & y;
            case OR -> x | y;
            case XOR -> x ^ y;
            case SHL -> y >= bits ? 0 : x << y;
            case LSHR -> y >= bits ? 0 : x >>> y;
            case ASHR -> t.signed(x) >> Math.min(y, bits - 1);
        });
    }

    /** A comparison of x and y of type t (both wrapped to t). */
    static boolean cmp(Instr.Pred pred, MType t, long x, long y) {
        long sx = t.signed(x);
        long sy = t.signed(y);
        return switch (pred) {
            case EQ -> x == y;
            case NE -> x != y;
            case ULT -> x < y;
            case ULE -> x <= y;
            case UGT -> x > y;
            case UGE -> x >= y;
            case SLT -> sx < sy;
            case SLE -> sx <= sy;
            case SGT -> sx > sy;
            case SGE -> sx >= sy;
        };
    }

    private int address(Value v, Frame frame) {
        long a = eval(v, frame);
        if (a < 0 || a >= SIZE) {
            throw new IllegalStateException(String.format("address $%X outside the interpreter's memory", a));
        }
        return (int) a;
    }
}
