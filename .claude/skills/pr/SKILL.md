---
name: pr
description: The kit's workflow for every change, from branch to release (ADR record 50). Start a change on its own branch and worktree made from develop, open its pull request into develop with what changed, how it was verified and what follows from it, answer review comments on GitHub, merge after the user's approval and clean up; and make a release (version pull request into develop, pull request from develop into main, tag). Use it for every change to the repository, when the user asks to implement or fix something, to open or update a pull request, to look at feedback on a PR, to merge one, or to make a release, or invokes /pr.
---

# pr: every change through a pull request

The rules are in ADR record 50. In short: `develop` is the next release and `main` holds the
released versions. Both change only through pull requests, and every change has its own branch
and worktree. The user reviews on GitHub; Claude merges after the approval and cleans up.

Paths and names used below:

- `MAIN` = `/home/tropper/develop_projects/superdevkit`, the main checkout. It stays on
  `develop`. Never edit, build, commit or switch branches in it. The one exception is the
  fast-forward after a merge (see `merge`).
- `WT` = `/home/tropper/develop_projects/superdevkit-wt/<branch>`, the worktree of a change.
- `REPO` = `skerkewitz/superdevkit`.

## Invocation

- `/pr start <branch>`: create the branch and its worktree.
- `/pr open`: verify the branch and open its pull request into `develop`.
- `/pr feedback <N>`: read the review of pull request N and answer it.
- `/pr merge <N>`: merge pull request N after the approval, then clean up.
- `/pr release`: make a release (two pull requests and a tag).
- `/pr status`: list the open pull requests, the worktrees and the branches, and say what each
  waits for.

When the user asks for a change without naming a step, start with `start` and go on to `open`
when the work is done. Long unattended runs follow the same steps; the run is the work between
`start` and `open`.

## start

1. Branch name: short, lower case, words joined by `-`, saying what the change is (`ci`,
   `inline-once-called`, `release-0.7.5`). Check that neither a local nor a remote branch of
   that name exists.
2. Create it from the latest `develop`, without upstream tracking (with tracking, a plain
   `git push` would go to `develop`):

   ```
   git -C MAIN fetch origin
   git -C MAIN worktree add --no-track -b <branch> WT origin/develop
   [ -d MAIN/.mesen ] && ln -s MAIN/.mesen WT/.mesen
   git -C WT push -u origin <branch>
   ```

   With the link the execution tests also run in Mesen in the worktree (`.gitignore` ignores it).
3. Do all work in `WT`: edits, builds, tests, benchmarks, commits. Commit each step that is done
   and verified, and push it. Follow CLAUDE.md as everywhere: a language feature needs an
   execution test, game code follows `lib/C_STYLE.md`, decisions get an ADR record, the book is
   left alone until the release.

If the work turns out to be a different change than the branch name says, tell the user; don't
fold a second change into the branch.

## open

1. Bring the branch up to date with `develop`. As long as nobody has reviewed it, rebase:
   `git -C WT fetch origin && git -C WT rebase origin/develop`, then
   `git -C WT push --force-with-lease`. Once a pull request is open, merge instead
   (`git -C WT merge origin/develop`) and never force-push.
2. Verify in `WT`: `./gradlew build` must pass. Add what the change needs on top: replays
   (`-Preplays`) and the benchmark (`dev/bench_examples.sh`) for changes to dampf's output, the
   numbers before and after for anything that claims to be faster or smaller. Note what you ran
   and what came out; the description reports it.
3. Write the description to a file in the scratchpad, following the template below and the
   `sker-natural-writing` skill. It is written for the user reviewing on GitHub, who has not
   watched the work.
4. Open it:

   ```
   gh pr create -R REPO --base develop --head <branch> --title "<title>" --body-file <file>
   ```

   The title is a short sentence in the style of the commit log ("K4: a large function called
   once in a loop is inlined"). Give the user the link.

### Template

```markdown
## What

What the change does, in a few sentences. The reason for it, if the title does not say it.

## How

How it is done, as much as a reviewer needs to read the diff: the approach, the files that
matter, anything surprising. Decisions with an ADR record are cited by number.

## Verified

What was run and what came out: the build (number of tests), replays, benchmarks with
numbers before and after, a manual check and what it showed. Say what was not checked and why.

## What follows

What the change means beyond its diff: generated code (size, cycles), the C guide or its
`StyleGuideTest` rules, the game (and whether `docs/game-findings.md` got an entry), the
documents changed, the book pages that will be stale at the release, compatibility. Leave out
the lines that do not apply.

## Open

What is left to do or decide, and what the reviewer should look at closely. Leave the section
out if there is nothing.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
```

Scale it to the change. A one-line fix gets a sentence under What and one under Verified; a
week of optimisation gets tables.

## feedback

1. Read everything on the pull request:

   ```
   gh pr view <N> -R REPO --comments
   gh api repos/REPO/pulls/<N>/reviews
   gh api repos/REPO/pulls/<N>/comments
   ```

   The last call gives the comments on lines of the diff, with `id`, `path`, `line` and
   `in_reply_to_id`.
2. The pull requests and every comment Claude writes appear under the user's account. Claude's
   comments therefore start with `🤖`; a comment without it is the user's.
3. For each point: if it asks for a change, make it in `WT` as a new commit, verify as in
   `open`, push. If it asks a question, answer it. If you disagree, say so with the reason and
   leave the decision to the user. Answer a comment on a line in its thread:

   ```
   gh api repos/REPO/pulls/<N>/comments/<id>/replies -f body="🤖 ..."
   ```

   and finish with one comment on the pull request that lists what changed, with the commits:
   `gh pr comment <N> -R REPO --body-file <file>`.
4. If the description no longer fits (new numbers, a changed approach), update it with
   `gh pr edit <N> -R REPO --body-file <file>`.

## merge

1. Check the approval. It is the user saying so in the session, or a comment or review by the
   user (without `🤖`) that approves the pull request. Without it, stop.
2. Check that it can be merged: `gh pr view <N> -R REPO --json mergeable,mergeStateStatus` and,
   once the repository has CI, `gh pr checks <N> -R REPO`. If `develop` has moved and the branch
   conflicts, merge `origin/develop` into the branch in `WT`, resolve, verify, push, and tell the
   user what the resolution did before merging.
3. Merge with a merge commit, and keep the branch for now (`--delete-branch` would try to
   switch the main checkout):

   ```
   gh pr merge <N> -R REPO --merge
   ```

4. Fast-forward the main checkout, but only if it has no changes to tracked files (another
   session may be working there); otherwise tell the user it is behind:

   ```
   git -C MAIN fetch origin
   [ -z "$(git -C MAIN status --porcelain --untracked-files=no)" ] && git -C MAIN merge --ff-only origin/develop
   ```

5. Clean up. `git worktree remove` refuses a worktree with changes; if it does, stop and ask.

   ```
   rm -f WT/.mesen
   git -C MAIN worktree remove WT
   git -C MAIN branch -d <branch>
   git -C MAIN push origin --delete <branch>
   ```

## release

The user decides when `develop` is released. Two pull requests and a tag:

1. Version. Propose the next one by the rule in the roadmap ("Versions", ADR record 45): the
   last number rises, the middle one when a milestone is concluded. The user confirms it.
2. Preparation, as a normal change: `start release-<version>`, then in `WT`:
   - `./gradlew checkBook`, then `/docs-gen stale` for the pages it lists, until `checkBook`
     passes (ADR record 43);
   - raise the version in `build.gradle.kts` and in the status line of `README.md`; if a
     milestone is concluded, mark it in `docs/roadmap.md`;
   - `./gradlew build`.

   `open` it with the title "Version <version>". Under What, list the pull requests merged since
   the last tag: `git log --first-parent --oneline v<last>..origin/develop`, or all of
   `develop`'s history if there is no tag yet. Merge it as usual after the approval.
3. Release pull request, from `develop` itself:

   ```
   gh pr create -R REPO --base main --head develop --title "Release <version>" --body-file <file>
   ```

   The description lists the pull requests in the release, with one line each, and what the
   user of the kit notices (new features, changes to the command line, the subset, the files
   dampf writes).
4. After the approval: `gh pr merge <N> -R REPO --merge`. Never pass `--delete-branch` here:
   `develop` stays.
5. Tag the merge commit on `main` and push the tag:

   ```
   git -C MAIN fetch origin
   git -C MAIN tag -a v<version> origin/main -m "superdevkit <version>"
   git -C MAIN push origin v<version>
   ```

## status

```
gh pr list -R REPO --state open
git -C MAIN worktree list
git -C MAIN branch -a --no-merged origin/develop
```

For each open pull request say whether it waits for the user (review), for Claude (feedback
to answer, conflicts) or is approved and ready to merge. Name worktrees whose branch is merged
or gone; remove them only if they are under `superdevkit-wt` and clean, and ask about the others,
which may belong to another session.
