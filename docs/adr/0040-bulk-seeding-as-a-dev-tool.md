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

**Amendment (2026-09-15, issue #333): a `book` kind, and a third signal — the classes a work may
state.**

Nothing above is withdrawn, no decision above is edited, and this ADR keeps `Accepted`. What
changes is that the `kind` column may now name a work rather than only a person or a group, and
that one kind checks a third property to do it. `seed.Expectations` is the authority on the
column's current values.

**Why the kind alone is not enough for a title.** "Auto-accept only when three independent signals
agree" reads, for a person, as name plus kind plus occupation. A book row has no occupation to
check, and its kind is `WORK` — which [ADR 21](0021-six-kind-ontology.md)'s six make the same kind
as an album, a film, a television episode and a song. A title is regularly shared by the book, the
film of the book and a dozen printings of the book, and the film is regularly the better known of
them. Kind plus margin would resolve such a row confidently and wrongly, which is the one outcome
this tool exists to avoid.

**The third signal, the same shape as the second.** An expectation is a set of node kinds, a set
of occupations and now a set of Wikidata classes; a kind whose class set is empty is checked
exactly as it was before, which is every kind but `book`. `book` expects a `WORK` that states one
of three classes — book, literary work, or written work — and those three are named where
`KindMapper` already maps them to `WORK`, so the ids have one home and the seed table cites it
rather than restating it. The check sits inside the same filter as the kind check, ahead of the
sitelink ranking, so a better-known candidate of the wrong class never reaches the margin. A
candidate refused on it goes to the review file with the classes it stated on the line, exactly as
a person refused on occupation goes there with the occupations they stated.

**"Version, edition or translation" is deliberately outside the set.** It is a `WORK` to the
mapper, and the owner's row means the work, not a printing of it. Leaving it out costs a review
line whenever a title finds only an edition — where a person can point it at the work in one look
— and including it would buy an auto-accepted answer that is quietly the wrong entity.

**No class is added to `KindMapper`.** The set is drawn from what that table already maps to
`WORK`. A class it does not map is not a `WORK` at all, so it could not pass the kind check
either; and widening the mapper would change every projection, which is
[ADR 42](0042-store-p31-and-rederive-kind-at-projection.md)'s territory and a different decision
from this one.

**`P31` read this way is a resolver filter, not an edge** — the rule this ADR already states for
`P106`, and for the same reason. The graph stores an entity's classes on the node and re-derives
its kind from them; reading those same classes to decide which of several same-titled works the
row meant creates no edge and adds nothing to the graph.

**The list itself.** Three columns still, `name,kind,status`, with `author` or `book` in the kind
column. A hand-written list carries no tour status, so the status field is empty on every row; the
column is carried through untouched, as it always has been, and the reader takes an empty field.
The file lives outside the working tree with every other list
([ADR 33](0033-taste-layer-separation.md)), and a row becomes known only by being rated, through
the rule [ADR 48](0048-a-high-rating-counts-as-something-you-have.md) already sets — this
amendment adds no second route to membership.

**Alternatives rejected.**

- **A fourth column naming the author, and a tie-break on the author property.** Fewer review
  lines, at the price of a four-column list, another property read in the facts pass, and a second
  pass that cannot run until the authors themselves resolve. The review file already exists for
  the residue. This is the upgrade to reach for if the residue turns out large on a real list, and
  it is cheap to add later precisely because nothing here forecloses it.
- **Authors only, with books picked out of their expansions.** Sidesteps title matching
  altogether, and hands the owner a picking step over lists of titles: books surfaced from author
  expansions would arrive as candidates, and the deck offers only people and groups as candidates
  (`CandidateSweep.couldBeExplored`), while a book already on the list is dealt as a known card.
- **Accept any `WORK` for a `book` row.** The cheapest change there is, and it is the confident
  wrong answer above: whenever the film is better known than the book, the film wins the margin.
- **Books known outright, without a rating.** The owner chose rate-first, and the promotion rule
  already turns a high rating into membership; a second membership rule would be a second answer
  to one question.
- **A `book` class added to `KindMapper`.** There is nothing to add: the set is what that table
  already maps to `WORK`. Adding to it would change every projection for a resolver's benefit.

**Nothing here is unit-testable on its own, and that is said out loud rather than left implied.**
This entry records a decision whose code landed with its own tests: the class set and its two
predicates seen red on a stub; the raw classes seen red against the stub server before they were
kept; the film refused and the identical candidate with a written class accepted; the edition
losing to the work it is an edition of; two written works within the margin sent to review; the
table's entry and the union the resolver actually asks for driven out separately, because the
first is green while the second is empty; and three planted controls — the review line's classes,
the union's permissive rule, and a reader made to refuse a blank status — each seen to fire and
then removed. The verification of the *document* is the full gate over an otherwise unchanged
tree: `AdrIndexTest`, `AdrCitationsTest`, `DocumentationLinksTest` for the relative links above,
and `javadoc -Werror` inside `./gradlew check`.
