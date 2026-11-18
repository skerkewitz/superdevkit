# 15. The naive code generator stays as an independent reference

**Context.** The first complete code generator worked straight from the AST and made every
program right, slowly. The optimizing pipeline came after it, through MIR and LIR.

**Decision.** The naive code generator stays, works on the AST, and shares none of the passes
of the code generator from MIR (`dampf build --backend naive`). An independent reference
catches more bugs than one that shares code with what it checks. Every test program is built
with both and compared with gcc (record 16).

**Consequences.** A difference between the two ROMs points to the optimizing pipeline. Two code
generators have to be kept up with every language feature. The naive one writes no timing
report and checks no budget; `--replace` still works only with it.
