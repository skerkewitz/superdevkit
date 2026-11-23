# ADR-6FHKAC1X: The website of a release is on GitHub Pages, with the book and the example games

**Context.** The repository is public and 0.9.1 is its first release with downloads. The book
exists only as Markdown in the repository and the zips, so nobody can read it before downloading
and search engines do not find it. The examples already build for the web, so a visitor could
play them without installing anything. The roadmap had planned the book on GitHub Pages.

**Decision.**

- The site is on GitHub Pages: the start page at `skerkewitz.github.io/superdevkit/`, the book at
  `/superdevkit/book/`, the example games at `/superdevkit/example/`.
- `dev/build_site.py` builds it and `.github/workflows/pages.yml` publishes it when a release is
  published, from that release's tag. The site therefore always describes a released version,
  never unfinished work on `develop`. A pull request that touches the site only builds it.
- The site holds the latest release only. The book of an older version stays in that release's
  zip.
- The gallery lists all seven examples, each with a screenshot from its own check. Er-Tüp is
  among them under its name, although that name is a pun on a trademark and its proposal
  (`docs/archive/er-tuep-proposal.md`) asks whether a public kit should keep it. It is a parody
  with its own ship, creatures and art, and the project's owner chose to show it.

**Alternatives.**

- Cloudflare Pages or Netlify: a preview for each pull request, but another account and an access
  token in CI, for a site that changes only at a release.
- Read the Docs: a book per version, more than an alpha needs, and VitePress needs a custom build
  there.
- Publishing from `develop` or on every merge into `main`: simpler triggers, but the site would
  show a version before its release is published.

**Consequences.** Two repository settings are needed once, by someone with admin rights: Pages
takes its source from GitHub Actions, and the `github-pages` environment allows the tags `v*`,
since a release runs on its tag. All games share one browser origin, and so one IndexedDB
database, with a save file per game. Of the listed games only CatchMe saves, so they cannot
overwrite each other; a second saving game would need this looked at, because a tab that was
opened earlier can write its older view of the database back. The games need a keyboard, so they
cannot be played on a phone.
