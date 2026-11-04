---
status: Accepted
date: "2026-11-03"
topic: expansion-sees-both-sources
tags: [project, architecture, ingest, extensibility]
supersedes: []
related: [layering-and-archunit, source-adapter-spi, musicbrainz-as-the-second-source, what-the-musicbrainz-adapter-refuses, the-bridge-returns-classes, expand-every-promotion-from-a-dev-tool, record-architecture-decisions, what-an-adr-may-quote]
---
# 68. `expansion` is the package that sees both sources, and ADR 32 is not amended for it

## Context

A sweep of all 67 ADRs against the tree on 2026-11-01 (issue #370) found eleven divergences. Ten
are an ADR that copied something instead of citing it — a pinned version, a quoted rule body, an
exhaustive call-site count — and each is repaired by deleting the copy. This is the eleventh, and
it is the only one of the set that is an architectural fact rather than a stale copy: **a privilege
moved from one package to another and no ADR says so.**

**What the tree says.** The MBID-to-QID crossing is `expansion.WikidataMusicBrainzIdentity`, and
`expansion.ExpansionSources` is what hands it to the MusicBrainz adapter. Those two classes are the
only ones in `src/main` that reach both `musicbrainz` and `wikidata`. Each is the authority on what
it does, how it batches and what it refuses; none of that is restated here.

**What the ADRs say.** Four of them place the crossing in `app`, and the two that correct
themselves both point at the same wrong place:

- [ADR 61](0061-the-bridge-returns-classes.md)'s Decision has a bullet headed *"The widened query
  lives in `app`"*, which also states that `app` "is the only package allowed to see two adapters
  at once". Its 2026-09-07 amendment corrects the placement and names
  [ADR 66](0066-expand-every-promotion-from-a-dev-tool.md) as where the decision is recorded.
- [ADR 54](0054-musicbrainz-as-the-second-source.md)'s 2026-09-07 amendment reports the same move
  from the adapter's side and cites ADR 66 the same way.
- **ADR 66 does not contain it.** Its Context says, in the present tense, that the bridge "lives in
  `app`, which every dev tool's fence bans" — that is its *motivation* — and its Decision moves
  `EntityExpansion` into `expansion` and never mentions the bridge at all.
- [ADR 25](0025-source-adapter-spi.md)'s issue-#91 amendment records a bridge that "had to be
  placed in `app` under ADR 32's one permitting sentence", as one item in a dated measurement of
  what the second source cost.

So the correction was written twice, the decision it reports was never written once, and the
citation chain terminates in an ADR that asserts the opposite. A reader asking *which package may
see two sources at once* is told `app` by every ADR in the set.

**Where the decision actually lived.** In one paragraph of javadoc on
`ArchitectureTest.adaptersDoNotDependOnEachOther`. That paragraph is right about the tree, and it
also rules — on its own authority — that [ADR 32](0032-layering-and-archunit.md) "is untouched by
that move and is not amended". Its argument for that is good, which is why this ADR adopts it
rather than reversing it. A good argument in a comment is still not a record: nothing indexes it,
nothing links to it, and the four ADRs a reader would actually reach all contradict it.

**And nothing counted.** No rule in `ArchitectureTest` asked how many sources a class may see.
`adaptersDoNotDependOnEachOther` forbids a source reaching its sibling — which is exactly what
forces the crossing to exist outside both — and then says nothing about where outside. A second
class anywhere in `src/main` doing the same crossing would have broken no rule, failed no build and
appeared in no diff review as anything other than a convenience. This is ADR 54's own finding in a
new place: *"those architecture rules name adapter packages as literal strings, so a new adapter
package inherits none of them and nothing goes red to say so."*

## Decision

**The package permitted to see more than one source adapter is `expansion`, and
`ArchitectureTest.onlyTheSharedExpansionPackageBridgesSources` is what holds it there.**

- **`expansion` holds the crossing because two entry points need it and only one of them may see
  Spring.** `musicbrainz` declares the seam and cannot implement it, `wikidata` may not be reached
  from it, and both directions are ArchUnit rules (ADR 32) — so the crossing has to live outside
  both adapters, and the only question is which outside. ADR 66 added the second caller: a
  plain-Java dev tool whose own sibling fence bans `..app..`, as every dev tool's does and rightly.
  A bridge that caller cannot reach is a bridge only one source crosses, so the class sits in the
  package the two callers already share, beside the one statement of the order the sources are
  asked in. That is the decision this ADR records; it was taken in #284 and shipped there, and what
  is new here is only that it is written down.

- **`app` keeps ADR 32's permission, and the rule exempts it by name.** ADR 32 says `app` may
  depend on everything because wiring is its job. This decision does not narrow that, and the
  exemption is written into the rule rather than left to be discovered, so the two cannot be read
  as contradicting each other. That `app` reaches one source today and not two is a fact about the
  tree, not a rule — `SegueConfiguration` wires through `ExpansionSources`, so it no longer needs
  to see MusicBrainz at all.

- **ADR 32 is not amended, and here is the argument that was previously only in a comment.** ADR
  32's sentence is a *permission* granted to `app`, not an exclusivity claim about seeing two of
  anything: it says `app` may depend on everything, and `expansion` depends on two adapters and a
  handful of other packages, which is not everything. ADR 32 also says of itself that
  "`ArchitectureTest` is the list, not this table", and records intent rather than a rule roster —
  so adding a rule it does not name is the thing it explicitly anticipates, not a change to it.
  **What was wrong was never ADR 32's text; it was the gloss three other ADRs put on it.** "The
  only package allowed to see two adapters at once" is a sentence ADR 32 never wrote, and it is the
  sentence that went stale. Amending ADR 32 would ratify the misreading by correcting it.

- **The rule counts sources, not adapters, and derives the set rather than listing it.** A storage
  adapter implements a port too, and every dev tool that opens a database depends on two of those
  at once; that is ordinary and fenced elsewhere. `ArchitectureTest.SOURCE_ADAPTER_PACKAGES` is the
  readable list and `PackageListsTest` is what holds it to the tree, deriving it from the packages
  of every class assignable to `port.SourceAdapter` and asserting equality in both directions. A
  third source that forgets the entry reds there instead of silently narrowing the rule to the two
  sources it was written against — issue #165's lesson, applied to a new list before it can cost
  anything rather than after.

- **What the rule can and cannot see is the test's to state, not this ADR's.** It is per class
  rather than per package; its javadoc says so, says why that is the honest reading, and says what
  a package split to evade it would look like.

- **ADR 61, ADR 66 and ADR 54 gain dated amendments pointing here.** None of their text is edited
  (ADR 1). ADR 25's does not — see the Consequences.

## Alternatives considered

- **Leave the bridge in `app` and have `expansion` call into it.** The second entry point is a
  plain-Java dev tool, and `app` is Spring — it reaches `mcp` through `SegueConfiguration`, which
  is why every dev tool's fence bans it. Taking this would have meant either opening that fence for
  one tool, which is the fence's whole value, or shipping a batch expander that reaches Wikidata
  only, which would have left the two callers disagreeing about what an expansion is.
- **A third, neutral package holding the bridge alone.** It does not reduce the number of packages
  that see both sources; it raises it to two. `ExpansionSources` constructs both adapters and hands
  the bridge to one of them, so it reaches both either way — the new package would be one class
  split from the only thing that wires it, bought at the price of a wider privilege rather than a
  narrower one.
- **Amend ADR 32, or supersede it.** Rejected for the reason in the Decision: its text is correct
  and the misreading is elsewhere. An amendment saying "this sentence never meant what ADR 61 said
  it meant" would put the correction in the one file that did not need correcting, and leave the
  three that did.
- **Let `musicbrainz` depend on `wikidata` and drop the seam.** One import and one query, as
  `adaptersDoNotDependOnEachOther`'s javadoc concedes — and then a third source's cost depends on
  which of the first two it happens to need, and ADR 54's question can never be asked again. This
  is the alternative the sibling rule exists to refuse; it is listed because the rule added here
  defends the same answer from the other side.
- **Record the move as an amendment to ADR 66.** ADR 66's Context asserts the opposite in the
  present tense and an amendment may not edit it, so the amendment would have to carry the entire
  decision — a new decision wearing an amendment's clothes, filed under a title about a dev-side
  tool where nobody looking for a layering rule would find it. ADR 61 set the precedent for the
  opposite move in its own "why this is a new ADR and not an edit" paragraph, and it is followed
  here.
- **Fix the citations and write no ADR.** Pointing the javadoc and the two amendments at ADR 54's
  amendment instead of ADR 66 would make every link resolve to a true sentence. It would still
  leave the decision recorded only as a report of itself, the ADR 32 ruling only in a comment, and
  nothing at all stopping the next move.
- **Prose only, with no ArchUnit rule.** The thing this ADR exists to repair is a move nothing
  detected for eight weeks. A decision about which package may depend on which is the exact shape
  ADR 32 commits to defending mechanically, and leaving it undefended would guarantee the next
  sweep finds it again.

## Consequences

- **A second bridge now reds.** Any class outside `expansion` and `app` that reaches both sources
  fails the build naming itself and the sources it reached. The rule was proved able to fail before
  it was believed: a class in `mcp` holding a `MusicBrainzClient` and a `WikidataClient` was
  planted under `src/main` and watched red on this rule alone, reported by name as depending on
  `[musicbrainz, wikidata]`, then deleted.
- **The javadoc that used to carry the decision now cites it.** `ArchitectureTest`'s sibling-rule
  paragraph points here rather than at ADR 66, and so do `WikidataMusicBrainzIdentity`'s and
  `ExpansionSources`'s. The paragraph keeps the argument; this ADR keeps the decision.
- **ADR 25's issue-#91 amendment is left exactly as written, deliberately.** It is a dated
  measurement of what adding the second source cost on 2026-08-30, and on that date the bridge was
  in `app`. Dated observations are how this repository records measurements, and rewriting one to
  match a later tree is editing history rather than amending a decision. A reader who lands there
  is one link from ADR 54, whose amendment now points here.
- **Three ADRs now carry two amendments on the same subject**, which is the cost of immutability
  and is paid knowingly: the first corrects the placement, the second corrects where the
  correction said to look.
- **This ADR is not the drift sweep.** Ten other findings sit in the report that opened issue #370
  and are not this ADR's business; naming them here would be the copying habit the report is about.
- **The rule is one more thing to maintain when a third source arrives.** It will need its entry in
  `SOURCE_ADAPTER_PACKAGES`, and `PackageListsTest` will red until it has one. That red is the
  feature.
