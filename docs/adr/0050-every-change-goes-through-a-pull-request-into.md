# 50. Every change goes through a pull request into develop, and a release through one into main

**Context.** Until now Claude worked on a branch for longer runs (`lir`, `optimise`, `flaws`) and
committed smaller changes straight to `develop`. The user reviewed locally, branches were merged
or fast-forwarded, and `main` was fast-forwarded to `develop` on request. Since 2026-11-21 the
main checkout stays on `develop`, and several sessions work at once: one in the main checkout,
others in worktrees. A change made in the main checkout is visible to the other sessions while it
is half done. No change carried a written account of how it was verified. Versions were raised
in `build.gradle.kts` but never tagged: `main` is at 0.7.3 and `develop` at 0.7.4, and the
repository has no tags. The user wants to run more sessions in parallel and review each change
on GitHub.

**Decision.**

- `develop` is the next release, and the main checkout always has it checked out. `main` holds
  the released versions. Both change only through pull requests on GitHub, a one-line fix
  included.
- Each change is made on a branch of its own, created from the latest `origin/develop`, in a
  worktree of its own in `superdevkit-wt/<branch>` next to the main checkout. Claude does
  not edit, build or switch branches in the main checkout. A session there sees `develop` either
  without a change or with all of it.
- When the change is done and verified, its pull request into `develop` says what changed and
  why, how it was verified (build, replays, measurements), what follows from it (generated code,
  the C guide, the game, the documents) and what is left open. The `pr` skill
  (`.claude/skills/pr`) has the steps and the template; it is refined as the workflow is.
- Everything sent to GitHub (commits, descriptions, comments) is written as public text, since
  the repository will be public. It names no paths on the user's machine, no user names, no
  passwords, tokens or keys, and nothing from Claude's memory or the session that the repository
  does not already contain. The skill checks each push and each text before it is sent.
- The user reviews on GitHub. Claude answers feedback with new commits on the same branch and
  does not force-push while the review runs. The pull requests are opened from the user's GitHub
  account, and GitHub does not let an author approve their own pull request. The approval is
  therefore the user's word, in a comment on the pull request or in the session, and branch
  protection asks for a pull request but for no approving review.
- After the approval Claude merges with a merge commit, then removes the worktree and the branch,
  locally and on GitHub. Squash and rebase merges are not used: they drop the single steps of a
  branch, each of them verified, and they would make `main` and `develop` diverge.
- A release is two pull requests. The first goes into `develop`: it raises the version (record
  45, roadmap "Versions") and brings the book in step (record 43). The second goes from `develop`
  into `main`. After it is merged, Claude tags the merge commit on `main` as `v<version>` and
  pushes the tag. The first tag is that of the first release made this way.
- A build on GitHub Actions for every pull request is the first change made this way. Once it
  exists, branch protection requires it to pass.

**Alternatives.**

- Small fixes straight on `develop`, branches only for long runs, as before: less work for a
  typo, but sessions in the main checkout see changes half done, and nothing records what was
  checked.
- Pull requests straight into `main` (GitHub flow): one branch less, but no place where changes
  collect before a release, and the version and the book would have to be brought in step after
  every pull request.
- A GitHub account or App of its own for Claude: the user could approve with GitHub's review
  button, and protection could require it. It is one more account and token to look after, and
  it can be added later without changing the rest of this record.
- Squash merges: a shorter history on `develop`, but the steps of a branch, each verified and
  measured, would be lost, and bisecting would stop at whole branches.

**Consequences.** Every change costs a branch, a worktree and a pull request, a small fix as
well. `develop` moves only by merges. Long unattended runs keep their rule of one verified commit
per step and end in a pull request like any other change. The user sets up branch protection for
`main` and `develop`: pull request required, no force-push, no deletion, no approving review
required, merge commits allowed, and no requirement that a branch be up to date with its base
(the merge commits on `main` are not on `develop`). Each worktree links `.mesen` to the main
checkout's, so the execution tests run in Mesen there too; `.gitignore` now ignores the link.
