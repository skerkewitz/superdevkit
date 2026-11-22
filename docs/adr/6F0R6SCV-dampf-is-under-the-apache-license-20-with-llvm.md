# ADR-6F0R6SCV: dampf is under the Apache License 2.0 with LLVM Exceptions

**Context.** dampf puts code of its own into every program it compiles: the runtime, and the
code it generates. Under a licence without an exception for that, the game would carry the
licence's conditions.

**Decision.** dampf is under the Apache License v2.0 with LLVM Exceptions, the licence of LLVM
(SPDX: `Apache-2.0 WITH LLVM-exception`). The exception means that code dampf puts into a
compiled program carries no licence conditions. Third-party parts keep their own licence, listed
in `NOTICE` (the C grammar from grammars-v4).

**Consequences.** Games built with dampf can use any licence. Code copied from a project under
another licence (Mesen's GPLv3, ADR-6F3CJMJ5) never goes into dampf.
