---
name: docs-review
description: Review pages of the dampf book (docs/book) with fresh readers and list what is unclear, contradictory, unsupported or off-style, each with the place the fix belongs (spec, style guide, glossary, source or page). Changes no files. Use when the user asks to review, proof-read or check book pages, after /docs-gen has written pages, or invokes /docs-review.
---

# docs-review: fresh eyes on book pages

The agent that wrote a page has read every source, so it fills the page's gaps from memory and
cannot see them. This skill hands the page to reviewers who have not, and turns what they find
into fixes in the right place. It changes no files in the repository.

## Invocation

- `/docs-review docs/book/<path>.md` – review that one page.
- `/docs-review all` – every page in `docs/book`.
- `/docs-review docs/book/<dir>` – every page in that directory.

## Where a fix belongs

A page whose first line is `<!-- GENERATED ...` is written by `/docs-gen`; an edit to it by hand
is lost the next time it is generated. So every finding names where it is fixed:

- **spec** – the spec (`doc-specs/<path>.spec.md`) lacks intent: a question the page should
  answer, a section, a "Pay attention to" or "Out of scope" item.
- **style guide** – `doc-specs/_styleguide.md` lacks a rule, or a rule is unclear. Use this when
  the same problem would appear on other pages too.
- **glossary** – `docs/book/glossary.md` lacks a term, or defines it differently from the page.
- **source** – the sources disagree with each other (a document against the code), or a source
  does not say what the page needs. Name both files.
- **regenerate** – spec and sources are fine and the page did not follow them. Running
  `/docs-gen` again should fix it; say which rule of the spec or style guide it broke.
- **page** – only for hand-written pages (no generation marker): the fix is an edit to the page.

## Steps

1. Determine the pages to review. For each generated page, find its spec from the marker.
2. For each page, start two subagents (`general-purpose`, `model: "sonnet"`) in the same
   message, so they run concurrently. Each gets a prompt that stands alone; neither sees the
   other's result. With `all`, start the agents for all pages together, at most about eight at
   a time. The judgement in steps 3 and 4 stays with the current model.
   1. **Reader** (pass 1). Give it the paths of the page, `docs/book/glossary.md`, and the
      section "Three guides, three readers" of `doc-specs/_styleguide.md`, with the guide the
      page belongs to (`doc-specs/README.md` says which). Tell it: you are the reader that section
      describes; read only these files, nothing in `src/`, `doc-specs/` or other docs. Report,
      each with the quoted sentence:
      - terms, options or names used before they are explained, and not in the glossary;
      - steps or reasons that are missing, where you could not follow or act on the text;
      - questions you still have after reading, which the page's purpose says it should answer;
      - statements on the page that contradict each other, or contradict the glossary;
      - sentences you had to read twice, and why;
      - passages that told you nothing you needed, or repeat what the page already said;
      - examples that do not show what the text around them says.
      For each, say how much it would hurt a reader: *blocks* (cannot use or understand),
      *confuses* (understands, with effort or doubt), *minor*.
   2. **Checker** (pass 2). Give it the paths of the page, its spec, the whole style guide, the
      glossary, and tell it to read every file matching the spec's `sources`. For a
      hand-written page, give it README.md and the documents in `docs/` instead. Report, each
      with the quoted sentence and the file and line of the source:
      - statements that do not follow from the sources, or that a source contradicts (the code
        and tests win over documents);
      - statements that present something planned as built;
      - sources that contradict each other on something the page says;
      - what the spec's "Sections" and "Pay attention to" ask for and the page does not
        deliver, and what "Out of scope" excludes and the page contains;
      - breaches of the style guide: tone, terminology, formatting, depth, "Never include";
      - claims that contradict another book page it links to (it may read linked pages).
3. Read the spec yourself and assign every finding its place (see above). Merge findings that
   are the same problem seen twice. Drop a finding only if the page plainly answers it; say so
   if you drop any.
4. Report to the user, per page:
   - a short verdict: is the page usable by its reader as it stands;
   - findings grouped by where the fix belongs, *blocks* first. Each finding: the quote, the
     problem, a concrete fix (the sentence to add to the spec, the rule for the style guide,
     the term for the glossary);
   - with several pages: problems found on more than one page, as candidates for the style
     guide or glossary.
5. For each generated page with findings in **regenerate**, write those findings to
   `build/docs-review/<path>.md` (for `docs/book/<path>.md`; `build/` is not under version
   control). Each finding: the quoted sentence, what is wrong, the source file and line that
   shows it. `/docs-gen` reads this file; without it, its "minimal changes" rule keeps a
   sentence it believes true.
6. Offer to apply the spec, style guide and glossary fixes and run `/docs-gen` for the
   affected specs. Do not change anything else before the user agrees.

## Rules

- **The reader must stay cold.** Never give the reader the sources, the spec, or your own
  understanding of the topic, not even as background. Its ignorance is the point.
- **Changes no files** other than the findings in `build/docs-review/`. Not pages, specs,
  style guide, glossary or sources, until the user agrees in step 6.
- **Facts from files.** A finding about correctness cites the source that shows it. "I think
  dampf does something else" without a source is not a finding.
- **Concrete fixes.** "Unclear" alone is not a finding; say what the reader is missing.
