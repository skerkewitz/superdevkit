# ADR-6FK7WP0H: The roadmap holds what is to come, and a concluded milestone moves to the history

**Context.** `docs/roadmap.md` had grown to 670 lines by 0.9.2. Nine concluded milestones, the
changes to the plan, the publishing steps, the playable ROM and the answered questions took
most of it, and the parts of the milestones still open were interleaved with notes on what had
already been built. Someone looking for what comes next had to read past all of it, and a
review on 2026-11-24 found an open item (the inlining limit "still to be checked" on the
examples) that had been done three days before but was buried in the history.

**Decision.**

- `docs/roadmap.md` holds what is to come: the milestones not yet concluded with what is left
  of them, what is not scheduled, the open items outside the milestones, the future ideas, the
  risks and the open questions, and the rule for versions.
- `docs/history.md` holds what is done: the concluded milestones, what was built ahead of a
  milestone and released, the changes to the plan, the answered questions.
- An item that is built and released leaves the roadmap. A note that says what is already there
  stays only where it is needed to say what is left.
- In a release's preparation, a concluded milestone moves from the roadmap to the history.

**Alternatives.**

- Keep one file and move the history to its end: it was already at the end, and the file
  still mixed done and open work in the milestones' sections.
- Let the release notes (`docs/releases/`) be the history: they start at 0.9.2, describe a
  version rather than a milestone, and have no place for the changes to the plan.

**Consequences.** The book's roadmap page and its introduction read both files, so their specs
list `docs/history.md` as a source. Links into the old sections point to the history. One more
step in a release's preparation, which replaces marking the milestone as done in the roadmap.
