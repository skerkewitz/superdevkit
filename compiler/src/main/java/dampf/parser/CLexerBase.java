// Adapted from grammars-v4 c/Java/CLexerBase.java (commit 754339a6). dampf runs the
// preprocessor itself (dampf.frontend.pp), so the lexer gets preprocessed text and does nothing extra.
package dampf.parser;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.Lexer;

public abstract class CLexerBase extends Lexer {
    protected CLexerBase(CharStream input) {
        super(input);
    }
}
