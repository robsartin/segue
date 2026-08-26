---
status: Accepted
date: "2026-08-25"
topic: affinity-capture-and-read
tags: [project, mcp, domain, privacy]
supersedes: []
related: [taste-layer-separation, mcp-tool-surface, wikidata-identity-and-vocabulary, privacy-and-data-handling, sqlite-assertion-log, assertion-log-source-of-truth]
---
# 39. Capture affinity as a required 1-5 rating, and read it back on get_entity

## Context

ADR 33 decided that taste and world facts are different kinds of claim and gave taste its own port,
its own tables and a single writing tool. It deliberately left the shape of a rating open, and
increment 5 could not be built without settling it. Four questions had no answer, and each one is a
decision that is cheap now and expensive later, because the moment ratings exist there is data in
the shape of whatever was chosen.

- **Is there a read at all?** ADR 26 pins six tools and `note_affinity` is the write. As scoped,
  nothing read affinity back: you could rate forty things and never see one of them again.
- **What is a rating?** Scale undecided — 1-5, 1-10, thumbs, love/like/meh — and unresolved whether a
  note could stand on its own. The sharp part is negative affinity: "not for me" is disproportionately
  useful as a filter on every future recommendation, and it is a scale decision now rather than a
  column added later.
- **Mutable, or a history?** World facts are append-only (ADR 19), but ADR 33 says affinity is
  explicitly not an assertion, so that reasoning does not carry over. "I loved this in 2010, it's
  fine now" is real signal; a trail also complicates the wholesale delete ADR 33 lists as a benefit.
- **Can you rate something the graph cannot identify?** Dogfooding made this concrete rather than
  theoretical: Eliot Peper is not in Wikidata at all — zero search candidates — and Reina del Cid is
  in it with zero relations. This decides whether segue is a graph of notable things or a graph of
  *your* things.

ADR 33's recommendation story — traverse the world graph, filter through affinity — is a further
capability and is blocked on issue #32: twelve dogfooding pairs returned zero routes, so a
recommendation built now would filter an empty set.

## Decision

- **v1 is capture plus a plain read.** `note_affinity` writes; affinity is readable back. No
  recommendations and no traversal, so this increment does not depend on issue #32.
- **The read is surfaced on `get_entity`, not as a seventh tool.** `get_entity` returns an
  `affinity` field alongside the neighbours: the rating, the note if there is one, and when it last
  changed. It is absent for an entity that has never been rated.
- **A QID is required, and the entity must already be in the graph.** One identity spine (ADR 22),
  and every rating is guaranteed to join to world facts. Rating an unknown entity is a readable
  error result, never a thrown protocol error (ADR 27).
- **A rating is an integer from 1 to 5, and it is required.** Negative affinity is expressed as 1-2
  rather than as a separate concept. The note is optional; a blank note is stored as no note.
- **Re-rating overwrites, with an `updated_at` timestamp.** One row per entity, enforced by making
  `qid` the primary key of the `affinity` table. Latest rating wins; the timestamp records when it
  last changed. There is no history table.
- **The refusals never echo the value they refused.** "rating must be an integer from 1 to 5", not
  "…, got 9". An error string is the likeliest thing on this path to be logged by something
  upstream, and ADR 33 keeps affinity out of every log line.

### Schema

```sql
CREATE TABLE IF NOT EXISTS affinity (
  qid        TEXT PRIMARY KEY,   -- one row per entity: the overwrite decision, in the schema
  rating     INTEGER NOT NULL,   -- 1..5, required
  note       TEXT,               -- optional
  updated_at TEXT NOT NULL       -- ISO-8601, sub-second precision preserved
)
```

In the same SQLite file as the assertion log, per ADR 33's rejection of a separate database, on its
own connection. No foreign key to a graph table, because there is no graph table: the graph is a
projection of the log (ADR 19, ADR 24), and the join is made above both ports in `SegueService`.

## Alternatives considered

- **A seventh tool, `get_affinity(qid)`** — the obvious symmetry, one tool to write and one to read,
  and a model that has just called `note_affinity` would find it immediately. Rejected because ADR
  26 pins the surface at six tools and this would spend that ADR-level change on a lookup the model
  already has a reason to make: `get_entity` is what it calls to ask "what do I know about this",
  and taste is part of the answer. The composition also demonstrates ADR 33's own claim that a
  cross-layer query is a join above the ports rather than a shared structure.
- **A bulk `list_affinity()`** — the natural "show me everything I like", and the first step towards
  recommendations. Rejected for now on ADR 16's data minimisation: it is the one operation that
  makes the entire taste layer readable in a single call, and nothing needs it until recommendations
  do. It is a small addition when that day comes.
- **Affinity as a field on the graph node** — no join at all, and it puts personal data inside the
  structure ADR 33 exists to keep it out of.
- **1-10, or thumbs up/down** — ten points invites false precision about the difference between a 6
  and a 7; two values cannot express "fine" at all, and the middle of the scale is where most of a
  personal library actually sits. Five is the smallest scale with a real middle and two distinct
  degrees on each side.
- **A note-only entry, with the rating optional** — appealing because the note is where the memory
  is. Rejected because the rating is the part a future recommendation can filter on, and an entry
  with no rating is invisible to the feature this layer exists to enable. Rating required, note
  optional, is the pairing that keeps every row useful.
- **Allowing a rating on an entity Wikidata does not have** — a personal graph arguably should hold
  the things you love that are not notable. Rejected because inventing local identity forks the
  identity spine ADR 22 depends on, and an entity with no world facts has nothing for a route to
  pass through: a rating that joins to nothing is a note in a text file with more steps. The cost
  is accepted deliberately, not overlooked — see Consequences.
- **A history table, one row per rating over time** — taste drift is genuinely interesting, and it
  is speculative structure ahead of any feature that reads it, and it turns ADR 33's "delete
  affinity wholesale" from a one-line `DELETE` into a decision about what a deletion means.
  `updated_at` keeps the question anyone actually asks — when did this last change.

## Consequences

- **Things Wikidata does not have cannot be rated at all.** Eliot Peper returns zero search
  candidates, so he cannot be recorded; Reina del Cid can be rated but has no relations to join to.
  This is the accepted cost of one identity spine, and the trigger to revisit it is a second source
  supplying identity for entities Wikidata lacks — not a special case in the taste layer.
- **Taste drift is not retained.** Re-rating loses the previous rating permanently. If that turns
  out to matter, the migration is additive: a history table alongside the current row, not a change
  to it.
- **`get_entity`'s response now composes both layers**, so any client rendering it is rendering
  personal data. That is intended — it is the user's own client — but it is why no bulk read exists
  and why the field is absent rather than defaulted when nothing has been said.
- **The tool surface stays at ADR 26's six.** A `get_affinity` or `list_affinity` appearing later is
  an ADR-level change, and `ToolSurfaceTest` fails until an ADR says so.
- **ArchUnit enforces the separation both ways** (`affinityNeverTouchesTheWorldFactLayer`,
  `theWorldFactLayerNeverTouchesAffinity`), so ADR 33's boundary is a build failure rather than a
  convention, even though the taste layer's four classes deliberately live in the packages their
  layers' conventions put them in rather than in a package of their own.
- **The affinity write is a prepared statement, and has to stay one.** sqlite-jdbc logs every
  statement it executes through SLF4J at TRACE — the SQL text, never the bound parameters. Building
  that SQL by concatenating values would put a rating and a note into a log line without anyone
  writing a logging call at all.
