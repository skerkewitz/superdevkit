# ADR-6FHSWZ2F: The notes of a release are a file in the repository, and its draft is not edited

**Context.** ADR-6FHEEVST has the release workflow make a draft with GitHub's generated list of
pull requests. For 0.9.1 the real notes were then written into the draft through the API, with
a request that changed only the body. GitHub answered that request by replacing the draft's tag
`v0.9.1` with its temporary name `untagged-...`. Publishing the draft then created a tag of that
name, and the `github-pages` environment, which allows only `v*`, refused the deployment. A test
draft showed the same: a body-only edit loses the tag, and an edit that names `tag_name` keeps it.

**Decision.**

- The notes of a release are the file `docs/releases/<version>.md`, written in the release's
  preparation and reviewed in its pull request.
- The release workflow creates the draft with that file and stops early on a version tag when
  the file is missing.
- A draft is not edited through the API. To change the notes before the tag, change the file.
- The website's workflow fails with a clear message when a release is published on a tag that
  is not a version tag.

**Alternatives.**

- Keep editing drafts and always pass `tag_name`: the smallest change, but one forgotten
  parameter breaks the next release again, and the notes are reviewed nowhere.
- Deploy on a release whatever its tag, by allowing `untagged-*` in the environment: the site
  would go live, but the release would still sit on the wrong tag.

**Consequences.** Every release has one more file to write, and its notes are in git. A mistake
found in the notes after the tag is corrected on the published release in GitHub's form, or, if
through the API, with `tag_name` in the request. ADR-6FHEEVST is amended: the draft's notes come
from this file instead of GitHub's generated list.
