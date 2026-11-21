#!/usr/bin/env bash
# Creates a new architecture decision record: prints its ID, writes docs/adr/<ID>-<slug>.md with
# the skeleton of a record and adds its row at the end of the table in docs/ADR.md.
#
#   dev/new_adr.sh "The decision, as a sentence"
#
# The ID is 8 characters of Crockford's Base32 (0-9 and A-Z without I, L, O and U): 6 for the
# seconds since 2020-01-01 00:00:00 UTC, then 2 for the first 10 bits of the SHA-256 of the
# title, so that two records made in the same second still differ. IDs sort by time. Cite a
# record as ADR-<ID>. See docs/ADR.md.
set -euo pipefail

title="${1:-}"
[[ -n "$title" ]] || { echo "usage: dev/new_adr.sh \"Title of the decision\"" >&2; exit 2; }
cd "$(dirname "$0")/.."

alphabet=0123456789ABCDEFGHJKMNPQRSTVWXYZ
encode() { # value width
  local n=$1 w=$2 s=""
  for ((k = 0; k < w; k++)); do s="${alphabet:n % 32:1}$s"; n=$((n / 32)); done
  echo "$s"
}

now=$(date -u +%s)
seconds=$((now - 1577836800))
hash=$((16#$(printf '%s' "$title" | sha256sum | cut -c1-4) >> 6))
id="$(encode "$seconds" 6)$(encode "$hash" 2)"
day=$(date -u -d "@$now" +%Y-%m-%d)

slug=""
for word in $(printf '%s' "$title" | tr 'A-Z' 'a-z' | sed -E 's/[^a-z0-9]+/ /g'); do
  next="${slug:+$slug-}$word"
  ((${#next} <= 50)) || break
  slug="$next"
done
[[ -n "$slug" ]] || { echo "new_adr: no file name can be made from the title" >&2; exit 1; }

file="docs/adr/$id-$slug.md"
[[ ! -e "$file" ]] || { echo "new_adr: $file exists" >&2; exit 1; }
cat > "$file" <<EOF
# ADR-$id: $title

**Context.**

**Decision.**

**Alternatives.**

**Consequences.**
EOF

row="| ADR-$id | [$title](adr/$id-$slug.md) | Accepted | $day |"
last=$(grep -n '^| ADR-' docs/ADR.md | tail -1 | cut -d: -f1)
[[ -n "$last" ]] || { echo "new_adr: no table in docs/ADR.md" >&2; exit 1; }
awk -v at="$last" -v row="$row" '{ print } NR == at { print row }' docs/ADR.md > docs/ADR.md.new
mv docs/ADR.md.new docs/ADR.md

echo "ADR-$id  $file"
