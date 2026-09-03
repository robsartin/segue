---
status: Accepted
date: "2026-09-02"
topic: reserve-a-shape-for-a-merges-canonical-side
tags: [project, domain, testing, wikidata, data]
supersedes: []
related: [stand-in-identifiers-cannot-be-allocatable, owner-claims-as-a-third-layer, wikidata-identity-and-vocabulary, what-an-adr-may-quote, privacy-and-data-handling, use-test-driven-development, mikado-method-for-changes]
---
# 62. Reserve a second unallocatable shape for a merge's canonical side, and give the allocatable grammar the upper bound it always claimed

## Context

[ADR 58](0058-stand-in-identifiers-cannot-be-allocatable.md) requires every stand-in identifier to
take a shape Wikibase's item-id grammar refuses, and reserved the leading zero for it.
[Issue #171](https://github.com/robsartin/segue/issues/171) is the sweep that makes the rest of
`src/test` obey, in ten bands. Almost every id in them migrates by prepending a zero. One
group cannot, and it cuts across two of the bands rather than being one of them: the id a fixture
merge names on its canonical side.

`SameAs` says *this local entity turned out to be that Wikidata item*, and its two sides are
deliberately not interchangeable: the local side must be an id Wikidata can never allocate, and the
canonical side must be one it could. That is the whole content of the claim —
[ADR 59](0059-owner-claims-as-a-third-layer.md) admits owner claims precisely so that a minted id
can later be reconciled with a real one — so the canonical side cannot take a leading zero without
the record ceasing to mean anything. `Equivalences` leans on the same pair a second time: it
resolves a merge in exactly one hop, and its argument that no chain can form is that the two side
checks are complements, so no id can sit on both sides.

Nine fixtures across fifteen files invent a merge, and each has to invent a canonical id for it.
Every one of them is in ADR 58's forbidden shape today, and every one of them denotes a real entity:
`Q901`, used as a canonical side in two test classes, is Wikidata's *scientist* class, which
production code maps in `seed/Expectations`. **These are the ids ADR 58 was written about, and they
are the ids production code requires to stay in the shape ADR 58 forbids.** That is a decision, not
a rename.

The design spec for #171 recorded three answers and left the choice to the owner, who took the
third on 2026-09-02.

### The defect the choice exposed

`Qid`'s javadoc has quoted Wikibase's grammar correctly since ADR 58 — `Q[1-9]\d{0,9}`, at most ten
digits. Its pattern did not: it was unbounded above, so segue accepted an eleven-digit qid Wikibase's
own `ItemId` cannot express. Nothing depended on the difference, because every id this repository
names is orders of magnitude below the bound, and nothing would have noticed it.

## Decision

**`Qid`'s allocatable pattern takes Wikibase's upper bound, and the shape immediately above that
bound — no leading zero, more than ten digits — is reserved for a merge's canonical side.** A
fixture merge names its canonical side in that shape; production merges name a real Wikidata id, and
nothing about which ids they may name has changed.

`Qid` is the authority for both shapes and for the predicate the two sides of a merge are checked
against; `SameAs` is the authority for which side takes which. Neither is restated here — the
patterns live in one place each, and a copy in this file is a copy that goes stale silently
([ADR 51](0051-what-an-adr-may-quote.md)'s neighbour rule, and the drift ADR 58's own measurement
section warns about).

**This is not the option ADR 58 rejected.** That one was `Q2147483648`: ten digits, *accepted* by
the grammar, and unallocatable only because `Int32EntityId::MAX` bounds the column it is stored in.
ADR 58 declined it because a storage width is an implementation detail Wikibase could migrate, and
the whole point of the leading zero is to rest on the grammar instead. An eleven-digit id is refused
by the grammar itself, on exactly the same footing as a leading zero and for the same reason: it is
not an under-used identifier, it is not an identifier.

**The local side moves to the same predicate.** Reserving a shape for one side of a merge without
excluding it from the other would have made that shape legal on both, and `Equivalences`' one-hop
argument would have been quietly false — a canonical id could then be the local side of a second
merge, stranding a rating on an intermediate id. So the two checks read one predicate, and the
complement is restored by construction rather than left to hold by coincidence.

**Fixture canonical sides stay traceable to what they were**, as every other band in #171 does: the
new id is the old one carried into the reserved shape rather than a fresh number from a list, so the
diff is reviewable by eye.

## Alternatives considered

**Allowlist the canonical sides, reason: "a merge's canonical side must be allocatable."** The
cheapest answer by a wide margin — no ADR, no production change, and roughly nine allowlist entries.
Rejected because of what it leaves standing: fabricated first-person claims asserting that a real
Wikidata entity is the owner's self-pressed record, written down as deliberate rather than
accidental. That is the exact falsehood ADR 58 exists to stop, and the allowlist entry would be its
permanent excuse. It also has no oracle — the guard would pass because it was told to.

**Use an eleven-digit id and leave `Qid`'s pattern unbounded.** Works today, and it needs no
production change at all: an eleven-digit id passes segue's allocatable check and is refused by the
grammar. Rejected because it *only* works while segue's pattern is wrong. The fixtures would depend
on a defect, and the day somebody corrected the pattern to match the javadoc beside it — a one-line
tidy-up with no visible risk — every fixture merge in the suite would break at once, for a reason
nobody would connect to a decision taken here.

**Narrow the pattern and stop there.** The narrowing is worth having on its own: it closes a hole
ADR 58 never named. But on its own it leaves the merge fixtures with nowhere to go, which is the
question this ADR was opened to answer.

**Give the canonical side a leading zero, like every other band.** Mechanically uniform, and it is
what the other ten bands do. Rejected because it destroys the record's meaning rather than an
identifier: a merge whose canonical side is a stand-in is one stand-in pointing at another, which is
not "Wikidata caught up" at all, and it is the case `SameAs`'s two-sided validation was written to
refuse. It would also take `Equivalences`' no-chains argument with it.

**Stop inventing merges in tests — assert `SameAs` only against real Wikidata ids.** Honest, and it
needs no new shape. Rejected on [ADR 51](0051-what-an-adr-may-quote.md) and
[ADR 16](0016-privacy-and-data-handling.md): the merge fixtures are about the owner's own claims,
and naming real entities as the owner's is the thing this repository may not do. The invented
fixture is not a shortcut around a real one; it is the only version that may be committed.

## Consequences

- `Qid`'s allocatable pattern and its javadoc agree for the first time. No production behaviour
  changed with it: the set a merge's canonical side accepts is the same set it accepted before, now
  named for what it is rather than mislabelled as the grammar.
- **Two unallocatable shapes now carry meaning instead of one**, and a reader has to know both.
  ADR 58's leading zero is a stand-in generally; ADR 59's second leading zero is a local entity;
  this one is admitted on a merge's canonical side alone. `Qid` and `SameAs` carry the reason at the
  point of use, so a contributor who meets one meets its argument.
- An eleven-digit number reads as an ordinary large number, which is the same legibility cost ADR 58
  accepted for the leading zero and refused to accept for `Int32EntityId::MAX`. The difference is
  that ADR 58's objection to `Q2147483648` was never its readability alone — it was resting the
  guarantee on a storage width — and that objection does not reach this shape.
- The local-side check is no longer "refuse what Wikidata could allocate" but "refuse what could
  stand on a canonical side". The two coincided until this ADR and no longer do, so the sentence had
  to be rewritten in three javadocs rather than left to be read the old way.
- ADR 58's consequence that **the repository is not clean** is discharged by #171 completing, not by
  this ADR. ADR 58 is immutable and takes a dated amendment when that happens.
