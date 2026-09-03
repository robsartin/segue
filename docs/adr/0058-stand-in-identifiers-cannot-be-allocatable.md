---
status: Accepted
date: "2026-08-31"
topic: stand-in-identifiers-cannot-be-allocatable
tags: [project, testing, wikidata, data]
supersedes: []
related: [wikidata-identity-and-vocabulary, what-an-adr-may-quote, musicbrainz-as-the-second-source, mikado-method-for-changes, use-test-driven-development, taste-layer-separation]
---
# 58. A stand-in identifier must take a shape Wikidata cannot allocate

## Context

`CLAUDE.md` and [ADR 22](0022-wikidata-identity-and-vocabulary.md) both stated that the QIDs in
`Fixture` are placeholders in the `Q9000xx` range and **not real Wikidata identifiers**. A dozen
test javadocs repeated it, and at least one piece of code was written because of it: a test in the
MusicBrainz adapter carried a comment reasoning that "a real QID would tie an invented mapping to a
real entity", and used a `Q9000xx` id on the strength of the guarantee.

The guarantee was false, and a single lookup disproves it.

### What was measured

**This section is the only place these counts live.** They are a snapshot, they drift as tests are
added, and restating them elsewhere is how they came to disagree during the review of this ADR. Cite
this section; do not copy it.

The set counted is every distinct `Q\d+` token — matched as `\bQ\d+\b`, so javadoc mentions count
as well as string literals — in every file `git ls-files src/test` reports. Each was resolved
against Wikidata's Action and REST APIs on **2026-08-31**, against `main` at `cd1d8dc`:

| set | distinct ids | resolve to a real entity |
|---|---|---|
| `Fixture`'s own ids | 15 | **14** — the fifteenth, `Q900014`, is a *deleted* item, not a free one |
| the `Q90000xxx` ids the seed tests use | 37 | 37 |
| all `Q\d+` tokens in `src/test` | 263 | 248 |

A narrower definition gives a smaller number: counting only fully-quoted string literals misses ids
named in javadoc and gives 258. The wider count is used here because a stand-in named in prose
denotes a real entity just as firmly as one in a literal.

So the fixture was not asserting things about nothing. It was asserting that a real entity — the
sample includes a German village, several Hungarian academics and two breweries — is a musician
named "Nick Cave", a band, or a film. The one id that does not resolve is no comfort: it is a
deleted item rather than an unused number, which is the same collision arriving a step later. `MusicBrainzLiveSmokeTest` was worse: it paired a genuine
MBID with `Q900001`, tying a real ensemble's identifier to an unrelated real place.

### Why this happened, and why it is this repository's own lesson

The standing rule here is **never invent an external identifier** — look it up, because a fixture
confirms the error forever and only a live test catches it. The `Q9000xx` range was invented, on
the assumption that a high number would be free. Choosing a number because it looks unused *is*
inventing an identifier; the assumption simply hid that fact behind a plausible-looking convention.

This is not an [ADR 51](0051-what-an-adr-may-quote.md) breach. No entity is named in the repository
and nothing is framed as the owner's taste. The defect is narrower and plainer: a stated guarantee
was false, and work was built on it.

### The fact the decision turns on

Wikibase's item-id grammar, in `WikibaseDataModel`'s `src/Entity/ItemId.php`, is `Q[1-9]` followed
by up to nine digits, bounded above by `Int32EntityId::MAX`. **The first digit may not be zero.**
An id with a leading zero is therefore not an unallocated identifier — it is not an identifier at
all, and no amount of future allocation can make it one.

Both of Wikidata's APIs distinguish the two cases, and they agree:

| id | Action API | REST API |
|---|---|---|
| `Q900001` (allocated) | the entity | `200` |
| `Q999999999` (well-formed, unallocated) | `missing` | `404 resource-not-found` |
| `Q0900001` (leading zero) | `no-such-entity` error | `400 invalid-path-parameter` |
| `Q2147483648` (above `Int32EntityId::MAX`) | `no-such-entity` error | `400 invalid-path-parameter` |

Every QID pattern in this codebase — `Qid`, and the seven places outside `domain` that spell the
same rule — is `Q\d+`, which accepts a leading zero. Nothing needed to change to permit the form.

## Decision

**A stand-in QID carries a leading zero.** `Fixture`'s ids are `Q0900001` … `Q0900015`, and any new
stand-in follows the same shape.

The guarantee is now a fact about the identifier grammar rather than a promise about a range, so it
cannot quietly stop being true.

Two tests hold it, and the pair is the point:

- `FixtureQidsDenoteNothingTest` asserts offline that every `Fixture` constant is refused by
  Wikibase's grammar, and separately that `Qid` still accepts it.
- `WikidataLiveSmokeTest.shouldResolveNothingWhenAskedForAFixtureQid` asks the real API. The
  grammar is a claim about a remote system, and an offline test asserting a regex would have passed
  just as happily on the day the fixture was wrong.

## Alternatives considered

**Accept and restate — say the ids are arbitrary, happen to resolve, and imply nothing.** Rejected.
It is the cheapest option and it does make the sentence true, but it leaves the repository publicly
asserting fabricated facts about identifiable real entities, and it leaves the next contributor
free to add `Q900016` in good faith. Worst, it has no oracle: a disclaimer cannot be tested, so its
truth depends on everyone remembering it. The whole failure being repaired is a rule that nothing
enforced.

**Move to a genuinely unallocated range, verified by lookup rather than assumed.** Rejected on the
measurement. Wikidata keeps allocating, so a range verified free today has a shelf life, and this
repository has already made the same bet twice — `Q9000xx`, which lost, and `Q999999996`–`Q999999999`,
which is currently unallocated and is the same wager on a longer fuse. Re-verifying forever is a
standing cost that the leading-zero form removes entirely.

**Use a number above `Int32EntityId::MAX`.** Rejected, though it does work today. It is
unallocatable because of a storage width rather than the grammar, so it rests on an implementation
detail Wikibase could migrate; and `Q2147483648` reads as an ordinary large number, where a leading
zero reads as deliberate.

**Stop using QID-shaped strings in fixtures.** Rejected: ADR 22 anchors identity to Wikidata and
every store and adapter validates `Q\d+`, so there is no non-QID option that reaches the code under
test.

## Consequences

- The claim in `CLAUDE.md` and ADR 22 becomes true, and is enforced rather than asserted. ADR 22
  carries a dated amendment; it is not edited.
- A leading zero is visually subtle, which is the cost of this choice. The two tests and the
  `Fixture` javadoc carry the reason, so a future contributor who "corrects" the zero fails the
  build with a message that explains itself.
- **The repository is not clean.** This decision moved `Fixture`'s own family — 401 literals across
  30 files. By the definition above, **207** distinct allocatable-form ids remain in `src/test`
  across **79** files, **193** of which resolve. They are a mix of deliberate real references that
  must not change (class ids such as `Q5`; the entities the live tests and recorded JSON fixtures
  are genuinely about) and stand-ins in exactly the shape this ADR forbids, the largest being
  `Q9001xx`, shared across roughly twenty unrelated files. Triaging them needs judgment per site
  rather than a rename, and half-migrating a shared family would split the convention rather than
  mend it ([ADR 4](0004-mikado-method-for-changes.md)), so it is
  [issue #171](https://github.com/robsartin/segue/issues/171).
- The enforcing test is scoped to `Fixture`'s constants. Widening it to a scan of all test sources
  needs the allowlist of deliberately-real ids that #171 produces.
- Historical records were left alone: `docs/plans/`, `docs/design/` and the superpowers plans say
  what was believed at the time, and rewriting them would falsify that record. `verification/`
  is a retired slice-0 artefact rather than a decision record, and `sparql_check.py` builds
  `wd:` IRIs and attaches labels to them, so it was swept — the record it preserves is the shape
  of the queries, which the sweep does not touch.

**Amendment (2026-09-02, issue #171): the consequence *"the repository is not clean"* is
discharged, and the rule is now enforced over the whole test tree rather than over `Fixture`'s
fifteen constants.**

[Issue #171](https://github.com/robsartin/segue/issues/171) completed on 2026-09-02. The
measurement above stands as what was true on 2026-08-31 and is not edited; the paragraphs below are
the retake and the mechanism that replaces the consequence.

**What moved.** Of the distinct allocatable-form ids that consequence counted, **100 were
stand-ins** — invented ids in exactly the shape this ADR forbids — across **61 files**, falling
into **ten families** by prefix. They were migrated one family per commit with the gate green after
each ([ADR 4](0004-mikado-method-for-changes.md)), and every migrated id is the old one carried
into an unallocatable shape rather than a fresh number, so each family's diff is reviewable by eye.
The remainder are deliberately real and stay. (A narrower scan — strip comments, then match quoted
runs — reaches 60 files rather than 61: it cannot read `//` inside a string literal, nor a text
block, whose quotes do not pair the way it assumes. The distinct-id count is 100 either way.)

**What holds it now.** `StandInQidsDenoteNothingTest` sweeps every file under `src/test` and reds
on any allocatable-form id it finds, reading Wikibase's grammar from the one constant this
repository writes it down in. That is the widening the last consequence above said would need an
allowlist of deliberately-real ids, and the allowlist is that test's, not this ADR's: the reasons
live beside the ids there and are deliberately not copied here. Each reason says which of four
kinds it is — a class id production code maps, an entity a recorded response or a live test is
genuinely about, a deliberately allocatable negative control, and a token that is not an identifier
at all. A companion assertion reds on an allowed id the tree no longer contains, so a reason cannot
outlive the site it was written about. There is no second list: the one that carried the ids
awaiting migration was deleted when it emptied.

**Three unallocatable shapes now, where this ADR left one.** A single leading zero is a stand-in;
[ADR 59](0059-owner-claims-as-a-third-layer.md)'s two leading zeros are a local entity; and
[ADR 62](0062-reserve-a-shape-for-a-merges-canonical-side.md)'s eleven digits are a merge's
canonical side, which cannot take a leading zero without the record ceasing to mean anything. ADR
62 also narrowed `Qid`'s allocatable pattern to the upper bound this ADR quoted and that code did
not have.

**What the guard cannot do, accepted rather than hidden.**

- **Six sites build an id at runtime** from a bare `"Q"` and an integer, and no scan over source
  text can reach them. They were fixed by hand, and each now asserts inside its own loop that the
  id it just minted is one the grammar refuses — reading that grammar from the same single place,
  rather than spelling it again.
- **The allowlist is keyed by the id, so it is context-free.** `Q1`–`Q4` are allowed because
  `GraphStoreContract` numbers its questions with them, and the guard therefore cannot catch a
  *new* use of `"Q1"` as a node id. Accepted: an allowlist keyed by site would go stale on every
  line that moves.
- **The guard's own allowlist is a literal in the tree it sweeps**, so it covers itself; the
  dead-entry assertion discounts that one file rather than be quietly vacuous.

**Alternatives rejected while discharging this.**

- **Renumber the stand-ins into a fresh sequence.** Rejected. An id that is the old one plus a zero
  stays traceable to what it was, which is what made ten family-sized diffs reviewable by eye and
  the shrinking exclusion list mechanical. A renumbering turns every diff into a puzzle and removes
  the only cheap check on it.
- **Pick a numeric floor and require stand-ins above it** — the `Q9000xx` wager again with a larger
  number. Rejected on this ADR's own postmortem: choosing a number because it looks unused *is*
  inventing an external identifier, and Wikidata keeps allocating. Enforcing that under a guard
  would have made the guard hold the defect in place.

`CLAUDE.md`'s companion warning that the fixtures are not clean is retired with this, and the
developer guide's testing-strategy table names the guard.
