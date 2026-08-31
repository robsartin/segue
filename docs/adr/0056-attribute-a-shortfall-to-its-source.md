---
status: Accepted
date: "2026-08-30"
topic: attribute-a-shortfall-to-its-source
tags: [project, ingest, extensibility, mcp]
supersedes: []
related: [source-adapter-spi, musicbrainz-as-the-second-source, mcp-protocol-conformance, layering-and-archunit, wikidata-identity-and-vocabulary, reverse-lookup-via-sparql]
---
# 56. Name the source a shortfall belongs to in the message, and give the identity seam a failure to report

## Context

`SegueService.expandEntity` builds one `ExpandContext`, hands it to every adapter in turn, and ORs
`ExpandResult`'s two booleans across all of them into one `ExpansionSummary`. With one source that
was unambiguous. [ADR 54](0054-musicbrainz-as-the-second-source.md) added a second, and the message
*"a source was unavailable and could not be reached"* stopped being actionable the moment it could
mean either of two things: **"MusicBrainz is down" and "Wikidata is down" call for different next
moves.** `docs/design/2026-08-30-three-source-adapters.md` calls this GAP 4 and calls it *"small,
contained, and worth doing in this issue"*; it was not done, and ADR 54 records it as established
and unfixed. Issue #148 is the deferral in that set that had no issue behind it.

**The second half is worse than the first, because it is silent.** `MusicBrainzIdentity` declared no
failure type, so `WikidataMusicBrainzIdentity` swallowed `WikidataUnavailableException` and degraded
to an empty answer — and the empty answer was already spoken for, twice. An empty `mbidFor` is how
`MusicBrainzSourceAdapter` is told *"MusicBrainz holds no record bridged to this seed"*, and an MBID
absent from `qidsFor` is [ADR 22](0022-wikidata-identity-and-vocabulary.md) clause 2 declining to
reach a neighbour, measured at 49% of them. So a Query Service outage on the `P434` lookup arrived
downstream as *"this artist has no members"*, with `sourceUnavailable` **false** and the tool result
**`ok`**. That is the shape this repository keeps finding: a failure that reads as a successful empty
result.

Both flags reaching the caller as prose is not incidental.
[ADR 27](0027-mcp-protocol-conformance.md) puts the shortfall in the result rather than in a protocol
error precisely because the caller is a language model, and `ToolResult.detail` is the field it reads
first.

## Decision

### The flags stay aggregate; the message names the source

`ExpansionSummary.sourceUnavailable()` and `truncated()` keep their shape and their meaning.
`expandEntity` now collects `adapter.id()` into two lists as it iterates, derives the booleans from
whether those lists are empty, and builds the reason strings from their contents. `SourceAdapter`,
`ExpandResult`, `ExpandContext` and `ExpansionSummary`'s wire shape are untouched; the code is the
authority on the exact strings.

**The question the booleans answer has one answer however many sources ran** — *is this result
complete?* — and the question they could not answer is *whose fault*, which is a different question
and belongs where the prose is.

### A shortfall the shared budget caused is attributed to nobody

Every adapter is handed the same `ExpandContext` and the bound is then applied to the
**concatenation** of what they returned, so when that bound makes the cut, no single adapter made it.
That reason says so instead of naming one. This is the design note's **GAP 3**, which this ADR does
not settle: the shared budget and the order-dependence remain exactly as ADR 54 pinned them.

### `MusicBrainzIdentity` declares a failure, and the adapter catches it

`MusicBrainzIdentityUnavailableException` is declared in `musicbrainz`, thrown by
`WikidataMusicBrainzIdentity` where it previously returned null, and caught by
`MusicBrainzSourceAdapter` at both call sites, which turns it into `ExpandResult.unavailable()`.

**The SPI contract is unchanged.** Nothing above `expand()` sees the throw, and what reaches the tool
layer is still a flagged result rather than an exception —
[ADR 25](0025-source-adapter-spi.md)'s *"failures degrade rather than propagate"* stands. What
changes is that the adapter now has something to degrade **from**.

**It is named for the seam, not for whoever is behind it.** In the shipped wiring the service that
falls over is Wikidata's Query Service, not MusicBrainz, so reusing `MusicBrainzUnavailableException`
would put one source's name on another's outage. `musicbrainz` may not name the real service either
([ADR 32](0032-layering-and-archunit.md) forbids it importing another adapter), so the translation
happens in `app`, which is the one package permitted to see both.

### `SourceAdapters` checks that an id identifies

Attribution keys on `adapter.id()`, which makes that string load-bearing in ways it was not: two
adapters sharing one id would name an ambiguity, a blank one names nothing, and a tab or a newline
would break the `ToolResult` detail and the log line it is put in. The compact constructor now
refuses all four.

**Two of the four were already forbidden and unenforced; two are new, and the difference is worth
stating rather than blurring.** `SourceAdapter.id()`'s own javadoc says it *is* the `sourceId` every
assertion the adapter emits will carry, and `Provenance` refuses tabs and newlines in that field —
but only when an assertion is emitted, and **an adapter reporting itself unavailable emits none**, so
the one case attribution exists for is the one nothing checked. That argument covers the separators.
It does **not** cover blank: `Provenance` accepts a blank `sourceId`, and `id()`'s *"Stable
identifier"* says nothing about non-blank, so refusing it is a new prohibition, justified by
attribution rather than inherited.

**Uniqueness is new too, and it forecloses something.** Provenance being keyed on `id()` implies it,
but nothing said it, and enforcing it rules out configuring two instances of one adapter class
against different endpoints under the same id — a mirror, a staging host, a second Query Service.
That is judged the right trade because such a pair is indistinguishable in provenance, in
`GraphStore.assertedBy`, and now in a shortfall message; the cost is that the day someone wants it,
they must give the second instance its own id rather than discovering the ambiguity later.

## Alternatives considered

Issue #148 listed three shapes and costed none. The third won.

- **Grow `ExpandResult` with per-source reporting.** Rejected: `ExpandResult` is already one
  adapter's result, so a source field on it would restate `adapter.id()` as a **second authority for
  who the source was** — one the adapter fills in itself, so it can disagree with the id the caller
  iterated to reach it. Two representations of one fact that can drift, for no reader.
- **Return a per-adapter breakdown from `expandEntity`.** Rejected on cost against a benefit nothing
  would use. It changes the tool's wire shape, and the consumer is a language model that reads
  `detail` first; no programmatic consumer of `ExpansionSummary` exists that would branch on a
  per-source structure. Worth revisiting if one ever does — the attribution is computed either way,
  and exposing it structurally is then a small change rather than a redesign.
- **Leave the seam degrading to empty and attribute only what already surfaces.** Rejected, and this
  is the alternative it would have been easiest to take, because ADR 54's own argument makes it look
  survivable: `WikidataSourceAdapter.supports` returns true for every kind, so a general Query
  Service outage surfaces through *it* and the residual is narrow. But "narrow" is not "reported",
  and attributing a flag the bridge never sets would have shipped a message that names Wikidata for
  an outage that also cost MusicBrainz every neighbour of the seed.
- **Reuse `MusicBrainzUnavailableException` for the bridge.** Rejected: it means MusicBrainz did not
  answer, and in the shipped wiring the bridge failing means Wikidata did not.
- **Have the bridge return a result type rather than throw.** Rejected as the larger change for the
  same outcome. Every caller is inside `MusicBrainzSourceAdapter.expand`, which has to collapse the
  failure into one flag either way; an unchecked exception matches the two `*UnavailableException`
  types already in the codebase, and is declared in both signatures so an implementor reads the
  channel off the interface.
- **Validate `adapter.id()` per expansion, or sanitise it.** Rejected. A bad id is a configuration
  mistake and the useful moment to hear about it is when the bean is built; sanitising would silently
  rename a source in the one string that exists to identify it.

## Consequences

- **This issue changes `port`, and #91 did not.** ADR 25's 2026-08-30 amendment records a second
  source ingesting with *"no change to `domain`, `port`, `tinker`, `jena` or `ingest`"*. That remains
  true of the branch it describes. It is **not** true of this one: `SourceAdapters` gains a compact
  constructor. **What it costs a future adapter is nothing it did not already owe** — a non-blank,
  separator-free id, unique among the configured set, is what `SourceAdapter.id()`'s contract already
  asked for. What changes is *when* a violation is discovered: at bean construction rather than never.
- **A bridge outage is reported against `musicbrainz`, and in the shipped wiring the service that
  fell over is Wikidata's Query Service.** This is the one place the decision knowingly puts a
  source's name on another source's failure — the hazard
  `MusicBrainzIdentityUnavailableException`'s own javadoc names, and avoids for the *exception type*,
  while the user-visible *message* does it anyway. It is accepted rather than overlooked: what the
  caller needs from that message is which source's results are missing, and MusicBrainz's are, whole.
  The alternative — a message naming the bridge's backing service — would mean `SegueService`
  knowing what is behind a seam ADR 32 exists to keep it from knowing. **The residual confusion is
  narrow and one-directional**: `musicbrainz` named alone means the bridge or MusicBrainz failed,
  and cannot distinguish them; a Query Service outage names *both* sources, because Wikidata's own
  reverse pass fails too. Worth revisiting if a bridge is ever wired that does not share Wikidata's
  client.
- **A future implementor of `MusicBrainzIdentity` must throw rather than degrade**, and must still
  answer empty when empty is what it means. Throwing is for *"I could not ask"*; empty is for *"the
  answer was nothing"*. Collapsing them again re-creates the defect this ADR closes.
- **ADR 54 has a consequence that is now closed, and a residual that is now pinned.** Its
  *"A P434-only outage reads downstream as 'this artist has no members'"* no longer holds. That ADR
  is immutable ([ADR 1](0001-record-architecture-decisions.md)), so it carries a dated amendment
  rather than an edit.
- **GAP 3 and GAP 6 are untouched.** The shared budget still goes to whichever adapter
  `SourceAdapters.all()` names first, and `truncated` still reports ADR 36's quality-ordered cut and
  MusicBrainz's arbitrary one identically. Naming *who* truncated does not say *how well*.
- **The user guide's bounded-expansion example was re-captured live, not edited.** The first attempt
  at this ADR's branch *did* edit it, to `"wikidata truncated its result at the bound of 5"`, which
  is a string the code does not produce for that seed: `Q1051182` is a `GROUP`,
  `MusicBrainzSourceAdapter` describes groups, so both sources run, share the one `ExpandContext`,
  and the concatenation overruns the bound as well. The real capture is two reason lines, and
  `nodesAdded` moved from 4 to 5 — the old payload was a single-source artifact predating ADR 54.
  **An edited example presented as what the code builds is the same defect this ADR exists to fix**,
  a claim about behaviour that the behaviour does not support, and it was caught in review rather
  than by anything failing.
- **What proves it is a test that distinguishes two outages**, which is the thing that did not exist
  before. `SegueServiceTest` runs the same expansion twice with the down source swapped and asserts
  each message names one source and not the other; both failed identically before the change, with
  *"a source was unavailable and could not be reached"*, which is the defect stated as an assertion
  failure. `CorroborationAcrossSourcesTest` then pins the residual end to end through the real
  adapter: the bridge alone fails, Wikidata stays healthy and its claim still lands, and the result
  is `partial` naming `musicbrainz`. That last test was written after the fix, so it was checked
  rather than assumed — swapping its double back to the pre-#148 degrade-to-empty bridge gives
  `expected: PARTIAL but was: OK`.
