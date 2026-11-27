package dampf.ast;

import dampf.frontend.SourcePos;
import java.util.ArrayList;
import java.util.List;

/** A function. Its body is set once the definition has been read (a prototype may come first). */
public final class Function {
    private final String name;
    private final String label;
    private final Type returnType;
    private final List<Type> paramTypes;
    private final SourcePos pos;
    private final List<Variable> params = new ArrayList<>();
    private final List<Variable> locals = new ArrayList<>();
    private Stmt.Block body;
    private SourcePos definitionPos;
    private Long budget;
    private Interrupt interrupt;

    public Function(String name, String label, Type returnType, List<Type> paramTypes, SourcePos pos) {
        this.name = name;
        this.label = label;
        this.returnType = returnType;
        this.paramTypes = List.copyOf(paramTypes);
        this.pos = pos;
    }

    public String name() {
        return name;
    }

    public String label() {
        return label;
    }

    public Type returnType() {
        return returnType;
    }

    public List<Type> paramTypes() {
        return paramTypes;
    }

    /** Where it was first declared. */
    public SourcePos pos() {
        return pos;
    }

    public List<Variable> params() {
        return params;
    }

    /** Locals and temporaries of the static frame. */
    public List<Variable> locals() {
        return locals;
    }

    public Stmt.Block body() {
        return body;
    }

    public SourcePos definitionPos() {
        return definitionPos;
    }

    /** The cycle budget from DAMPF_BUDGET (master cycles, calls included), or null. */
    public Long budget() {
        return budget;
    }

    public void setBudget(long cycles) {
        this.budget = cycles;
    }

    private int irqHdmaChannels;
    private int irqHdmaBytes;

    /**
     * The HDMA the IRQ handler declares for its line (SDEVKIT_IRQ_HDMA, sdevkit/irq.h): the channels
     * of every declaration in it, and the most bytes one names; 0 and 0 without one.
     */
    public int irqHdmaChannels() {
        return irqHdmaChannels;
    }

    public int irqHdmaBytes() {
        return irqHdmaBytes;
    }

    public void declareIrqHdma(int channels, int bytes) {
        irqHdmaChannels |= channels;
        irqHdmaBytes = Math.max(irqHdmaBytes, bytes);
    }

    /** The interrupt this function handles (DAMPF_INTERRUPT), or null for an ordinary function. */
    public Interrupt interrupt() {
        return interrupt;
    }

    public void setInterrupt(Interrupt kind) {
        this.interrupt = kind;
    }

    private String asmFile;

    /**
     * The file of hand-written assembly from DAMPF_ASM, as written in the source (relative to the
     * C file the definition is in), or null.
     */
    public String asmFile() {
        return asmFile;
    }

    public void setAsmFile(String file) {
        this.asmFile = file;
    }

    public boolean isDefined() {
        return body != null;
    }

    public void define(List<Variable> params, Stmt.Block body, SourcePos definitionPos) {
        this.params.addAll(params);
        this.body = body;
        this.definitionPos = definitionPos;
    }

    /**
     * Replaces the body by one that differs only in the functions it calls: in an interrupt
     * context, a function calls that context's copies ({@code dampf.check.ContextCopies}).
     */
    public void replaceBody(Stmt.Block body) {
        if (this.body == null) {
            throw new IllegalStateException(name + " has no body to replace");
        }
        this.body = body;
    }

    @Override
    public String toString() {
        return name;
    }
}
