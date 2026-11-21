---
name: pr
description: The kit's workflow for every change, from branch to release (ADR records 50 and 51). Start a change on its own branch and worktree made from develop, open its pull request into develop with what changed, how it was verified and what follows from it, answer review comments on GitHub, clean up after the user has merged; and make a release (version pull request into develop, pull request from develop into main, tag). Use it for every change to the repository, when the user asks to implement or fix something, to open or update a pull request, to look at feedback on a PR, to clean up after a merge, or to make a release, or invokes /pr.
---

# pr: every change through a pull request

The rules are in ADR records 50 and 51. In short: `develop` is the next release and `main` holds
the released versions. Both change only through pull requests, and every change has its own
branch and worktree. Claude works on GitHub as an app of its own. The user reviews, approves and
merges on GitHub; Claude cleans up after the merge.

Paths and names used below:

- `MAIN` = the main checkout, the folder that holds the repository's `.git`. From any worktree:
  `dirname "$(git rev-parse --path-format=absolute --git-common-dir)"`. It stays on `develop`.
  Never edit, build, commit or switch branches in it. The one exception is the fast-forward
  after a merge (see `cleanup`).
- `WT` = `MAIN/../superdevkit-wt/<branch>`, the worktree of a change, in a folder next to the
  main checkout.
- `REPO` = `skerkewitz/superdevkit`.
- `USER` = `skerkewitz`, the user's GitHub account, which reviews every pull request.

## Working as the app

Every `gh` command, every `git push` and every git command that writes a commit or a tag
(`commit`, a `merge` that is not a fast-forward, `rebase`, `tag`) runs through the
`gh-as-bot` wrapper (ADR record 51): `gh-as-bot gh pr create ...`, `gh-as-bot git push ...`.
It fetches a token of Claude's GitHub App for the one command and makes the app the git author
and committer. A plain `gh` or `git push` acts as the user: never use one, and if the wrapper
fails, stop and tell the user. The commands below all carry the wrapper. The wrapper reads
standard input while it fetches the token, so a text never goes in through it: give a commit
message as a file (`git commit -F <file>`) and a body with `--body-file <file>`, not `-F -`.

Claude never approves or merges a pull request; that is the user's, on GitHub. The app's token
cannot read `gh api user` (HTTP 403 is the expected answer). To check that the wrapper works,
ask for something the app can reach: `gh-as-bot gh api repos/REPO --jq .full_name`.

## Everything sent to GitHub is public

The repository will be public one day, and with it every pull request, comment, commit message
and committed file. GitHub keeps the edit history of descriptions and comments, and a merge
commit keeps the commits of its branch, so neither an edit nor a later commit takes text back.
Before every `git push`, `gh pr create`, `gh pr edit`, `gh pr comment` and `gh api ... -f body=`,
check what goes out (the new commits' diff and messages, and the text) for:

- paths on the user's machine: home folders, WSL mounts (`/mnt/<drive>/`), Windows drives and
  user folders, the session's scratchpad, `/tmp/claude-*`. Name a place by its path in the
  repository (`tools/`, `docs/testing.md`) or say where it is ("next to the main checkout").
  Paths already in the repository may be quoted as they are;
- user names, host names, email addresses (the author line of a commit is the exception), and
  anything else about the user's machine or accounts;
- passwords, tokens and keys (`github_pat_`, `ghp_`, `gho_`, private key blocks, cloud keys),
  and files that hold them (`.env`, `*.pem`, credential files, `.claude/settings.local.json`);
- anything from Claude's memory or the conversation that the repository does not already
  contain. Some of the user's plans are private on purpose; when unsure, leave it out or ask;
- the game's code and data (ADR record 40): say what a change means for the game without
  quoting it.

The mechanical part of the check, for the lines a branch adds and for a text before it is sent:

```
LEAK='/home/|/Users/|/mnt/[a-z]/|[A-Za-z]:\\|/tmp/claude|github_pat_|gh[opsu]_[A-Za-z0-9]{20}|PRIVATE KEY|AKIA[0-9A-Z]{16}|password *[:=]'
git -C WT diff origin/develop... | grep '^+' | grep -nE "$LEAK"
git -C WT log --format=%B origin/develop.. | grep -nE "$LEAK"
grep -nE "$LEAK" <text file>
```

Look at every hit; a hit can be harmless (this skill's own pattern, a path that is in the
repository already). The pattern does not find the rest of the list above: read the text for
it. If something has already gone out, stop and tell the user what and where. Removing it
takes a force-push or the deletion of a revision on GitHub, and that is the user's decision.

## Invocation

- `/pr start <branch>`: create the branch and its worktree.
- `/pr open`: verify the branch and open its pull request into `develop`.
- `/pr feedback <N>`: read the review of pull request N and answer it.
- `/pr cleanup <N>`: after the user has merged pull request N, update the main checkout and
  remove the worktree and the branch.
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
   gh-as-bot git -C WT push -u origin <branch>
   ```

   With the link the execution tests also run in Mesen in the worktree (`.gitignore` ignores it).
3. Do all work in `WT`: edits, builds, tests, benchmarks, commits. Commit each step that is done
   and verified (`gh-as-bot git -C WT commit ...`), and push it. Follow CLAUDE.md as everywhere: a language feature needs an
   execution test, game code follows `lib/C_STYLE.md`, decisions get an ADR record, the book is
   left alone until the release.

If the work turns out to be a different change than the branch name says, tell the user; don't
fold a second change into the branch.

## open

1. Bring the branch up to date with `develop`. As long as nobody has reviewed it, rebase:
   `git -C WT fetch origin && gh-as-bot git -C WT rebase origin/develop`, then
   `gh-as-bot git -C WT push --force-with-lease`. Once a pull request is open, merge instead
   (`gh-as-bot git -C WT merge origin/develop`) and never force-push.
2. Verify in `WT`: `./gradlew build` must pass. Add what the change needs on top: replays
   (`-Preplays`) and the benchmark (`dev/bench_examples.sh`) for changes to dampf's output, the
   numbers before and after for anything that claims to be faster or smaller. Note what you ran
   and what came out; the description reports it.
3. Write the description to a file in the scratchpad, following the template below and the
   `sker-natural-writing` skill. It is written for the user reviewing on GitHub, who has not
   watched the work.
4. Check the branch and the description for leaks (see "Everything sent to GitHub is public").
5. Open it:

   ```
   gh-as-bot gh pr create -R REPO --base develop --head <branch> --reviewer USER --title "<title>" --body-file <file>
   ```

   The title is a short sentence in the style of the commit log ("K4: a large function called
   once in a loop is inlined"). The user is asked for a review on every pull request. Give the
   user the link.
6. CI builds the pull request's last commit. Wait for it and check that it passed:
   `gh-as-bot gh pr checks <N> -R REPO --watch` (run it in the background; a build takes
   minutes). If the build failed, `gh-as-bot gh run view <id> -R REPO --log-failed` shows why
   (the id is in the link `gh pr checks` prints), and
   `gh-as-bot gh run download <id> -R REPO -n test-reports -D <scratchpad folder>` fetches the
   test reports. Fix it as a change to the open pull request.

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

## Changes to an open pull request

Every push to a pull request that is already open gets a comment, so the user can follow what
changed since they last looked. This holds whatever caused the change: a review comment, the
user's word in the session, something Claude found itself, a merge of `develop`, a resolved
conflict. After the push, one comment per push:

```
🤖 <short title of the fix>

Problem: what was wrong or missing, and how it came up (review comment, own check, failed build).
Fix: what was changed and why this way.
Commits: <hash> <subject>, ...
Verified: what was run again and what came out.
```

Post it with `gh-as-bot gh pr comment <N> -R REPO --body-file <file>`, after the leak check. A merge of
`develop` without conflicts needs only a line ("Merged develop at <hash>; build passed"). If the
fix changes what the description says, update the description too (`gh-as-bot gh pr edit`), and say so in
the comment.

## feedback

1. Read everything on the pull request:

   ```
   gh-as-bot gh pr view <N> -R REPO --comments
   gh-as-bot gh api repos/REPO/pulls/<N>/reviews
   gh-as-bot gh api repos/REPO/pulls/<N>/comments
   ```

   The last call gives the comments on lines of the diff, with `id`, `path`, `line` and
   `in_reply_to_id`.
2. The pull requests and every comment Claude writes appear under the app's name, with `[bot]`
   after it. Claude's comments start with `🤖` all the same; a comment from the user's account is
   the user's.
3. For each point: if it asks for a change, make it in `WT` as a new commit, verify as in
   `open`, push. If it asks a question, answer it. If you disagree, say so with the reason and
   leave the decision to the user. Answer a comment on a line in its thread:

   ```
   gh-as-bot gh api repos/REPO/pulls/<N>/comments/<id>/replies -f body="🤖 ..."
   ```

   and after the push write the comment of "Changes to an open pull request", one section per
   point of the review.
4. If the description no longer fits (new numbers, a changed approach), update it with
   `gh-as-bot gh pr edit <N> -R REPO --body-file <file>`.

## cleanup

The user merges on GitHub. Claude never does, not even after an approval in the session; if
the user asks Claude to merge, say that the merge is theirs.

1. Check that the pull request is merged:
   `gh-as-bot gh pr view <N> -R REPO --json state,headRefName`. If `state` is not `MERGED`, stop.
2. Fast-forward the main checkout, but only if it has no changes to tracked files (another
   session may be working there); otherwise tell the user it is behind:

   ```
   git -C MAIN fetch origin
   [ -z "$(git -C MAIN status --porcelain --untracked-files=no)" ] && git -C MAIN merge --ff-only origin/develop
   ```

3. Clean up. `git worktree remove` refuses a worktree with changes; if it does, stop and ask.
   If GitHub has deleted the branch already, the last line has nothing left to do.

   ```
   rm -f WT/.mesen
   git -C MAIN worktree remove WT
   git -C MAIN branch -d <branch>
   gh-as-bot git -C MAIN push origin --delete <branch>
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
   `develop`'s history if there is no tag yet. The user merges it; then `cleanup`.
3. Release pull request, from `develop` itself:

   ```
   gh-as-bot gh pr create -R REPO --base main --head develop --reviewer USER --title "Release <version>" --body-file <file>
   ```

   The description lists the pull requests in the release, with one line each, and what the
   user of the kit notices (new features, changes to the command line, the subset, the files
   dampf writes).
4. The user merges it, with a merge commit, and keeps `develop`. There is nothing to clean up.
5. Once it is merged, tag the merge commit on `main` and push the tag:

   ```
   git -C MAIN fetch origin
   gh-as-bot git -C MAIN tag -a v<version> origin/main -m "superdevkit <version>"
   gh-as-bot git -C MAIN push origin v<version>
   ```

## status

```
gh-as-bot gh pr list -R REPO --state open
git -C MAIN worktree list
git -C MAIN branch -a --no-merged origin/develop
```

For each open pull request say whether it waits for the user (review, merge) or for Claude
(feedback to answer, a failed CI build, conflicts with `develop`). For a conflict, merge
`origin/develop` into the branch in `WT` (`gh-as-bot git -C WT merge origin/develop`), resolve, verify, push, and say in the comment what the
resolution did. Name worktrees whose branch is merged
or gone; remove them only if they are under `superdevkit-wt` and clean, and ask about the others,
which may belong to another session.
