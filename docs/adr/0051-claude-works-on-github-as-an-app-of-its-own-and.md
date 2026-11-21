# 51. Claude works on GitHub as an app of its own, and the user approves

**Context.** Record 50 had Claude open pull requests, comment and push from the user's GitHub
account. On GitHub that work could not be told apart from the user's own except by the `🤖` at
the start of a comment, and an author cannot approve their own pull request, so the approval had
to be a comment or a word in the session. Record 50 named a GitHub account or App of Claude's
own as the alternative that could be added later. The user has now set up a GitHub App for
Claude, and a wrapper on their machine, `gh-as-bot`, that runs a command with a short-lived
token of that app and with the app as git author and committer.

**Decision.**

- Claude runs every `gh` command, every `git commit` and every `git push` through `gh-as-bot`.
  Pull requests, comments, commits and pushed branches are the app's, not the user's.
- Claude's comments keep starting with `🤖`.
- Every pull request Claude opens asks the user for a review.
- Claude never approves a pull request. The approval is the user's: an approving review on
  GitHub, which is now possible because the app is the author, or the user's word in the
  session. After the approval, and once the CI build of the last commit has passed, Claude
  merges as the app, with a merge commit as before, and cleans up.

The rest of record 50 stands: a branch and worktree per change, the pull request template, the
check for leaks, merge commits only, no force-push while a review runs, and the release with its
tag.

**Alternatives.**

- The user merges as well as approves, and Claude only cleans up afterwards. It keeps every
  change of `develop` in the user's hand, but each pull request then needs the user twice, and
  the approval already says the change may go in.

**Consequences.** Branch protection can now require an approving review, since the reviewer is
no longer the author; whether it does is the user's choice. A plain `gh` or `git push` in a
session acts as the user again, so the `pr` skill puts `gh-as-bot` in front of each one. Its
token lasts an hour and the wrapper fetches a new one on each call. The app needs read access to
checks and Actions for the skill's CI check. Its token cannot read `gh api user`, so a check of
the identity looks at what it can reach, not at a user name.
