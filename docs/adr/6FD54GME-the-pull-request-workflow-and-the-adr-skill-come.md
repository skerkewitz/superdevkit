# ADR-6FD54GME: The pull-request workflow and the ADR skill come from the sker-project package, installed with apmw

**Context.** The steps of ADR-6FB9QJ3R were in `.claude/skills/pr`, a skill of this repository.
Most of it holds for any project that works this way: a branch and worktree per change, the
wrapper of ADR-6FB9QKSV, the check for leaks before anything goes to GitHub, the template of a
description, review, merge and release. The rest is this project's: `./gradlew build`, replays
and the benchmark, the link to `.mesen` in a worktree, the release preparation with `checkBook`,
the topics of dampf and the game in a description. The user wants the workflow and the scheme of
ADR-6FCBBNSH in other projects too. Their agent skills are kept as APM packages in
`skerkewitz/agent-packages`, where the package `sker-project` now holds the conventions a
project opts into: `sker-pr` (the workflow, with the project's rules read from its CLAUDE.md)
and `sker-adr` (the records and their scripts).

**Decision.**

- `apm.yml` names `sker-project` at a release tag; `apm.lock.yaml` pins the commit. `./apmw
  install` puts `sker-pr` and `sker-adr` into `.claude/skills/`.
- `apmw` (`skerkewitz/apmw`) is a wrapper in the manner of `gradlew`: `apmw`, `apmw.cmd` and
  `apm/wrapper/apmw.properties` are committed and pin the version of APM and its checksums, so
  nobody installs APM by hand.
- The installed skills are not committed: `.gitignore` leaves out `.claude/skills/sker-*/` and
  `apm_modules/`. Each checkout runs `./apmw install` once, and again after the version in
  `apm.yml` is raised. The project's own skills in `.claude/skills/` are not named `sker-*`.
- `.claude/skills/pr` is removed. What only this project needs is the section "Pull requests"
  of CLAUDE.md, which `sker-pr` reads.
- The records keep `dev/new_adr.sh` and `AdrTest`; `sker-adr` follows what CLAUDE.md says about
  them. `AdrTest` skips `apm_modules/`.

**Alternatives.**

- Commit the installed skills: every checkout, worktree and cloud session would have them at
  once, and a new version would show up as a diff of the skills' text in its pull request. The
  user chose to run `./apmw install` once per checkout for now.
- Keep the skill in this repository and copy it into other projects: the copies drift apart.
- Install APM by hand or globally instead of `apmw`: each machine and CI would run another
  version.

**Consequences.** A session that starts in a fresh clone or a worktree where `./apmw install`
has not run has no `sker-pr`; CLAUDE.md still states the rules, so it can follow them, but it
should run the install first. A change to the workflow is made in `agent-packages`, released
there with a tag and reaches this project by raising the version in `apm.yml`. ADR-6FB9QJ3R
names `.claude/skills/pr` as the home of the steps; that is now `sker-pr` together with
CLAUDE.md. The other projects of the user can take the same workflow by adding `sker-project`
to their `apm.yml` and a section "Pull requests" to their CLAUDE.md.
