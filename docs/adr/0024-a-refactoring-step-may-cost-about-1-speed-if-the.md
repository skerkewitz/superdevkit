# 24. A refactoring step may cost about 1 % speed if the back end gets much cleaner

**Context.** The move of the back end to LIR ([transition-to-lir.md](../transition-to-lir.md))
replaced jobs the code generator did on the side with passes of their own. A pass that does a
job cleanly can find a little less than the special case it replaces.

**Decision.** A step that changes the generated code tries first not to make the game slower. If
it makes the back end much cleaner (for example writing each function once instead of up to 14
times) and costs about 1 % of cycles per tick that no reasonable effort wins back, it is taken
anyway. A bigger loss is taken too, since a step can be reverted, but every loss gets an entry in
[optimizations-log.md](../optimizations-log.md): how much slower, where, and why it could not be
avoided. A step never moves code and changes it at the same time.

**Consequences.** Cleaner code wins a tie with speed, and every such trade can be found in the
log.
