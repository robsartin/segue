---
status: Accepted
date: "2026-08-27"
topic: listing-your-own-ratings
tags: [project, tooling, privacy, domain]
supersedes: []
related: [affinity-capture-and-read, taste-layer-separation, privacy-and-data-handling, mcp-tool-surface, bulk-seeding-as-a-dev-tool, graph-exporter-views-and-formats, layering-and-archunit]
---
# 43. List your own ratings from a third dev-side tool, and give the bulk read to it alone

## Context

Affinity is readable in exactly one way: `get_entity`, one QID at a time, and only if you already
know the QID. There is no way to answer "what have I rated?"

That was a deliberate omission and it is worth restating why, because this ADR does not overturn
it. ADR 39 declined a bulk `list_affinity` on ADR 16's data minimisation: it is the single
operation that would make the entire taste layer readable in one call, and the caller of an MCP
tool is a model. Nothing has changed about that argument.

What ADR 39 did not notice is that the same decision also locked out the **owner**. Rating forty
things and being unable to list them is a real gap, and it is worse than the equivalent gap
anywhere else in segue, because **affinity is the one thing here that cannot be regenerated**. A
world fact deleted by accident comes back from Wikidata in seventeen minutes (ADR 42's re-seed
proved it). A rating deleted by accident is gone: there is no source to re-fetch it from, no
history table (ADR 39 chose overwrite), and no second copy.

So the question this ADR answers is not "should the taste layer be enumerable" but **"by whom"** —
and once it is put that way the two audiences turn out to want different things and to deserve
different answers.

Two precedents already exist for the shape of the answer. ADR 40 put bulk seeding in a Gradle task
rather than a seventh tool; ADR 41 did the same for the graph exporter. Both name the same reason:
ADR 26 pins the surface at six, and an operator's job whose output is a file on a filesystem the
model cannot see is not a tool call.

## Decision

- **A third dev-side tool, `ratings`, run as `./gradlew listRatings --args="--out …"`.** Plain
  Java, a `main` behind a `JavaExec`, exactly the shape ADR 40 gave `resolveNames` and ADR 41 gave
  `exportGraph`. **Still six MCP tools.** A model cannot enumerate the taste layer; the person who
  owns it can, on their own machine.

- **Its own package, not a fifth view in the exporter.** This is the placement decision and it went
  the other way from the obvious one, so the reasoning is worth stating. The exporter already reads
  this database, already has an `--include-affinity` flag, and its selection layer is deliberately
  format-blind, so a third `ViewWriter` would slot in. Four things argued against it:

  - **A rating list is a table, not a graph.** A `GraphView` is a description, nodes and edges; a
    listing has no edges at all, and the two fields that matter most here — the note and
    `updated_at` — have nowhere to live on a `ViewNode`. Adding them would widen the model that DOT
    and GraphML both consume, which puts **note text** one line of code away from every graph
    export. Today `--include-affinity` leaks a rating; that change would make the prose leakable
    too, in the tool whose default posture is "no personal data unless asked".
  - **The defaults are opposite.** The exporter carries no affinity unless asked; this tool is
    nothing but affinity. A `--view affinity` would make `--include-affinity` either meaningless or
    mandatory, and would make the exporter's warning conditional in a second, quieter way.
  - **The selection axis is different.** The exporter's four views select by graph position — a
    route, a neighbourhood, a list, everything. This selects by "has a rating", and then *orders*
    the result. Order is meaningless in DOT and GraphML: a layout engine reads a set.
  - **The fences differ, and that is the pattern.** `seed` may not open a store at all; `export`
    may read one and may build a projection; this may read two and may not project. Three tools,
    three relationships with the data, a rule each — which is exactly the argument ADR 41 made for
    not putting the exporter in `seed`.

  The cost is a third `main` with its own `--db` flag. It was preferred to a shared view model that
  would have to carry a personal-data field for one consumer.

- **`AffinityStore` gains `readAll()`, and one package may call it.** The port's Javadoc used to say
  "no `readAll`" and cite data minimisation. That argument was never about the port — it was about
  the tool surface — and this ADR separates them. **`ArchitectureTest.onlyTheRatingsToolReadsEveryRating`
  forbids any class outside `..ratings..` from calling it**, so ADR 39's refusal is now a build
  failure rather than a sentence in a document. It needs to be: `ToolSurfaceTest` counts tools, and
  a bulk read leaking onto the surface would arrive as a *field* on an existing tool, which that
  test would not notice. `find(qid)` stays available everywhere, which is what `get_entity` and
  `AffinityOverlay` use.

- **The output is a file, and the log lines are counts.** Not a stylistic choice: ADR 30 makes SLF4J
  the only logging API and `nothingWritesToStandardOut` forbids `System.out` project-wide, so
  "print it to the terminal" means "log it" — and ADR 33 says affinity is never logged. The whole
  listing goes to the operator's chosen path; every note the tool emits is a count or a file path.
  `RatingsAreNeverLoggedTest` drives the real `main` with a Logback appender attached and asserts
  that no log line from anywhere carries a label, a note or even a qid: **since no line names an
  entity, no line can attribute a rating to one.**

- **`--out` has no default**, for the reason `exportGraph`'s has none: a tool that picks a path for
  you is a tool that quietly writes personal data into the repository. `*.txt` joins `*.db`,
  `*.csv`, `*.dot` and `*.graphml` in `.gitignore`, and **the file names itself as personal data on
  its own first line** — a third lock aimed at the case the other two miss, which is a file that has
  been copied, pasted or attached somewhere else.

- **Two orderings, and the second one is the interesting one.** `--sort rating` (the default)
  answers "what do I love"; `--sort recent` answers "what did I change my mind about", which is the
  only question ADR 39's `updated_at` can answer at all, since there is no history. Both comparators
  end in `qid`, so two runs over an unchanged table produce byte-identical files — diffing two
  listings a month apart is the closest thing to a history this layer has.

- **Labels come from the log, and a missing one says so.** `GraphStore` has no enumerate-all method
  and ADR 41 already refused to add one for a dev tool; ADR 19 makes the log the source of truth
  anyway, and last-claim-wins matches `upsertNode`. Reading it means this tool never builds a
  projection — no Gremlin, no `ingest`, no replay — which is what lets it carry the tightest fence
  of the three. A rating whose entity the graph has no claim about is listed as `(not in the graph)`
  and counted in the summary: **honest rather than helpful**, the way ADR 41's DOT tooltip falls
  back to a bare QID. ADR 39 requires an entity to be in the graph before it can be rated, so this
  should be empty — but the graph around a rating can be rebuilt at any time, and the rating must
  outlive it.

- **`ArchitectureTest.theRatingsToolOnlyReads` names one method no other rule in the project
  names: `AffinityStore.put`.** `onlyIngestAppliesClaimsToTheGraph` and `theExporterOnlyReads`
  between them guard the three world-fact writes, and nothing anywhere forbade writing a *rating* —
  because until now the only class outside `mcp` holding an `AffinityStore` looked up one qid at a
  time. This tool holds the whole table. It is also fenced from `tinker`, `jena`, `ingest`, `mcp`,
  `app`, its two sibling tools, and `java.net`: a listing of personal data is a pure function of one
  local file, and nothing leaves the machine.

- **The naming is load-bearing, and it is worth knowing why two classes here are named as they
  are.** `affinityNeverTouchesTheWorldFactLayer` matches taste-layer types by *simple name* rather
  than by package, because ADR 33's boundary is not a package. So `AffinityRow` — the joined row —
  is called that deliberately, to opt into that fence exactly as `AffinityOverlay` did; it can never
  grow a `Provenance`. And `RatingsRun` and `Labels` are deliberately *not* called
  `Affinity`-anything: they hold an `AssertionLog`, and the same fence would refuse to compile them.
  The join between the two layers happens above both ports and nowhere else (ADR 33), and here the
  class names say which side of that line each one is on.

## Alternatives considered

- **A seventh MCP tool, `list_affinity`** — the model could answer "what have I rated" in
  conversation, which is where the rest of segue lives, and it is precisely what ADR 39 refused on
  ADR 16 grounds. Nothing has changed except who is asking, and this ADR answers that by changing
  the *caller*, not the surface.

- **A fifth view in the exporter** — no third entry point, no second `--db` flag, and a selection
  layer that already exists. Rejected above: it would put a free-text note on the shared view model
  that both graph writers consume, and it would give the tool whose default is "no personal data" a
  mode where personal data is the entire point.

- **Print to the console and write nothing** — the most privacy-preserving answer, since a file
  that is never created cannot be committed, mailed or forgotten. It is not available: stdout is
  the MCP protocol channel and ArchUnit forbids it project-wide, and the only other route to a
  terminal is a logger, which is the one place ADR 33 says a rating must never go. The exporter's
  precedent — an explicit `--out` plus a gitignore line — is the shape that fits the constraints
  already in place.

- **CSV instead of a text table** — already gitignored, opens in a spreadsheet, sorts any way you
  like. Rejected because the question is one a person asks and then *reads the answer to*: the
  format that needs another program to be legible is the wrong one for a listing of forty rows, and
  the two orderings the tool offers are the two anybody wants.

- **Refuse to write inside the working tree** — a genuine first lock rather than a second one, and
  cheap to implement. Rejected for now because the sibling tool that writes the same class of file
  does not do it, and one tool with an extra refusal teaches the operator that the other one is
  safe. If it is worth doing it is worth doing to all three, which is a different change.

- **A `--min-rating` filter, or a search** — obvious next asks, and neither has a use yet: the whole
  point is that the list is small enough to read. Speculative structure ahead of a real need.

- **Read the labels from a rebuilt graph projection instead of the log** — reuses what the exporter
  does at boot, and it would cost a replay of a quarter of a million assertions and a dependency on
  `ingest` and `tinker`, to answer a question that is a map lookup. It would also loosen this tool's
  fence to the exporter's, for nothing.

## Consequences

- The owner can review the only data in segue that cannot be regenerated, which is the thing that
  makes losing it noticeable before it is lost.
- **The taste layer is now enumerable in code, and one ArchUnit rule is what keeps it off the tool
  surface.** That is a real narrowing of the previous position, where the operation did not exist at
  all. It is deliberate, and the rule names ADR 39 so the next person to widen it has to argue with
  the ADR rather than with an interface.
- A third dev-side tool means a third `main`, a third `--db` flag and a third default-database
  expression stated in Java rather than in `application.yaml`. Two was already one more than ideal;
  the alternative was worse.
- The listing is a snapshot, deliberately, like the exporter's. Re-run it to see a change.
- `*.txt` is now gitignored, which is blunt. No `.txt` file is tracked today; if one is ever needed,
  it takes an explicit negation and a commit message saying why it carries no personal data — the
  same convention `*.csv` already carries.
- Nothing here reads `~/.segue/segue.db` during `./gradlew check`. Every test in `ratings` runs
  against invented ratings in an in-memory or `@TempDir` store, and every label, note and rating in
  the suite and in this document is made up.
