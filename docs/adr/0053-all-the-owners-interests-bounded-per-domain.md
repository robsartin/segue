---
status: Accepted
date: "2026-08-30"
topic: all-the-owners-interests-bounded-per-domain
tags: [project, domain, modelling, ingest]
supersedes: []
related: [six-kind-ontology, wikidata-identity-and-vocabulary, source-adapter-spi, path-ranking-by-confidence, award-received-as-the-first-non-collaboration-edge, store-p31-and-rederive-kind-at-projection, main-subject-as-the-route-through-what-a-book-is-about, a-kind-scoped-ceiling-on-concept-expansion, what-an-adr-may-quote]
---
# 53. Segue holds all the owner's interests, and one source does not reach all of them

## Context

**Decided by the owner on 2026-08-27, in issue #78:** segue should hold *all* his interests —
musicians, artists, authors, comedians, restaurants, movies, books, newspapers, the subject areas
of his book collection. Not only things made by people, connected by who made them. Those interest
areas are the motivation for this decision and are stated by the owner in that issue; nothing below
names an entity as his.

The issue filed itself as "a program, not a task", decomposed into five steps. Three have closed
and two are open. **The three that closed did not confirm the widening; they narrowed it**, and
they narrowed it unevenly — one domain is richly enough modelled to have produced a new edge type,
and another is out of reach through the only source that exists. That asymmetry is the reason this
decision needs a record of its own rather than living as a checked box on an epic.

This ADR records the decision, what the measurements did to it, and what it deliberately does not
decide. It is written after the fact, which is why so much of it cites work already merged.

### Where each step ended

| step | issue | outcome |
| --- | --- | --- |
| 1. Generalise hub demotion first, as a prerequisite | #88 | **Closed by refusal.** [ADR 31](0031-path-ranking-by-confidence.md), issue-#88 amendment |
| 2. Measure coverage per domain before adding properties | #89 | **Closed.** Two of three named domains measured; the third was not |
| 3. Admit properties in small measured increments | #111 | **Closed.** [ADR 47](0047-main-subject-as-the-route-through-what-a-book-is-about.md), amended by #123 |
| 4. A second source, to prove the SPI takes one | #91 | Open |
| 5. The long tail Wikidata does not model | #92 | Open, and it carries the question #78 deferred |

## Decision

- **The scope is widened as the owner stated it.** Segue holds his interests generally, not only
  creative works and not only relations of authorship.

- **The widening is bounded per domain, by measurement, not granted uniformly.** A domain is in
  scope for a source when that source holds its entities *and* states something about them that
  maps to a registered relation. Either half can fail on its own, and for one domain below the
  second half failed while the first only partly did.

- **New relations are admitted one at a time, on a measured hub size, in an ADR** — the shape
  [ADR 38](0038-award-received-as-the-first-non-collaboration-edge.md) set and
  [ADR 47](0047-main-subject-as-the-route-through-what-a-book-is-about.md) followed. Not a
  vocabulary widened per domain in one move.

- **No new hub rule is created by this decision, and none was needed by the widening that
  happened.** #78's stated ordering — generalise hub demotion first, do not widen before it — is
  withdrawn, because the generalisation was attempted and refused. See below.

- **The QID-less question stays deferred.** #78 declined to decide whether local, QID-less entities
  are ever allowed. This ADR declines too, and records where the question lives (#92) and what
  evidence has arrived since.

## What survives the widening — checked rather than repeated

#78 rests on two claims about the existing design. Both were checked against the ADRs and the code
rather than carried over, and both hold with a qualification the issue does not state.

### The six-kind ontology holds, and a seventh kind costs more than it did

[ADR 21](0021-six-kind-ontology.md) fixed `NodeKind` at six constants precisely so it would span
any domain, and named the urge to add a seventh as a design smell to investigate. `NodeKind` is the
authority and still holds exactly six; nothing in the widening needed another. A place is a `PLACE`
and a book's subject is a `CONCEPT`, and ADR 47 confirmed the second against `KindMapper` when it
admitted `P921`.

**The qualification is that two mechanisms acquired an opinion about a seventh kind after #78 was
filed**, and a future scope change should know it:

- ADR 21's own issue-#87 amendment ranks the kinds in `KindMapper.PRECEDENCE`, with a static check
  that fails the build unless every `NodeKind` constant is ranked exactly once. Adding a kind is
  now also deciding where it ranks.
- [ADR 49](0049-a-kind-scoped-ceiling-on-concept-expansion.md)'s expansion ceiling is scoped to
  `CONCEPT`, and that ADR states explicitly that a seventh kind would pass through unbounded and
  that whether this is right is left open.

Neither makes a seventh kind wrong. Both make it a larger decision than ADR 21 alone implies.

### The SPI is the right extension point, and it has one production implementor

[ADR 25](0025-source-adapter-spi.md) split ingest into `SourceAdapter` and `EntityResolver`, both in
`port`, under the rule that adding a source must not require touching the graph layer. Adapters
never see `GraphStore` or `AssertionLog`. That shape is intact.

**What #78 states as an established property is a design rule with one implementor.** In `src/main`
there is exactly one `SourceAdapter` (`WikidataSourceAdapter`) and one `EntityResolver`
(`WikidataEntityResolver`), both in `wikidata`. The fixture adapter in `src/test` exercises the
interfaces but is not a second source with a vocabulary and an identity space of its own. #91 is
where the rule gets tested, and it is filed with a named suspect rather than a general worry:

**[ADR 42](0042-store-p31-and-rederive-kind-at-projection.md) already recorded the seam that would
have to move.** Its consequences say that `ingest` and `export` both depend on `wikidata` for the
re-derivation rule, that ADR 32's layering allows the direction, and that *"if a second source ever
states classes of its own, this is the seam that has to move."* Checked in the tree today,
`KindMapper` is in `wikidata` and is referenced from `seed`, `ingest`, `ratings`, `mcp`, `support`
and `export` as well as from within `wikidata` itself. It is named in one `domain` javadoc and
imported nowhere in `domain`, so ADR 18's purity rule is intact; what is not intact is the claim
that deciding what a stated class *means* is a Wikidata-local concern.

### The vocabulary claim needs correcting, and its conclusion survives anyway

#78 states that the vocabulary "is eleven properties, and every one models creative collaboration".
`EdgeTypes` is the authority on what is registered and this ADR does not reproduce it, but the
claim was already untrue when the issue was written, in two ways worth recording because a wrong
premise invites a wrong fix:

- **ADR 38 had registered `RECEIVED_AWARD` (P166) on 2026-08-25**, under a title naming it *the
  first non-collaboration edge*. A prize is a body's judgement, not a co-credit.
- **`INFLUENCED_BY` (P737) has been registered since slice 0** — it states an intellectual or
  artistic debt, and [ADR 45](0045-recommend-by-normalised-lift-with-routes.md) weights it in the
  tier *above* collaboration for exactly that reason.

The conclusion the issue drew from the claim survives the correction. A vocabulary built mostly on
co-credit does not reach a shelf of single-authored technical books, and ADR 47 measured that
directly rather than arguing it. The lesson is that the count of properties is not the quantity to
reason from; what each property is *stated on*, and how widely, is.

## What the measurements narrowed

This is the part the issue could not have written, and the part a reader of the issue alone would
get wrong. #89 was filed as *"a measurement, which may correctly conclude that a domain is not
doable"*, and required a sample of the owner's own real examples rather than famous ones, on the
grounds that the whole question is whether the long tail exists. It did.

**Nothing below names an entity.** [ADR 51](0051-what-an-adr-may-quote.md) permits an aggregate
over the owner's data and forbids naming an entity as his, and the samples here were supplied by
him. The figures are aggregates and property-level facts about Wikidata; the named entities and the
SPARQL that produced them are in issue #89 and in ADR 47, which are the citations. Where an
argument would have been stronger with a name, it is made weaker on purpose and said so.

### Places you go: out of scope for this source, and the reason is not thinness

Measured in #89 against ten real examples the owner supplied, all in one city. (#89's body asked
for twenty; ten were measured, and the finding below does not depend on which.)

- **Wikidata holds ten restaurants in that city in total** — ten that exist under the class-and-
  location query, not ten that were found. Six of the ten real examples supplied do not exist at
  all.
- **The count is not the fatal part; the statements are.** The best-documented restaurant in the
  city carries ten statements, of which two connect it to anything else. Another carries two in
  total: its class and its coordinates. A third carries fourteen, mostly social handles, a phone
  number and a street address.
- **Nothing they carry maps to a registered relation.** `EdgeTypes` holds no location property, so
  ingesting every restaurant the source has would produce isolated nodes that can never appear in a
  route.

**So restaurants are not in scope for the Wikidata source, and no property admission fixes it.**
This is the outcome #89 existed to make sayable: sampling a famous restaurant would have found a
rich entity and given the opposite, wrong answer. Reaching this domain requires a different source,
which is #91's territory and not a vocabulary decision.

The corollary sharpens ADR 31's issue-#88 amendment rather than contradicting it. That amendment
observed that the real graph holds a single `PLACE` at a single edge; the reason is upstream of the
ranking, in what the vocabulary registers and what the source states.

### Book subjects: reachable, and much narrower than the domain name suggests

Fourteen of sixteen real subjects resolved to a QID. Their `P921` "main subject" hub sizes, measured
in #89, **ran from 1 to 796** — against ADR 38's measured rejections at 35,977 and 16,552, and its
single acceptance at 127.

**One comparison carried the admission, and it is a fact about two properties rather than about
anybody's shelf:** science fiction counts **16,552** items as a genre (`P136`) and **228** as a main
subject (`P921`). The same concept, 72× smaller, because `P136` is what a work is *in* while `P921`
is what it is *about*. **#78's claim that `P921` is "the same shape or larger" than genre is
therefore false**, and it was load-bearing: it is the whole reason the issue made hub
generalisation a blocking prerequisite. The issue makes the same claim about `P27` country in the
same sentence; **`P27` was not measured** and this ADR does not extend the refutation to it.

**And the domain is narrower than "the subject areas of a book collection" sounds.** #111 was
rewritten after a second measurement showed that the books do not state the subjects a person would
name. ADR 47 records the retarget and its own limits: two generic subject nodes rather than the
subjects that were measured, and a "What this cannot reach" section tabulating four further titles,
one of which has no Wikidata item at all. ADR 47 is the authority on those rows and this ADR does
not reproduce them.
Its issue-#123 amendment records what that section discloses.

### Newspapers as connectors: named as unproven, and not measured

#89 named three unproven domains and reported on two. **Nothing in #89 or its closing comment
measures newspapers-as-connectors**, whose interesting relation is `P108` employer — employment
rather than authorship. `P108` is not registered; `EdgeTypes` is the authority. The domain is
therefore neither in nor out: it is unmeasured, and this ADR says so rather than letting the
two answers cover for a third.

### What was already proven, and what was only estimated

#89's body records six domains proven on real data across 815 seeds — musicians, authors,
comedians, actors, films and books. It adds that visual artists and newspapers-*as-entities*
"almost certainly" work. **That is an estimate and this ADR keeps it as one**, distinct from the
six.

## What was built to make it safe

Three mechanisms landed between #78 being filed and this ADR. Each is cited to where it lives; none
is restated here.

- **Aboutness, admitted and retargeted.** ADR 47 registers `P921` as `ABOUT` and registers nothing
  else, aimed at the two subject nodes the measurement found rather than at the ones a person would
  name. `direct`, correcting what #111 originally asserted; not `fallbackOnly`, because `P921`
  states no `P1696`. Weighed in a fourth tier strictly below recognition, with the argument for the
  gap and the refusal to tune the number against a hub side effect both recorded there.
  `RecommendationWeights` is the authority on the table. ADR 47's own consequences record that the
  margin under the hub rule is thin and contingent, and that the demonstration was small.

- **A ceiling on `CONCEPT` expansion.** ADR 49, from issue #112. `ExpansionBounds.effective` in
  `domain` is the whole rule and the authority on the number: a ceiling on the *request*, never a
  smaller default, `CONCEPT` alone, reported through the existing `partial` path. ADR 49 states
  plainly what it does not fix — it bounds the damage, does not express the policy, bounds one call
  rather than a sequence, and fires on `KindMapper` classification gaps as well as on broad
  subjects.

- **Hubs: the prerequisite was refused, and that is the finding.** #88 was written as this epic's
  hard prerequisite. It was attempted, measured on a replayed copy of the real graph against a
  38-node gold set — 17 intermediates that must be demoted, 21 that must keep working — and
  **refused**. Seven candidate measures were run and every one overlaps. The reason is one
  sentence, recorded in ADR 31's issue-#88 amendment: **a film and an award are the same shape** —
  same edge count profile, one edge type, all-person neighbours, no triangles — so only what the
  node *is* separates them. `PathRanking` is unchanged by that amendment. What #88 shipped instead
  was four new classes in `RecognitionInstitutions`, measured, plus the finding that **hub demotion
  is partial by construction**.

  For this epic specifically, that amendment answers in two directions and they are not symmetric:

  - **Aboutness needed no new rule.** A `P921` subject node is a `CONCEPT` and busy by
    construction, which is the exact shape the degree-plus-kind rule was built for. Demonstrated on
    a synthetic hub-rich vocabulary rather than waited for:
    `PathRankingTest.aSubjectHubIsAlreadyCovered`. ADR 47 confirmed the same conclusion when it
    admitted the property, and needed no third rule.
  - **Location is covered by nothing.** The degree rule is `CONCEPT`-scoped on purpose and a city
    states no class meaning "elected to". The amendment declines to build a third rule until a
    property creates the need, on the same argument its edition-node amendment used, and **pins the
    gap with a test instead**: `PathRankingTest.aPlaceHubIsNotCoveredByEitherRule`, whose display
    name says it is issue #78's to fix.

  The same judgement is shared with recommendation rather than copied: `PathRanking.isHub` is
  public and `CandidateSweep` calls it, so routing demotes a hub route where recommending excludes
  one (ADR 45).

## What remains open

- **#91 — a second source adapter.** Now load-bearing rather than a proof of the SPI: two of the
  owner's stated domains are proven unreachable through the only source there is. Its 2026-08-29
  comment records that MusicBrainz's `ws/2` answers anonymously with no key or approval, that it
  states relations on the *artist* rather than on the work — a genuinely different ingest shape,
  which is what would test the seam — and an identity probe over 40 seeds and 387 distinct
  neighbours, of which 197 (51%) carry a Wikidata QID and 190 (49%) do not. Its conclusion is that
  **ADR 22 stays**, because the character of the 49% is tribute acts, pseudonyms, billing variants
  and relatives rather than unmodelled interests. That comment is the authority on the probe and on
  its own instrument caveat.

- **#92 — the long tail, and the deferred question.** #78 declined to decide whether QID-less local
  entities are ever allowed, and #92 carries it. **This ADR does not decide it either.** The
  evidence points both ways and is recorded there: #5's routing argument that a hand-made node with
  no relations is a leaf, against the fact that some real interests have no Wikidata entity at all;
  and #91's music probe, which #92 reads as evidence *for* keeping QID-required in that domain
  while noting it should be re-tested per domain rather than settled once for all of them. #92's
  own framing is worth preserving — that the routing argument is about the *graph* and not about
  the *record*, and those may deserve different answers.

## Alternatives considered

- **A seventh `NodeKind` for the new domains.** The intuitive move, and the invariant most at risk
  from a scope change. Rejected because nothing in the widening needs one — ADR 21 fixed six kinds
  to span any domain, and a place is a `PLACE` and a subject a `CONCEPT`, the second confirmed
  against `KindMapper` by ADR 47. Also rejected on a cost that rose after #78 was filed: a seventh
  kind now has to be ranked in `KindMapper.PRECEDENCE` or the build fails (ADR 21, issue-#87
  amendment), and ADR 49 leaves open what the expansion ceiling should do with it.

- **Generalise hub demotion first, as a blocking prerequisite.** #78's own stated ordering, and the
  strongest-looking argument in the issue: the two existing rules are one idea in two costumes, and
  the recommender had already found that a computed measure beat a hand-enumerated one. Rejected
  because it was attempted and cannot be satisfied — every candidate measure overlaps the gold set,
  the strongest one demotes bands and philosophers to catch a dozen busy institutions, and the
  recommender's normalisation is *provably inert* here because routing compares routes between one
  fixed pair. ADR 31's issue-#88 amendment is the record, and it warns against re-opening the
  question with a measure not run against the 21 must-keep nodes.

- **Widen the vocabulary per domain — location, employment, genre — in one move.** #78's step 3 in
  its unmeasured form. Rejected on numbers that already existed: `P106` occupation at 35,977 and
  `P136` genre at 16,552 were ADR 38's measured rejections and ADR 47 re-confirmed them. And for
  places it would not have worked at any hub size, because the entities carry nothing to map.
  Adding a property to make a domain *appear* to work is precisely what #89 was filed to prevent.

- **Admit QID-less local entities, so the graph holds things Wikidata does not know.** #92's second
  shape, and the one that makes the graph genuinely his. **Not rejected here — deferred**, to #92,
  under #92's own ordering: try a second source first and re-ask, because the long tail may shrink
  enough that the identity question never needs answering. Deciding it now would be deciding it
  without #91's outcome, which is the evidence #92's acceptance criteria ask for.

- **Restaurants simply stay out.** One of #89's three honest outcomes, and the cheapest. Not taken
  as a permanent answer: the finding is scoped to *this source*, and stating it as "out of scope"
  without the qualifier would foreclose #91's third design candidate. What is decided is that no
  Wikidata property reaches them.

- **Build the place source first, since that is the domain with nothing.** Considered in #91 and
  refused there on a specific ground: MusicBrainz's "member of band" maps to `P463` cleanly and so
  does not force ADR 22's third clause — vocabulary borrowed from Wikidata properties — while
  places and books are where that clause bites. Building the one that does not force the harder
  question first tests the seam without deciding the vocabulary at the same time.

## Consequences

- **Scope is now a per-domain question with a per-domain answer**, and the answer can be "no". One
  domain produced a new edge type, one is unreachable through this source, and one was never
  measured. A future statement of what segue covers has to be a list, not a sentence.

- **#91 changes character.** It was filed as a proof that the SPI takes a second implementor. It is
  now the only route to a domain the owner explicitly asked for, and ADR 42's seam is the first
  thing it will press on.

- **The next property is admitted ADR 38's way, and ADR 38's open question 1 is still open.** Two
  properties have now been admitted on a measurement, which is not the same as a rule for selecting
  the next one. ADR 47 sharpened what the rule would have to be about — how widely a property is
  *stated*, not what it means — and that is progress, not an answer.

- **Hub demotion is partial by construction**, and this decision inherits that. Anything built on
  the assumption that it is total is built on sand; ADR 31's issue-#88 amendment names the
  populations no safe class entry reaches.

- **The place-hub gap is live and pinned rather than fixed.** The day a location property is
  registered, `PathRankingTest.aPlaceHubIsNotCoveredByEitherRule` is where the design decision
  becomes due. It is currently a no-op against a graph with one place in it, which is why it was
  not built.

- **This ADR names no entity and drops names its own sources published.** The restaurant argument
  is made on counts alone and the subject figures as a range, so a reader who wants to check them
  goes to #89 and ADR 47. That is a real loss of checkability, taken deliberately under ADR 51,
  and recorded here so a future reader knows it was a choice rather than an omission. The one
  entity-shaped figure retained — 16,552 against 228 for one concept across two properties — is a
  fact about Wikidata's own modelling and carries no possessive.

- **ADRs 21, 22 and 25 are unamended by this decision.** Nothing here withdraws or corrects them;
  the qualifications above are about what has been *tested*, not about what was decided.

- **Nothing in `./gradlew check` changes.** This is a scope decision with no production code of its
  own; the code it describes was merged under ADRs 47, 49 and 31's issue-#88 amendment.
