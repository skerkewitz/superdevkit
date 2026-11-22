package dampf.frontend.pp;

import java.util.List;

/**
 * A macro (C99 6.10.3): object-like ({@code params == null}) or function-like, with its parameters
 * ({@code __VA_ARGS__} last for a variadic one) and its replacement list.
 */
record Macro(String name, List<String> params, boolean variadic, List<Token> body) {
    boolean functionLike() {
        return params != null;
    }

    /** The index of a parameter, or -1. */
    int param(Token t) {
        return params == null || t.kind() != Token.Kind.IDENT ? -1 : params.indexOf(t.text());
    }
}
