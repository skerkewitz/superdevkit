package dampf.frontend;

import dampf.parser.CLexer;
import dampf.parser.CParser;
import java.util.ArrayList;
import java.util.List;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ParserATNSimulator;
import org.antlr.v4.runtime.atn.PredictionContextCache;
import org.antlr.v4.runtime.dfa.DFA;

/** Parses preprocessed C text into an ANTLR parse tree, with errors at original source positions. */
public final class Parser {
    private Parser() {}

    /**
     * ANTLR's prediction caches. The generated parser shares one cache forever, which grows with
     * every new input; long test runs (thousands of random programs) ran out of memory. dampf keeps
     * its own and starts them fresh every {@link #FRESH_AFTER} parses.
     */
    private static final int FRESH_AFTER = 200;
    private static DFA[] dfa;
    private static PredictionContextCache cache;
    private static int parses;

    private static synchronized void useCaches(CParser parser) {
        if (dfa == null || ++parses % FRESH_AFTER == 0) {
            ATN atn = parser.getATN();
            dfa = new DFA[atn.getNumberOfDecisions()];
            for (int i = 0; i < dfa.length; i++) {
                dfa[i] = new DFA(atn.getDecisionState(i), i);
            }
            cache = new PredictionContextCache();
        }
        parser.setInterpreter(new ParserATNSimulator(parser, parser.getATN(), dfa, cache));
    }

    /** The parse tree of one translation unit, plus everything needed to report on it. */
    public record Result(CParser.CompilationUnitContext tree, CommonTokenStream tokens, SourceMap sourceMap,
                         List<Diagnostic> diagnostics) {
        public boolean ok() {
            return diagnostics.stream().noneMatch(d -> d.severity() == Diagnostic.Severity.ERROR);
        }
    }

    public static Result parse(String preprocessed, String sourceName) {
        SourceMap map = SourceMap.of(preprocessed, sourceName);
        List<Diagnostic> diagnostics = new ArrayList<>();
        BaseErrorListener listener = new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine,
                                    String msg, RecognitionException e) {
                diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, map.map(line, charPositionInLine), msg));
            }
        };
        CLexer lexer = new CLexer(CharStreams.fromString(preprocessed, sourceName));
        lexer.removeErrorListeners();
        lexer.addErrorListener(listener);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        CParser parser = new CParser(tokens);
        useCaches(parser);
        parser.removeErrorListeners();
        parser.addErrorListener(listener);
        CParser.CompilationUnitContext tree = parser.compilationUnit();
        return new Result(tree, tokens, map, diagnostics);
    }
}
