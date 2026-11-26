package dampf.check;

import dampf.ast.Expr;
import dampf.ast.Function;
import dampf.ast.Interrupt;
import dampf.ast.Program;
import dampf.ast.Stmt;
import dampf.ast.Variable;
import dampf.frontend.CompileError;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One copy of a function per context that reaches it (ADR-6FQ4NSHE, amending ADR-6FM9TXV4). The main code and each interrupt handler are the roots of contexts, and
 * each context has frames, a direct page and scratch registers of its own, so a function reached
 * from two of them cannot be one piece of code. Before code is generated, every such function is
 * copied in the AST: the copy has its own parameters and locals (frames in its context's area),
 * and the functions of each context call their own context's copies.
 *
 * <p>The original belongs to the first context that reaches it, in the order main code, NMI, IRQ,
 * and keeps its name and label; so a program without handlers, or whose handlers share nothing
 * with the main code, is not changed at all. A copy's label is two underscores, the context's
 * key and the original's label, {@code __irq_clamp} for {@code _clamp} (and {@code __irq_clamp__x}
 * for its local {@code x}): the C name it would come from begins with an underscore, which C
 * reserves at file scope, and a label that is taken anyway gets a number, as the front end
 * numbers its own. Its name, in messages and in the debug
 * file, is {@code clamp@irq}.
 *
 * <p>Shared, not copied: globals, {@code static} locals, string literals and the constant
 * templates of initialised local arrays (all {@link Variable.Kind#GLOBAL} or
 * {@link Variable.Kind#STATIC_LOCAL}), so a static local counts the calls of every copy.
 */
public final class ContextCopies {
    /**
     * The program with the copies (appended after the original functions), and the interrupt
     * context of every function an interrupt handler reaches, copies included; the main code's
     * functions are not in the map. {@code reachedFrom} names, for each original function an
     * interrupt reaches, the first such interrupt (for the checks that forbid a function there,
     * such as one written in assembly).
     */
    public record Result(Program program, Map<Function, Interrupt> contexts, Map<Function, Interrupt> reachedFrom) {}

    private ContextCopies() {}

    public static Result of(Program program, Function entry) throws CompileError {
        CallGraph graph = CallGraph.of(program);
        Map<Interrupt, Function> handlers = graph.handlers(entry);
        Set<Function> main = graph.reached(entry);
        // the context each original belongs to: the first that reaches it (null: the main code)
        Map<Function, Interrupt> owner = new HashMap<>();
        Map<Interrupt, Set<Function>> reach = new EnumMap<>(Interrupt.class);
        Map<Function, Interrupt> reachedFrom = new LinkedHashMap<>();
        for (Map.Entry<Interrupt, Function> h : handlers.entrySet()) {
            Set<Function> mine = graph.reached(h.getValue());
            reach.put(h.getKey(), mine);
            for (Function f : mine) {
                reachedFrom.putIfAbsent(f, h.getKey());
                if (!main.contains(f)) {
                    owner.putIfAbsent(f, h.getKey());
                }
            }
        }
        if (handlers.isEmpty()) {
            return new Result(program, Map.of(), Map.of());
        }
        // each context's version of every function it reaches: the original or a new copy
        Set<String> labels = labels(program);
        Map<Interrupt, Map<Function, Function>> version = new EnumMap<>(Interrupt.class);
        List<Function> copies = new ArrayList<>();
        Map<Function, Function> originalOf = new HashMap<>();
        for (Map.Entry<Interrupt, Set<Function>> r : reach.entrySet()) {
            Interrupt c = r.getKey();
            Map<Function, Function> v = new HashMap<>();
            for (Function f : r.getValue()) {
                if (owner.get(f) == c) {
                    v.put(f, f);
                } else {
                    Function copy = new Function(f.name() + "@" + c.key, unique(prefix(c) + f.label(), labels), f.returnType(), f.paramTypes(), f.pos());
                    if (f.budget() != null) {
                        copy.setBudget(f.budget());
                    }
                    v.put(f, copy);
                    copies.add(copy);
                    originalOf.put(copy, f);
                }
            }
            version.put(c, v);
        }
        // the bodies: a copy gets its own parameters and locals; an original of an interrupt
        // context only calls that context's copies
        Map<Function, Interrupt> contexts = new LinkedHashMap<>();
        for (Map.Entry<Interrupt, Map<Function, Function>> e : version.entrySet()) {
            Interrupt c = e.getKey();
            Map<Function, Function> v = e.getValue();
            for (Function f : reach.get(c)) {
                Function target = v.get(f);
                contexts.put(target, c);
                if (!f.isDefined()) {
                    continue;
                }
                if (target == f) {
                    f.replaceBody(new Copier(c, v, Map.of()).block(f.body()));
                } else {
                    Map<Variable, Variable> vars = new HashMap<>();
                    List<Variable> params = new ArrayList<>();
                    for (Variable p : f.params()) {
                        params.add(copy(p, c, vars, labels));
                    }
                    for (Variable l : f.locals()) {
                        target.locals().add(copy(l, c, vars, labels));
                    }
                    target.define(params, new Copier(c, v, vars).block(f.body()), f.definitionPos());
                }
            }
        }
        List<Function> functions = new ArrayList<>(program.functions());
        functions.addAll(copies);
        return new Result(new Program(program.globals(), program.initialValues(), List.copyOf(functions)),
                java.util.Collections.unmodifiableMap(contexts), java.util.Collections.unmodifiableMap(reachedFrom));
    }

    /** What a copy's labels start with: {@code __irq} or {@code __nmi}. */
    private static String prefix(Interrupt c) {
        return "__" + c.key;
    }

    /** Every label of the program: its functions, globals, parameters and locals. */
    private static Set<String> labels(Program program) {
        Set<String> labels = new java.util.HashSet<>();
        program.globals().forEach(v -> labels.add(v.label()));
        for (Function f : program.functions()) {
            labels.add(f.label());
            f.params().forEach(v -> labels.add(v.label()));
            f.locals().forEach(v -> labels.add(v.label()));
        }
        return labels;
    }

    private static String unique(String base, Set<String> labels) {
        String label = base;
        for (int i = 2; !labels.add(label); i++) {
            label = base + "_" + i;
        }
        return label;
    }

    private static Variable copy(Variable v, Interrupt c, Map<Variable, Variable> vars, Set<String> labels) {
        Variable n = new Variable(v.name(), unique(prefix(c) + v.label(), labels), v.type(), v.kind(), v.pos(), v.readOnly());
        vars.put(v, n);
        return n;
    }

    /** A body rewritten for one context: its own variables, and calls to the context's versions. */
    private record Copier(Interrupt context, Map<Function, Function> version, Map<Variable, Variable> vars) {
        Variable var(Variable v) {
            if (v.kind() == Variable.Kind.GLOBAL || v.kind() == Variable.Kind.STATIC_LOCAL) {
                return v;   // one object for every copy
            }
            Variable n = vars.get(v);
            if (n == null) {
                if (vars.isEmpty()) {
                    return v;   // an original, whose variables stay its own
                }
                throw new IllegalStateException("a local of another function: " + v.label());
            }
            return n;
        }

        Stmt.Block block(Stmt.Block b) {
            return new Stmt.Block(b.statements().stream().map(this::stmt).toList(), b.pos(), b.end());
        }

        Stmt stmt(Stmt s) {
            if (s == null) {
                return null;
            }
            return switch (s) {
                case Stmt.Block b -> block(b);
                case Stmt.ExprStmt e -> new Stmt.ExprStmt(expr(e.expr()), e.pos());
                case Stmt.Return r -> new Stmt.Return(expr(r.value()), r.pos());
                case Stmt.If i -> new Stmt.If(expr(i.cond()), stmt(i.then()), stmt(i.otherwise()), i.pos());
                case Stmt.While w -> new Stmt.While(expr(w.cond()), stmt(w.body()), w.pos());
                case Stmt.DoWhile d -> new Stmt.DoWhile(stmt(d.body()), expr(d.cond()), d.pos());
                case Stmt.For f -> new Stmt.For(stmt(f.init()), expr(f.cond()), f.step().stream().map(this::expr).toList(),
                        stmt(f.body()), f.pos());
                case Stmt.Break b -> b;
                case Stmt.Continue c -> c;
                case Stmt.Switch sw -> new Stmt.Switch(expr(sw.value()), sw.body().stream().map(this::stmt).toList(), sw.pos());
                case Stmt.Case c -> c;
                case Stmt.Range r -> new Stmt.Range(var(r.variable()), r.lo(), r.hi(), r.pos());
            };
        }

        Expr expr(Expr e) {
            if (e == null) {
                return null;
            }
            return switch (e) {
                case Expr.Const k -> k;
                case Expr.Var v -> new Expr.Var(var(v.variable()), v.pos());
                case Expr.Binary b -> new Expr.Binary(b.op(), expr(b.left()), expr(b.right()), b.type(), b.pos());
                case Expr.Unary u -> new Expr.Unary(u.op(), expr(u.operand()), u.type(), u.pos());
                case Expr.LogicalNot n -> new Expr.LogicalNot(expr(n.operand()), n.pos());
                case Expr.Logical l -> new Expr.Logical(l.and(), expr(l.left()), expr(l.right()), l.pos());
                case Expr.Conditional c -> new Expr.Conditional(expr(c.cond()), expr(c.then()), expr(c.otherwise()), c.type(), c.pos());
                case Expr.Convert c -> new Expr.Convert(expr(c.operand()), c.type(), c.explicit(), c.pos());
                case Expr.Assign a -> new Expr.Assign(expr(a.target()), expr(a.value()), a.pos());
                case Expr.PostIncDec p -> new Expr.PostIncDec(expr(p.target()), p.increment(), p.step(), p.pos());
                case Expr.Index i -> new Expr.Index(expr(i.base()), expr(i.index()), i.type(), i.pos());
                case Expr.Member m -> new Expr.Member(expr(m.base()), m.field(), m.pos());
                case Expr.Deref d -> new Expr.Deref(expr(d.pointer()), d.type(), d.pos());
                case Expr.AddressOf a -> new Expr.AddressOf(expr(a.lvalue()), a.type(), a.pos());
                case Expr.Decay d -> new Expr.Decay(expr(d.array()), d.type(), d.pos());
                case Expr.PointerAdd p -> new Expr.PointerAdd(expr(p.pointer()), expr(p.offset()), p.scale(), p.subtract(), p.type(), p.pos());
                case Expr.PointerDiff p -> new Expr.PointerDiff(expr(p.left()), expr(p.right()), p.scale(), p.pos());
                case Expr.PointerCast p -> new Expr.PointerCast(expr(p.operand()), p.type(), p.pos());
                case Expr.PointerToInt p -> new Expr.PointerToInt(expr(p.operand()), p.pos());
                case Expr.Call c -> new Expr.Call(version.getOrDefault(c.function(), c.function()),
                        c.args().stream().map(this::expr).toList(), c.pos());
            };
        }
    }
}
