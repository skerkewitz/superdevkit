# 51. Claude works on GitHub as an app of its own, and the user merges

**Context.** Record 50 had Claude open pull requests, comment and push from the user's GitHub
account, and merge a pull request after the user's word. On GitHub that work could not be told
apart from the user's own except by the `🤖` at the start of a comment, and an author cannot
approve their own pull request, so the approval had to be a comment or a word in the session.
Record 50 named a GitHub account or App of Claude's own as the alternative that could be added
later. The user has now set up a GitHub App for Claude, and a wrapper on their machine,
`gh-as-bot`, that runs a command with a short-lived token of that app and with the app as git
author and committer.

**Decision.**

- Claude runs every `gh` command, every `git commit` and every `git push` through `gh-as-bot`.
  Pull requests, comments, commits and pushed branches are the app's, not the user's.
- Claude's comments keep starting with `🤖`.
- Claude never approves or merges a pull request. The user approves and merges on GitHub. After
  a merge Claude brings the main checkout up to date and removes the worktree and the branch, as
  before.
- For a release the user merges both pull requests. Claude tags the merge commit on `main` and
  pushes the tag, as before.

The rest of record 50 stands: a branch and worktree per change, the pull request template, the
check for leaks, merge commits only, no force-push while a review runs.

**Consequences.** The user can approve with GitHub's review button, and branch protection could
now require an approving review; whether it does is the user's choice. A plain `gh` or
`git push` in a session acts as the user again, so the `pr` skill puts `gh-as-bot` in front of
each one. Its token lasts an hour and the wrapper fetches a new one on each call. The app's token
cannot read `gh api user`, so a check of the identity looks at what it can reach, not at a user
name. The `pr` skill's `merge` step becomes `cleanup`, run after the user has merged.
