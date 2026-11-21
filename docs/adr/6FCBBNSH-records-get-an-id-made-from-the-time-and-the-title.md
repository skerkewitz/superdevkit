# ADR-6FCBBNSH: Records get an ID made from the time and the title instead of a number

**Context.** The records were numbered 1, 2, 3 in the order they were written, and the next
record took the next number. Since ADR-6FB9QJ3R several sessions work at once, each on its own
branch. Two of them that each make a decision both take the same next number, and the clash
shows only when the second pull request is merged: a file and a table row to renumber, and every
citation of the new record in that branch to change. A number also says nothing about when a
decision was taken, and the table was not in the order of its dates.

**Decision.**

- A record's ID is 8 characters of Crockford's Base32 (the digits and the capital letters
  without I, L, O and U). The first 6 are the moment the record was made, in seconds since
  2020-01-01 00:00 UTC, which lasts until 2054. The last 2 are the first 10 bits of the SHA-256
  of its title, so that two records made in the same second still differ. The title is hashed
  once, when the ID is made; a later change of the title keeps the ID.
- A record is cited as `ADR-<ID>` ("ADR-6F0R61EZ"). Its file is `docs/adr/<ID>-<start of the
  title>.md` and starts with `# ADR-<ID>: Title`. The table in `docs/ADR.md` is sorted by ID,
  which is the order of time, and the date of a row is the day of its ID in UTC.
- `dev/new_adr.sh "Title"` makes the ID, the file with the parts of a record and the row.
- The 51 numbered records get IDs from the date of their row at 12:00 UTC plus their former
  number in seconds, so records of the same day keep their order. Their files are renamed, their
  headings changed, and every citation of them in the repository is rewritten, inside the
  records too. That is the one change made to existing records, and it changes no content.
  The book is left alone until the release, as ADR-6F60ZB4N says.
- `docs/ADR.md` keeps a table from the former numbers to the IDs, for commit messages, pull
  requests and other projects that cite a record by number.

**Alternatives.**

- Keep the numbers and give a record its number only at the merge: the clash goes away, but the
  numbers stay, and a branch cites its own record by a placeholder until then.
- Name a record by its slug or by its date and slug: readable citations, but long ones, and
  every rename of a title would ask whether the name has to change.
- Case-sensitive encodings such as Base58 or Base64: a character or two shorter, but the
  repository is also used on Windows file systems, where two names that differ only in case are
  the same file, and IDs are easy to mistype.

**Consequences.** Branches no longer need each other to name a record. They still both add a row
at the end of the table; when both are merged, the second one has a conflict there, solved by
keeping both rows in the order of their IDs. IDs made on the same day share their first 4 or 5
characters, so neighbours look alike; the link to the file carries the title. `AdrTest` checks
the format of the IDs, that each decodes to the date of its row, the order of the table, the
statuses and every `ADR-<ID>` cited, and that no document outside the book still cites a record
by number. The book pages cite records by number until they are written anew at the release.
