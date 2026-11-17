package dampf.mir;

import dampf.ast.Init;
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
            next += Math.max(1, g.size());
            if (next >= FRAMES) {
                throw new IllegalStateException("the globals do not fit the interpreter's memory");
            }
        }
        for (MModule.Global g : module.globals()) {
            if (g.init() == null) {
                continue;
            }
            int base = globalAddress.get(g.symbol());
            for (Init.Item item : g.init().items()) {
                long v = item.address() == null ? item.value() : globalAddress.get(item.address().label()) + item.addressOffset();
                store(base + item.offset(), item.size(), v);
            }
        }
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
                        default -> frame.values[i.index] = execute(i, frame);
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

    private long execute(Instr i, Frame frame) {
        MType t = i.type();
        return switch (i) {
            case Instr.Bin b -> {
                long x = eval(b.op(0), frame);
                long y = eval(b.op(1), frame);
                int bits = t.bits();
                yield t.wrap(switch (b.op) {
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
            case Instr.Cmp c -> {
                MType ot = c.op(0).type();
                long x = eval(c.op(0), frame);
                long y = eval(c.op(1), frame);
                long sx = ot.signed(x);
                long sy = ot.signed(y);
                boolean r = switch (c.pred) {
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
                yield r ? 1 : 0;
            }
            case Instr.Cast c -> {
                long x = eval(c.op(0), frame);
                yield switch (c.kind) {
                    case ZEXT, TRUNC, PTRTOINT, INTTOPTR -> t.wrap(x);
                    case SEXT -> t.wrap(c.op(0).type().signed(x));
                };
            }
            case Instr.PtrAdd p -> MType.PTR.wrap(eval(p.op(0), frame) + MType.I32.signed(eval(p.op(1), frame)));
            case Instr.Load l -> load(address(l.op(0), frame), t.size);
            case Instr.Store s -> {
                store(address(s.op(0), frame), s.op(1).type().size, eval(s.op(1), frame));
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

    private int address(Value v, Frame frame) {
        long a = eval(v, frame);
        if (a < 0 || a >= SIZE) {
            throw new IllegalStateException(String.format("address $%X outside the interpreter's memory", a));
        }
        return (int) a;
    }
}
