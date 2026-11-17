package dampf.frontend;

import dampf.ast.Expr;
import dampf.ast.Function;
import dampf.ast.Program;
import dampf.ast.Stmt;
import dampf.ast.Type;
import dampf.ast.Variable;
import dampf.parser.CParser;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

/**
 * Turns parse trees into the typed AST of the whole program. Applies C's typing rules exactly
 * as gcc does on x86-64 (integer promotions to a 32-bit int, usual arithmetic conversions) and
 * makes every implicit conversion explicit.
 *
 * <p>It accepts only what the current code generator supports and stops with a clear message at
 * anything else. The full subset checker comes later; the one subset rule enforced here already
 * is that plain {@code char}, {@code short}, {@code int} and {@code long} may not be written in
 * game code, only in dampf's own {@code <stdint.h>}.
 */
public final class AstBuilder {
    private final Path systemHeaders;

    // program-wide
    private final Map<String, Function> externFunctions = new LinkedHashMap<>();
    private final Map<String, Variable> externGlobals = new LinkedHashMap<>();
    private final List<Function> functions = new ArrayList<>();
    private final List<Variable> globals = new ArrayList<>();
    private final Map<Variable, Long> initialValues = new HashMap<>();
    private final Set<Variable> definedGlobals = new HashSet<>();
    private final Map<Function, SourcePos> firstCall = new HashMap<>();
    private final Set<String> labels = new HashSet<>();

    // per file
    private SourceMap sourceMap;
    private int fileIndex;
    private Map<String, Type> typedefs;
    private Map<String, Object> fileScope;
    private Map<String, Type> enumTags;

    // per function
    private Function current;
    private List<Variable> currentLocals;
    /** Block scopes: variables and enumeration constants ({@link EnumConst}). */
    private final Deque<Map<String, Object>> blockScopes = new ArrayDeque<>();
    private int loopDepth;
    private int breakDepth;

    public AstBuilder(Path systemHeaders) {
        this.systemHeaders = systemHeaders.toAbsolutePath().normalize();
    }

    /** Adds one parsed translation unit. */
    public void addFile(Parser.Result parsed) throws CompileError {
        if (!parsed.ok()) {
            throw new CompileError(parsed.diagnostics());
        }
        sourceMap = parsed.sourceMap();
        fileIndex++;
        typedefs = new HashMap<>();
        fileScope = new HashMap<>();
        enumTags = new HashMap<>();
        CParser.TranslationUnitContext tu = parsed.tree().translationUnit();
        if (tu == null) {
            return;
        }
        for (CParser.ExternalDeclarationContext ed : tu.externalDeclaration()) {
            if (ed.functionDefinition() != null) {
                functionDefinition(ed.functionDefinition());
            } else if (ed.declaration() != null) {
                topLevelDeclaration(ed.declaration());
            } else if (ed.asmDefinition() != null) {
                throw unsupported(ed, "inline assembly");
            }
        }
    }

    /** Checks the program as a whole and returns it. */
    public Program finish() throws CompileError {
        for (Map.Entry<Function, SourcePos> e : firstCall.entrySet()) {
            if (!e.getKey().isDefined()) {
                throw new CompileError(e.getValue(), "function '" + e.getKey().name() + "' is called but never defined");
            }
        }
        for (Variable v : globals) {
            if (!definedGlobals.contains(v)) {
                throw new CompileError(v.pos(), "'" + v.name() + "' is declared extern but never defined");
            }
        }
        return new Program(List.copyOf(globals), Map.copyOf(initialValues), List.copyOf(functions));
    }

    // ---------------------------------------------------------------- declarations

    private enum Storage { NONE, STATIC, EXTERN, TYPEDEF }

    private record Specs(Storage storage, Type type) {}

    private record Param(String name, Type type, SourcePos pos) {}

    /** A declarator: a name, and for a function its parameters. */
    private record Declarator(String name, SourcePos pos, List<Param> params) {
        boolean isFunction() {
            return params != null;
        }
    }

    private Specs specs(List<? extends ParserRuleContext> specifiers, ParserRuleContext where) throws CompileError {
        Storage storage = Storage.NONE;
        List<String> keywords = new ArrayList<>();
        Type named = null;
        for (ParserRuleContext s : specifiers) {
            ParserRuleContext inner = (ParserRuleContext) s.getChild(0);
            switch (inner) {
                case CParser.StorageClassSpecifierContext sc -> {
                    Storage st = switch (sc.getText()) {
                        case "static" -> Storage.STATIC;
                        case "extern" -> Storage.EXTERN;
                        case "typedef" -> Storage.TYPEDEF;
                        default -> throw unsupported(sc, "'" + sc.getText() + "'");
                    };
                    if (storage != Storage.NONE) {
                        throw error(sc, "more than one storage class");
                    }
                    storage = st;
                }
                case CParser.TypeQualifierContext tq -> {
                    if (!tq.getText().equals("const")) {
                        throw unsupported(tq, "'" + tq.getText() + "'");
                    }
                }
                case CParser.TypeSpecifierContext ts -> {
                    if (ts.typedefName() != null) {
                        named = typedefs.get(ts.typedefName().getText());
                        if (named == null) {
                            throw error(ts, "unknown type '" + ts.typedefName().getText() + "'");
                        }
                    } else if (ts.structOrUnionSpecifier() != null) {
                        throw unsupported(ts, "structs and unions");
                    } else if (ts.enumSpecifier() != null) {
                        named = enumType(ts.enumSpecifier());
                    } else {
                        keywords.add(ts.getText());
                    }
                }
                default -> throw unsupported(inner, "'" + inner.getText() + "'");
            }
        }
        if (named != null) {
            if (!keywords.isEmpty()) {
                throw error(where, "'" + keywords.getFirst() + "' cannot be combined with a typedef name");
            }
            return new Specs(storage, named);
        }
        return new Specs(storage, typeFromKeywords(keywords, where));
    }

    private Type typeFromKeywords(List<String> kw, ParserRuleContext where) throws CompileError {
        if (kw.equals(List.of("void"))) {
            return Type.VOID;
        }
        for (String k : kw) {
            switch (k) {
                case "signed", "unsigned", "char", "short", "int" -> { }
                case "long" -> throw new CompileError(pos(where),
                        "'long' is not supported: use int32_t or uint32_t (long is 64 bit on the PC)");
                case "float", "double" -> throw new CompileError(pos(where), "'" + k + "' is not supported: dampf has no floating point");
                case "_Bool" -> throw unsupported(where, "_Bool");
                default -> throw unsupported(where, "type '" + k + "'");
            }
        }
        if (kw.isEmpty()) {
            throw error(where, "missing type (C99 has no implicit int)");
        }
        boolean unsigned = kw.contains("unsigned");
        Type.Int t;
        if (kw.contains("char")) {
            if (!kw.contains("signed") && !unsigned && !inSystemHeader(where)) {
                throw new CompileError(pos(where), "plain 'char' is not supported yet: use int8_t or uint8_t (see docs/subset.md, decision 1)");
            }
            t = unsigned ? Type.U8 : Type.I8;
        } else if (kw.contains("short")) {
            t = unsigned ? Type.U16 : Type.I16;
        } else {
            t = unsigned ? Type.U32 : Type.I32;
        }
        if (!inSystemHeader(where)) {
            throw new CompileError(pos(where), "'" + String.join(" ", kw) + "' is not allowed: use " + t
                    + " (plain C integer types differ between the PC and the SNES)");
        }
        return t;
    }

    private boolean inSystemHeader(ParserRuleContext ctx) {
        try {
            return Path.of(pos(ctx).file()).toAbsolutePath().normalize().startsWith(systemHeaders);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** An enumeration constant: in C it has type int. */
    private record EnumConst(String name, long value) {}

    /**
     * An enum type, as gcc sees it: unsigned int if no enumerator is negative, otherwise int.
     * Its enumerators go into the current scope.
     */
    private Type enumType(CParser.EnumSpecifierContext es) throws CompileError {
        String tag = es.Identifier() != null ? es.Identifier().getText() : null;
        if (es.enumTypeSpecifier() != null) {
            throw unsupported(es, "enums with a fixed underlying type");
        }
        if (es.enumeratorList() == null) {
            Type t = enumTags.get(tag);
            if (t == null) {
                throw error(es, "unknown enum '" + tag + "'");
            }
            return t;
        }
        long next = 0;
        boolean negative = false;
        Map<String, Object> scope = blockScopes.isEmpty() ? fileScope : blockScopes.peek();
        for (CParser.EnumeratorContext en : es.enumeratorList().enumerator()) {
            String name = en.enumerationConstant().getText();
            long value = next;
            if (en.constantExpression() != null) {
                Long v = fold(conditional(en.constantExpression().conditionalExpression()));
                if (v == null) {
                    throw error(en, "the value of '" + name + "' must be an integer constant");
                }
                value = v;
            }
            if (value < Type.I32.min() || value > Type.I32.max()) {
                throw error(en, "the value of '" + name + "' does not fit in an int");
            }
            if (scope.containsKey(name)) {
                throw error(en, "'" + name + "' is already declared");
            }
            scope.put(name, new EnumConst(name, value));
            negative |= value < 0;
            next = value + 1;
        }
        Type t = negative ? Type.I32 : Type.U32;
        if (tag != null) {
            enumTags.put(tag, t);
        }
        return t;
    }

    private Declarator declarator(CParser.DeclaratorContext d) throws CompileError {
        if (!d.pointer().isEmpty()) {
            throw unsupported(d, "pointers");
        }
        CParser.DirectDeclaratorContext dd = d.directDeclarator();
        if (dd.Identifier() == null || dd.getChildCount() > 1 && dd.getChild(1) instanceof TerminalNode t && t.getText().equals(":")) {
            throw unsupported(dd, "this kind of declarator");
        }
        String name = dd.Identifier().getText();
        List<Param> params = null;
        for (int i = 1; i < dd.getChildCount(); i++) {
            ParseTree c = dd.getChild(i);
            if (c instanceof TerminalNode t && t.getText().equals("[")) {
                throw unsupported(dd, "arrays");
            }
            if (c instanceof TerminalNode t && t.getText().equals("(")) {
                if (params != null) {
                    throw unsupported(dd, "functions returning functions");
                }
                params = new ArrayList<>();
                if (dd.getChild(i + 1) instanceof CParser.ParameterTypeListContext ptl) {
                    params = parameters(ptl);
                }
            } else if (c instanceof CParser.AttributeSpecifierSequenceContext) {
                throw unsupported(dd, "attributes");
            }
        }
        return new Declarator(name, pos(dd), params);
    }

    private List<Param> parameters(CParser.ParameterTypeListContext ptl) throws CompileError {
        if (ptl.parameterList() == null || ptl.getText().endsWith("...")) {
            throw new CompileError(pos(ptl), "variadic functions are not supported");
        }
        List<Param> params = new ArrayList<>();
        List<CParser.ParameterDeclarationContext> decls = ptl.parameterList().parameterDeclaration();
        for (CParser.ParameterDeclarationContext pd : decls) {
            if (pd.declarationSpecifiers() == null) {
                throw error(pd, "missing parameter type");
            }
            Specs s = specs(pd.declarationSpecifiers().declarationSpecifier(), pd);
            if (s.storage() != Storage.NONE) {
                throw error(pd, "storage class on a parameter");
            }
            if (pd.abstractDeclarator() != null) {
                throw unsupported(pd, "this kind of parameter");
            }
            if (s.type() instanceof Type.Void) {
                if (decls.size() != 1 || pd.declarator() != null) {
                    throw error(pd, "'void' must be the only parameter");
                }
                return List.of();
            }
            if (pd.declarator() == null) {
                params.add(new Param(null, s.type(), pos(pd)));
            } else {
                Declarator d = declarator(pd.declarator());
                if (d.isFunction()) {
                    throw unsupported(pd, "function pointers");
                }
                params.add(new Param(d.name(), s.type(), d.pos()));
            }
        }
        return params;
    }

    private void topLevelDeclaration(CParser.DeclarationContext d) throws CompileError {
        if (d.declarationSpecifiers() == null) {
            throw unsupported(d, "this kind of declaration");
        }
        Specs s = specs(d.declarationSpecifiers().declarationSpecifier(), d);
        if (d.initDeclaratorList() == null) {
            return; // e.g. "int;" - nothing declared
        }
        for (CParser.InitDeclaratorContext id : d.initDeclaratorList().initDeclarator()) {
            Declarator dec = declarator(id.declarator());
            if (s.storage() == Storage.TYPEDEF) {
                if (dec.isFunction() || id.initializer() != null) {
                    throw unsupported(id, "this kind of typedef");
                }
                typedefs.put(dec.name(), s.type());
            } else if (dec.isFunction()) {
                if (id.initializer() != null) {
                    throw error(id, "a function cannot have an initializer");
                }
                declareFunction(dec, s);
            } else {
                globalVariable(dec, s, id.initializer());
            }
        }
    }

    private Function declareFunction(Declarator dec, Specs s) throws CompileError {
        List<Type> paramTypes = dec.params().stream().map(Param::type).toList();
        Object existing = fileScope.get(dec.name());
        if (existing == null && s.storage() != Storage.STATIC) {
            existing = externFunctions.get(dec.name());
        }
        if (existing instanceof Function f) {
            if (!f.returnType().equals(s.type()) || !f.paramTypes().equals(paramTypes)) {
                throw new CompileError(dec.pos(), "conflicting types for '" + dec.name() + "' (first declared at " + f.pos() + ")");
            }
            fileScope.put(dec.name(), f);
            return f;
        }
        if (existing != null) {
            throw new CompileError(dec.pos(), "'" + dec.name() + "' is already declared as a variable");
        }
        Function f = new Function(dec.name(), uniqueLabel("_" + dec.name()), s.type(), paramTypes, dec.pos());
        functions.add(f);
        fileScope.put(dec.name(), f);
        if (s.storage() != Storage.STATIC) {
            externFunctions.put(dec.name(), f);
        }
        return f;
    }

    private void globalVariable(Declarator dec, Specs s, CParser.InitializerContext init) throws CompileError {
        if (!(s.type() instanceof Type.Int t)) {
            throw new CompileError(dec.pos(), "variable '" + dec.name() + "' has type void");
        }
        Object existing = fileScope.get(dec.name());
        if (existing == null && s.storage() != Storage.STATIC) {
            existing = externGlobals.get(dec.name());
        }
        Variable v;
        if (existing instanceof Variable old) {
            if (!old.type().equals(t)) {
                throw new CompileError(dec.pos(), "conflicting types for '" + dec.name() + "' (first declared at " + old.pos() + ")");
            }
            v = old;
        } else if (existing != null) {
            throw new CompileError(dec.pos(), "'" + dec.name() + "' is already declared as a function");
        } else {
            v = new Variable(dec.name(), uniqueLabel("_" + dec.name()), t, Variable.Kind.GLOBAL, dec.pos());
            globals.add(v);
            if (s.storage() != Storage.STATIC) {
                externGlobals.put(dec.name(), v);
            }
        }
        fileScope.put(dec.name(), v);
        if (s.storage() != Storage.EXTERN) {
            definedGlobals.add(v);
        }
        if (init != null) {
            if (s.storage() == Storage.EXTERN) {
                throw error(init, "an extern declaration cannot have an initializer");
            }
            if (initialValues.containsKey(v)) {
                throw error(init, "'" + dec.name() + "' is initialised twice");
            }
            if (init.assignmentExpression() == null) {
                throw unsupported(init, "brace initializers");
            }
            Expr e = convert(assignment(init.assignmentExpression()), t, pos(init));
            Long value = fold(e);
            if (value == null) {
                throw error(init, "the initializer of a global must be a constant");
            }
            initialValues.put(v, value);
        }
    }

    // ---------------------------------------------------------------- functions and statements

    private void functionDefinition(CParser.FunctionDefinitionContext fd) throws CompileError {
        if (fd.declarationList() != null) {
            throw unsupported(fd, "old-style (K&R) parameter declarations");
        }
        if (fd.declarationSpecifiers() == null) {
            throw error(fd, "missing return type (C99 has no implicit int)");
        }
        Specs s = specs(fd.declarationSpecifiers().declarationSpecifier(), fd);
        if (s.storage() == Storage.TYPEDEF) {
            throw error(fd, "a function definition cannot be a typedef");
        }
        Declarator dec = declarator(fd.declarator());
        if (!dec.isFunction()) {
            throw error(fd, "expected a function");
        }
        Function f = declareFunction(dec, s);
        if (f.isDefined()) {
            throw new CompileError(dec.pos(), "redefinition of '" + f.name() + "' (first defined at " + f.definitionPos() + ")");
        }
        current = f;
        currentLocals = f.locals();
        blockScopes.clear();
        Map<String, Object> paramScope = new LinkedHashMap<>();
        List<Variable> params = new ArrayList<>();
        for (Param p : dec.params()) {
            if (p.name() == null) {
                throw new CompileError(p.pos(), "parameter name missing in a function definition");
            }
            Variable v = new Variable(p.name(), uniqueLabel(f.label() + "__" + p.name()), p.type(), Variable.Kind.PARAM, p.pos());
            if (paramScope.put(p.name(), v) != null) {
                throw new CompileError(p.pos(), "duplicate parameter '" + p.name() + "'");
            }
            params.add(v);
        }
        blockScopes.push(paramScope);
        Stmt.Block body = block(fd.functionBody().compoundStatement());
        blockScopes.pop();
        f.define(params, body, dec.pos());
        current = null;
    }

    private Stmt.Block block(CParser.CompoundStatementContext cs) throws CompileError {
        blockScopes.push(new HashMap<>());
        List<Stmt> out = new ArrayList<>();
        if (cs.blockItemList() != null) {
            for (CParser.BlockItemContext item : cs.blockItemList().blockItem()) {
                if (item.declaration() != null) {
                    localDeclaration(item.declaration(), out);
                } else {
                    Stmt st = statement(item.statement());
                    if (st != null) {
                        out.add(st);
                    }
                }
            }
        }
        blockScopes.pop();
        return new Stmt.Block(out, pos(cs), pos(cs.getStop()));
    }

    private void localDeclaration(CParser.DeclarationContext d, List<Stmt> out) throws CompileError {
        if (d.declarationSpecifiers() == null) {
            throw unsupported(d, "this kind of declaration");
        }
        localDeclaration(d.declarationSpecifiers(), d.initDeclaratorList(), d, out);
    }

    private void localDeclaration(CParser.DeclarationSpecifiersContext specs, CParser.InitDeclaratorListContext list,
                                  ParserRuleContext d, List<Stmt> out) throws CompileError {
        Specs s = specs(specs.declarationSpecifier(), d);
        if (s.storage() == Storage.EXTERN || s.storage() == Storage.TYPEDEF) {
            throw unsupported(d, "'" + s.storage().name().toLowerCase() + "' inside a function");
        }
        if (list == null) {
            return;
        }
        for (CParser.InitDeclaratorContext id : list.initDeclarator()) {
            Declarator dec = declarator(id.declarator());
            if (dec.isFunction()) {
                throw unsupported(id, "function declarations inside a function");
            }
            if (!(s.type() instanceof Type.Int t)) {
                throw new CompileError(dec.pos(), "variable '" + dec.name() + "' has type void");
            }
            Map<String, Object> scope = blockScopes.peek();
            if (scope.containsKey(dec.name())) {
                throw new CompileError(dec.pos(), "redeclaration of '" + dec.name() + "'");
            }
            if (s.storage() == Storage.STATIC) {
                // a static local is a global with a name only this block sees
                Variable v = new Variable(dec.name(), uniqueLabel(current.label() + "__" + dec.name()), t, Variable.Kind.STATIC_LOCAL, dec.pos());
                globals.add(v);
                definedGlobals.add(v);
                scope.put(dec.name(), v);
                if (id.initializer() != null) {
                    if (id.initializer().assignmentExpression() == null) {
                        throw unsupported(id.initializer(), "brace initializers");
                    }
                    Long value = fold(convert(assignment(id.initializer().assignmentExpression()), t, pos(id.initializer())));
                    if (value == null) {
                        throw error(id.initializer(), "the initializer of a static variable must be a constant");
                    }
                    initialValues.put(v, value);
                }
                continue;
            }
            Variable v = new Variable(dec.name(), uniqueLabel(current.label() + "__" + dec.name()), t, Variable.Kind.LOCAL, dec.pos());
            currentLocals.add(v);
            if (id.initializer() != null) {
                if (id.initializer().assignmentExpression() == null) {
                    throw unsupported(id.initializer(), "brace initializers");
                }
                // C: the variable is in scope inside its own initializer
                scope.put(dec.name(), v);
                Expr value = convert(assignment(id.initializer().assignmentExpression()), t, pos(id.initializer()));
                out.add(new Stmt.ExprStmt(new Expr.Assign(v, value, dec.pos()), pos(d)));
            }
            scope.put(dec.name(), v);
        }
    }

    private Stmt statement(CParser.StatementContext st) throws CompileError {
        if (st.compoundStatement() != null) {
            return block(st.compoundStatement());
        }
        if (st.expressionStatement() != null) {
            CParser.ExpressionStatementContext es = st.expressionStatement();
            return es.expression() == null ? null : new Stmt.ExprStmt(discarded(expression(es.expression())), pos(st));
        }
        if (st.jumpStatement() != null) {
            CParser.JumpStatementContext js = st.jumpStatement();
            switch (js.getStart().getText()) {
                case "break" -> {
                    if (breakDepth == 0) {
                        throw error(js, "'break' outside a loop or switch");
                    }
                    return new Stmt.Break(pos(st));
                }
                case "continue" -> {
                    if (loopDepth == 0) {
                        throw error(js, "'continue' outside a loop");
                    }
                    return new Stmt.Continue(pos(st));
                }
                case "return" -> { }
                default -> throw unsupported(js, "'" + js.getStart().getText() + "'");
            }
            Type rt = current.returnType();
            if (js.expression() == null) {
                if (!(rt instanceof Type.Void)) {
                    throw error(js, "'return' without a value in a function returning " + rt);
                }
                return new Stmt.Return(null, pos(st));
            }
            if (!(rt instanceof Type.Int t)) {
                throw error(js, "'return' with a value in a void function");
            }
            return new Stmt.Return(convert(expression(js.expression()), t, pos(js.expression())), pos(st));
        }
        if (st.selectionStatement() != null) {
            CParser.SelectionStatementContext sel = st.selectionStatement();
            if (sel.getStart().getText().equals("switch")) {
                return switchStatement(sel);
            }
            Expr cond = condition(sel.expression());
            Stmt then = substatement(sel.statement(0));
            Stmt otherwise = sel.statement().size() > 1 ? substatement(sel.statement(1)) : null;
            return new Stmt.If(cond, then, otherwise, pos(st));
        }
        if (st.iterationStatement() != null) {
            return loop(st.iterationStatement());
        }
        if (st.labeledStatement() != null) {
            String first = st.getStart().getText();
            if (first.equals("case") || first.equals("default")) {
                throw unsupported(st, "'" + first + "' labels that are not directly in the switch body");
            }
            throw unsupported(st, "labels and goto");
        }
        throw unsupported(st, "this statement");
    }

    /** A statement that is a branch or loop body: an empty statement becomes an empty block. */
    private Stmt substatement(CParser.StatementContext st) throws CompileError {
        Stmt s = statement(st);
        return s != null ? s : new Stmt.Block(List.of(), pos(st), pos(st));
    }

    private Expr condition(CParser.ExpressionContext e) throws CompileError {
        Expr c = expression(e);
        intType(c, e);
        return c;
    }

    /** An expression whose value is not used: x++ becomes ++x, which is simpler to compile. */
    private static Expr discarded(Expr e) {
        if (e instanceof Expr.PostIncDec p) {
            Type.Int t = (Type.Int) p.target().type();
            Type.Int pt = Type.common(t, Type.I32);
            Expr old = new Expr.Var(p.target(), p.pos());
            Expr widened = t.equals(pt) ? old : new Expr.Convert(old, pt, false, p.pos());
            Expr sum = new Expr.Binary(p.increment() ? Expr.BinOp.ADD : Expr.BinOp.SUB, widened, new Expr.Const(1, pt, p.pos()), pt, p.pos());
            Expr value = t.equals(pt) ? sum : new Expr.Convert(sum, t, false, p.pos());
            return new Expr.Assign(p.target(), value, p.pos());
        }
        return e;
    }

    private Stmt loop(CParser.IterationStatementContext it) throws CompileError {
        String kind = it.getStart().getText();
        SourcePos p = pos(it);
        switch (kind) {
            case "while" -> {
                Expr cond = condition(it.expression());
                return new Stmt.While(cond, loopBody(it.statement()), p);
            }
            case "do" -> {
                Stmt body = loopBody(it.statement());
                return new Stmt.DoWhile(body, condition(it.expression()), p);
            }
            default -> {
                CParser.ForConditionContext fc = it.forCondition();
                blockScopes.push(new HashMap<>());
                Stmt init = null;
                if (fc.forDeclaration() != null) {
                    List<Stmt> decl = new ArrayList<>();
                    CParser.ForDeclarationContext d = fc.forDeclaration();
                    localDeclaration(d.declarationSpecifiers(), d.initDeclaratorList(), d, decl);
                    init = new Stmt.Block(decl, pos(d), pos(d));
                } else if (fc.expression() != null) {
                    init = new Stmt.ExprStmt(discarded(expression(fc.expression())), pos(fc.expression()));
                }
                // forCondition: (decl | expr?) ';' forExpression? ';' forExpression?
                CParser.ForExpressionContext condCtx = null;
                CParser.ForExpressionContext stepCtx = null;
                int semis = 0;
                for (ParseTree c : fc.children) {
                    if (c instanceof TerminalNode t && t.getText().equals(";")) {
                        semis++;
                    } else if (c instanceof CParser.ForExpressionContext fe) {
                        if (semis == 1) {
                            condCtx = fe;
                        } else {
                            stepCtx = fe;
                        }
                    }
                }
                Expr cond = null;
                if (condCtx != null) {
                    if (condCtx.assignmentExpression().size() > 1) {
                        throw unsupported(condCtx, "the comma operator");
                    }
                    cond = assignment(condCtx.assignmentExpression(0));
                    intType(cond, condCtx);
                }
                List<Expr> step = new ArrayList<>();
                if (stepCtx != null) {
                    for (CParser.AssignmentExpressionContext a : stepCtx.assignmentExpression()) {
                        step.add(discarded(assignment(a)));
                    }
                }
                Stmt body = loopBody(it.statement());
                blockScopes.pop();
                return new Stmt.For(init, cond, step, body, p);
            }
        }
    }

    private Stmt loopBody(CParser.StatementContext st) throws CompileError {
        loopDepth++;
        breakDepth++;
        try {
            return substatement(st);
        } finally {
            loopDepth--;
            breakDepth--;
        }
    }

    private Stmt switchStatement(CParser.SelectionStatementContext sel) throws CompileError {
        Expr value = expression(sel.expression());
        Type.Int t = Type.promote(intType(value, sel));
        value = convert(value, t, pos(sel.expression()));
        CParser.StatementContext bodyCtx = sel.statement(0);
        if (bodyCtx.compoundStatement() == null) {
            throw unsupported(bodyCtx, "a switch body that is not a block");
        }
        CParser.CompoundStatementContext cs = bodyCtx.compoundStatement();
        List<Stmt> body = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        breakDepth++;
        blockScopes.push(new HashMap<>());
        if (cs.blockItemList() != null) {
            for (CParser.BlockItemContext item : cs.blockItemList().blockItem()) {
                if (item.declaration() != null) {
                    localDeclaration(item.declaration(), body);
                } else {
                    switchItem(item.statement(), t, seen, body);
                }
            }
        }
        blockScopes.pop();
        breakDepth--;
        return new Stmt.Switch(value, body, pos(sel));
    }

    /** A statement of a switch body; case labels become markers, followed by their statement. */
    private void switchItem(CParser.StatementContext st, Type.Int t, Set<Long> seen, List<Stmt> body) throws CompileError {
        CParser.LabeledStatementContext ls = st.labeledStatement();
        if (ls != null && (ls.getStart().getText().equals("case") || ls.getStart().getText().equals("default"))) {
            Long v = null;
            if (ls.getStart().getText().equals("case")) {
                v = fold(convert(chain(ls.constantExpression().conditionalExpression().logicalOrExpression()), t, pos(ls)));
                if (ls.constantExpression().conditionalExpression().expression() != null || v == null) {
                    throw error(ls, "a case label must be an integer constant");
                }
            }
            if (!seen.add(v)) {
                throw error(ls, v == null ? "more than one default label" : "duplicate case value " + v);
            }
            body.add(new Stmt.Case(v, pos(ls)));
            if (ls.statement() != null) {
                switchItem(ls.statement(), t, seen, body);
            }
            return;
        }
        Stmt s = statement(st);
        if (s != null) {
            body.add(s);
        }
    }

    // ---------------------------------------------------------------- expressions

    private Expr expression(CParser.ExpressionContext e) throws CompileError {
        if (e.assignmentExpression().size() > 1) {
            throw unsupported(e, "the comma operator");
        }
        return assignment(e.assignmentExpression(0));
    }

    private Expr assignment(CParser.AssignmentExpressionContext a) throws CompileError {
        if (a.conditionalExpression() != null) {
            return conditional(a.conditionalExpression());
        }
        if (a.DigitSequence() != null) {
            return integerConstant(a.DigitSequence().getSymbol());
        }
        Expr target = unary(a.unaryExpression());
        if (!(target instanceof Expr.Var var)) {
            throw error(a.unaryExpression(), "can only assign to a variable");
        }
        Type.Int t = (Type.Int) var.type();
        Token op = a.assignementOperator;
        Expr rhs = assignment(a.assignmentExpression());
        Expr value;
        if (op.getText().equals("=")) {
            value = convert(rhs, t, pos(op));
        } else {
            String bin = op.getText().substring(0, op.getText().length() - 1);
            value = convert(binary(bin, var, rhs, pos(op), a), t, pos(op));
        }
        return new Expr.Assign(var.variable(), value, pos(op));
    }

    private Expr conditional(CParser.ConditionalExpressionContext c) throws CompileError {
        Expr cond = chain(c.logicalOrExpression());
        if (c.expression() == null) {
            return cond;
        }
        intType(cond, c);
        Expr then = expression(c.expression());
        Expr otherwise = conditional(c.conditionalExpression());
        Type.Int t = Type.common(intType(then, c), intType(otherwise, c));
        SourcePos p = pos(c);
        Long k = fold(cond);
        if (k != null) {
            return convert(k != 0 ? then : otherwise, t, p);
        }
        return new Expr.Conditional(cond, convert(then, t, p), convert(otherwise, t, p), t, p);
    }

    /** The binary operator rules, from logical-or down to multiplicative: a left-to-right chain. */
    private Expr chain(ParserRuleContext ctx) throws CompileError {
        if (ctx instanceof CParser.CastExpressionContext c) {
            return cast(c);
        }
        Expr left = chain((ParserRuleContext) ctx.getChild(0));
        for (int i = 1; i < ctx.getChildCount(); i += 2) {
            TerminalNode op = (TerminalNode) ctx.getChild(i);
            Expr right = chain((ParserRuleContext) ctx.getChild(i + 1));
            left = binary(op.getText(), left, right, pos(op.getSymbol()), ctx);
        }
        return left;
    }

    private Expr binary(String op, Expr left, Expr right, SourcePos pos, ParserRuleContext where) throws CompileError {
        Type.Int lt = intType(left, where);
        Type.Int rt = intType(right, where);
        Expr result;
        if (op.equals("&&") || op.equals("||")) {
            result = new Expr.Logical(op.equals("&&"), left, right, pos);
        } else {
            Expr.BinOp bop = Expr.BinOp.of(op);
            if (bop == null) {
                throw new CompileError(pos, "operator '" + op + "' is not supported yet");
            }
            if (bop.isShift()) {
                Type.Int t = Type.promote(lt);
                result = new Expr.Binary(bop, convert(left, t, pos), convert(right, Type.promote(rt), pos), t, pos);
            } else {
                Type.Int t = Type.common(lt, rt);
                Type.Int resultType = bop.isComparison() ? Type.I32 : t;
                result = new Expr.Binary(bop, convert(left, t, pos), convert(right, t, pos), resultType, pos);
            }
        }
        Long k = fold(result);
        return k != null ? new Expr.Const(k, (Type.Int) result.type(), pos) : result;
    }

    private Expr cast(CParser.CastExpressionContext c) throws CompileError {
        if (c.DigitSequence() != null) {
            return integerConstant(c.DigitSequence().getSymbol());
        }
        if (c.unaryExpression() != null) {
            return unary(c.unaryExpression());
        }
        CParser.TypeNameContext tn = c.typeName();
        if (tn.abstractDeclarator() != null) {
            throw unsupported(tn, "pointer casts");
        }
        Specs s = specs(tn.specifierQualifierList().typeSpecifierQualifier(), tn);
        Expr operand = cast(c.castExpression());
        if (s.type() instanceof Type.Void) {
            throw unsupported(c, "casts to void");
        }
        Type.Int t = (Type.Int) s.type();
        intType(operand, c);
        if (operand instanceof Expr.Const k) {
            return new Expr.Const(t.wrap(k.value()), t, pos(c));
        }
        return new Expr.Convert(operand, t, true, pos(c));
    }

    private Expr unary(CParser.UnaryExpressionContext u) throws CompileError {
        ParseTree first = u.getChild(0);
        if (first instanceof TerminalNode t) {
            String op = t.getText();
            if (op.equals("++") || op.equals("--")) {
                // the grammar puts "++x" into one unaryExpression: the prefix, then the operand
                if (u.getChildCount() != 2 || u.postfixExpression() == null) {
                    throw unsupported(u, "this use of '" + op + "'");
                }
                Expr target = postfix(u.postfixExpression());
                if (!(target instanceof Expr.Var var)) {
                    throw error(u, "'" + op + "' needs a variable");
                }
                Type.Int vt = (Type.Int) var.type();
                Expr one = new Expr.Const(1, Type.I32, pos(t.getSymbol()));
                Expr sum = binary(op.equals("++") ? "+" : "-", var, one, pos(t.getSymbol()), u);
                return new Expr.Assign(var.variable(), convert(sum, vt, pos(u)), pos(u));
            }
            if (op.equals("sizeof") || op.equals("_Alignof") || op.equals("&&")) {
                throw unsupported(u, "'" + op + "'");
            }
        }
        if (u.unaryOperator != null) {
            String op = u.unaryOperator.getText();
            Expr operand = cast(u.castExpression());
            SourcePos p = pos(u.unaryOperator);
            return switch (op) {
                case "+" -> convert(operand, Type.promote(intType(operand, u)), p);
                case "-", "~" -> {
                    Type.Int t = Type.promote(intType(operand, u));
                    Expr.UnOp uop = op.equals("-") ? Expr.UnOp.NEG : Expr.UnOp.NOT;
                    Expr conv = convert(operand, t, p);
                    if (conv instanceof Expr.Const k) {
                        yield new Expr.Const(t.wrap(op.equals("-") ? -k.value() : ~k.value()), t, p);
                    }
                    yield new Expr.Unary(uop, conv, t, p);
                }
                case "!" -> {
                    intType(operand, u);
                    Long k = fold(operand);
                    yield k != null ? new Expr.Const(k == 0 ? 1 : 0, Type.I32, p) : new Expr.LogicalNot(operand, p);
                }
                default -> throw new CompileError(p, "operator '" + op + "' is not supported yet");
            };
        }
        if (u.postfixExpression() != null) {
            return postfix(u.postfixExpression());
        }
        throw unsupported(u, "this expression");
    }

    private Expr postfix(CParser.PostfixExpressionContext p) throws CompileError {
        CParser.PrimaryExpressionContext prim = p.primaryExpression();
        if (prim == null) {
            throw unsupported(p, "compound literals");
        }
        int n = p.getChildCount();
        if (n == 1) {
            return primary(prim);
        }
        String suffix = p.getChild(1).getText();
        if ((suffix.equals("++") || suffix.equals("--")) && n == 2) {
            Expr target = primary(prim);
            if (!(target instanceof Expr.Var var)) {
                throw error(p, "'" + suffix + "' needs a variable");
            }
            return new Expr.PostIncDec(var.variable(), suffix.equals("++"), pos(p));
        }
        if (suffix.equals("(")) {
            if (prim.Identifier() == null) {
                throw unsupported(p, "calls through expressions");
            }
            List<CParser.AssignmentExpressionContext> argCtx = p.argumentExpressionList(0) == null
                    ? List.of() : p.argumentExpressionList(0).assignmentExpression();
            int close = argCtx.isEmpty() ? 2 : 3;
            if (n > close + 1) {
                throw unsupported(p, "using the result of a call this way");
            }
            return call(prim.Identifier().getSymbol(), argCtx);
        }
        throw unsupported(p, switch (suffix) {
            case "[" -> "arrays";
            case ".", "->" -> "structs";
            default -> "postfix '" + suffix + "'";
        });
    }

    private Expr call(Token name, List<CParser.AssignmentExpressionContext> argCtx) throws CompileError {
        Object o = lookup(name.getText());
        if (o == null) {
            throw new CompileError(pos(name), "implicit declaration of function '" + name.getText() + "'");
        }
        if (!(o instanceof Function f)) {
            throw new CompileError(pos(name), "'" + name.getText() + "' is not a function");
        }
        if (argCtx.size() != f.paramTypes().size()) {
            throw new CompileError(pos(name), "'" + f.name() + "' takes " + f.paramTypes().size() + " argument(s), not " + argCtx.size());
        }
        List<Expr> args = new ArrayList<>();
        for (int i = 0; i < argCtx.size(); i++) {
            Expr a = assignment(argCtx.get(i));
            args.add(convert(a, (Type.Int) f.paramTypes().get(i), pos(argCtx.get(i))));
        }
        firstCall.putIfAbsent(f, pos(name));
        return new Expr.Call(f, args, pos(name));
    }

    private Expr primary(CParser.PrimaryExpressionContext prim) throws CompileError {
        if (prim.Identifier() != null) {
            String name = prim.Identifier().getText();
            Object o = lookup(name);
            if (o instanceof Variable v) {
                return new Expr.Var(v, pos(prim));
            }
            if (o instanceof EnumConst k) {
                return new Expr.Const(k.value(), Type.I32, pos(prim));
            }
            if (o instanceof Function) {
                throw unsupported(prim, "function pointers");
            }
            throw error(prim, "'" + name + "' is not declared");
        }
        if (prim.constant() != null) {
            CParser.ConstantContext c = prim.constant();
            if (c.IntegerConstant() != null) {
                return integerConstant(c.IntegerConstant().getSymbol());
            }
            if (c.CharacterConstant() != null) {
                return characterConstant(c.CharacterConstant().getSymbol());
            }
            throw unsupported(c, "this constant");
        }
        if (prim.expression() != null) {
            return expression(prim.expression());
        }
        if (!prim.StringLiteral().isEmpty()) {
            throw unsupported(prim, "string literals");
        }
        throw unsupported(prim, "this expression");
    }

    private Object lookup(String name) {
        for (Map<String, Object> scope : blockScopes) {
            Object o = scope.get(name);
            if (o != null) {
                return o;
            }
        }
        return fileScope.get(name);
    }

    /** Integer constant with its C type, as on x86-64 (C99 6.4.4.1). */
    private Expr integerConstant(Token tok) throws CompileError {
        String text = tok.getText().toLowerCase();
        boolean unsigned = false;
        while (text.endsWith("u") || text.endsWith("l")) {
            if (text.endsWith("l")) {
                throw new CompileError(pos(tok), "the 'l' suffix is not supported: on the PC it makes a 64-bit long");
            }
            unsigned = true;
            text = text.substring(0, text.length() - 1);
        }
        boolean decimal;
        long value;
        try {
            if (text.startsWith("0x")) {
                value = Long.parseLong(text.substring(2), 16);
                decimal = false;
            } else if (text.startsWith("0b")) {
                value = Long.parseLong(text.substring(2), 2);
                decimal = false;
            } else if (text.length() > 1 && text.startsWith("0")) {
                value = Long.parseLong(text.substring(1), 8);
                decimal = false;
            } else {
                value = Long.parseLong(text);
                decimal = true;
            }
        } catch (NumberFormatException e) {
            throw new CompileError(pos(tok), "integer constant '" + tok.getText() + "' is too large");
        }
        Type.Int t;
        if (!unsigned && value <= Type.I32.max()) {
            t = Type.I32;
        } else if ((unsigned || !decimal) && value <= Type.U32.max()) {
            t = Type.U32;
        } else {
            throw new CompileError(pos(tok), "integer constant '" + tok.getText() + "' does not fit in 32 bits (on the PC it would be a 64-bit long)");
        }
        return new Expr.Const(value, t, pos(tok));
    }

    private Expr characterConstant(Token tok) throws CompileError {
        String s = tok.getText();
        if (!s.startsWith("'")) {
            throw new CompileError(pos(tok), "wide character constants are not supported");
        }
        String body = s.substring(1, s.length() - 1);
        long v;
        if (body.length() == 1) {
            v = body.charAt(0);
        } else if (body.startsWith("\\")) {
            v = switch (body) {
                case "\\n" -> '\n';
                case "\\t" -> '\t';
                case "\\r" -> '\r';
                case "\\0" -> 0;
                case "\\\\" -> '\\';
                case "\\'" -> '\'';
                case "\\\"" -> '"';
                default -> {
                    if (body.startsWith("\\x")) {
                        yield Long.parseLong(body.substring(2), 16);
                    }
                    throw new CompileError(pos(tok), "character constant " + s + " is not supported");
                }
            };
        } else {
            throw new CompileError(pos(tok), "multi-character constants are not supported");
        }
        if (v > 127) {
            throw new CompileError(pos(tok), "character constants above 127 are not supported (their value depends on the signedness of char)");
        }
        return new Expr.Const(v, Type.I32, pos(tok));
    }

    // ---------------------------------------------------------------- helpers

    private Type.Int intType(Expr e, ParserRuleContext where) throws CompileError {
        if (e.type() instanceof Type.Int t) {
            return t;
        }
        throw error(where, "a void value is used");
    }

    /** Converts to {@code t} as C does implicitly; constants are folded. */
    private static Expr convert(Expr e, Type.Int t, SourcePos pos) throws CompileError {
        if (!(e.type() instanceof Type.Int)) {
            throw new CompileError(pos, "a void value is used");
        }
        if (e.type().equals(t)) {
            return e;
        }
        if (e instanceof Expr.Const k) {
            return new Expr.Const(t.wrap(k.value()), t, k.pos());
        }
        return new Expr.Convert(e, t, false, pos);
    }

    /** The value of a constant expression, or null if it is not constant. */
    static Long fold(Expr e) {
        return switch (e) {
            case Expr.Const k -> k.value();
            case Expr.Convert c -> {
                Long v = fold(c.operand());
                yield v == null ? null : c.type().wrap(v);
            }
            case Expr.Unary u -> {
                Long v = fold(u.operand());
                yield v == null ? null : u.type().wrap(u.op() == Expr.UnOp.NEG ? -v : ~v);
            }
            case Expr.Binary b -> {
                Long l = fold(b.left());
                Long r = fold(b.right());
                if (l == null || r == null) {
                    yield null;
                }
                // l and r are already wrapped into their types, so Java's long arithmetic gives
                // C's result before the final wrap (division truncates towards zero in both)
                yield switch (b.op()) {
                    case ADD -> b.type().wrap(l + r);
                    case SUB -> b.type().wrap(l - r);
                    case MUL -> b.type().wrap(l * r);
                    case DIV -> r == 0 ? null : b.type().wrap(l / r);
                    case MOD -> r == 0 ? null : b.type().wrap(l % r);
                    case SHL -> r < 0 || r >= 32 ? null : b.type().wrap(l << r);
                    case SHR -> r < 0 || r >= 32 ? null : b.type().wrap(l >> r);
                    case AND -> b.type().wrap(l & r);
                    case OR -> b.type().wrap(l | r);
                    case XOR -> b.type().wrap(l ^ r);
                    case LT -> l < r ? 1L : 0L;
                    case LE -> l <= r ? 1L : 0L;
                    case GT -> l > r ? 1L : 0L;
                    case GE -> l >= r ? 1L : 0L;
                    case EQ -> l.equals(r) ? 1L : 0L;
                    case NE -> l.equals(r) ? 0L : 1L;
                };
            }
            case Expr.LogicalNot n -> {
                Long v = fold(n.operand());
                yield v == null ? null : (v == 0 ? 1L : 0L);
            }
            case Expr.Logical lg -> {
                Long l = fold(lg.left());
                if (l == null) {
                    yield null;
                }
                if (lg.and() ? l == 0 : l != 0) {
                    yield lg.and() ? 0L : 1L;
                }
                Long r = fold(lg.right());
                yield r == null ? null : (r != 0 ? 1L : 0L);
            }
            case Expr.Conditional c -> {
                Long k = fold(c.cond());
                yield k == null ? null : fold(k != 0 ? c.then() : c.otherwise());
            }
            default -> null;
        };
    }

    private String uniqueLabel(String base) {
        String label = base;
        for (int i = 2; !labels.add(label); i++) {
            label = base + "_" + i;
        }
        return label;
    }

    private SourcePos pos(ParserRuleContext ctx) {
        return pos(ctx.getStart());
    }

    private SourcePos pos(Token t) {
        return sourceMap.map(t.getLine(), t.getCharPositionInLine());
    }

    private CompileError unsupported(ParserRuleContext ctx, String what) {
        return new CompileError(pos(ctx), "not supported yet: " + what);
    }

    private CompileError error(ParserRuleContext ctx, String message) {
        return new CompileError(pos(ctx), message);
    }
}
