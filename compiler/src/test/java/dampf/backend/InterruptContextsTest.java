package dampf.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dampf.Compiler;
import dampf.ast.Function;
import dampf.ast.Interrupt;
import dampf.ast.Program;
import dampf.check.CallGraph;
import dampf.emu.Cpu65816;
import dampf.emu.Snes;
import dampf.frontend.CompileError;
import dampf.mir.MFunction;
import dampf.mir.MModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Interrupt handlers in C (DAMPF_INTERRUPT, ADR-6FM9TXV4): the contexts the call graph gets from
 * them and the errors around them, the frame areas of the contexts, the rule that interrupt code
 * never uses the CPU's multiplier, the runtime's entry stub (the state it saves and sets, its
 * cycles) and the timing of each interrupt. The program is the execution test
 * {@code exec/interrupts.c}, which also checks that interrupts in the middle of the main code
 * change none of its results, in dampf's emulator and in Mesen.
 */
class InterruptContextsTest {
    @TempDir
    Path tmp;

    private static Path program() throws Exception {
        return Path.of(InterruptContextsTest.class.getResource("/exec/interrupts.c").toURI());
    }

    private Compiler.Result build(Path source, Compiler.Backend backend) throws Exception {
        return Compiler.compile(new Compiler.Options(List.of(source), List.of(), List.of(), "test_main",
                tmp.resolve(backend + ".sfc"), Map.of(), backend));
    }

    private CompileError error(String code) throws Exception {
        Path f = tmp.resolve("e.c");
        Files.writeString(f, "#include <stdint.h>\n#include <dampf.h>\nuint16_t g;\n" + code);
        return assertThrows(CompileError.class, () -> build(f, Compiler.Backend.MIR));
    }

    // ------------------------------------------------------------------ the contexts and their errors

    @Test
    void aFunctionCalledFromTwoContextsIsAnError() throws Exception {
        CompileError e = error("""
                static void clamp(void) { if (g > 100) { g = 100; } }
                static void step(void) { g++; clamp(); }
                DAMPF_INTERRUPT(irq) void on_irq(void) { clamp(); }
                void test_main(void) { step(); }
                """);
        assertTrue(e.getMessage().contains("'clamp' is called from two contexts, the main code (test_main → step → clamp)"
                + " and the IRQ handler (on_irq → clamp)"), e.getMessage());
        assertTrue(e.getMessage().contains("e.c:6:"), "at the call in the second context: " + e.getMessage());
        CompileError both = error("""
                static void count(void) { g++; }
                DAMPF_INTERRUPT(nmi) void on_nmi(void) { count(); }
                DAMPF_INTERRUPT(irq) void on_irq(void) { count(); }
                void test_main(void) { }
                """);
        assertTrue(both.getMessage().contains("the NMI handler (on_nmi → count) and the IRQ handler (on_irq → count)"),
                both.getMessage());
    }

    @Test
    void aHandlerIsCalledByTheHardwareOnly() throws Exception {
        assertTrue(error("DAMPF_INTERRUPT(irq) void on_irq(void) { g++; }\nvoid test_main(void) { on_irq(); }\n")
                .getMessage().contains("'on_irq' is the IRQ handler (DAMPF_INTERRUPT): the hardware calls it, C code cannot"));
        assertTrue(error("DAMPF_INTERRUPT(irq) void a(void) { }\nDAMPF_INTERRUPT(irq) void b(void) { }\nvoid test_main(void) { }\n")
                .getMessage().contains("'b' is a second IRQ handler; the first is 'a'"));
        assertTrue(error("DAMPF_INTERRUPT(irq) uint16_t f(void) { return g; }\nvoid test_main(void) { }\n")
                .getMessage().contains("an interrupt handler must be 'void f(void)'"));
        assertTrue(error("DAMPF_INTERRUPT(irq) void test_main(void) { }\n")
                .getMessage().contains("the entry function 'test_main' cannot be an interrupt handler"));
        assertTrue(error("DAMPF_INTERRUPT(brk) void f(void) { }\nvoid test_main(void) { }\n")
                .getMessage().contains("DAMPF_INTERRUPT needs 'nmi' or 'irq'"));
    }

    @Test
    void anAssemblyFunctionCannotBeReachedFromAHandler() throws Exception {
        // its convention (ADR-6FMATG2V) assumes D = 0 and its frame at dampf_frame, and its code
        // may use the multiplier: neither fits an interrupt context (ADR-6FM9TXV4)
        Files.createDirectories(tmp.resolve("asm"));
        Files.writeString(tmp.resolve("asm/seven.s"), """
                        .p816
                        .smart  off
                        .a16
                        .i16
                        .export _seven
                        .segment "C_seven"
                .proc _seven
                        lda     #7
                        rtl
                .endproc
                """);
        String code = """
                static uint16_t seven(void) DAMPF_ASM("asm/seven.s") { return 7; }
                HANDLER void on_irq(void) { g = seven(); }
                void test_main(void) { g = 1; }
                """;
        for (Compiler.Backend b : Compiler.Backend.values()) {
            Path f = tmp.resolve("a.c");
            Files.writeString(f, "#include <stdint.h>\n#include <dampf.h>\nuint16_t g;\n" + code.replace("HANDLER", "DAMPF_INTERRUPT(irq)"));
            CompileError e = assertThrows(CompileError.class, () -> build(f, b));
            assertTrue(e.getMessage().contains("'seven' is written in assembly (")
                    && e.getMessage().contains("asm/seven.s) and reached from the IRQ handler 'on_irq'"), b + ": " + e.getMessage());
            // the same function in the main code builds
            Files.writeString(f, "#include <stdint.h>\n#include <dampf.h>\nuint16_t g;\n"
                    + code.replace("HANDLER ", "").replace("g = 1;", "on_irq();"));
            build(f, b);
        }
    }

    @Test
    void eachHandlerIsTheRootOfItsContext() throws Exception {
        Program p = Compiler.frontEnd(new Compiler.Options(List.of(program()), List.of(), List.of(), "test_main", tmp.resolve("x.sfc")));
        Map<String, Interrupt> byName = new HashMap<>();
        CallGraph.of(p).contexts(p.function("test_main")).forEach((f, c) -> byName.put(f.name(), c));
        assertEquals(Map.of("on_irq", Interrupt.IRQ, "irq_work", Interrupt.IRQ, "on_nmi", Interrupt.NMI, "nmi_work", Interrupt.NMI),
                byName, "the main code's functions are not in the map");
    }

    // ------------------------------------------------------------------ frames, direct pages, multiplier

    /** The code generator from MIR on exec/interrupts.c, as dampf.Compiler runs it. */
    private static MirCodeGen generate() throws Exception {
        Program p = Compiler.frontEnd(new Compiler.Options(List.of(program()), List.of(), List.of(), "test_main", Path.of("x.sfc")));
        Function entry = p.function("test_main");
        Map<String, Interrupt> contexts = new HashMap<>();
        CallGraph.of(p).contexts(entry).forEach((f, c) -> contexts.put(f.label(), c));
        Map<Interrupt, String> handlers = new EnumMap<>(Interrupt.class);
        p.functions().stream().filter(f -> f.interrupt() != null).forEach(f -> handlers.put(f.interrupt(), f.label()));
        MModule m = dampf.mir.Lower.lower(p);
        dampf.mir.Optimizer.run(m, false, true, new dampf.util.PassLimit(-1, ""));
        MirCodeGen gen = new MirCodeGen(m, entry.label(), x -> x).interrupts(contexts, handlers);
        gen.generate();
        return gen;
    }

    @Test
    void theContextsFramesNeverOverlap() throws Exception {
        MirCodeGen gen = generate();
        assertEquals(Map.of(Interrupt.NMI, 0x1D00, Interrupt.IRQ, 0x1B00), gen.directPage,
                "the NMI's page under the stack, the IRQ's two pages (its frames take more than 224 bytes) under it");
        assertTrue(gen.contextFrames.get(Interrupt.IRQ) > 0xE0);
        Map<Interrupt, Integer> top = Map.of(Interrupt.NMI, ContextAreas.STACK, Interrupt.IRQ, 0x1D00);
        int mainEnd = 0;
        for (MFunction f : gen.module.functions()) {
            Interrupt c = gen.context(f);
            int base = gen.placedBase.get(f);
            int end = base + gen.placedSize.get(f);
            if (c == null) {
                mainEnd = Math.max(mainEnd, Selection.FRAME_START + end);
                continue;
            }
            int page = gen.directPage.get(c);
            assertTrue(page + Selection.FRAME_START + end <= top.get(c), f.name() + " ends in the next area");
            assertTrue(page + Selection.FRAME_START + base >= page + 0x20, f.name() + " overlaps the scratch registers");
        }
        assertTrue(mainEnd <= 0x1B00, "the main code's frames end at " + Integer.toHexString(mainEnd));
        // the areas follow from the pages: the scratch registers in each page's first 32 bytes
        assertEquals(0x1B00, ContextAreas.mainEnd(gen.directPage));
    }

    @Test
    void eachContextTakesWholePagesUnderTheStack() {
        assertEquals(Map.of(Interrupt.NMI, 0x1D00, Interrupt.IRQ, 0x1C00),
                ContextAreas.pages(Map.of(Interrupt.NMI, 0, Interrupt.IRQ, 0xE0)));
        assertEquals(Map.of(Interrupt.IRQ, 0x1C00), ContextAreas.pages(Map.of(Interrupt.IRQ, 0xE1)));
        assertEquals(Map.of(Interrupt.NMI, 0x1C00, Interrupt.IRQ, 0x1B00),
                ContextAreas.pages(Map.of(Interrupt.NMI, 0x100, Interrupt.IRQ, 0)));
        assertEquals(ContextAreas.STACK, ContextAreas.mainEnd(Map.of()));
    }

    /** The assembly of every function, by name (from {@code .proc} to {@code .endproc}). */
    private static Map<String, String> functions(List<String> modules) {
        Map<String, String> out = new HashMap<>();
        Matcher m = Pattern.compile("(?ms)^\\.proc (\\S+)$(.*?)^\\.endproc$").matcher(String.join("\n", modules));
        while (m.find()) {
            out.put(m.group(1), m.group(2));
        }
        return out;
    }

    private static final Pattern MULTIPLIER = Pattern.compile("\\$00420[23]|\\$004216|jsl\\s+dampf_mul(16|32)\\b(?!_soft)");

    @Test
    void interruptCodeNeverUsesTheMultiplierAndFramesOfItsOwn() throws Exception {
        for (Compiler.Backend b : Compiler.Backend.values()) {
            Compiler.Result r = build(program(), b);
            Map<String, String> code = functions(r.assembly());
            for (String f : List.of("_on_irq", "_on_nmi", "_nmi_work", "_irq_work")) {
                if (!code.containsKey(f)) {
                    continue;   // inlined into its handler
                }
                assertFalse(MULTIPLIER.matcher(code.get(f)).find(), b + " " + f + " uses the CPU's multiplier");
                assertTrue(code.get(f).contains("_soft"), b + " " + f + " multiplies in software");
                assertFalse(code.get(f).contains("a:dampf_frame+") || code.get(f).contains("(dampf_frame+"),
                        b + " " + f + " reaches the main code's frames");
            }
            assertTrue(MULTIPLIER.matcher(code.get("_mix")).find(), b + ": the main code still uses the multiplier");
            assertFalse(code.get("_mix").contains("dampf_frame_"), b + ": the main code reaches no context's frames");
        }
        // and the runtime's software multiplications do not touch it either
        String runtime = new String(RomBuilderAccess.runtime());
        String soft = runtime.substring(runtime.indexOf("dampf_mul16_soft:"), runtime.indexOf(".endif", runtime.indexOf("dampf_mul16_soft:")));
        assertFalse(soft.contains("WRMPY") || soft.contains("RDMPY") || soft.contains("$4202"), soft);
    }

    /** The runtime's source, as the ROM builder reads it. */
    private static final class RomBuilderAccess {
        static byte[] runtime() throws java.io.IOException {
            try (var in = dampf.snes.RomBuilder.class.getResourceAsStream("/dampf/snes/runtime.s")) {
                return in.readAllBytes();
            }
        }
    }

    // ------------------------------------------------------------------ the entry stub, in the emulator

    private static Map<String, Long> labels(Path file) throws Exception {
        Map<String, Long> m = new HashMap<>();
        for (String line : Files.readAllLines(file)) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 3 && parts[0].equals("al")) {
                m.put(parts[2].substring(1), Long.parseLong(parts[1], 16));
            }
        }
        return m;
    }

    /** One interrupt as the CPU took it: the registers it interrupted, and when the stub's parts ran. */
    private static final class Taken {
        Interrupt kind;
        int depth;
        int a;
        int x;
        int y;
        int p;
        int d;
        int dbr;
        int s;
        int returnTo;
        long start = -1;
        long handler = -1;
        long back = -1;
        long end = -1;
    }

    @Test
    void theStubSavesTheStateSetsTheContextAndTakesItsCycles() throws Exception {
        for (Compiler.Backend b : Compiler.Backend.values()) {
            Compiler.Result r = build(program(), b);
            Map<String, Long> at = labels(r.rom().labels());
            Map<Interrupt, Long> stub = Map.of(Interrupt.NMI, at.get("dampf_nmi"), Interrupt.IRQ, at.get("dampf_irq"));
            Map<Interrupt, Long> handler = Map.of(Interrupt.NMI, at.get("dampf_nmi_handler"), Interrupt.IRQ, at.get("dampf_irq_handler"));
            Map<Interrupt, Long> page = Map.of(Interrupt.NMI, at.get("dampf_nmi_dp"), Interrupt.IRQ, at.get("dampf_irq_dp"));
            Snes snes = Snes.load(r.rom().rom());
            Cpu65816 cpu = snes.cpu();
            Deque<Taken> open = new ArrayDeque<>();
            List<Taken> done = new ArrayList<>();
            List<String> wrong = new ArrayList<>();
            long[] seen = {0};
            snes.run(200L * 357_368, address -> {
                if (cpu.interrupts != seen[0]) {
                    seen[0] = cpu.interrupts;
                    Taken t = new Taken();
                    t.kind = (address | 0x800000) == stub.get(Interrupt.NMI) ? Interrupt.NMI : Interrupt.IRQ;
                    t.depth = cpu.depth;
                    t.a = cpu.a;
                    t.x = cpu.x;
                    t.y = cpu.y;
                    t.p = snes.peek(cpu.s + 1);   // as the CPU pushed it
                    t.d = cpu.d;
                    t.dbr = cpu.dbr;
                    t.s = cpu.s + 4;
                    t.returnTo = cpu.interruptReturn;
                    t.start = cpu.interruptStart - cpu.cyclesAbove[t.depth];   // (the entry's cycles are not nested ones)
                    open.push(t);
                }
                Taken t = open.peek();
                if (t == null || cpu.depth != t.depth && address != t.returnTo) {
                    return;
                }
                long own = cpu.cycles - cpu.cyclesAbove[t.depth];
                if (address == handler.get(t.kind) && t.handler < 0) {
                    t.handler = own;
                    if (cpu.d != page.get(t.kind) || cpu.dbr != 0x7E || cpu.m || cpu.xf) {
                        wrong.add(t.kind + " handler entered with D " + Integer.toHexString(cpu.d) + ", DBR "
                                + Integer.toHexString(cpu.dbr) + ", m " + cpu.m + ", x " + cpu.xf);
                    }
                } else if (t.handler >= 0 && t.back < 0 && address >= stub.get(t.kind) && address < stub.get(t.kind) + 64) {
                    t.back = own;
                } else if (address == t.returnTo && cpu.depth == t.depth - 1) {
                    t.end = own;
                    if (cpu.a != t.a || cpu.x != t.x || cpu.y != t.y || cpu.p() != t.p || cpu.d != t.d || cpu.dbr != t.dbr
                            || cpu.s != t.s) {
                        wrong.add(t.kind + " did not restore the state it interrupted");
                    }
                    open.pop();
                    done.add(t);
                }
            });
            assertTrue(wrong.isEmpty(), b + ": " + wrong.subList(0, Math.min(5, wrong.size())));
            long irqs = done.stream().filter(t -> t.kind == Interrupt.IRQ).count();
            long nmis = done.stream().filter(t -> t.kind == Interrupt.NMI).count();
            assertTrue(irqs >= 20 && nmis >= 10, b + ": " + irqs + " IRQs and " + nmis + " NMIs");
            assertTrue(done.stream().anyMatch(t -> t.kind == Interrupt.NMI && t.depth == 2), b + ": an NMI inside the IRQ handler");
            for (Taken t : done) {
                assertEquals((long) Timing.INTERRUPT_ENTRY.get(t.kind), t.handler - t.start, b + ": the " + t.kind + " stub's entry");
                assertEquals(Timing.INTERRUPT_EXIT, t.end - t.back, b + ": the " + t.kind + " stub's exit");
            }
        }
    }

    @Test
    void eachInterruptIsTimedWithItsStub() throws Exception {
        Compiler.Result r = build(program(), Compiler.Backend.MIR);
        Map<String, long[]> t = r.timing();
        for (Interrupt c : Interrupt.values()) {
            long stub = Timing.INTERRUPT_ENTRY.get(c) + Timing.INTERRUPT_EXIT;
            long[] handler = t.get(c == Interrupt.IRQ ? "_on_irq" : "_on_nmi");
            assertEquals(handler[0] + stub, t.get("dampf_" + c.key)[0], c + " best case");
            assertEquals(handler[1] + stub, t.get("dampf_" + c.key)[1], c + " worst case");
        }
        assertTrue(t.containsKey("dampf_mul16_soft") && t.containsKey("dampf_mul32_soft"));
        String report = Files.readString(tmp.resolve("MIR.timing.txt"));
        assertTrue(report.contains("dampf_irq   (the IRQ from the interrupt to the end of its RTI: entry stub and _on_irq)"), report);
    }
}
