# Vendored C grammar

`CLexer.g4` and `CParser.g4` come unchanged from
[antlr/grammars-v4](https://github.com/antlr/grammars-v4/tree/754339a6bf1e8e622fdce4e26dc846baee603ff4/c),
commit `754339a6bf1e8e622fdce4e26dc846baee603ff4`, under the BSD licence in their headers.
The support classes in `compiler/src/main/java/dampf/parser/` (`CParserBase`, `Symbol`, `SymbolTable`,
`TypeClassification`) come from the same commit (`c/Java/`). Changes:

- all support classes are in package `dampf.parser`;
- `CLexerBase` no longer runs a preprocessor: dampf runs `gcc -E` itself and lexes its output;
- `CParserBase` no longer reads options from the `sun.java.command` system property;
- `CParserBase.EnterDeclaration` no longer declares struct and union tags as type names (C keeps
  tags in a namespace of their own; declaring them broke `typedef struct S {...} S;`).

The grammar parses C23 plus GNU extensions, which is far more than the dampf subset. That is on
purpose: the subset checker reports what is not supported, with a clear message, instead of a
parse error.
