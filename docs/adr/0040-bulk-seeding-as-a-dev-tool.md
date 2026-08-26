---
status: Accepted
date: "2026-08-26"
topic: bulk-seeding-as-a-dev-tool
tags: [project, ingest, tooling, privacy]
supersedes: []
related: [mcp-tool-surface, source-adapter-spi, taste-layer-separation, privacy-and-data-handling, wikidata-identity-and-vocabulary, layering-and-archunit]
---
# 40. Seed in bulk with a committed Gradle task, not a seventh MCP tool

## Context

Segue's open risk is whether MCP is a pleasant *authoring* interface. Seeding nine
hundred names one conversational turn at a time is not the experiment; it is the
thing the experiment was supposed to avoid. A list of that size already exists
outside the repository, with a `kind` column, and it needs turning into `name → QID`
once.

Three constraints shape the answer.

**ADR 26 pins the tool surface at six**, and argues that the size of the surface is
the point. Bulk import is an authoring concern that happens once, against a file
that lives outside the repository, and it is not something a model should drive: it
is a batch job with a resume file.

**The list is personal data.** ADR 33 governs "who I listen to, read and watch", and
issue #37 settled that the protection is the filesystem rather than repository
visibility — this repository is public.

**Names do not resolve themselves.** Measured against the real list: `wbsearchentities`
cannot see `P31`, so a search result says nothing about what it found; the top hit
for a band's name is regularly a film, a crater or a surname; and `P31` alone cannot
tell a musician from a minister, because every human is `Q5`.

## Decision

- **A committed tool in `seed`, run as `./gradlew resolveNames --args="--list …"`.**
  Plain Java, no Spring, a `main` behind a `JavaExec` task. Not an MCP tool, and not a
  seventh row in ADR 26's table.
- **It resolves and reports. It never writes.** Output is a mapping file, a review file
  and a console summary. Nothing reaches `~/.segue/segue.db`; adding an entity remains
  `add_entity`'s job through `IngestService`, which ADR 19 makes the only writer.
  `ArchitectureTest.seedNeverOpensAStore` forbids `seed` from depending on `sqlite`,
  `tinker`, `jena`, `ingest`, `mcp` or `app` at all, so it cannot open the database even
  to read it, and cannot become an MCP tool by accident.
- **The tool is committed; its input and its output are not.** `*.csv` is gitignored
  beside `*.db`. Every name in a test, a fixture, a document or a commit message in this
  project is invented.
- **Names are folded before resolution.** 913 rows are 909 unique strings and 887 acts:
  the same act appears with and without a leading article, with a non-breaking hyphen
  (U+2011) or an ordinary one, with a curly apostrophe or a straight one, with an accent
  or without. Folding unifies dashes and apostrophes, maps stroke letters, strips
  accents, drops a leading "the", lowercases and keeps alphanumerics.
  **`ł` has no NFKD decomposition**, so the usual normalise-then-drop-marks pass deletes
  it rather than folding it to `l`; stroke letters are mapped explicitly first.
  **Folding is never fuzzy.** Two names one edit apart are two different people often
  enough that an edit-distance pass would merge real distinctions. A Discogs-style `(N)`
  suffix is likewise NOT folded away — its whole purpose is to separate two acts with one
  name.
- **Auto-accept only when three independent signals agree.**
  1. **Name** — the queried spelling equals the entity's own label or one of its recorded
     aliases, folded. A label match outranks an alias match rather than competing with it,
     because an alias is regularly some more famous person's discarded birth name. An
     alias match on a name shorter than three characters is not evidence at all.
  2. **Kind, and for a person occupation** — `P31` gives the `NodeKind`; for a `PERSON`,
     `P106` is checked against the vocabulary the input `kind` column implies. Groups have
     no `P106` and are not asked for one.
  3. **Margin** — where two candidates both match the name and both fit the kind, the
     winner must have at least twice the runner-up's sitelinks.
  Anything else goes to the review file, carrying the best candidate and the reason.
- **`P106` is a resolver filter, not an edge.** Issue #32 excluded it from the graph
  vocabulary because "novelist" is a 36,000-item hub node. Reading it to choose between
  six humans with one name creates no edge and does not reopen that decision.
- **Facts are fetched in batches.** `wbgetentities` takes fifty identifiers, so one call
  serves a whole chunk of names rather than one call per candidate.
- **Resumable, with the results as the ledger.** Each chunk is written before the next
  starts, and a re-run skips every folded name either output file already holds. There is
  no separate progress file, because a progress file can disagree with the results.
- **`./gradlew check` needs no network.** The judgement is a pure function over invented
  names; everything that speaks HTTP is exercised against `StubWikidataServer`.

## Alternatives considered

- **A seventh MCP tool, `import_list`** — the model could drive it conversationally, and
  it breaks ADR 26's six for an authoring job that happens once, hands a model a file path
  outside the repository, and makes the personal list part of a conversation transcript.
- **Resolve on the fly inside `add_entity`** — no new surface at all, and it turns one
  interactive call into a batch of network round trips and gives nowhere to put the names
  a human has to adjudicate.
- **A separate repository for the tool** — perfect separation of the personal data from
  the public code, and the data was never going in either repository; what it would
  actually separate is the tool from the resolver it reuses and the gate that tests it.
- **A `src/tools` source set, so the tool stays out of the application jar** — cleaner
  packaging, at the cost of a second compilation unit, a second classpath, and an ArchUnit
  configuration that has to be told about it. `seed` is 800 lines of plain Java that the
  existing rules already fence.
- **Accept the search engine's top hit and review nothing** — one pass, no review file,
  and measured against the real list it would have confidently assigned a lunar crater, a
  surname and a cartoon.
- **Fuzzy matching to shrink the review pile** — would resolve more names, and the list
  contains two different musicians whose names differ by one letter. If a fuzzy pass is
  ever added it feeds review, never acceptance.

## Consequences

- Nine hundred names resolve in about four minutes, with a short list a person reads once.
- Some correct answers land in review, and that is the intended direction of the trade: a
  review line costs a minute, and a wrong QID makes every route through it quietly false.
  The occupation vocabularies are deliberately generous and deliberately incomplete, so a
  gap in them costs a manual check rather than a wrong answer.
- The summary is counts, not a headline percentage. A threshold tuned until the review
  pile looks small has not resolved anything; it has moved the wrong answers into the file
  nobody reads.
- Two findings from the real run belong to the codebase rather than to this tool, and were
  fixed with it:
  - **Wikidata moves proper names to the `mul` language code.** `languages=en` then returns
    an empty labels object for exactly the best-documented entities. `fetch(qid)` read only
    `/labels/en` and reported them as missing, so `add_entity` on a well-known person
    failed. Both callers now ask for `en|mul` and `ClaimMapper.label` falls back.
  - **`KindMapper`'s whitelist did not cover how Wikidata says "band".** Acts typed as rock
    band, musical duo, a cappella group, orchestra, choir, string quartet, collective or
    group of humans all fell through to `CONCEPT`. Those classes were measured against the
    real list, not guessed, which is the growth path the class's own note describes.
- The tool has no scheduled second use. If the list is re-imported it will be re-run, and
  the resume behaviour means that is cheap.
