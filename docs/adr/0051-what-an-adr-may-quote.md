---
status: Accepted
date: "2026-08-29"
topic: what-an-adr-may-quote
tags: [project, privacy, docs]
supersedes: []
related: [taste-layer-separation, privacy-and-data-handling, record-architecture-decisions, path-ranking-by-confidence, recommend-by-normalised-lift-with-routes, bulk-seeding-as-a-dev-tool, main-subject-as-the-route-through-what-a-book-is-about, a-high-rating-counts-as-something-you-have]
---
# 51. An ADR may quote an aggregate; it may not name an entity as the owner's

## Context

This repository is public, and it was created on 2026-08-24.
[ADR 33](0033-taste-layer-separation.md) makes the owner's taste personal data, and issue #37
corrected an ADR bullet that had claimed the protection came from the repository being closed. It
does not. The protection is that the data lives at `${user.home}/.segue/` and never enters git.

`CLAUDE.md` already states one half of the rule, and states it plainly:

> Ratings and notes in test fixtures, ADR examples, CLAUDE.md snippets and commit messages must be
> invented, not Rob's.

That sentence covers the taste layer's own two columns. **It does not cover a measurement**, and a
measurement is where the recent ADRs get their evidence: ADRs 45, 47, 48, 49 and 50 each argue from
one — a degree distribution, a candidate pool, a before-and-after ranking — computed on a copy of
the real graph or against live Wikidata. Those documents were written under a rule that had nothing
to say about them.

The gap matters because two of those measurements are taste at one remove:

- **A recommendation ranking is derived from the known-list.** Publishing the ranking discloses,
  indirectly, what is on the list. ADR 33's issue-#85 amendment already reached this conclusion
  about the list itself — it calls the known-list "a statement of taste, handed to a tool and,
  through its output, to whoever reads it." This is that output.
- **A table whose column counts the owner's own seeds** is a measurement over the list, whatever
  the rows are named. The node names in such a table may be world facts; the column is not.

Issue #115 found two accepted ADRs in this shape, [31](0031-path-ranking-by-confidence.md) and
[45](0045-recommend-by-normalised-lift-with-routes.md). Both are amended below. Both are also
already public: every part of each reached `main` through a merged pull request, ADR 31's exposing
table on 2026-08-27 and ADR 45's on 2026-08-27 and 2026-08-28.

### What was checked, one file at a time

The four ADRs written after 45 were opened and read for the same shape, because a rule presented as
codifying existing practice has to be true of the practice. It is not true of all four:

- **ADR 47 is in breach**, in the plainest form this rule forbids. It names four books as *the
  owner's books* and repeats the framing twice more, in a decision bullet and in a consequence.
  These are entity names presented as holdings, with QIDs. Nothing about them is a measurement.
- **ADR 48 names no entity at all.** It contains no QID, and no band, person, work or
  institution is named anywhere in it. Its evidence is rating counts and a distribution.
- **ADR 49 names no entity as the owner's.** It contains no QID. It names two subject terms, one
  programming language and one Wikipedia editing project, every one of them as the cost of an
  `expand_entity` call measured against live Wikidata rather than as something the owner has.
  The subjects it names also appear in ADR 47's owner-derived list, so they are traceable through
  that document; on ADR 49's own face they are Wikidata facts and pass.
- **ADR 50 names no entity at all.** No QID, and no band, person, work or institution either. Its
  evidence is candidate-pool sizes and rank movements.

So this ADR is not purely a codification. Three of those four already hold the line and one does
not, which makes this a correction as well as a rule.

## Decision

### The line: aggregates may be published; names presented as the owner's may not

- **An aggregate over the owner's data is publishable.** Counts, degrees, distributions,
  thresholds, pool sizes, rank movements, before-and-after tallies. How many seeds are on the
  list, how many ratings exist, how many of a top 25 survived a change: each describes the shape of
  the data without handing over its contents.
- **An entity name presented as the owner's taste, holdings, or the output of a tool run over them
  is not publishable.** *"The owner's books are X, Y and Z"*, *"the top of the recommender's list
  for this list is A, B, C"*, and a table row whose meaning is "this many of his seeds reach this
  node" are all the same disclosure at different resolutions.
- **A world fact stays a world fact.** `Q80006` is computer programming whether or not anybody
  owns a book about it, and a node's degree in Wikidata is a fact about Wikidata. Naming an entity
  is not the offence; **the framing is**. The same QID is a citation in one sentence and a
  disclosure in the next, and only the sentence around it decides which.
- **Derived output counts as taste.** A ranking produced by running a tool over the known-list is
  the known-list at lower resolution, and a recommendation is about the list even though it names
  something not on it.
- **Invented names remain the default for examples**, exactly as `CLAUDE.md` already requires for
  ratings and notes. Where a real name is not carrying an argument, it should not be there.

### This rule is held by review, and by nothing else

**No test can enforce it, and this ADR does not add one.** Two reasons, and both are fatal to the
idea rather than merely inconvenient:

- **The text does not distinguish the two cases.** A QID in an ADR may be a world fact or a
  disclosure, and the difference is in the framing, which is prose. There is no rule over the
  characters `Q80006` that separates a citation from a statement about what the owner owns.
- **A test would have to read the private store to know.** Deciding whether a name is on the
  known-list means consulting `~/.segue/segue.db` or `--known`, neither of which this repository
  may contain and neither of which CI has. And the only mechanical alternative — committing a
  blocklist of the owner's entities so a test can grep for them — **is the disclosure it would be
  defending against.**

So this is a review obligation. It is stated here rather than left implicit precisely because
nothing will catch it otherwise, and **an ADR that implied a gate exists would be worse than no
ADR at all**: the reviewer would stop looking.

### Existing ADRs are amended, never edited

ADR 1 makes an ADR immutable. ADRs 31 and 45 therefore keep their text unchanged and each receives
a dated amendment recording what is exposed, why the text stays, and why editing would not help.

## Alternatives considered

- **Amend both with invented equivalents, and redact the real names.** The obvious repair, and it
  fails twice. It destroys ADR 31's argument, which is a *negative* result: the claim that degree
  cannot separate an institution from a band is checkable only because the real nodes and their
  real degrees are named, and a reader can go to Wikidata and confirm the collision. Invented
  numbers would prove nothing. And, decisively, **redaction does not un-publish.** These documents
  have been on `main` in a public repository for days; git history keeps
  what an edit removes and GitHub keeps its pull-request refs indefinitely. This project has
  already recorded that lesson once, about commit email addresses in PR refs. An edit would give
  false comfort and break immutability to buy it.
- **Rewrite the history instead.** Worse on the same axis. Both ADRs are on `main`, both arrived
  through merged pull requests, and a force-push does not reach GitHub's PR refs. It is the
  scenario the commit-email lesson describes, and it also discards the record ADR 1 exists to keep.
- **Amend only the recommendation output, and leave the graph-structure tables alone** (issue
  #115's third option). Genuinely the most defensible partial move, because ADR 45's names are
  illustrative where ADR 31's are load-bearing. It still loses to the un-publishing argument, and
  it would leave the rule for the *next* ADR stated nowhere.
- **Leave both alone and note the rule in `CLAUDE.md` only.** Cheapest, and it is half of what this
  decision does. It loses on its own because `CLAUDE.md` is working guidance that gets rewritten,
  while the thing worth keeping here is the reasoning — which alternatives were refused and why —
  and that is what an ADR is for.
- **Enforce it with a test.** Refused above, on the two grounds that the text cannot be read
  mechanically and that the only oracle is the private store.
- **Make the repository private.** ADR 33 and issue #37 already refused this framing: the
  protection is where the data lives, not who can read the code. It would also not un-publish
  anything already fetched, and it would cost the reason the repository is public.

## Consequences

- **Two dated amendments land with this ADR**, on ADR 31 and ADR 45. Neither original is edited,
  and neither decision is withdrawn — the amendments are about what the documents disclose, not
  about what they decided.
- **ADR 47 is in the same category and is not repaired here.** This ADR records the finding; the
  repair is its own dated amendment on its own issue, because ADR 47's exposure is a different one
  from either of the two #115 was filed about — a direct statement of holdings rather than a
  measurement — and it deserves its own argument about whether the four names are load-bearing.
- **The rule bites at review time**, and it adds one question to reviewing any ADR that quotes a
  measurement: is this name a fact about the world, or is it his? The question is cheap. Missing it
  is not recoverable, which is the whole point of the paragraph above about redaction.
- **Measurements against the real graph are still allowed**, and this is deliberate. ADRs 45, 47,
  48, 49 and 50 each argue from one, and the project would be much worse documented without them.
  The rule costs the names, not the numbers.
- **Sometimes it will cost an argument.** Where a real name genuinely carries a claim, the honest
  options are to make the point on invented data and say the evidence is weaker for it, or to
  decide the name is a world fact and defend that in the text. Publishing first and amending later
  is not one of the options, because an amendment cannot take anything back.
- **Nothing in `./gradlew check` changes.** No production code, no test, no build file. The
  enforcement is a reviewer, stated as such.
