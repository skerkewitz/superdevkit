package dampf.frontend;

import dampf.ast.Expr;
import dampf.ast.Function;
import dampf.ast.Init;
import dampf.ast.Program;
import dampf.ast.Stmt;
import dampf.ast.Type;
import dampf.ast.Variable;
import dampf.parser.CParser;
import java.nio.charset.StandardCharsets;
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
 * as gcc does on x86-64 (integer promotions to a 32-bit int, usual arithmetic conversions,
 * array-to-pointer decay) and makes every implicit conversion explicit.
 *
 * <p>It accepts only what the current code generator supports and stops with a clear message at
 * anything else. The full subset checker comes later; the subset rule enforced here already is
 * that {@code short}, {@code int}, {@code long}, {@code signed} and {@code unsigned} may not be
 * written in game code, only in dampf's own {@code <stdint.h>}. Plain {@code char} is allowed for
 * text and means what it means on x86-64: a signed 8-bit type (docs/subset.md, decision 1).
 */
public final class AstBuilder {
    private final Path systemHeaders;

    // program-wide
    private final Map<String, Function> externFunctions = new LinkedHashMap<>();
    private final Map<String, Variable> externGlobals = new LinkedHashMap<>();
    private final List<Function> functions = new ArrayList<>();
    private final List<Variable> globals = new ArrayList<>();
    private final Map<Variable, Init> initialValues = new HashMap<>();
    private final Set<String> definedGlobals = new HashSet<>();
    /** Where a global with external linkage is first defined, and in which translation unit. */
    private final Map<String, SourcePos> definedAt = new HashMap<>();
    private final Map<String, Integer> definedInUnit = new HashMap<>();
    private int unit;
    private final Map<Function, SourcePos> firstCall = new LinkedHashMap<>();
    private final Set<String> labels = new HashSet<>();
    private int anonymous;

    // per file
    private SourceMap sourceMap;
    private Map<String, Type> typedefs;
    private Map<String, Object> fileScope;
    private Map<String, Type> enumTags;
    private Map<String, Type.StructDef> structTags;

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
        unit++;
        typedefs = new HashMap<>();
        fileScope = new HashMap<>();
        enumTags = new HashMap<>();
        structTags = new HashMap<>();
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
        if (!sizeDiffers.isEmpty()) {
            Map.Entry<Expr.Const, String> e = sizeDiffers.entrySet().iterator().next();
            throw new CompileError(e.getKey().pos(), e.getValue());
        }
    }

    /**
     * sizeof values that differ from the PC's, not yet excused: the program would compute a
     * different value than its PC build. The only excuse is a division of two of them with the
     * same quotient on both (the element count {@code sizeof a / sizeof a[0]}), see binaryOp.
     */
    private final Map<Expr.Const, String> sizeDiffers = new java.util.IdentityHashMap<>();
    private final Map<Expr.Const, Long> pcSizes = new java.util.IdentityHashMap<>();

    /** sizeof a type: dampf's size; noted if gcc on x86-64 gives another. */
    private Expr.Const sizeOf(Type t, SourcePos p) {
        Expr.Const k = new Expr.Const(t.size(), Type.SIZE, p);
        long pc = Type.pcSize(t);
        if (pc != t.size()) {
            sizeDiffers.put(k, "sizeof " + t + " is " + t.size() + " in dampf but " + pc + " on the PC (dampf's pointers are 4 bytes, "
                    + "8 on x86-64, and dampf does not pad struct fields): the program would compute other values than "
                    + "its PC build. Only the element count sizeof a / sizeof a[0] is allowed for such types");
            pcSizes.put(k, pc);
        }
        return k;
    }

    /** Checks the program as a whole and returns it. */
    public Program finish() throws CompileError {
        for (Map.Entry<Function, SourcePos> e : firstCall.entrySet()) {
            if (!e.getKey().isDefined()) {
                throw new CompileError(e.getValue(), "function '" + e.getKey().name() + "' is called but never defined");
            }
        }
        for (Variable v : globals) {
            if (!definedGlobals.contains(v.label())) {
                throw new CompileError(v.pos(), "'" + v.name() + "' is declared extern but never defined");
            }
            if (v.type() instanceof Type.Array a && a.length() < 0) {
                throw new CompileError(v.pos(), "the size of '" + v.name() + "' is unknown");
            }
        }
        return new Program(List.copyOf(globals), Map.copyOf(initialValues), List.copyOf(functions));
    }

    // ---------------------------------------------------------------- declarations

    private enum Storage { NONE, STATIC, EXTERN, TYPEDEF }

    /** Declaration specifiers: storage class, base type, and whether {@code const} or {@code volatile} was given. */
    private record Specs(Storage storage, Type type, boolean isConst, boolean isVolatile) {
        Specs(Storage storage, Type type, boolean isConst) {
            this(storage, type, isConst, false);
        }
    }

    private record Param(String name, Type type, SourcePos pos) {}

    /**
     * A declarator applied to a base type: the name, the full type, whether the object itself is
     * const, and for a function its parameters (the type is then the return type).
     */
    private record Declared(String name, Type type, boolean objectConst, SourcePos pos, List<Param> params) {
        boolean isFunction() {
            return params != null;
        }
    }

    private Specs specs(List<? extends ParserRuleContext> specifiers, ParserRuleContext where) throws CompileError {
        return specs(specifiers, where, false);
    }

    /**
     * {@code mainReturn}: these are the specifiers of {@code int main(void)}. C requires
     * {@code int} there for the PC build, so it is the one place plain {@code int} is allowed;
     * on the SNES the return value is ignored.
     */
    private Specs specs(List<? extends ParserRuleContext> specifiers, ParserRuleContext where, boolean mainReturn)
            throws CompileError {
        if (mainReturn && specifiers.size() == 1 && specifiers.getFirst().getText().equals("int")) {
            return new Specs(Storage.NONE, Type.I32, false);
        }
        Storage storage = Storage.NONE;
        List<String> keywords = new ArrayList<>();
        Type named = null;
        boolean isConst = false;
        boolean isVolatile = false;
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
                    switch (tq.getText()) {
                        case "const" -> isConst = true;
                        case "volatile" -> isVolatile = true;
                        default -> throw unsupported(tq, "'" + tq.getText() + "'");
                    }
                }
                case CParser.TypeSpecifierContext ts -> {
                    if (ts.typedefName() != null) {
                        named = typedefs.get(ts.typedefName().getText());
                        if (named == null) {
                            throw error(ts, "unknown type '" + ts.typedefName().getText() + "'");
                        }
                    } else if (ts.structOrUnionSpecifier() != null) {
                        named = structType(ts.structOrUnionSpecifier());
                    } else if (ts.enumSpecifier() != null) {
                        named = enumType(ts.enumSpecifier());
                    } else {
                        keywords.add(ts.getText());
                    }
                }
                case CParser.FunctionSpecifierContext fs -> {
                    if (!fs.getText().equals("inline")) {
                        throw unsupported(fs, "'" + fs.getText() + "'");
                    }
                    // inline is a hint; dampf decides inlining itself
                }
                default -> throw unsupported(inner, "'" + inner.getText() + "'");
            }
        }
        if (named != null) {
            if (!keywords.isEmpty()) {
                throw error(where, "'" + keywords.getFirst() + "' cannot be combined with a type name");
            }
            return new Specs(storage, named, isConst, isVolatile);
        }
        return new Specs(storage, typeFromKeywords(keywords, where), isConst, isVolatile);
    }

    private Type typeFromKeywords(List<String> kw, ParserRuleContext where) throws CompileError {
        if (kw.equals(List.of("void"))) {
            return Type.VOID;
        }
        if (kw.equals(List.of("char"))) {
            return Type.I8; // text; signed 8 bit as on x86-64
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

    /** Is this the declarator of a function called main? */
    private static boolean isMain(CParser.DeclaratorContext d) {
        CParser.DirectDeclaratorContext dd = d.directDeclarator();
        return d.pointer().isEmpty() && dd.Identifier() != null && dd.Identifier().getText().equals("main")
                && dd.getText().startsWith("main(");
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

    /** A struct type; its fields are laid out one after the other, without padding. */
    private Type structType(CParser.StructOrUnionSpecifierContext ss) throws CompileError {
        if (ss.structOrUnion().getText().equals("union")) {
            throw new CompileError(pos(ss), "unions are not supported");
        }
        String tag = ss.Identifier() != null ? ss.Identifier().getText() : null;
        boolean hasBody = ss.getText().contains("{");
        if (!hasBody) {
            // a reference, possibly before the definition (struct Node *next;)
            return new Type.Struct(structTags.computeIfAbsent(tag, Type.StructDef::new));
        }
        Type.StructDef def = tag != null ? structTags.computeIfAbsent(tag, Type.StructDef::new) : new Type.StructDef("<anonymous at " + pos(ss) + ">") /* the same header gives the same name in every file */;
        if (def.complete()) {
            throw error(ss, "redefinition of struct " + tag);
        }
        List<Type.Field> fields = new ArrayList<>();
        int offset = 0;
        if (ss.memberDeclarationList() != null) {
            for (CParser.MemberDeclarationContext md : ss.memberDeclarationList().memberDeclaration()) {
                if (md.specifierQualifierList() == null) {
                    throw unsupported(md, "this kind of struct member");
                }
                Specs s = specs(md.specifierQualifierList().typeSpecifierQualifier(), md);
                if (md.memberDeclaratorList() == null) {
                    throw unsupported(md, "anonymous struct members");
                }
                for (CParser.MemberDeclaratorContext m : md.memberDeclaratorList().memberDeclarator()) {
                    if (m.declarator() == null || m.constantExpression() != null) {
                        throw new CompileError(pos(m), "bit fields are not supported");
                    }
                    Declared d = declare(s, m.declarator());
                    if (d.isFunction() || d.type() instanceof Type.Void || !complete(d.type())) {
                        throw new CompileError(d.pos(), "field '" + d.name() + "' has an incomplete type");
                    }
                    for (Type.Field f : fields) {
                        if (f.name().equals(d.name())) {
                            throw new CompileError(d.pos(), "duplicate field '" + d.name() + "'");
                        }
                    }
                    fields.add(new Type.Field(d.name(), d.type(), offset));
                    offset += d.type().size();
                }
            }
        }
        def.complete(fields);
        return new Type.Struct(def);
    }

    private static boolean complete(Type t) {
        return switch (t) {
            case Type.Array a -> a.length() >= 0 && complete(a.element());
            case Type.Struct s -> s.def().complete();
            case Type.Void v -> false;
            default -> true;
        };
    }

    /** Applies a declarator to a base type. C reads declarators inside out: {@code T *a[3]} is an array of pointers. */
    private Declared declare(Type base, boolean baseConst, CParser.DeclaratorContext d) throws CompileError {
        return declare(base, baseConst, false, d);
    }

    private Declared declare(Specs s, CParser.DeclaratorContext d) throws CompileError {
        return declare(s.type(), s.isConst(), s.isVolatile(), d);
    }

    /**
     * {@code baseVolatile}: the specifiers said {@code volatile}. That is supported for what a
     * pointer points to (I/O registers); a volatile variable itself is rejected for now.
     */
    private Declared declare(Type base, boolean baseConst, boolean baseVolatile, CParser.DeclaratorContext d) throws CompileError {
        Type t = base;
        boolean objectConst = baseConst;
        boolean firstPointer = true;
        boolean pointers = false;
        for (ParseTree c : d.children) {
            if (c instanceof CParser.PointerContext p) {
                for (ParseTree pc : p.children) {
                    if (pc instanceof TerminalNode tn && tn.getText().equals("*")) {
                        t = new Type.Pointer(t, firstPointer && baseVolatile);
                        firstPointer = false;
                        pointers = true;
                        objectConst = false; // a pointer is const only if its own qualifier says so
                    } else if (pc instanceof CParser.TypeQualifierListContext ql) {
                        for (CParser.TypeQualifierContext q : ql.typeQualifier()) {
                            if (!q.getText().equals("const")) {
                                throw unsupported(q, "'" + q.getText() + "' on a pointer itself");
                            }
                            objectConst = true;
                        }
                    } else {
                        throw unsupported(p, "this kind of pointer");
                    }
                }
            } else if (c instanceof CParser.DeclarationSpecifiersContext || c instanceof CParser.GnuAttributeContext
                    || c instanceof CParser.GccDeclaratorExtensionContext) {
                throw unsupported(d, "attributes and extensions in declarators");
            }
        }
        if (baseVolatile && !pointers) {
            throw unsupported(d, "volatile variables (only pointers to volatile memory)");
        }
        CParser.DirectDeclaratorContext dd = d.directDeclarator();
        if (dd.Identifier() == null || dd.getChildCount() > 1 && dd.getChild(1) instanceof TerminalNode tn && tn.getText().equals(":")) {
            throw unsupported(dd, "this kind of declarator (function pointers, bit fields)");
        }
        String name = dd.Identifier().getText();
        List<Integer> dims = new ArrayList<>();
        List<Param> params = null;
        for (int i = 1; i < dd.getChildCount(); i++) {
            ParseTree c = dd.getChild(i);
            if (c instanceof TerminalNode tn && tn.getText().equals("[")) {
                if (params != null) {
                    throw unsupported(dd, "functions returning arrays");
                }
                ParseTree next = dd.getChild(i + 1);
                if (next instanceof CParser.AssignmentExpressionContext size) {
                    Long n = fold(rvalue(assignment(size)));
                    if (n == null || n <= 0) {
                        throw error(size, "an array size must be a positive integer constant");
                    }
                    dims.add(n.intValue());
                } else if (next instanceof TerminalNode close && close.getText().equals("]")) {
                    if (!dims.isEmpty()) {
                        throw error(dd, "only the first dimension of an array may be left out");
                    }
                    dims.add(-1);
                } else {
                    throw unsupported(dd, "this kind of array declarator");
                }
            } else if (c instanceof TerminalNode tn && tn.getText().equals("(")) {
                if (params != null || !dims.isEmpty()) {
                    throw unsupported(dd, "this kind of function declarator");
                }
                params = new ArrayList<>();
                if (dd.getChild(i + 1) instanceof CParser.ParameterTypeListContext ptl) {
                    params = parameters(ptl);
                }
            } else if (c instanceof CParser.AttributeSpecifierSequenceContext) {
                throw unsupported(dd, "attributes");
            }
        }
        for (int i = dims.size() - 1; i >= 0; i--) {
            t = new Type.Array(t, dims.get(i));
        }
        if (!dims.isEmpty()) {
            objectConst = constElements(t, base, baseConst, objectConst);
        }
        return new Declared(name, t, objectConst, pos(dd), params);
    }

    /** An array is const (goes to ROM) when its innermost elements are. */
    private static boolean constElements(Type arrayType, Type base, boolean baseConst, boolean pointerConst) {
        Type e = arrayType;
        while (e instanceof Type.Array a) {
            e = a.element();
        }
        return e instanceof Type.Pointer ? pointerConst : baseConst;
    }

    /** A type name (in casts and sizeof): specifiers, optionally followed by pointers. */
    private Type typeName(CParser.TypeNameContext tn) throws CompileError {
        Specs s = specs(tn.specifierQualifierList().typeSpecifierQualifier(), tn);
        Type t = s.type();
        CParser.AbstractDeclaratorContext ad = tn.abstractDeclarator();
        if (ad != null) {
            if (ad.directAbstractDeclarator() != null || ad.pointer() == null) {
                throw unsupported(ad, "this kind of type name");
            }
            boolean first = true;
            for (ParseTree c : ad.pointer().children) {
                if (c instanceof TerminalNode tnode && tnode.getText().equals("*")) {
                    t = new Type.Pointer(t, first && s.isVolatile());
                    first = false;
                }
            }
        } else if (s.isVolatile()) {
            throw unsupported(tn, "volatile values (only pointers to volatile memory)");
        }
        return t;
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
                // unnamed parameter with pointers: void f(uint8_t *);
                CParser.AbstractDeclaratorContext ad = pd.abstractDeclarator();
                if (ad.directAbstractDeclarator() != null || ad.pointer() == null) {
                    throw unsupported(pd, "this kind of parameter");
                }
                Type t = s.type();
                for (ParseTree c : ad.pointer().children) {
                    if (c instanceof TerminalNode tn && tn.getText().equals("*")) {
                        t = new Type.Pointer(t);
                    }
                }
                params.add(new Param(null, t, pos(pd)));
                continue;
            }
            if (s.type() instanceof Type.Void && pd.declarator() == null) {
                if (decls.size() != 1) {
                    throw error(pd, "'void' must be the only parameter");
                }
                return List.of();
            }
            Type t = s.type();
            String name = null;
            SourcePos p = pos(pd);
            if (pd.declarator() != null) {
                Declared d = declare(s, pd.declarator());
                if (d.isFunction()) {
                    throw unsupported(pd, "function pointers");
                }
                t = d.type();
                name = d.name();
                p = d.pos();
            }
            if (t instanceof Type.Array a) {
                t = new Type.Pointer(a.element()); // an array parameter is a pointer
            }
            if (t instanceof Type.Struct) {
                throw new CompileError(p, "structs passed by value are not supported: pass a pointer");
            }
            if (t instanceof Type.Void) {
                throw new CompileError(p, "a parameter cannot have type void");
            }
            params.add(new Param(name, t, p));
        }
        return params;
    }

    private void topLevelDeclaration(CParser.DeclarationContext d) throws CompileError {
        if (d.declarationSpecifiers() == null) {
            if (d.staticAssertDeclaration() != null) {
                throw unsupported(d, "_Static_assert");
            }
            throw unsupported(d, "this kind of declaration");
        }
        CParser.InitDeclaratorListContext idl = d.initDeclaratorList();
        boolean main = idl != null && idl.initDeclarator().size() == 1 && isMain(idl.initDeclarator(0).declarator());
        Specs s = specs(d.declarationSpecifiers().declarationSpecifier(), d, main);
        if (d.initDeclaratorList() == null) {
            return; // only a struct or enum definition
        }
        for (CParser.InitDeclaratorContext id : d.initDeclaratorList().initDeclarator()) {
            Declared dec = declare(s, id.declarator());
            if (s.storage() == Storage.TYPEDEF) {
                if (dec.isFunction() || id.initializer() != null) {
                    throw unsupported(id, "this kind of typedef");
                }
                typedefs.put(dec.name(), dec.type());
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

    private Function declareFunction(Declared dec, Specs s) throws CompileError {
        List<Type> paramTypes = dec.params().stream().map(Param::type).toList();
        Object existing = fileScope.get(dec.name());
        if (existing == null && s.storage() != Storage.STATIC) {
            existing = externFunctions.get(dec.name());
        }
        if (existing instanceof Function f) {
            if (!f.returnType().equals(dec.type()) || !f.paramTypes().equals(paramTypes)) {
                throw new CompileError(dec.pos(), "conflicting types for '" + dec.name() + "' (first declared at " + f.pos() + ")");
            }
            fileScope.put(dec.name(), f);
            return f;
        }
        if (existing != null) {
            throw new CompileError(dec.pos(), "'" + dec.name() + "' is already declared as a variable");
        }
        Function f = new Function(dec.name(), uniqueLabel("_" + dec.name()), dec.type(), paramTypes, dec.pos());
        functions.add(f);
        fileScope.put(dec.name(), f);
        if (s.storage() != Storage.STATIC) {
            externFunctions.put(dec.name(), f);
        }
        return f;
    }

    private void globalVariable(Declared dec, Specs s, CParser.InitializerContext init) throws CompileError {
        Type t = dec.type();
        if (t instanceof Type.Void) {
            throw new CompileError(dec.pos(), "variable '" + dec.name() + "' has type void");
        }
        Object existing = fileScope.get(dec.name());
        if (existing == null && s.storage() != Storage.STATIC) {
            existing = externGlobals.get(dec.name());
        }
        Variable v;
        if (existing instanceof Variable old) {
            v = merge(old, t, dec);
        } else if (existing != null) {
            throw new CompileError(dec.pos(), "'" + dec.name() + "' is already declared as a function");
        } else {
            v = new Variable(dec.name(), uniqueLabel("_" + dec.name()), t, Variable.Kind.GLOBAL, dec.pos(), dec.objectConst());
            globals.add(v);
            if (s.storage() != Storage.STATIC) {
                externGlobals.put(dec.name(), v);
            }
        }
        fileScope.put(dec.name(), v);
        if (s.storage() != Storage.EXTERN) {
            // Repeated (tentative) definitions are fine within one file; in two files they are
            // a link error with gcc (-fno-common, the default since gcc 10).
            Integer first = definedInUnit.putIfAbsent(v.label(), unit);
            if (first != null && first != unit) {
                throw new CompileError(dec.pos(), "redefinition of '" + dec.name() + "' (first defined at "
                        + definedAt.get(v.label()) + "); declare it extern in all files but one");
            }
            definedAt.putIfAbsent(v.label(), dec.pos());
            definedGlobals.add(v.label());
        }
        if (init != null) {
            if (s.storage() == Storage.EXTERN) {
                throw error(init, "an extern declaration cannot have an initializer");
            }
            if (initialValues.containsKey(v)) {
                throw error(init, "'" + dec.name() + "' is initialised twice");
            }
            v = staticInit(v, init);
        } else if (s.storage() != Storage.EXTERN && !complete(v.type())) {
            throw new CompileError(dec.pos(), "the size of '" + dec.name() + "' is unknown");
        }
    }

    /**
     * A second declaration of a global: the types must agree, except that one may give the size
     * of an array the other leaves out. The variable keeps its label, so earlier uses stay valid.
     */
    private Variable merge(Variable old, Type t, Declared dec) throws CompileError {
        if (old.type().equals(t)) {
            return old;
        }
        if (old.type() instanceof Type.Array a && t instanceof Type.Array b && a.element().equals(b.element())
                && (a.length() < 0 || b.length() < 0)) {
            if (b.length() < 0) {
                return old;
            }
            return replace(old, t);
        }
        throw new CompileError(dec.pos(), "conflicting types for '" + dec.name() + "' (first declared at " + old.pos() + ")");
    }

    /** Replaces a global by the same variable with a completed type. */
    private Variable replace(Variable old, Type t) {
        Variable v = new Variable(old.name(), old.label(), t, old.kind(), old.pos(), old.readOnly());
        globals.set(globals.indexOf(old), v);
        externGlobals.replaceAll((k, x) -> x == old ? v : x);
        fileScope.replaceAll((k, x) -> x == old ? v : x);
        Init i = initialValues.remove(old);
        if (i != null) {
            initialValues.put(v, i);
        }
        return v;
    }

    /** Builds the initial contents of a global or static local; completes an array size from it. */
    private Variable staticInit(Variable v, CParser.InitializerContext init) throws CompileError {
        List<Init.Item> items = new ArrayList<>();
        Type t = v.type();
        int length = initialize(t, init, 0, items);
        if (t instanceof Type.Array a && a.length() < 0) {
            v = replace(v, new Type.Array(a.element(), length));
        }
        initialValues.put(v, new Init(items));
        return v;
    }

    // ---------------------------------------------------------------- initializers

    /**
     * Records the initial contents of an object of type {@code t} at {@code offset}. Returns the
     * number of top-level elements given (the length of an array whose size was left out).
     */
    private int initialize(Type t, CParser.InitializerContext init, int offset, List<Init.Item> items) throws CompileError {
        if (init.assignmentExpression() != null) {
            CParser.AssignmentExpressionContext a = init.assignmentExpression();
            if (t instanceof Type.Array arr && stringLiteral(a) != null) {
                byte[] s = stringLiteral(a);
                int n = arr.length() < 0 ? s.length + 1 : arr.length();
                if (!(arr.element() instanceof Type.Int e && e.bits() == 8) || s.length > n) {
                    throw error(init, "string literal does not fit");
                }
                for (int i = 0; i < s.length; i++) {
                    items.add(Init.Item.number(offset + i, 1, s[i]));
                }
                return n;
            }
            if (!Type.isScalar(t)) {
                throw error(init, "an array or struct needs a brace initializer");
            }
            items.add(scalarInit(t, rvalue(assignment(a)), offset, init));
            return 1;
        }
        List<CParser.InitializerContext> list = new ArrayList<>();
        if (init.initializerList() != null) {
            for (ParseTree c : init.initializerList().children) {
                if (c instanceof CParser.DesignationContext) {
                    throw unsupported(init, "designated initializers");
                }
                if (c instanceof CParser.InitializerContext i) {
                    list.add(i);
                }
            }
        }
        if (Type.isScalar(t)) {
            if (list.size() != 1) {
                throw error(init, "a scalar needs exactly one initializer");
            }
            return initialize(t, list.getFirst(), offset, items);
        }
        int[] pos = {0};
        int n = aggregate(t, list, pos, offset, items, true);
        if (pos[0] < list.size()) {
            throw error(list.get(pos[0]), "too many initializers");
        }
        return n;
    }

    /**
     * Fills an array or struct from {@code list}, starting at {@code pos[0]}, with C's brace
     * elision: an aggregate member without its own braces takes as many values as it needs.
     */
    private int aggregate(Type t, List<CParser.InitializerContext> list, int[] pos, int offset, List<Init.Item> items,
                          boolean outermost) throws CompileError {
        List<Type> members = new ArrayList<>();
        List<Integer> offsets = new ArrayList<>();
        boolean open = false;
        if (t instanceof Type.Array a) {
            open = a.length() < 0;
            int n = open ? Integer.MAX_VALUE : a.length();
            for (int i = 0; i < n && (!open || pos[0] < list.size()); i++) {
                members.add(a.element());
                offsets.add(offset + i * a.element().size());
                if (open) {
                    // an open array takes as many elements as there are values
                    memberInit(a.element(), list, pos, offset + i * a.element().size(), items);
                }
            }
            if (open) {
                if (!outermost) {
                    throw error(list.getFirst(), "only the outermost array may leave its size out");
                }
                return members.size();
            }
        } else if (t instanceof Type.Struct s) {
            for (Type.Field f : s.def().fields()) {
                members.add(f.type());
                offsets.add(offset + f.offset());
            }
        } else {
            throw new IllegalStateException(t.toString());
        }
        int i = 0;
        for (; i < members.size() && pos[0] < list.size(); i++) {
            memberInit(members.get(i), list, pos, offsets.get(i), items);
        }
        return t instanceof Type.Array a ? a.length() : 1;
    }

    private void memberInit(Type m, List<CParser.InitializerContext> list, int[] pos, int offset, List<Init.Item> items)
            throws CompileError {
        CParser.InitializerContext next = list.get(pos[0]);
        boolean braced = next.assignmentExpression() == null;
        boolean string = !braced && m instanceof Type.Array && stringLiteral(next.assignmentExpression()) != null;
        if (braced || string || Type.isScalar(m)) {
            initialize(m, next, offset, items);
            pos[0]++;
        } else {
            aggregate(m, list, pos, offset, items, false); // brace elision
        }
    }

    /** One scalar of static data: a number or an address constant. */
    private Init.Item scalarInit(Type t, Expr e, int offset, ParserRuleContext where) throws CompileError {
        Expr v = assignConvert(e, t, pos(where));
        if (t instanceof Type.Int it) {
            Long k = fold(v);
            if (k == null) {
                throw error(where, "the initializer of a static object must be a constant");
            }
            return Init.Item.number(offset, it.size(), k);
        }
        Init.Item a = addressConstant(v, offset);
        if (a == null) {
            throw error(where, "the initializer of a static pointer must be a constant address");
        }
        return a;
    }

    /** {@code &global}, an array name, a string literal, plus a constant offset; or the null pointer. */
    private Init.Item addressConstant(Expr e, int offset) {
        return switch (e) {
            case Expr.PointerCast c when fold(c.operand()) != null && fold(c.operand()) == 0 -> Init.Item.number(offset, 4, 0);
            case Expr.PointerCast c -> addressConstant(c.operand(), offset);
            case Expr.Decay d -> lvalueAddress(d.array(), offset);
            case Expr.AddressOf a -> lvalueAddress(a.lvalue(), offset);
            case Expr.PointerAdd p -> {
                Long k = fold(p.offset());
                Init.Item base = addressConstant(p.pointer(), offset);
                if (k == null || base == null || base.address() == null) {
                    yield null;
                }
                long d = (p.subtract() ? -k : k) * p.scale();
                yield Init.Item.address(offset, base.address(), base.addressOffset() + d);
            }
            default -> null;
        };
    }

    private Init.Item lvalueAddress(Expr lv, int offset) {
        return switch (lv) {
            case Expr.Var v when v.variable().kind() == Variable.Kind.GLOBAL || v.variable().kind() == Variable.Kind.STATIC_LOCAL ->
                    Init.Item.address(offset, v.variable(), 0);
            case Expr.Index i -> {
                Long k = fold(i.index());
                Init.Item base = i.base().type() instanceof Type.Array ? lvalueAddress(i.base(), offset) : null;
                yield k == null || base == null ? null
                        : Init.Item.address(offset, base.address(), base.addressOffset() + k * i.type().size());
            }
            case Expr.Member m -> {
                Init.Item base = lvalueAddress(m.base(), offset);
                yield base == null ? null : Init.Item.address(offset, base.address(), base.addressOffset() + m.field().offset());
            }
            default -> null;
        };
    }

    /** The bytes of a string literal expression (adjacent literals joined), or null. */
    private static byte[] stringLiteral(CParser.AssignmentExpressionContext a) {
        CParser.PrimaryExpressionContext p = primaryOf(a);
        if (p == null || p.StringLiteral().isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (TerminalNode s : p.StringLiteral()) {
            sb.append(unescape(s.getText()));
        }
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    /** The primary expression an assignment expression consists of, if it is nothing more. */
    private static CParser.PrimaryExpressionContext primaryOf(ParseTree t) {
        ParseTree x = t;
        while (x != null && !(x instanceof CParser.PrimaryExpressionContext)) {
            if (x.getChildCount() != 1) {
                return null;
            }
            x = x.getChild(0);
        }
        return (CParser.PrimaryExpressionContext) x;
    }

    /**
     * The characters of a string literal token. The grammar's lexer joins adjacent literals
     * ({@code "VERSION " "1.4.0"}, also across macros) into one token, so the token is a
     * sequence of quoted pieces with white space between them; each piece is unescaped and the
     * pieces are joined, as C's translation phase 6 does.
     */
    static String unescape(String literal) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        int n = literal.length();
        while (i < n) {
            char c = literal.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c != '"') {
                throw new IllegalArgumentException("wide and unicode string literals are not supported: " + literal);
            }
            i++; // opening quote
            while (true) {
                if (i >= n) {
                    throw new IllegalArgumentException("unterminated string literal: " + literal);
                }
                char ch = literal.charAt(i);
                if (ch == '"') {
                    i++; // closing quote
                    break;
                }
                if (ch != '\\') {
                    sb.append(ch);
                    i++;
                    continue;
                }
                char e = literal.charAt(i + 1);
                i += 2;
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case '0' -> sb.append('\0');
                    case '\\' -> sb.append('\\');
                    case '\'' -> sb.append('\'');
                    case '"' -> sb.append('"');
                    case 'x' -> {
                        int j = i;
                        while (j < n && Character.digit(literal.charAt(j), 16) >= 0) {
                            j++;
                        }
                        sb.append((char) Integer.parseInt(literal.substring(i, j), 16));
                        i = j;
                    }
                    default -> throw new IllegalArgumentException("escape \\" + e + " is not supported");
                }
            }
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- functions and statements

    private void functionDefinition(CParser.FunctionDefinitionContext fd) throws CompileError {
        if (fd.declarationList() != null) {
            throw unsupported(fd, "old-style (K&R) parameter declarations");
        }
        if (fd.declarationSpecifiers() == null) {
            throw error(fd, "missing return type (C99 has no implicit int)");
        }
        Specs s = specs(fd.declarationSpecifiers().declarationSpecifier(), fd, isMain(fd.declarator()));
        if (s.storage() == Storage.TYPEDEF) {
            throw error(fd, "a function definition cannot be a typedef");
        }
        Declared dec = declare(s, fd.declarator());
        if (!dec.isFunction()) {
            throw error(fd, "expected a function");
        }
        if (dec.type() instanceof Type.Struct) {
            throw new CompileError(dec.pos(), "structs returned by value are not supported");
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
        if (s.storage() == Storage.EXTERN) {
            throw unsupported(d, "'extern' inside a function");
        }
        if (list == null) {
            return;
        }
        for (CParser.InitDeclaratorContext id : list.initDeclarator()) {
            Declared dec = declare(s, id.declarator());
            if (dec.isFunction()) {
                throw unsupported(id, "function declarations inside a function");
            }
            Map<String, Object> scope = blockScopes.peek();
            if (s.storage() == Storage.TYPEDEF) {
                throw unsupported(id, "typedef inside a function");
            }
            if (dec.type() instanceof Type.Void) {
                throw new CompileError(dec.pos(), "variable '" + dec.name() + "' has type void");
            }
            if (scope.containsKey(dec.name())) {
                throw new CompileError(dec.pos(), "redeclaration of '" + dec.name() + "'");
            }
            if (s.storage() == Storage.STATIC) {
                // a static local is a global with a name only this block sees
                Variable v = new Variable(dec.name(), uniqueLabel(current.label() + "__" + dec.name()), dec.type(),
                        Variable.Kind.STATIC_LOCAL, dec.pos(), dec.objectConst());
                globals.add(v);
                definedGlobals.add(v.label());
                if (id.initializer() != null) {
                    v = staticInit(v, id.initializer());
                } else if (!complete(v.type())) {
                    throw new CompileError(dec.pos(), "the size of '" + dec.name() + "' is unknown");
                }
                scope.put(dec.name(), v);
                continue;
            }
            Type t = dec.type();
            if (id.initializer() == null) {
                if (!complete(t)) {
                    throw new CompileError(dec.pos(), "the size of '" + dec.name() + "' is unknown");
                }
                Variable v = new Variable(dec.name(), uniqueLabel(current.label() + "__" + dec.name()), t, Variable.Kind.LOCAL, dec.pos());
                currentLocals.add(v);
                scope.put(dec.name(), v);
                continue;
            }
            if (Type.isScalar(t) && id.initializer().assignmentExpression() != null) {
                Variable v = new Variable(dec.name(), uniqueLabel(current.label() + "__" + dec.name()), t, Variable.Kind.LOCAL, dec.pos());
                currentLocals.add(v);
                scope.put(dec.name(), v); // C: the variable is in scope inside its own initializer
                Expr value = assignConvert(rvalue(assignment(id.initializer().assignmentExpression())), t, pos(id.initializer()));
                out.add(new Stmt.ExprStmt(new Expr.Assign(new Expr.Var(v, dec.pos()), value, dec.pos()), pos(d)));
                continue;
            }
            // an array or struct with an initializer: copied from a constant template in ROM
            Variable template = new Variable(dec.name() + " (initial value)", uniqueLabel(current.label() + "__" + dec.name() + "__init"),
                    t, Variable.Kind.STATIC_LOCAL, dec.pos(), true);
            globals.add(template);
            definedGlobals.add(template.label());
            template = staticInit(template, id.initializer());
            Variable v = new Variable(dec.name(), uniqueLabel(current.label() + "__" + dec.name()), template.type(), Variable.Kind.LOCAL, dec.pos());
            currentLocals.add(v);
            scope.put(dec.name(), v);
            out.add(new Stmt.ExprStmt(new Expr.Assign(new Expr.Var(v, dec.pos()), new Expr.Var(template, dec.pos()), dec.pos()), pos(d)));
        }
    }

    private Stmt statement(CParser.StatementContext st) throws CompileError {
        if (st.compoundStatement() != null) {
            return block(st.compoundStatement());
        }
        if (st.expressionStatement() != null) {
            CParser.ExpressionStatementContext es = st.expressionStatement();
            if (es.expression() == null) {
                return null;
            }
            Stmt hint = hint(es.expression());
            return hint != null ? hint : new Stmt.ExprStmt(discarded(expression(es.expression())), pos(st));
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
            if (rt instanceof Type.Void) {
                throw error(js, "'return' with a value in a void function");
            }
            return new Stmt.Return(assignConvert(rvalue(expression(js.expression())), rt, pos(js.expression())), pos(st));
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
        Expr c = rvalue(expression(e));
        scalar(c, e);
        return c;
    }

    /** An expression whose value is not used: x++ becomes ++x, which is simpler to compile. */
    private Expr discarded(Expr e) throws CompileError {
        if (e instanceof Expr.PostIncDec p && !hasSideEffects(p.target())) {
            return incremented(p.target(), p.increment(), p.pos());
        }
        return e;
    }

    /** {@code target = target ± 1} (for a pointer: one element). */
    private Expr incremented(Expr target, boolean increment, SourcePos p) throws CompileError {
        Expr one = new Expr.Const(1, Type.I32, p);
        Expr sum = binaryOp(increment ? "+" : "-", target, one, p);
        return new Expr.Assign(target, assignConvert(sum, target.type(), p), p);
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
                    List<Stmt> parts = new ArrayList<>();
                    for (CParser.AssignmentExpressionContext a : fc.expression().assignmentExpression()) {
                        parts.add(new Stmt.ExprStmt(discarded(assignment(a)), pos(a)));
                    }
                    init = parts.size() == 1 ? parts.getFirst() : new Stmt.Block(parts, pos(fc), pos(fc));
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
                    cond = rvalue(assignment(condCtx.assignmentExpression(0)));
                    scalar(cond, condCtx);
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
        Expr value = rvalue(expression(sel.expression()));
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
                v = fold(convert(rvalue(conditional(ls.constantExpression().conditionalExpression())), t, pos(ls)));
                if (v == null) {
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
        Token op = a.assignementOperator;
        SourcePos p = pos(op);
        checkAssignable(target, a.unaryExpression());
        Expr rhs = rvalue(assignment(a.assignmentExpression()));
        if (op.getText().equals("=")) {
            return new Expr.Assign(target, assignConvert(rhs, target.type(), p), p);
        }
        if (hasSideEffects(target)) {
            throw unsupported(a, "compound assignment to an lvalue with side effects");
        }
        String bin = op.getText().substring(0, op.getText().length() - 1);
        return new Expr.Assign(target, assignConvert(binaryOp(bin, target, rhs, p), target.type(), p), p);
    }

    private void checkAssignable(Expr target, ParserRuleContext where) throws CompileError {
        if (!Expr.isLvalue(target)) {
            throw error(where, "the left side of an assignment must be a variable or an element");
        }
        if (target.type() instanceof Type.Array) {
            throw error(where, "arrays cannot be assigned");
        }
    }

    private Expr conditional(CParser.ConditionalExpressionContext c) throws CompileError {
        Expr cond = chain(c.logicalOrExpression());
        if (c.expression() == null) {
            return cond;
        }
        cond = rvalue(cond);
        scalar(cond, c);
        Expr then = rvalue(expression(c.expression()));
        Expr otherwise = rvalue(conditional(c.conditionalExpression()));
        SourcePos p = pos(c);
        Type t;
        if (then.type() instanceof Type.Int a && otherwise.type() instanceof Type.Int b) {
            t = Type.common(a, b);
        } else if (then.type() instanceof Type.Pointer) {
            t = then.type();
        } else if (otherwise.type() instanceof Type.Pointer) {
            t = otherwise.type();
        } else {
            throw error(c, "the branches of ?: must both be numbers or pointers");
        }
        Long k = fold(cond);
        if (k != null) {
            return assignConvert(k != 0 ? then : otherwise, t, p);
        }
        return new Expr.Conditional(cond, assignConvert(then, t, p), assignConvert(otherwise, t, p), t, p);
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
            left = binaryOp(op.getText(), rvalue(left), rvalue(right), pos(op.getSymbol()));
        }
        return left;
    }

    /** A binary operator on two values (already through {@link #rvalue}). */
    private Expr binaryOp(String op, Expr left, Expr right, SourcePos pos) throws CompileError {
        left = rvalue(left);
        right = rvalue(right);
        if (op.equals("/") && left instanceof Expr.Const a && right instanceof Expr.Const b
                && (sizeDiffers.containsKey(a) || sizeDiffers.containsKey(b))) {
            // sizeof a / sizeof a[0]: fine if the quotient is the same as on the PC
            long pa = pcSizes.getOrDefault(a, a.value());
            long pb = pcSizes.getOrDefault(b, b.value());
            if (b.value() != 0 && pb != 0 && a.value() % b.value() == 0 && pa % pb == 0
                    && a.value() / b.value() == pa / pb) {
                sizeDiffers.remove(a);
                sizeDiffers.remove(b);
            }
        }
        if (op.equals("&&") || op.equals("||")) {
            scalar(left, pos);
            scalar(right, pos);
            Expr result = new Expr.Logical(op.equals("&&"), left, right, pos);
            Long k = fold(result);
            return k != null ? new Expr.Const(k, Type.I32, pos) : result;
        }
        Expr.BinOp bop = Expr.BinOp.of(op);
        if (bop == null) {
            throw new CompileError(pos, "operator '" + op + "' is not supported yet");
        }
        boolean lp = left.type() instanceof Type.Pointer;
        boolean rp = right.type() instanceof Type.Pointer;
        if (lp || rp) {
            return pointerOp(bop, left, right, pos);
        }
        Type.Int lt = intType(left, pos);
        Type.Int rt = intType(right, pos);
        Expr result;
        if (bop.isShift()) {
            Type.Int t = Type.promote(lt);
            result = new Expr.Binary(bop, convert(left, t, pos), convert(right, Type.promote(rt), pos), t, pos);
        } else {
            Type.Int t = Type.common(lt, rt);
            Type.Int resultType = bop.isComparison() ? Type.I32 : t;
            result = new Expr.Binary(bop, convert(left, t, pos), convert(right, t, pos), resultType, pos);
        }
        Long k = fold(result);
        return k != null ? new Expr.Const(k, (Type.Int) result.type(), pos) : result;
    }

    /** Pointer arithmetic and pointer comparisons. */
    private Expr pointerOp(Expr.BinOp op, Expr left, Expr right, SourcePos pos) throws CompileError {
        boolean lp = left.type() instanceof Type.Pointer;
        boolean rp = right.type() instanceof Type.Pointer;
        if (op == Expr.BinOp.ADD && lp != rp) {
            Expr ptr = lp ? left : right;
            Expr off = lp ? right : left;
            return new Expr.PointerAdd(ptr, convert(off, Type.promote(intType(off, pos)), pos), elementSize(ptr, pos), false,
                    (Type.Pointer) ptr.type(), pos);
        }
        if (op == Expr.BinOp.SUB && lp && !rp) {
            return new Expr.PointerAdd(left, convert(right, Type.promote(intType(right, pos)), pos), elementSize(left, pos), true,
                    (Type.Pointer) left.type(), pos);
        }
        if (op == Expr.BinOp.SUB && lp && rp) {
            return new Expr.PointerDiff(left, right, elementSize(left, pos), pos);
        }
        if (op.isComparison()) {
            // a pointer compared with a pointer, or with the null pointer constant 0
            Expr l = lp ? left : nullPointer(left, (Type.Pointer) right.type(), pos);
            Expr r = rp ? right : nullPointer(right, (Type.Pointer) left.type(), pos);
            return new Expr.Binary(op, l, r, Type.I32, pos);
        }
        throw new CompileError(pos, "operator '" + op.symbol + "' does not work on pointers");
    }

    private Expr nullPointer(Expr e, Type.Pointer t, SourcePos pos) throws CompileError {
        Long k = fold(e);
        if (k == null || k != 0) {
            throw new CompileError(pos, "a pointer can only be compared with a pointer or 0");
        }
        return new Expr.PointerCast(new Expr.Const(0, Type.I32, pos), t, pos);
    }

    private static int elementSize(Expr ptr, SourcePos pos) throws CompileError {
        Type target = ((Type.Pointer) ptr.type()).target();
        if (target instanceof Type.Void || !complete(target)) {
            throw new CompileError(pos, "arithmetic on a pointer to an incomplete type");
        }
        return target.size();
    }

    private Expr cast(CParser.CastExpressionContext c) throws CompileError {
        if (c.DigitSequence() != null) {
            return integerConstant(c.DigitSequence().getSymbol());
        }
        if (c.unaryExpression() != null) {
            return unary(c.unaryExpression());
        }
        Type t = typeName(c.typeName());
        Expr operand = rvalue(cast(c.castExpression()));
        SourcePos p = pos(c);
        if (t instanceof Type.Void) {
            return operand; // (void)x: only evaluated, the value is not used
        }
        if (t instanceof Type.Pointer pt) {
            if (operand.type() instanceof Type.Pointer) {
                return new Expr.PointerCast(operand, pt, p);
            }
            intType(operand, p);
            return new Expr.PointerCast(operand, pt, p);
        }
        if (!(t instanceof Type.Int it)) {
            throw error(c, "cannot cast to " + t);
        }
        if (operand.type() instanceof Type.Pointer) {
            throw unsupported(c, "casts from pointers to integers");
        }
        intType(operand, p);
        if (operand instanceof Expr.Const k) {
            return new Expr.Const(it.wrap(k.value()), it, p);
        }
        return new Expr.Convert(operand, it, true, p);
    }

    private Expr unary(CParser.UnaryExpressionContext u) throws CompileError {
        // unaryExpression: ('++' | '--' | 'sizeof')* core, where core is a postfix expression,
        // an operator with its operand, or sizeof/_Alignof with a type name
        int n = u.getChildCount();
        int first = 0;
        while (first < n && u.getChild(first) instanceof TerminalNode t
                && (t.getText().equals("++") || t.getText().equals("--") || t.getText().equals("sizeof"))
                && !(first + 1 < n && u.getChild(first + 1) instanceof TerminalNode paren && paren.getText().equals("(")
                     && u.typeName() != null && first + 1 == n - 3)) {
            first++;
        }
        Expr e;
        if (u.typeName() != null) {
            // sizeof ( type-name )
            if (!u.getChild(first).getText().equals("sizeof")) {
                throw unsupported(u, "'" + u.getChild(first).getText() + "'");
            }
            Type t = typeName(u.typeName());
            if (!complete(t)) {
                throw error(u, "sizeof of an incomplete type");
            }
            e = sizeOf(t, pos(u));
        } else if (u.postfixExpression() != null && first > 0 && u.getChild(first - 1).getText().equals("sizeof")
                && sizeofTypedef(u.postfixExpression()) != null) {
            // sizeof (name) with a typedef name: the grammar's first alternative reads it as a
            // parenthesised expression; it is sizeof of the type (unless a variable hides the name)
            Type t = sizeofTypedef(u.postfixExpression());
            if (!complete(t)) {
                throw error(u, "sizeof of an incomplete type");
            }
            e = sizeOf(t, pos(u));
            first--;
        } else if (u.postfixExpression() != null) {
            e = postfix(u.postfixExpression());
        } else if (u.unaryOperator != null) {
            e = unaryOperator(u.unaryOperator, cast(u.castExpression()), u);
        } else if (u.unaryExpression() != null) {
            e = unary(u.unaryExpression()); // sizeof unary-expression (GNU form)
        } else {
            throw unsupported(u, "this expression");
        }
        for (int i = first - 1; i >= 0; i--) {
            Token op = ((TerminalNode) u.getChild(i)).getSymbol();
            switch (op.getText()) {
                case "sizeof" -> {
                    if (!complete(e.type())) {
                        throw error(u, "sizeof of an incomplete type");
                    }
                    e = sizeOf(e.type(), pos(op));
                }
                default -> {
                    checkAssignable(e, u);
                    if (hasSideEffects(e)) {
                        throw unsupported(u, "'" + op.getText() + "' on an lvalue with side effects");
                    }
                    e = incremented(e, op.getText().equals("++"), pos(op));
                }
            }
        }
        return e;
    }

    private Expr unaryOperator(Token opTok, Expr operand, ParserRuleContext where) throws CompileError {
        String op = opTok.getText();
        SourcePos p = pos(opTok);
        if (op.equals("&")) {
            if (!Expr.isLvalue(operand)) {
                throw error(where, "'&' needs a variable or an element");
            }
            return new Expr.AddressOf(operand, new Type.Pointer(operand.type()), p);
        }
        operand = rvalue(operand);
        if (op.equals("*")) {
            if (!(operand.type() instanceof Type.Pointer pt)) {
                throw error(where, "'*' needs a pointer");
            }
            if (pt.target() instanceof Type.Void) {
                throw error(where, "cannot dereference a void pointer");
            }
            return new Expr.Deref(operand, pt.target(), p);
        }
        if (op.equals("!")) {
            scalar(operand, where);
            Long k = fold(operand);
            return k != null ? new Expr.Const(k == 0 ? 1 : 0, Type.I32, p) : new Expr.LogicalNot(operand, p);
        }
        Type.Int t = Type.promote(intType(operand, where));
        Expr conv = convert(operand, t, p);
        return switch (op) {
            case "+" -> conv;
            case "-", "~" -> {
                if (conv instanceof Expr.Const k) {
                    yield new Expr.Const(t.wrap(op.equals("-") ? -k.value() : ~k.value()), t, p);
                }
                yield new Expr.Unary(op.equals("-") ? Expr.UnOp.NEG : Expr.UnOp.NOT, conv, t, p);
            }
            default -> throw new CompileError(p, "operator '" + op + "' is not supported yet");
        };
    }

    /** A hint from {@code <dampf.h>} as a statement ({@code __dampf_range(v, lo, hi)}), else null. */
    private Stmt hint(CParser.ExpressionContext e) throws CompileError {
        if (e.assignmentExpression().size() != 1 || !e.getText().startsWith("__dampf_")) {
            return null;
        }
        CParser.PostfixExpressionContext p = postfixOf(e.assignmentExpression().getFirst());
        if (p == null || p.primaryExpression() == null || p.primaryExpression().Identifier() == null
                || p.getChildCount() < 3 || !p.getChild(1).getText().equals("(")
                || p.getChildCount() != (p.getChild(2) instanceof CParser.ArgumentExpressionListContext ? 4 : 3)) {
            return null;
        }
        String name = p.primaryExpression().Identifier().getText();
        SourcePos at = pos(p.primaryExpression().Identifier().getSymbol());
        List<CParser.AssignmentExpressionContext> args = p.getChild(2) instanceof CParser.ArgumentExpressionListContext al
                ? al.assignmentExpression() : List.of();
        if (!name.equals("__dampf_range")) {
            throw new CompileError(at, "unknown hint '" + name + "'");
        }
        if (args.size() != 3) {
            throw new CompileError(at, "DAMPF_RANGE needs three arguments: a variable, the lowest and the highest value");
        }
        Expr v = assignment(args.get(0));
        if (!(v instanceof Expr.Var var) || var.variable().kind() == Variable.Kind.GLOBAL
                || var.variable().kind() == Variable.Kind.STATIC_LOCAL
                || !(var.type() instanceof Type.Int t)) {
            throw new CompileError(at, "DAMPF_RANGE needs a local variable or parameter of an integer type");
        }
        long[] bounds = new long[2];
        for (int k = 0; k < 2; k++) {
            Long c = fold(rvalue(assignment(args.get(k + 1))));
            if (c == null) {
                throw new CompileError(pos(args.get(k + 1)), "the bounds of DAMPF_RANGE must be constants");
            }
            if (c < t.min() || c > t.max()) {
                throw new CompileError(pos(args.get(k + 1)), "DAMPF_RANGE: " + c + " is outside the range of " + t);
            }
            bounds[k] = c;
        }
        if (bounds[0] > bounds[1]) {
            throw new CompileError(at, "DAMPF_RANGE: the lowest value is above the highest");
        }
        return new Stmt.Range(var.variable(), bounds[0], bounds[1], at);
    }

    /** The postfix expression an assignment expression consists of, if that is all it is. */
    private static CParser.PostfixExpressionContext postfixOf(ParserRuleContext c) {
        while (c != null) {
            if (c instanceof CParser.PostfixExpressionContext p) {
                return p;
            }
            if (c.getChildCount() != 1 || !(c.getChild(0) instanceof ParserRuleContext inner)) {
                return null;
            }
            c = inner;
        }
        return null;
    }

    private Expr postfix(CParser.PostfixExpressionContext p) throws CompileError {
        CParser.PrimaryExpressionContext prim = p.primaryExpression();
        if (prim == null) {
            throw unsupported(p, "compound literals");
        }
        int n = p.getChildCount();
        Expr e = null;
        int i = 1;
        if (n > 1 && p.getChild(1).getText().equals("(")) {
            if (prim.Identifier() == null) {
                throw unsupported(p, "calls through expressions");
            }
            List<CParser.AssignmentExpressionContext> args = List.of();
            i = 3;
            if (p.getChild(2) instanceof CParser.ArgumentExpressionListContext al) {
                args = al.assignmentExpression();
                i = 4;
            }
            e = call(prim.Identifier().getSymbol(), args);
        } else {
            e = primary(prim);
        }
        while (i < n) {
            String suffix = p.getChild(i).getText();
            SourcePos sp = pos(((TerminalNode) p.getChild(i)).getSymbol());
            switch (suffix) {
                case "[" -> {
                    Expr index = rvalue(expression((CParser.ExpressionContext) p.getChild(i + 1)));
                    e = index(e, index, sp);
                    i += 3;
                }
                case ".", "->" -> {
                    String field = p.getChild(i + 1).getText();
                    Expr base = e;
                    if (suffix.equals("->")) {
                        base = rvalue(e);
                        if (!(base.type() instanceof Type.Pointer pt) || !(pt.target() instanceof Type.Struct)) {
                            throw new CompileError(sp, "'->' needs a pointer to a struct");
                        }
                        base = new Expr.Deref(base, pt.target(), sp);
                    }
                    if (!(base.type() instanceof Type.Struct st)) {
                        throw new CompileError(sp, "'" + suffix + "' needs a struct");
                    }
                    Type.Field f = st.def().field(field);
                    if (f == null) {
                        throw new CompileError(sp, "struct " + st.def().name() + " has no field '" + field + "'");
                    }
                    e = new Expr.Member(base, f, sp);
                    i += 2;
                }
                case "++", "--" -> {
                    checkAssignable(e, p);
                    Type t = e.type();
                    int step = t instanceof Type.Pointer ? elementSize(e, sp) : 1;
                    if (!Type.isScalar(t)) {
                        throw new CompileError(sp, "'" + suffix + "' needs a number or a pointer");
                    }
                    e = new Expr.PostIncDec(e, suffix.equals("++"), step, sp);
                    i += 1;
                }
                case "(" -> throw unsupported(p, "calling the result of an expression");
                default -> throw unsupported(p, "postfix '" + suffix + "'");
            }
        }
        return e;
    }

    /** {@code base[index]}, where either side may be the pointer (C allows {@code 2[a]}). */
    private Expr index(Expr base, Expr index, SourcePos p) throws CompileError {
        Expr b = base;
        Expr x = index;
        if (!(b.type() instanceof Type.Array || b.type() instanceof Type.Pointer)) {
            b = index;
            x = base;
        }
        x = rvalue(x);
        Type.Int it = Type.promote(intType(x, p));
        Type element;
        if (b.type() instanceof Type.Array a) {
            element = a.element();
        } else if (b.type() instanceof Type.Pointer pt) {
            element = pt.target();
            b = rvalue(b);
        } else {
            throw new CompileError(p, "'[]' needs an array or a pointer");
        }
        if (!complete(element)) {
            throw new CompileError(p, "indexing an array of incomplete type");
        }
        return new Expr.Index(b, convert(x, it, p), element, p);
    }

    private Expr call(Token name, List<CParser.AssignmentExpressionContext> argCtx) throws CompileError {
        Object o = lookup(name.getText());
        if (o == null) {
            throw new CompileError(pos(name), "implicit declaration of function '" + name.getText() + "'");
        }
        if (!(o instanceof Function f)) {
            throw new CompileError(pos(name), "'" + name.getText() + "' is not a function");
        }
        if (f.returnType() instanceof Type.Struct) {
            throw new CompileError(pos(name), "calls to functions that return a struct by value are not supported");
        }
        if (argCtx.size() != f.paramTypes().size()) {
            throw new CompileError(pos(name), "'" + f.name() + "' takes " + f.paramTypes().size() + " argument(s), not " + argCtx.size());
        }
        List<Expr> args = new ArrayList<>();
        for (int i = 0; i < argCtx.size(); i++) {
            Expr a = rvalue(assignment(argCtx.get(i)));
            args.add(assignConvert(a, f.paramTypes().get(i), pos(argCtx.get(i))));
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
            return stringLiteralObject(prim);
        }
        throw unsupported(prim, "this expression");
    }

    /** A string literal: an anonymous const char array in ROM. */
    private Expr stringLiteralObject(CParser.PrimaryExpressionContext prim) throws CompileError {
        StringBuilder sb = new StringBuilder();
        try {
            for (TerminalNode s : prim.StringLiteral()) {
                sb.append(unescape(s.getText()));
            }
        } catch (IllegalArgumentException e) {
            throw error(prim, e.getMessage());
        }
        byte[] bytes = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        Type.Array t = new Type.Array(Type.I8, bytes.length + 1);
        Variable v = new Variable("\"" + sb.toString().replace("\n", "\\n") + "\"", uniqueLabel("__str" + (anonymous++)), t,
                Variable.Kind.GLOBAL, pos(prim), true);
        globals.add(v);
        definedGlobals.add(v.label());
        List<Init.Item> items = new ArrayList<>();
        for (int i = 0; i < bytes.length; i++) {
            items.add(Init.Item.number(i, 1, bytes[i]));
        }
        initialValues.put(v, new Init(items));
        return new Expr.Var(v, pos(prim));
    }

    /** The type, if a postfix expression is just "(name)" with a typedef name no variable hides; else null. */
    private Type sizeofTypedef(CParser.PostfixExpressionContext p) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\(([A-Za-z_][A-Za-z_0-9]*)\\)").matcher(p.getText());
        if (!m.matches() || lookup(m.group(1)) != null) {
            return null;
        }
        return typedefs.get(m.group(1));
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

    // ---------------------------------------------------------------- conversions and helpers

    /** An array used as a value becomes a pointer to its first element; everything else stays. */
    private static Expr rvalue(Expr e) {
        if (e.type() instanceof Type.Array a) {
            return new Expr.Decay(e, new Type.Pointer(a.element()), e.pos());
        }
        return e;
    }

    /** Converts a value as an assignment does (also for arguments, return values and initializers). */
    private static Expr assignConvert(Expr e, Type t, SourcePos pos) throws CompileError {
        e = rvalue(e);
        switch (t) {
            case Type.Int it -> {
                if (e.type() instanceof Type.Pointer) {
                    throw new CompileError(pos, "a pointer cannot be assigned to an integer without a cast");
                }
                return convert(e, it, pos);
            }
            case Type.Pointer pt -> {
                if (e.type() instanceof Type.Pointer) {
                    return e.type().equals(pt) ? e : new Expr.PointerCast(e, pt, pos);
                }
                Long k = fold(e);
                if (k == null || k != 0) {
                    throw new CompileError(pos, "an integer cannot be assigned to a pointer (only the constant 0)");
                }
                return new Expr.PointerCast(e, pt, pos);
            }
            case Type.Struct st -> {
                if (!e.type().equals(st)) {
                    throw new CompileError(pos, "incompatible struct types");
                }
                return e;
            }
            default -> throw new CompileError(pos, "cannot assign a value of type " + e.type() + " to " + t);
        }
    }

    private Type.Int intType(Expr e, ParserRuleContext where) throws CompileError {
        return intType(e, pos(where));
    }

    private static Type.Int intType(Expr e, SourcePos pos) throws CompileError {
        if (e.type() instanceof Type.Int t) {
            return t;
        }
        throw new CompileError(pos, e.type() instanceof Type.Void ? "a void value is used" : "a number is needed here, not " + e.type());
    }

    private void scalar(Expr e, ParserRuleContext where) throws CompileError {
        scalar(e, pos(where));
    }

    private static void scalar(Expr e, SourcePos pos) throws CompileError {
        if (!Type.isScalar(e.type())) {
            throw new CompileError(pos, "a number or a pointer is needed here, not " + e.type());
        }
    }

    /** Converts to {@code t} as C does implicitly; constants are folded. */
    private static Expr convert(Expr e, Type.Int t, SourcePos pos) throws CompileError {
        if (!(e.type() instanceof Type.Int)) {
            throw new CompileError(pos, e.type() instanceof Type.Void ? "a void value is used" : "a number is needed here, not " + e.type());
        }
        if (e.type().equals(t)) {
            return e;
        }
        if (e instanceof Expr.Const k) {
            return new Expr.Const(t.wrap(k.value()), t, k.pos());
        }
        return new Expr.Convert(e, t, false, pos);
    }

    /** True if evaluating {@code e} changes something (so it must not be evaluated twice). */
    private static boolean hasSideEffects(Expr e) {
        if (e instanceof Expr.Assign || e instanceof Expr.PostIncDec || e instanceof Expr.Call) {
            return true;
        }
        for (Expr c : dampf.ast.Children.of(e)) {
            if (hasSideEffects(c)) {
                return true;
            }
        }
        return false;
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
                if (!(b.left().type() instanceof Type.Int)) {
                    yield null; // pointer comparison
                }
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
