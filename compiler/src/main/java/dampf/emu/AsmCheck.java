package dampf.emu;

import dampf.target.SnesTarget;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Runs each function written in assembly (DAMPF_ASM) against its C body in dampf's emulator, on
 * many inputs: "this assembly computes what this C computes" is a fact hint, and a fact hint is
 * checked (docs/hints.md). The ROM holds the program, the assembly, and the C body compiled by
 * dampf under another label; nothing calls the latter.
 *
 * <p>The ROM runs its start-up until the program's entry; from that state each input calls the C
 * body and then the assembly, each from the same memory: the parameters (edge values, then random
 * ones from a fixed seed, within a parameter's {@code DAMPF_RANGE}), the globals the C body reads
 * (as after start-up, all zero, all ones, then random bytes; not those holding pointers), and the
 * part of bank $7F the program's variables leave free (all of it in most programs, ADR-6FVM9QWS),
 * filled with random bytes: a pointer parameter points into it.
 * A, X and Y start with the same random values for both. After each call the result (the bytes
 * of its type: the low byte of A for 8 bits, A and X for 32 bits and pointers) and all of RAM from
 * $7E:2000 to $7F:FFFF (globals, bank $7F) must be the same, and the assembly's cycles must lie
 * within the bounds of its static analysis (a bound comment set too low shows here). The frames,
 * the scratch registers and the stack ($7E:0000-$1FFF) are not compared; neither are writes to
 * I/O registers.
 */
public final class AsmCheck {
    /** How many inputs per function. */
    public static final int INPUTS = 256;
    /** Master cycles for the start-up (it clears all of RAM) and for one call. */
    private static final long BOOT = 200_000_000L;
    private static final long CALL = 400_000_000L;

    private AsmCheck() {}

    /**
     * A parameter: its name, size (1, 2 or 4 bytes), whether signed, whether a pointer, and the
     * values it takes (lo..hi, in its type's reading).
     */
    public record Param(String name, int size, boolean signed, boolean pointer, long lo, long hi) {}

    /** A global in RAM: name, address in WRAM (bank $7E from 0, $7F from $10000), size, whether the check gives it other values. */
    public record Global(String name, int address, int size, boolean varied) {}

    /**
     * One assembly function: its name (in C), its label's and its C body's CPU addresses, its
     * parameters with the addresses of their places for each, the size of its result (0: void),
     * whether that is a pointer, and its bounds in master cycles from the static analysis.
     */
    public record Function(String name, int asmAddress, int cAddress, List<Param> params, List<Integer> asmPlaces,
                           List<Integer> cPlaces, int resultSize, boolean resultPointer, long best, long worst) {}

    /** What the check measured: inputs, and the master cycles of the assembly and of the C body (fewest, most). */
    public record Report(String name, int inputs, long asmBest, long asmWorst, long cBest, long cWorst) {}

    /** The assembly does something else than the C body, or breaks its bounds. */
    public static final class Failure extends Exception {
        private static final long serialVersionUID = 1L;
        private final String function;

        Failure(String function, String message) {
            super(message);
            this.function = function;
        }

        /** The function (its name in C). */
        public String function() {
            return function;
        }
    }

    /** The fewest free bytes in bank $7F the check needs for what pointer parameters point to. */
    public static final int SCRATCH = 0x1000;

    /**
     * Runs the check. {@code entry}: the CPU address of the program's entry function, where the
     * start-up ends; {@code globals}: the program's globals in RAM (for the messages, and those
     * marked varied for the inputs); {@code scratch}: where the free part of bank $7F starts
     * (after the variables there, at least {@link #SCRATCH} bytes before its end).
     */
    public static List<Report> run(byte[] rom, int entry, List<Function> functions, List<Global> globals, int inputs, int scratch)
            throws Failure {
        if (scratch < 0 || scratch > 0x10000 - SCRATCH) {
            throw new IllegalArgumentException("the scratch memory must leave " + SCRATCH + " bytes of bank $7F");
        }
        Snes snes = new Snes(rom);
        Cpu65816 cpu = snes.cpu();
        cpu.reset();
        if (!snes.resume(BOOT, a -> {
            if (a == entry) {
                snes.stop();
            }
        })) {
            throw new IllegalStateException("the start-up of the check's ROM did not reach the program's entry");
        }
        snes.write(0x004200, 0);   // no NMI or IRQ during the calls
        cpu.i = true;
        byte[] start = snes.wram().clone();
        int stack = cpu.s;
        List<Report> reports = new ArrayList<>();
        int n = 0;
        for (Function f : functions) {
            reports.add(check(snes, start, stack, entry, f, globals, inputs, scratch, 0x5EED_0000L + n++));
        }
        return reports;
    }

    private record Outcome(long result, byte[] ram, long cycles) {}

    private static Report check(Snes snes, byte[] start, int stack, int returnTo, Function f, List<Global> globals,
                                int inputs, int scratch, long seed) throws Failure {
        Random random = new Random(seed);
        // pointer parameters point into the middle of the free part of bank $7F
        long pointerBase = (long) SnesTarget.SECOND_VARIABLE_BANK << 16 | (scratch + 0x10000) / 2;
        List<long[]> tuples = inputs(f.params(), inputs, random, pointerBase);
        byte[] bank7f = new byte[0x10000 - scratch];
        long asmBest = Long.MAX_VALUE;
        long asmWorst = 0;
        long cBest = Long.MAX_VALUE;
        long cWorst = 0;
        for (int k = 0; k < tuples.size(); k++) {
            long[] args = tuples.get(k);
            byte[] memory = start.clone();
            for (Global g : globals) {
                if (g.varied() && k > 0) {
                    for (int b = 0; b < g.size(); b++) {
                        memory[g.address() + b] = (byte) (k == 1 ? 0 : k == 2 ? 0xFF : random.nextInt(256));
                    }
                }
            }
            random.nextBytes(bank7f);
            System.arraycopy(bank7f, 0, memory, 0x10000 + scratch, bank7f.length);   // where pointer parameters point
            int[] registers = {random.nextInt(0x10000), random.nextInt(0x10000), random.nextInt(0x10000)};
            byte[] cMemory = memory.clone();
            byte[] asmMemory = memory.clone();
            for (int p = 0; p < args.length; p++) {
                Param param = f.params().get(p);
                place(cMemory, f.cPlaces().get(p), param, args[p], 0);
                // an 8-bit parameter's high byte is undefined in the convention: not 0 here
                place(asmMemory, f.asmPlaces().get(p), param, args[p], random.nextInt(256));
            }
            Outcome c = call(snes, cMemory, stack, f.cAddress(), returnTo, registers, f, "its C body", args);
            Outcome a = call(snes, asmMemory, stack, f.asmAddress(), returnTo, registers, f, "the assembly", args);
            long mask = switch (f.resultSize()) {
                case 0 -> 0;
                case 1 -> 0xFF;
                case 2 -> 0xFFFF;
                default -> 0xFFFF_FFFFL;
            };
            if ((c.result() & mask) != (a.result() & mask)) {
                throw new Failure(f.name(), "the assembly does not compute what the C body of '" + f.name()
                        + "' computes: " + inputText(f, args, globals, memory, k) + ", the C body returns "
                        + number(c.result() & mask, f.resultSize()) + ", the assembly " + number(a.result() & mask, f.resultSize()));
            }
            int from = SnesTarget.VARIABLES_START;
            int b = java.util.Arrays.mismatch(c.ram(), from, 0x20000, a.ram(), from, 0x20000);
            if (b >= 0) {
                b += from;
                throw new Failure(f.name(), "the assembly does not compute what the C body of '" + f.name()
                        + "' computes: " + inputText(f, args, globals, memory, k) + ", the C body leaves "
                        + where(b, globals) + " at $" + hex(c.ram()[b] & 0xFF, 2) + ", the assembly at $"
                        + hex(a.ram()[b] & 0xFF, 2));
            }
            if (a.cycles() < f.best() || a.cycles() > f.worst()) {
                throw new Failure(f.name(), "the assembly of '" + f.name() + "' took " + a.cycles() + " CPU cycles for "
                        + inputText(f, args, globals, memory, k) + ", outside the bounds dampf computed from it ("
                        + f.best() + ".." + f.worst() + "); is a bound comment ('; dampf: at most N') too low?");
            }
            asmBest = Math.min(asmBest, a.cycles());
            asmWorst = Math.max(asmWorst, a.cycles());
            cBest = Math.min(cBest, c.cycles());
            cWorst = Math.max(cWorst, c.cycles());
        }
        return new Report(f.name(), tuples.size(), asmBest, asmWorst, cBest, cWorst);
    }

    /** Calls the function at {@code address} with {@code memory} as RAM; returns its result, RAM and cycles. */
    private static Outcome call(Snes snes, byte[] memory, int stack, int address, int returnTo, int[] registers,
                                Function f, String what, long[] args) throws Failure {
        Cpu65816 cpu = snes.cpu();
        byte[] wram = snes.wram();
        System.arraycopy(memory, 0, wram, 0, wram.length);
        int back = returnTo - 1;   // RTL adds one
        wram[stack] = (byte) (back >> 16);
        wram[stack - 1] = (byte) (back >> 8);
        wram[stack - 2] = (byte) back;
        cpu.s = stack - 3;
        cpu.e = false;
        cpu.m = false;
        cpu.xf = false;
        cpu.dec = false;
        cpu.dbr = SnesTarget.DATA_BANK;
        cpu.d = 0;
        cpu.a = registers[0];
        cpu.x = registers[1];
        cpu.y = registers[2];
        cpu.pbr = address >> 16;
        cpu.pc = address & 0xFFFF;
        long cycles = cpu.master;
        if (!snes.resume(snes.masterClock() + CALL, a -> {
            if (a == returnTo && cpu.s == stack) {
                snes.stop();
            }
        })) {
            throw new Failure(f.name(), what + " of '" + f.name() + "' did not return within " + CALL / 8
                    + " CPU cycles for " + argsText(f, args));
        }
        long took = cpu.master - cycles;
        if (cpu.m || cpu.xf || cpu.dbr != SnesTarget.DATA_BANK || cpu.d != 0) {
            throw new Failure(f.name(), what + " of '" + f.name() + "' returned with another CPU state (16-bit A, X and Y, "
                    + "DBR $" + hex(SnesTarget.DATA_BANK, 2) + ", D 0) for " + argsText(f, args));
        }
        long result = (cpu.a & 0xFFFF) | (long) (cpu.x & 0xFFFF) << 16;
        return new Outcome(result, wram.clone(), took);
    }

    /** Writes a parameter's value into its place; {@code high}: the high byte of an 8-bit one. */
    private static void place(byte[] memory, int address, Param p, long value, int high) {
        if (p.pointer()) {
            memory[address] = (byte) value;
            memory[address + 1] = (byte) (value >> 8);
            memory[address + 2] = (byte) (value >> 16);
            memory[address + 3] = 0;
            return;
        }
        for (int b = 0; b < Math.max(2, p.size()); b++) {
            memory[address + b] = b < p.size() ? (byte) (value >> (8 * b)) : (byte) high;
        }
    }

    // ------------------------------------------------------------------ inputs

    /**
     * The argument tuples: the edge values of every parameter, then random tuples, at most
     * {@code count}; a pointer parameter points near {@code pointerBase}.
     */
    static List<long[]> inputs(List<Param> params, int count, Random random, long pointerBase) {
        List<long[]> out = new ArrayList<>();
        if (params.isEmpty()) {
            for (int k = 0; k < Math.min(count, 64); k++) {
                out.add(new long[0]);   // the globals change from one input to the next
            }
            return out;
        }
        List<List<Long>> edges = new ArrayList<>();
        long product = 1;
        for (Param p : params) {
            List<Long> e = edges(p, pointerBase);
            edges.add(e);
            product = Math.min(Long.MAX_VALUE / 1024, product * e.size());
        }
        if (product <= count / 2) {
            // every combination of edge values
            for (long i = 0; i < product; i++) {
                long[] t = new long[params.size()];
                long r = i;
                for (int p = 0; p < params.size(); p++) {
                    t[p] = edges.get(p).get((int) (r % edges.get(p).size()));
                    r /= edges.get(p).size();
                }
                out.add(t);
            }
        } else {
            int longest = edges.stream().mapToInt(List::size).max().orElse(0);
            for (int i = 0; i < longest; i++) {
                long[] t = new long[params.size()];
                for (int p = 0; p < params.size(); p++) {
                    t[p] = edges.get(p).get(i % edges.get(p).size());
                }
                out.add(t);
            }
        }
        while (out.size() < count) {
            long[] t = new long[params.size()];
            for (int p = 0; p < params.size(); p++) {
                t[p] = randomValue(params.get(p), random, pointerBase);
            }
            out.add(t);
        }
        return out;
    }

    private static List<Long> edges(Param p, long pointerBase) {
        Set<Long> e = new LinkedHashSet<>();
        if (p.pointer()) {
            e.add(pointerBase);
            e.add(pointerBase + 1);
            return List.copyOf(e);
        }
        long[] candidates = {p.lo(), p.hi(), 0, 1, -1, p.lo() + 1, p.hi() - 1, 0x7F, 0x80, 0xFF, 0x100, 0x7FFF, 0x8000,
            0xFFFF, -0x80, -0x8000, (p.lo() + p.hi()) / 2};
        for (long c : candidates) {
            if (c >= p.lo() && c <= p.hi()) {
                e.add(c);
            }
        }
        return List.copyOf(e);
    }

    private static long randomValue(Param p, Random random, long pointerBase) {
        if (p.pointer()) {
            return pointerBase + random.nextInt(1024) - 512;
        }
        long span = p.hi() - p.lo() + 1;
        return p.lo() + (long) (random.nextDouble() * span);
    }

    // ------------------------------------------------------------------ messages

    private static String argsText(Function f, long[] args) {
        if (args.length == 0) {
            return "no parameters";
        }
        List<String> l = new ArrayList<>();
        for (int p = 0; p < args.length; p++) {
            Param param = f.params().get(p);
            l.add(param.name() + " = " + (param.pointer() ? "$" + hex(args[p], 6) : number(args[p], param.size())));
        }
        return String.join(", ", l);
    }

    private static String inputText(Function f, long[] args, List<Global> globals, byte[] memory, int k) {
        StringBuilder s = new StringBuilder(args.length == 0 ? "" : "for " + argsText(f, args));
        List<String> state = new ArrayList<>();
        for (Global g : globals) {
            if (g.varied()) {
                StringBuilder v = new StringBuilder();
                for (int b = Math.min(g.size(), 8) - 1; b >= 0; b--) {
                    v.append(hex(memory[g.address() + b] & 0xFF, 2));
                }
                state.add(g.name() + " = $" + v + (g.size() > 8 ? "..." : ""));
            }
        }
        if (!state.isEmpty()) {
            s.append(args.length == 0 ? "for " : " with ").append(String.join(", ", state.subList(0, Math.min(4, state.size()))))
                    .append(state.size() > 4 ? ", ..." : "").append(k == 0 ? " (as after start-up)" : "");
        }
        return s.toString();
    }

    private static String where(int b, List<Global> globals) {
        for (Global g : globals) {
            if (b >= g.address() && b < g.address() + g.size()) {
                return g.name() + (b == g.address() ? "" : "+" + (b - g.address())) + " (" + bankAddress(b) + ")";
            }
        }
        return bankAddress(b);
    }

    /** A WRAM index as bank and address. */
    private static String bankAddress(int b) {
        return b >= 0x10000 ? "$7F:" + hex(b - 0x10000, 4) : "$7E:" + hex(b, 4);
    }

    private static String number(long v, int size) {
        int bytes = Math.max(1, Math.min(size, 4));
        return v + " ($" + hex(v & ((1L << (8 * bytes)) - 1), 2 * bytes) + ")";
    }

    private static String hex(long v, int digits) {
        String h = Long.toHexString(v).toUpperCase(Locale.ROOT);
        return h.length() >= digits ? h : "0".repeat(digits - h.length()) + h;
    }
}
