---
name: docs-gen
description: Generate or update pages of the dampf book (docs/book, VitePress) from the spec files in doc-specs/. Use when the user asks to generate, regenerate or update book pages from specs, to check whether a spec is complete, or invokes /docs-gen.
---

# docs-gen: book pages from specs

Hand-maintained specs in `doc-specs/` say what a page of the book covers; the files they list
under `sources` say what is true. You write the page.

## Invocation

- `/docs-gen doc-specs/<path>.spec.md` – generate or update that one page.
- `/docs-gen all` – every spec in `doc-specs/`.
- `/docs-gen stale` – every page that is older than its sources, usually before a release (ADR
  ADR-6F60ZB4N). Run `./gradlew checkBook` and take the pages from what it lists
  (`<path>: <files>` stands for `doc-specs/<path>.spec.md`).
- `/docs-gen fresh doc-specs/<path>.spec.md <out-file>` – write the page from scratch to
  `<out-file>`, without reading the existing page. Used to test whether a spec carries enough
  intent: compare the result with the existing page. Writes nothing in the repository.

With several specs (`all`, `stale`), start one subagent per spec (`general-purpose`), at most
about six at a time, each told to follow step 3 of this skill for its spec and to write only its
page. Steps 4 to 6 stay with you, once for all pages: the build and the stamp must not run
concurrently.

## Mapping

`doc-specs/<path>/<name>.spec.md` → `docs/book/<path>/<name>.md`.
Files in `doc-specs/` whose name starts with `_` are not specs. A page in `docs/book` without a
generation marker is hand-written; leave it alone.

A spec with `status: planned` in its frontmatter describes something not built yet. Do not
generate its page: skip it in `all`, and if it is named on its own, say so and stop. Its page
is written once what it describes is built and the line is removed.

## Steps

1. Read `doc-specs/README.md` (which guide a folder belongs to), `doc-specs/_styleguide.md`
   and `docs/book/glossary.md`.
2. Determine the specs to process.
3. For each spec:
   1. Read the spec: frontmatter and body.
   2. Resolve the `sources` globs and read every matching file. Report a pattern that matches
      nothing, and continue.
   3. Unless in `fresh` mode, read the target page if it exists, and the review findings in
      `build/docs-review/<path>.md` if they exist (written by `/docs-review`). Check each
      finding against the sources; fix those that hold, even where "minimal changes" would
      keep the sentence. Delete the findings file once the page is written.
   4. Write the page, following the rules below.
   5. Check every example on the page (see "Checking examples").
   6. Read the page once more for two rules of the style guide that pages often break: the
      first use of every term the glossary has is linked to it or explained, and no line
      outside code blocks and tables is longer than about 96 characters.
4. Unless in `fresh` mode, run `npm run docs:build` in `docs/book` (after `npm install` there if
   `docs/book/node_modules` is missing) to check the site still builds and has no dead links. Fix problems in the generated
   pages only.
5. Unless in `fresh` mode, record the sources the pages were written from:
   `./gradlew stampBook -Pspecs=doc-specs/<path>.spec.md,...` with every spec processed, also
   those whose page did not change. Then run `./gradlew test --tests 'dampf.book.*'`, which
   must pass, and, for `stale` or `all`, `./gradlew checkBook`, which must pass as well.
6. Report to the user: pages written, TODO containers added, source patterns without matches,
   examples that could not be checked, anything in the spec you could not fulfil, and any
   contradiction between sources (for example a document and the code disagreeing). Unless in
   `fresh` mode, suggest `/docs-review` for the pages written.

## Rules

- **Facts only from sources.** Every technical statement must follow from the files listed in
  `sources`. Never invent option names, messages, numbers or behaviour. The code and the tests
  are the reference for behaviour; where a document in `docs/` disagrees with the code, the
  code wins, and you report the disagreement. The documents in `docs/` mix what is built with
  what is planned; write only what is built as fact.
- **Links are not facts.** You may link to other book pages the spec names without reading
  them.
- **Gaps are visible.** If the spec asks for something the sources do not contain, insert:

  ```markdown
  ::: warning TODO
  What is missing, and which source would have to say it.
  :::
  ```

- **Follow the spec.** H1 is the spec's `title`. The items under `## Sections` become H2, in
  that order, with the headings the spec gives. Text the spec places "before the first H2"
  goes between the H1 and the first H2. Respect `## Pay attention to` and `## Out of scope`.
- **Follow the style guide**: language, tone, terminology, depth, formatting, "never
  include".
- **Minimal changes.** If the page exists, edit it in place. Keep every sentence that is still
  true and fits the spec, even where you would phrase it differently. Change only what the spec
  or the sources require. If the spec and sources have not changed since the page was written,
  the page should not change at all.
- **Generation marker.** The first line of the page, before the H1:
  `<!-- GENERATED from doc-specs/<path>.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->`
- **Write only the target page** (or `<out-file>` in `fresh` mode), and the lock through
  `stampBook`. Never change specs, the style guide, the glossary, sources or the VitePress
  config.

## Checking examples

C programs, commands, messages and generated assembly are the parts of a page most likely to be
wrong, and they can be checked by running dampf.

1. Build once: `./gradlew installDist -q`. The launcher is `build/install/superdevkit/bin/dampf`.
2. Write each example C program as a complete file into a fresh directory in the scratchpad,
   never into the repository or the game's folders.
3. Run the command the page shows (`dampf check`, `build`, `mir`, `profile`) and compare what it
   prints with the page. Commands on the page run from the top of the repository; run them
   there, with outputs going to `build/` or the scratchpad.
4. For a program whose result the page states, compile it with gcc on the PC as well
   (`gcc -std=c99 -Wall -Wextra`) and check the result.
5. Take assembly excerpts from the `.s` file dampf writes for the program, and MIR from
   `dampf mir`; never write them by hand.
6. Never start Mesen for the book. If an example cannot be reproduced, take it from a test
   instead, or report it.
