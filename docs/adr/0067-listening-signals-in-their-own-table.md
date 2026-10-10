---
status: Accepted
date: "2026-10-10"
topic: listening-signals-in-their-own-table
tags: [project, tooling, privacy, data, taste]
supersedes: []
related: [taste-layer-separation, affinity-capture-and-read, bulk-seeding-as-a-dev-tool, owner-claims-as-a-third-layer, privacy-and-data-handling, what-an-adr-may-quote, layering-and-archunit, mcp-tool-surface, listing-your-own-ratings]
---
# 67. Store what a listening source reports in its own table, from an eleventh dev-side tool

## Context

Segue knows what the owner rated and what a known-list file names. It does not know what the owner
actually listens to, and the Music app does: play counts, loved and favorited flags. Those are the
first *inferred* taste signals, and the first source for the daily-use goal — explore interests with
Claude, highest-confidence items first (issue #350).

Three things about them are not like anything already stored. They are not world facts, so they
cannot be assertions ([ADR 33](0033-taste-layer-separation.md)): no provenance, no corroboration, and
nothing that should reach the graph or the log. They are not ratings either — a rating is something
the owner said, a count is something a source reported — and
[ADR 39](0039-affinity-capture-and-read.md)'s one-row-per-entity table with a required 1-5 score has
no honest place for "played 40 times, loved". And they are personal data in bulk: a play count per
artist is the owner's listening history ([ADR 16](0016-privacy-and-data-handling.md)).

## Decision

**A new `signal` table in the same SQLite file, behind its own `SignalStore` port.** One row per
entity and source, keyed on both, holding the raw `play_count`, `loved`, `favorited` and an
`observed_at`. The write is an upsert, so re-running a source overwrites its own rows and leaves any
other source's alone. A `CREATE TABLE IF NOT EXISTS` touches no existing row, so there is nothing to
migrate. The record, `domain.Signal`, carries no `Provenance`, for the reason
[ADR 33](0033-taste-layer-separation.md) gives affinity none.

**An eleventh dev-side tool, `signals`, run as `./gradlew importSignals`, that resolves nothing.**
Resolution is the seed tool's job ([ADR 40](0040-bulk-seeding-as-a-dev-tool.md)), and a second
answer to "who is this artist" is how two tools come to disagree. So the tool works in two steps
around `resolveNames`:

1. `--signals <export.csv> --names-out <names.csv>` writes the `name,kind,status` list `resolveNames
   --list` reads. It opens no store.
2. `--signals <export.csv> --mapping <qids.csv> --review <review.csv>` reads the answers
   `resolveNames` wrote, through `ResolutionFiles`, and stores what resolved. A name left in the
   review file stays there with the seed tool's confidence label and is counted, not stored. A row
   the owner minted as a local entity ([ADR 59](0059-owner-claims-as-a-third-layer.md)) is read as
   resolved under its own id, so a minted artist is a first-class signal. A name neither file
   mentions is reported as unanswered, not as lost.

Two spellings that resolve to one entity combine: counts add and flags are either-or.

**The fence is three ArchUnit rules, and the tightest of the dev-tool fences.**
`theSignalsToolOpensNothingElse` bans every sibling tool (`seed` and `own` included, so no second
resolver can grow here), the engines, `ingest`, `mcp`, `app`, both source packages and the network.
`theSignalsToolTouchesOnlyTheSignalTable` bans `AffinityStore`, `AffinityRecord`, `AssertionLog`,
`GraphStore` and `IngestService` by type, which is what keeps a signal from being written as a
rating or an assertion. `onlyTheSignalsToolReadsSignals` bans `Signal` and `SignalStore` from
everything outside `signals`, `sqlite`, `port` and `domain` — a call-site fence in the manner of
[ADR 43](0043-listing-your-own-ratings.md), so that a recommender or an MCP tool reaching for a raw
count fails the build where the decision is made rather than in review.

**Nothing is logged, and no raw count is exposed.** The coverage block the tool prints is counts and
a basename: rows read, resolved, minted, left in the review file, unanswered, entities written —
never an artist, an entity or a play count, and never a path. No MCP surface carries a count
([ADR 26](0026-mcp-tool-surface.md) stays at six): turning signals into a confidence is the interest
model (#351), and only that confidence and its evidence kind may reach a model, for the reason
[ADR 33](0033-taste-layer-separation.md) keeps a note off it.

**The export step is a script on the owner's Mac** (`scripts/export-apple-music.sh`). It
reads the library, writes a CSV of `artist,play_count,loved,favorited`, and writes nothing back. It
is committed because it is code; its output is not, and `*.csv` is gitignored.

## Alternatives considered

- **Resolving inside the tool, with the seed tool's classes.** Rejected: `seed` is a sibling, and
  [ADR 32](0032-layering-and-archunit.md)'s fences forbid one dev tool depending on another for a
  reason this would re-create. The mapping and review files are the shared contract; they already
  are the seed tool's resume ledger.
- **A column on `affinity`.** Rejected: it would make a count and a rating one row with one
  `updated_at`, and `onlyTheRecommenderReadsEveryRating` and its siblings fence that table by
  *what a rating is*. A signal is written by a source, a rating by the owner, and overwriting the
  second with the first is the failure the separate table prevents.
- **Appending signals to the assertion log.** Rejected for [ADR 33](0033-taste-layer-separation.md)'s
  reason: it would give a claim about the owner a provenance and a corroboration it must not have.
- **A seventh MCP tool.** Rejected: [ADR 26](0026-mcp-tool-surface.md) pins the surface, and raw
  counts are the one thing it must not carry.

## Consequences

- The coverage block is the measurement the issue asks for: how many of the owner's artists resolve
  on a first real run. That number decides whether entity resolution needs work before the interest
  model, and it is recorded as a count — never the names
  ([ADR 51](0051-what-an-adr-may-quote.md)).
- Nothing reads the table yet except `signals` itself. That is deliberate: #351 defines the
  blended confidence, and the third fence above is where it will have to be widened, on purpose.
- A signal the owner no longer wants has no un-import; the table can be emptied with one `DELETE`,
  as ADR 33 says of affinity.
- The script is unverified from this repository's CI, which has no Music app. It is a script
  the owner runs, and its first run is its test.
