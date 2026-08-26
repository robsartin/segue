---
status: Accepted
date: "2026-08-24"
topic: taste-layer-separation
tags: [project, domain, modelling]
supersedes: []
related: [assertion-log-source-of-truth, sqlite-assertion-log, mcp-tool-surface, privacy-and-data-handling, bitemporal-time-model]
---
# 33. Keep the taste layer separate from the world-facts layer

## Context

Segue holds two kinds of claim that look superficially alike and behave nothing alike.

"Blixa Bargeld was a Bad Seed from 1983 to 2003" is a claim about the world. Sources
assert it, sources can corroborate or contradict it, it has a validity period, and it
is true or false independently of anyone's opinion.

"I love this record, and I first heard it in a shop in Glasgow" is a claim about the
user. It has no external source to corroborate, it cannot be wrong, its dimensions are
different — a rating, a memory, a context — and it is personal data in a way that a
band's lineup is not.

Modelling the second as an edge in the graph would put both in one namespace, give
affinity a `Provenance` and a corroboration count that mean nothing, and mix personal
data into a structure whose whole purpose is to be traversed and cited.

## Decision

- **Two layers, two stores.** World facts live in the assertion log and its graph
  projection. Affinity lives in its own table behind an `AffinityStore` port.
  *(Amended 2026-08-25, issue #46. This bullet read "its own tables", plural, when this ADR
  was written and the schema was open; **ADR 39** settled it as exactly one table, `affinity`,
  one row per qid. Singular is not a smaller commitment — the boundary this decision draws is
  the separate port and the separate store, not the number of tables behind it — but a plural
  that no longer matches the schema invites a reader to go looking for the other ones.)*
- **`note_affinity` is the only tool that writes affinity**, and it never writes to
  the graph. `IngestService` never sees a rating.
- **Recommendations are derived by traversing the world graph and filtering through
  affinity**, not by storing preference as graph structure. The route between two
  things you like is a world-graph question; which things you like is a taste question.
- **Affinity is not an assertion.** It carries no `Provenance`, no corroboration count,
  and no `llm:` prefix, because none of those concepts apply to a first-person statement.
- **Affinity is personal data** under ADR 16: never logged, and kept out of version control.
  *(Amended 2026-08-25, issue #37. This bullet previously read "and the repository is private".
  The repository is public and is staying public, so that sentence named a protection that did not
  exist. The real boundary is the filesystem, not GitHub: the graph lives at `${user.home}/.segue/`
  outside the working tree, `*.db` is gitignored, and no real rating, note or affinity example ever
  goes into a fixture, a document or a commit message. Those are the things that could actually
  leak it, and none of them is prevented by repository visibility.)*
- **v1 is a rating and a free-text note.** `CLAUDE.md` floats first-heard-where and
  seen-live-when; the note absorbs them until a real need argues for columns.
  *(Amended 2026-08-25, issue #5. Still true, and no longer the whole story: **ADR 39** settles what
  this ADR left open — the rating is a required integer from 1 to 5 with negative affinity expressed
  as 1-2, the note is optional, re-rating overwrites in place with an `updated_at` stamp rather than
  keeping a history, the entity must already be in the graph under its Wikidata QID, and the rating
  is read back on `get_entity` rather than through a seventh tool. Read ADR 39 before adding a
  dimension here.)*

## Alternatives considered

- **Affinity as an edge from a "me" node** — elegant on paper, one traversal for
  everything, and it makes every path query walk personal data, gives affinity a
  provenance and corroboration count that are meaningless, and entangles the two
  retention and privacy regimes.
- **Affinity as an assertion with `sourceId: "me"`** — reuses the whole log machinery,
  and it claims the user is a source that can be corroborated or contradicted, which
  inverts what a first-person preference is.
- **Full dimensional schema now** (rating, first-heard-where, seen-live-when)
  — matches the eventual shape, and it is speculative structure ahead of a real need;
  the note field is the honest placeholder until the shape is known.
- **A separate database file entirely** — stronger isolation for personal data, at the
  cost of two connection lifecycles and no transactional relationship, for a boundary
  that a separate table and a separate port already express.

## Consequences

- The world graph can be shared, exported or made public without carrying personal data.
- Affinity can be deleted wholesale without touching a single world fact.
- The two layers evolve independently: adding a taste dimension never touches ingest,
  and adding a source never touches the taste layer.
- A recommendation query has to join across the two rather than reading one structure.
  That is the intended cost, and at personal scale it is a filter, not a join problem.
- Nothing in the graph records that you like anything, so any future export of "my
  interests" has to compose both layers deliberately.
