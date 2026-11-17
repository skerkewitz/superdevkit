package dampf.mir;

import dampf.ast.Children;
import dampf.ast.Expr;
import dampf.ast.Function;
import dampf.ast.Program;
import dampf.ast.Stmt;
import dampf.ast.Type;
import dampf.ast.Variable;
import dampf.frontend.SourcePos;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lowers the typed AST to MIR.
 *
 * <p>Scalar locals and parameters whose address is never taken become SSA values; arrays,
 * structs and locals whose address is taken get a frame slot in memory. SSA is built directly
 * while lowering, with the algorithm of Braun et al., "Simple and Efficient Construction of
 * Static Single Assignment Form" (CC 2013): a block is sealed once all its predecessors are
 * known; reading a variable in an unsealed block makes a provisional φ, completed when the block
 * is sealed; φs that turn out trivial are removed.
 *
 * <p>C's meaning is kept exactly: the AST already has every conversion explicit; here the
 * signedness of each C type picks the operation (sdiv/udiv, ashr/lshr, slt/ult, sext/zext).
 */
public final class Lower {
    private final Program program;
    private final MModule module = new MModule();

    // per function
    private MFunction mf;
    private Block cur;
    private final Map<Variable, Value.Slot> slotOf = new HashMap<>();
    private final Set<Variable> ssa = new HashSet<>();
    private final Map<Variable, Map<Block, Value>> defs = new HashMap<>();
    private final Set<Block> sealed = new HashSet<>();
    private final Map<Block, Map<Variable, Instr.Phi>> incomplete = new HashMap<>();

    private record Targets(Block breakTo, Block continueTo) {}

    private final Deque<Targets> targets = new ArrayDeque<>();

    private Lower(Program program) {
        this.program = program;
    }

    public static MModule lower(Program program) {
        Lower l = new Lower(program);
        for (Variable v : program.globals()) {
            List<MModule.Datum> init = new ArrayList<>();
            dampf.ast.Init astInit = program.initialValues().get(v);
            if (astInit != null) {
                for (dampf.ast.Init.Item i : astInit.items()) {
                    init.add(i.address() == null ? new MModule.Datum(i.offset(), i.size(), i.value(), null)
                            : new MModule.Datum(i.offset(), 4, i.addressOffset(), i.address().label()));
                }
            }
            l.module.globals().add(new MModule.Global(v.label(), v.type().size(), init, v.inRom()));
        }
        for (Function f : program.functions()) {
            if (f.isDefined()) {
                l.function(f);
            }
        }
        Verifier.verify(l.module);
        return l.module;
    }

    // ------------------------------------------------------------------ functions

    private void function(Function f) {
        mf = new MFunction(f.label(), mtype(f.returnType()), f.definitionPos());
        module.functions().add(mf);
        slotOf.clear();
        ssa.clear();
        defs.clear();
        sealed.clear();
        incomplete.clear();
        targets.clear();

        Set<Variable> addressTaken = new HashSet<>();
        collectAddressTaken(f.body(), addressTaken);
        List<Variable> vars = new ArrayList<>(f.params());
        vars.addAll(f.locals());
        for (Variable v : vars) {
            if (Type.isScalar(v.type()) && !addressTaken.contains(v)) {
                ssa.add(v);
            } else {
                Value.Slot s = new Value.Slot(v.label(), v.type().size());
                mf.slots().add(s);
                slotOf.put(v, s);
            }
        }

        cur = mf.newBlock("entry");
        seal(cur);
        for (Variable p : f.params()) {
            Value.Param param = new Value.Param(p.name(), mtype(p.type()));
            mf.params().add(param);
            if (ssa.contains(p)) {
                write(p, cur, param);
            } else {
                emit(new Instr.Store(new Value.SlotAddr(slotOf.get(p)), param, false, p.pos()));
            }
        }
        statement(f.body());
        if (!cur.terminated()) {
            Value result = mf.result() == MType.VOID ? null : new Value.Undef(mf.result()); // C: undefined if used
            emit(new Instr.Ret(result, f.body().end()));
        }
        removeUnreachable();
    }

    private static void collectAddressTaken(Stmt s, Set<Variable> out) {
        for (Expr e : Children.exprsOf(s)) {
            collectAddressTaken(e, out);
        }
        for (Stmt c : Children.stmtsOf(s)) {
            collectAddressTaken(c, out);
        }
    }

    private static void collectAddressTaken(Expr e, Set<Variable> out) {
        if (e instanceof Expr.AddressOf a && a.lvalue() instanceof Expr.Var v) {
            out.add(v.variable());
        }
        for (Expr c : Children.of(e)) {
            collectAddressTaken(c, out);
        }
    }

    // ------------------------------------------------------------------ SSA construction (Braun et al.)

    private void write(Variable v, Block b, Value value) {
        defs.computeIfAbsent(v, k -> new HashMap<>()).put(b, value);
    }

    private Value read(Variable v, Block b) {
        Map<Block, Value> m = defs.get(v);
        if (m != null && m.containsKey(b)) {
            return m.get(b);
        }
        return readRecursive(v, b);
    }

    private Value readRecursive(Variable v, Block b) {
        MType t = mtype(v.type());
        Value val;
        if (!sealed.contains(b)) {
            Instr.Phi phi = b.add(new Instr.Phi(t, v.pos()));
            incomplete.computeIfAbsent(b, k -> new LinkedHashMap<>()).put(v, phi);
            val = phi;
        } else if (b.preds().isEmpty()) {
            val = new Value.Undef(t); // read before any assignment
        } else if (b.preds().size() == 1) {
            val = read(v, b.preds().getFirst());
        } else {
            Instr.Phi phi = b.add(new Instr.Phi(t, v.pos()));
            write(v, b, phi);
            val = addPhiOperands(v, phi);
        }
        write(v, b, val);
        return val;
    }

    private Value addPhiOperands(Variable v, Instr.Phi phi) {
        for (Block pred : List.copyOf(phi.block().preds())) {
            phi.add(pred, read(v, pred));
        }
        return tryRemoveTrivialPhi(phi);
    }

    private Value tryRemoveTrivialPhi(Instr.Phi phi) {
        Value same = null;
        for (Value op : phi.operands()) {
            if (op == same || op == phi) {
                continue;
            }
            if (same != null) {
                return phi; // two different values: a real φ
            }
            same = op;
        }
        if (same == null) {
            same = new Value.Undef(phi.type());
        }
        List<Instr.Phi> phiUsers = new ArrayList<>();
        for (Block b : mf.blocks()) {
            for (Instr i : b.instrs()) {
                if (i != phi && i.operands().contains(phi) && i instanceof Instr.Phi p) {
                    phiUsers.add(p);
                }
            }
        }
        replaceUses(phi, same);
        if (phi.block() != null) {
            phi.block().remove(phi);
        }
        for (Instr.Phi p : phiUsers) {
            if (p.block() != null) {
                tryRemoveTrivialPhi(p);
            }
        }
        return same;
    }

    private void replaceUses(Value old, Value with) {
        for (Block b : mf.blocks()) {
            for (Instr i : b.instrs()) {
                for (int k = 0; k < i.operands().size(); k++) {
                    if (i.op(k) == old) {
                        i.setOp(k, with);
                    }
                }
            }
        }
        for (Map<Block, Value> m : defs.values()) {
            m.replaceAll((blk, val) -> val == old ? with : val);
        }
    }

    private void seal(Block b) {
        Map<Variable, Instr.Phi> phis = incomplete.remove(b);
        sealed.add(b);
        if (phis != null) {
            for (Map.Entry<Variable, Instr.Phi> e : phis.entrySet()) {
                addPhiOperands(e.getKey(), e.getValue());
            }
        }
    }

    /** Removes blocks no path reaches (code after return, break, continue). */
    private void removeUnreachable() {
        Set<Block> reachable = new HashSet<>();
        Deque<Block> work = new ArrayDeque<>(List.of(mf.entry()));
        while (!work.isEmpty()) {
            Block b = work.pop();
            if (reachable.add(b)) {
                Instr t = b.terminator();
                if (t != null) {
                    work.addAll(t.successors());
                }
            }
        }
        List<Block> dead = mf.blocks().stream().filter(b -> !reachable.contains(b)).toList();
        for (Block d : dead) {
            Instr t = d.terminator();
            if (t != null) {
                for (Block s : t.successors()) {
                    if (reachable.contains(s)) {
                        int k = s.preds().indexOf(d);
                        s.preds().remove(k);
                        for (Instr i : s.instrs()) {
                            if (i instanceof Instr.Phi p) {
                                int j = p.from.indexOf(d);
                                p.from.remove(j);
                                p.operands().remove(j);
                            }
                        }
                    }
                }
            }
        }
        mf.blocks().removeAll(dead);
        // φs that lost an operand may have become trivial
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Block b : mf.blocks()) {
                for (Instr i : List.copyOf(b.instrs())) {
                    if (i instanceof Instr.Phi p && p.block() != null && tryRemoveTrivialPhi(p) != p) {
                        changed = true;
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ statements

    /**
     * Appends to the current block. Always use this, never {@code cur.add(...)}: Java reads
     * {@code cur} before it evaluates the arguments, and evaluating an operand can move to a new
     * block (a ?: or && inside it), so the instruction would land in the old, finished block.
     */
    private <T extends Instr> T emit(T instr) {
        return cur.add(instr);
    }

    private void jump(Block target, SourcePos pos) {
        if (!cur.terminated()) {
            emit(new Instr.Jump(target, pos));
        }
    }

    /** Continues in a new block nothing jumps to (after return, break, continue); it is removed at the end. */
    private void deadBlock() {
        cur = mf.newBlock("dead");
        seal(cur);
    }

    private void statement(Stmt s) {
        if (cur.terminated()) {
            deadBlock();
        }
        switch (s) {
            case Stmt.Block b -> b.statements().forEach(this::statement);
            case Stmt.ExprStmt e -> value(e.expr());
            case Stmt.Return r -> {
                emit(new Instr.Ret(r.value() == null ? null : value(r.value()), r.pos()));
                deadBlock();
            }
            case Stmt.If i -> {
                Block then = mf.newBlock("then");
                Block join = mf.newBlock("endif");
                Block otherwise = i.otherwise() == null ? join : mf.newBlock("else");
                branch(i.cond(), then, otherwise);
                seal(then);
                cur = then;
                statement(i.then());
                jump(join, i.pos());
                if (i.otherwise() != null) {
                    seal(otherwise);
                    cur = otherwise;
                    statement(i.otherwise());
                    jump(join, i.pos());
                }
                seal(join);
                cur = join;
            }
            case Stmt.While w -> {
                Block head = mf.newBlock("while");
                Block body = mf.newBlock("do");
                Block exit = mf.newBlock("endwhile");
                jump(head, w.pos());
                cur = head;
                branch(w.cond(), body, exit);
                seal(body);
                cur = body;
                loop(w.body(), exit, head);
                jump(head, w.pos());
                seal(head);
                seal(exit);
                cur = exit;
            }
            case Stmt.DoWhile d -> {
                Block body = mf.newBlock("do");
                Block cond = mf.newBlock("dowhile");
                Block exit = mf.newBlock("enddo");
                jump(body, d.pos());
                cur = body;
                loop(d.body(), exit, cond);
                jump(cond, d.pos());
                seal(cond);
                cur = cond;
                branch(d.cond(), body, exit);
                seal(body);
                seal(exit);
                cur = exit;
            }
            case Stmt.For f -> {
                if (f.init() != null) {
                    statement(f.init());
                }
                Block head = mf.newBlock("for");
                Block body = mf.newBlock("body");
                Block step = mf.newBlock("step");
                Block exit = mf.newBlock("endfor");
                jump(head, f.pos());
                cur = head;
                if (f.cond() != null) {
                    branch(f.cond(), body, exit);
                } else {
                    jump(body, f.pos());
                }
                seal(body);
                cur = body;
                loop(f.body(), exit, step);
                jump(step, f.pos());
                seal(step);
                cur = step;
                for (Expr e : f.step()) {
                    value(e);
                }
                jump(head, f.pos());
                seal(head);
                seal(exit);
                cur = exit;
            }
            case Stmt.Break b -> {
                jump(targets.peek().breakTo(), b.pos());
                deadBlock();
            }
            case Stmt.Continue c -> {
                Block to = targets.stream().map(Targets::continueTo).filter(x -> x != null).findFirst().orElseThrow();
                jump(to, c.pos());
                deadBlock();
            }
            case Stmt.Switch sw -> switchStatement(sw);
            case Stmt.Case c -> throw new IllegalStateException("case label outside a switch body");
        }
    }

    private void loop(Stmt body, Block breakTo, Block continueTo) {
        targets.push(new Targets(breakTo, continueTo));
        statement(body);
        targets.pop();
    }

    private void switchStatement(Stmt.Switch sw) {
        Value v = value(sw.value());
        Block exit = mf.newBlock("endswitch");
        Map<Stmt.Case, Block> blocks = new LinkedHashMap<>();
        Block otherwise = exit;
        for (Stmt s : sw.body()) {
            if (s instanceof Stmt.Case c) {
                Block b = mf.newBlock(c.value() == null ? "default" : "case");
                blocks.put(c, b);
                if (c.value() == null) {
                    otherwise = b;
                }
            }
        }
        Instr.Switch dispatch = new Instr.Switch(v, otherwise, sw.pos());
        for (Map.Entry<Stmt.Case, Block> e : blocks.entrySet()) {
            if (e.getKey().value() != null) {
                dispatch.cases.add(e.getKey().value());
                dispatch.targets.add(e.getValue());
            }
        }
        emit(dispatch);
        deadBlock(); // statements before the first case label are not reached
        targets.push(new Targets(exit, null));
        for (Stmt s : sw.body()) {
            if (s instanceof Stmt.Case c) {
                Block b = blocks.get(c);
                jump(b, c.pos()); // fall through from the case before
                seal(b);
                cur = b;
            } else {
                statement(s);
            }
        }
        targets.pop();
        jump(exit, sw.pos());
        seal(exit);
        cur = exit;
    }

    /** Branches on the truth of {@code e}; the targets are sealed by the caller. */
    private void branch(Expr e, Block ifTrue, Block ifFalse) {
        switch (e) {
            case Expr.Const k -> emit(new Instr.Jump(k.value() != 0 ? ifTrue : ifFalse, k.pos()));
            case Expr.LogicalNot n -> branch(n.operand(), ifFalse, ifTrue);
            case Expr.Logical l -> {
                Block second = mf.newBlock(l.and() ? "and" : "or");
                if (l.and()) {
                    branch(l.left(), second, ifFalse);
                } else {
                    branch(l.left(), ifTrue, second);
                }
                seal(second);
                cur = second;
                branch(l.right(), ifTrue, ifFalse);
            }
            case Expr.Binary b when b.op().isComparison() -> emit(new Instr.Branch(compare(b), ifTrue, ifFalse, b.pos()));
            default -> {
                Value v = value(e);
                Value zero = new Value.Const(v.type(), 0);
                Instr c = emit(new Instr.Cmp(Instr.Pred.NE, v, zero, e.pos()));
                emit(new Instr.Branch(c, ifTrue, ifFalse, e.pos()));
            }
        }
    }

    // ------------------------------------------------------------------ expressions

    /** The value of {@code e} (null for a struct assignment, which has none). */
    private Value value(Expr e) {
        SourcePos pos = e.pos();
        return switch (e) {
            case Expr.Const k -> new Value.Const(mtype(k.type()), k.value());
            case Expr.Var v -> ssa.contains(v.variable()) ? read(v.variable(), cur)
                    : emit(new Instr.Load(mtype(v.type()), address(v), false, pos));
            case Expr.Index i -> emit(new Instr.Load(mtype(i.type()), address(i), isVolatile(i), pos));
            case Expr.Member m -> emit(new Instr.Load(mtype(m.type()), address(m), isVolatile(m), pos));
            case Expr.Deref d -> emit(new Instr.Load(mtype(d.type()), address(d), isVolatile(d), pos));
            case Expr.Convert c -> convert(value(c.operand()), (Type.Int) c.operand().type(), c.type(), pos);
            case Expr.Unary u -> {
                Value x = value(u.operand());
                MType t = x.type();
                yield u.op() == Expr.UnOp.NEG
                        ? emit(new Instr.Bin(Instr.BinOp.SUB, new Value.Const(t, 0), x, pos))
                        : emit(new Instr.Bin(Instr.BinOp.XOR, x, new Value.Const(t, -1), pos));
            }
            case Expr.Binary b when b.op().isComparison() -> zext(compare(b), pos);
            case Expr.Binary b -> binary(b);
            case Expr.LogicalNot n -> truthValue(n);
            case Expr.Logical l -> truthValue(l);
            case Expr.Conditional c -> conditional(c);
            case Expr.Assign a -> assign(a);
            case Expr.PostIncDec p -> postIncDec(p);
            case Expr.Call c -> {
                List<Value> args = new ArrayList<>();
                for (Expr a : c.args()) {
                    args.add(value(a));
                }
                yield emit(new Instr.Call(c.function().label(), mtype(c.function().returnType()), args, pos));
            }
            case Expr.AddressOf a -> address(a.lvalue());
            case Expr.Decay d -> address(d.array());
            case Expr.PointerAdd p -> {
                Value ptr = value(p.pointer());
                Value bytes = scaled(value(p.offset()), p.scale(), pos);
                if (p.subtract()) {
                    bytes = emit(new Instr.Bin(Instr.BinOp.SUB, new Value.Const(MType.I32, 0), bytes, pos));
                }
                yield emit(new Instr.PtrAdd(ptr, bytes, pos));
            }
            case Expr.PointerDiff p -> {
                Value l = emit(new Instr.Cast(Instr.CastKind.PTRTOINT, value(p.left()), MType.I32, pos));
                Value r = emit(new Instr.Cast(Instr.CastKind.PTRTOINT, value(p.right()), MType.I32, pos));
                Value d = emit(new Instr.Bin(Instr.BinOp.SUB, l, r, pos));
                yield p.scale() == 1 ? d : emit(new Instr.Bin(Instr.BinOp.SDIV, d, new Value.Const(MType.I32, p.scale()), pos));
            }
            case Expr.PointerCast c -> {
                Value x = value(c.operand());
                if (x.type() == MType.PTR) {
                    yield x;
                }
                Type.Int from = (Type.Int) c.operand().type();
                yield emit(new Instr.Cast(Instr.CastKind.INTTOPTR, convert(x, from, Type.U32, pos), MType.PTR, pos));
            }
        };
    }

    private Value zext(Value i1, SourcePos pos) {
        return emit(new Instr.Cast(Instr.CastKind.ZEXT, i1, MType.I32, pos));
    }

    /** An int-type conversion as C does it: the source's signedness decides the extension. */
    private Value convert(Value x, Type.Int from, Type.Int to, SourcePos pos) {
        MType f = mtype(from);
        MType t = mtype(to);
        if (f == t) {
            return x;
        }
        if (x instanceof Value.Const k) {
            long v = from.signed() ? f.signed(k.bits()) : k.bits();
            return new Value.Const(t, v);
        }
        if (t.bits() > f.bits()) {
            return emit(new Instr.Cast(from.signed() ? Instr.CastKind.SEXT : Instr.CastKind.ZEXT, x, t, pos));
        }
        return emit(new Instr.Cast(Instr.CastKind.TRUNC, x, t, pos));
    }

    private Value binary(Expr.Binary b) {
        Value l = value(b.left());
        Value r = value(b.right());
        boolean signed = b.type().signed();
        Instr.BinOp op = switch (b.op()) {
            case ADD -> Instr.BinOp.ADD;
            case SUB -> Instr.BinOp.SUB;
            case MUL -> Instr.BinOp.MUL;
            case DIV -> signed ? Instr.BinOp.SDIV : Instr.BinOp.UDIV;
            case MOD -> signed ? Instr.BinOp.SREM : Instr.BinOp.UREM;
            case SHL -> Instr.BinOp.SHL;
            case SHR -> signed ? Instr.BinOp.ASHR : Instr.BinOp.LSHR;
            case AND -> Instr.BinOp.AND;
            case OR -> Instr.BinOp.OR;
            case XOR -> Instr.BinOp.XOR;
            default -> throw new IllegalStateException(b.op().toString());
        };
        return emit(new Instr.Bin(op, l, r, b.pos()));
    }

    private Instr compare(Expr.Binary b) {
        Value l = value(b.left());
        Value r = value(b.right());
        boolean signed = b.left().type() instanceof Type.Int t && t.signed();
        Instr.Pred p = switch (b.op()) {
            case EQ -> Instr.Pred.EQ;
            case NE -> Instr.Pred.NE;
            case LT -> signed ? Instr.Pred.SLT : Instr.Pred.ULT;
            case LE -> signed ? Instr.Pred.SLE : Instr.Pred.ULE;
            case GT -> signed ? Instr.Pred.SGT : Instr.Pred.UGT;
            case GE -> signed ? Instr.Pred.SGE : Instr.Pred.UGE;
            default -> throw new IllegalStateException(b.op().toString());
        };
        return emit(new Instr.Cmp(p, l, r, b.pos()));
    }

    /** 0 or 1 as an int, from a truth value computed by branching. */
    private Value truthValue(Expr e) {
        Block yes = mf.newBlock("true");
        Block no = mf.newBlock("false");
        Block join = mf.newBlock("bool");
        branch(e, yes, no);
        seal(yes);
        seal(no);
        yes.add(new Instr.Jump(join, e.pos()));
        no.add(new Instr.Jump(join, e.pos()));
        seal(join);
        cur = join;
        Instr.Phi phi = join.add(new Instr.Phi(MType.I32, e.pos()));
        phi.add(yes, new Value.Const(MType.I32, 1));
        phi.add(no, new Value.Const(MType.I32, 0));
        return phi;
    }

    private Value conditional(Expr.Conditional c) {
        Block yes = mf.newBlock("then");
        Block no = mf.newBlock("else");
        Block join = mf.newBlock("cond");
        branch(c.cond(), yes, no);
        seal(yes);
        seal(no);
        cur = yes;
        Value a = value(c.then());
        Block yesEnd = cur;
        emit(new Instr.Jump(join, c.pos()));
        cur = no;
        Value b = value(c.otherwise());
        Block noEnd = cur;
        emit(new Instr.Jump(join, c.pos()));
        seal(join);
        cur = join;
        Instr.Phi phi = join.add(new Instr.Phi(a.type(), c.pos()));
        phi.add(yesEnd, a);
        phi.add(noEnd, b);
        return phi;
    }

    private Value assign(Expr.Assign a) {
        SourcePos pos = a.pos();
        if (a.target() instanceof Expr.Var v && ssa.contains(v.variable())) {
            Value x = value(a.value());
            write(v.variable(), cur, x);
            return x;
        }
        if (!Type.isScalar(a.target().type())) {
            Value to = address(a.target());
            Value from = address(a.value());
            emit(new Instr.Copy(to, from, a.target().type().size(), pos));
            return null;
        }
        Value to = address(a.target());
        Value x = value(a.value());
        emit(new Instr.Store(to, x, isVolatile(a.target()), pos));
        return x;
    }

    private Value postIncDec(Expr.PostIncDec p) {
        SourcePos pos = p.pos();
        if (p.target() instanceof Expr.Var v && ssa.contains(v.variable())) {
            Value old = read(v.variable(), cur);
            write(v.variable(), cur, stepped(old, p, pos));
            return old;
        }
        Value at = address(p.target());
        boolean vol = isVolatile(p.target());
        Value old = emit(new Instr.Load(mtype(p.type()), at, vol, pos));
        emit(new Instr.Store(at, stepped(old, p, pos), vol, pos));
        return old;
    }

    private Value stepped(Value old, Expr.PostIncDec p, SourcePos pos) {
        if (old.type() == MType.PTR) {
            return emit(new Instr.PtrAdd(old, new Value.Const(MType.I32, p.increment() ? p.step() : -p.step()), pos));
        }
        return emit(new Instr.Bin(p.increment() ? Instr.BinOp.ADD : Instr.BinOp.SUB, old, new Value.Const(old.type(), p.step()), pos));
    }

    private Value scaled(Value index, int size, SourcePos pos) {
        return size == 1 ? index : emit(new Instr.Bin(Instr.BinOp.MUL, index, new Value.Const(MType.I32, size), pos));
    }

    /** The address of an lvalue. */
    private Value address(Expr lv) {
        SourcePos pos = lv.pos();
        return switch (lv) {
            case Expr.Var v -> {
                Variable var = v.variable();
                if (ssa.contains(var)) {
                    throw new IllegalStateException("the address of SSA variable " + var.name());
                }
                Value.Slot s = slotOf.get(var);
                yield s != null ? new Value.SlotAddr(s) : new Value.GlobalAddr(var.label());
            }
            case Expr.Member m -> {
                Value base = address(m.base());
                yield m.field().offset() == 0 ? base
                        : emit(new Instr.PtrAdd(base, new Value.Const(MType.I32, m.field().offset()), pos));
            }
            case Expr.Deref d -> value(d.pointer());
            case Expr.Index i -> {
                Value base = i.base().type() instanceof Type.Array ? address(i.base()) : value(i.base());
                Value bytes = scaled(value(i.index()), i.type().size(), pos);
                yield emit(new Instr.PtrAdd(base, bytes, pos));
            }
            default -> throw new IllegalStateException("not an lvalue: " + lv);
        };
    }

    /** Accesses through a pointer to volatile memory (an I/O register). */
    private static boolean isVolatile(Expr lv) {
        return switch (lv) {
            case Expr.Deref d -> d.pointer().type() instanceof Type.Pointer p && p.volatileTarget();
            case Expr.Index i -> i.base().type() instanceof Type.Pointer p && p.volatileTarget();
            case Expr.Member m -> isVolatile(m.base());
            default -> false;
        };
    }

    static MType mtype(Type t) {
        return switch (t) {
            case Type.Void v -> MType.VOID;
            case Type.Int i -> switch (i.bits()) {
                case 8 -> MType.I8;
                case 16 -> MType.I16;
                default -> MType.I32;
            };
            case Type.Pointer p -> MType.PTR;
            default -> throw new IllegalStateException("no MIR type for " + t);
        };
    }
}
