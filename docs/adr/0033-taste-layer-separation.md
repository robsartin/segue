---
status: Accepted
date: "2026-08-24"
topic: taste-layer-separation
tags: [project, domain, modelling]
supersedes: []
related: [assertion-log-source-of-truth, sqlite-assertion-log, mcp-tool-surface, privacy-and-data-handling, bitemporal-time-model, affinity-capture-and-read, listing-your-own-ratings, recommend-by-normalised-lift-with-routes]
---
# 33. Keep the taste layer separate from the world-facts layer

## Context

Segue holds two kinds of claim that look superficially alike and behave nothing alike.

"Blixa Bargeld was a Bad Seed from 1983 to 2003" is a claim about the world. Sources
assert it, sources can corroborate or contradict it, it has a validity period, and it
is true or false independently of anyone's opinion.

"I love this record, and I first heard it in a shop in Glasgow" is a claim about the
user. It has no external source to corroborate, it cannot be wrong, its dimensions are
different — a rating, a memory, a context — and it is personal data in a way that a
band's lineup is not.

Modelling the second as an edge in the graph would put both in one namespace, give
affinity a `Provenance` and a corroboration count that mean nothing, and mix personal
data into a structure whose whole purpose is to be traversed and cited.

## Decision

- **Two layers, two stores.** World facts live in the assertion log and its graph
  projection. Affinity lives in its own table behind an `AffinityStore` port.
  *(Amended 2026-08-25, issue #46. This bullet read "its own tables", plural, when this ADR
  was written and the schema was open; **ADR 39** settled it as exactly one table, `affinity`,
  one row per qid. Singular is not a smaller commitment — the boundary this decision draws is
  the separate port and the separate store, not the number of tables behind it — but a plural
  that no longer matches the schema invites a reader to go looking for the other ones.)*
- **`note_affinity` is the only tool that writes affinity**, and it never writes to
  the graph. `IngestService` never sees a rating.
- **Recommendations are derived by traversing the world graph and filtering through
  affinity**, not by storing preference as graph structure. The route between two
  things you like is a world-graph question; which things you like is a taste question.
- **Affinity is not an assertion.** It carries no `Provenance`, no corroboration count,
  and no `llm:` prefix, because none of those concepts apply to a first-person statement.
- **The rating is ordinary data; the note is not.** A model may read a rating, weight
  recommendations by it and discuss it. A note is never returned to a model, never appears in an
  MCP tool result, and is read back only by `listRatings` on the owner's own machine (ADR 43).
  *(Added 2026-08-28, issue #85. This is a real move of the boundary, and it deserves the argument
  rather than a tidy sentence.*

  *The line used to run around the whole taste layer, and that turned out to be inconsistent with
  what this project already does. The recommender reads `all-acts.csv` — 815 entities that are on
  that list because the owner likes them. That file is a statement of taste, handed to a tool and,
  through its output, to whoever reads it. **A 1-5 score on one of those same entities is the same
  data at higher resolution.** If the list can be handed over, the scores are not categorically
  different, and treating them as if they were blocked the one feature this project exists for:
  ADR 45 built a recommender that could not weight by what the owner actually loves.*

  ***What is categorically different is the note.** "4/5" is bounded by a schema; free text is
  bounded by nothing, and "reminds me of Dad's funeral" is a different kind of fact about a person
  than a score is. Nothing in the design can constrain what somebody writes there, so nothing in
  the design should let it leave.*

  ***The cost is real and is not being minimised.** An MCP tool result enters a model's context,
  and context leaves the machine. Making the score ordinary means a remote model can be told, one
  `get_entity` at a time, how much the owner likes a named entity, and — through `recommend` —
  what that pattern of scores implies. That is a genuine disclosure, decided rather than
  inherited. Three things make it acceptable: the same preferences already leave in the
  known-list, less precisely; the score has a use that pays for it, which the note does not; and
  the number of entities a model can ask about is bounded by what is already in the graph.*

  ***The counter-argument, recorded because it is not silly:** "less precisely" is doing work in
  that sentence. A list says *these are liked*; scores say *this one is a 5 and that one is a 2*,
  which is strictly more, and the two are not equivalent just because both are about taste.
  Somebody rebuilding this decision from scratch could reasonably keep the score behind the same
  fence as the note and give the recommender a rating-only projection it never returns. That was
  weighed and rejected — a score a model may compute with but never mention produces
  recommendations it cannot explain, and this project's whole position is that a score without its
  receipts is not an answer.*

  ***Three ArchUnit rules keep the new line where it is**, since a boundary that runs between two
  fields of one record cannot be a package: `onlyTheRatingsToolReadsANote` (nothing outside the
  listing tool and the store may call `AffinityRecord.note()`),
  `theRecommenderReadsRatingsAndNeverNotes` (the recommender may hold the store and call the
  note-free bulk read, and may not call `find`, `readAll`, or name `AffinityRecord`), and
  `onlyTheRecommenderReadsEveryRating` (the note-free bulk read is the recommender's alone, so the
  MCP surface stays at ADR 26's six tools). `NoteNeverLeavesThroughAToolTest` proves it
  behaviourally, over every tool the mcp package carries rather than the ones somebody
  remembered.)*
- **Affinity is personal data** under ADR 16: never logged, and kept out of version control.
  *(Amended 2026-08-28, issue #85: read this as "a note is personal data, and a rating is the
  owner's data" — see the bullet above. Neither one is ever logged, neither one is ever committed,
  and the split governs only what may cross to a model.)*
  *(Amended 2026-08-25, issue #37. This bullet previously read "and the repository is private".
  The repository is public and is staying public, so that sentence named a protection that did not
  exist. The real boundary is the filesystem, not GitHub: the graph lives at `${user.home}/.segue/`
  outside the working tree, `*.db` is gitignored, and no real rating, note or affinity example ever
  goes into a fixture, a document or a commit message. Those are the things that could actually
  leak it, and none of them is prevented by repository visibility.)*
- **v1 is a rating and a free-text note.** `CLAUDE.md` floats first-heard-where and
  seen-live-when; the note absorbs them until a real need argues for columns.
  *(Amended 2026-08-25, issue #5. Still true, and no longer the whole story: **ADR 39** settles what
  this ADR left open — the rating is a required integer from 1 to 5 with negative affinity expressed
  as 1-2, the note is optional, re-rating overwrites in place with an `updated_at` stamp rather than
  keeping a history, the entity must already be in the graph under its Wikidata QID, and the rating
  is read back on `get_entity` rather than through a seventh tool. Read ADR 39 before adding a
  dimension here.)*
  *(Amended 2026-08-28, issue #85. A third dimension would now have to say which side of the
  score/note line it falls on before it can be added at all: is it a value a model may read, or is
  it the owner's own words? "Seen live when" is the first kind; "what I remember about that night"
  is the second. A field whose answer is "it depends what somebody types into it" is the note
  again, and belongs in it.)*

## Alternatives considered

- **Affinity as an edge from a "me" node** — elegant on paper, one traversal for
  everything, and it makes every path query walk personal data, gives affinity a
  provenance and corroboration count that are meaningless, and entangles the two
  retention and privacy regimes.
- **Affinity as an assertion with `sourceId: "me"`** — reuses the whole log machinery,
  and it claims the user is a source that can be corroborated or contradicted, which
  inverts what a first-person preference is.
- **Full dimensional schema now** (rating, first-heard-where, seen-live-when)
  — matches the eventual shape, and it is speculative structure ahead of a real need;
  the note field is the honest placeholder until the shape is known.
- **A separate database file entirely** — stronger isolation for personal data, at the
  cost of two connection lifecycles and no transactional relationship, for a boundary
  that a separate table and a separate port already express.

## Consequences

- The world graph can be shared, exported or made public without carrying personal data.
- Affinity can be deleted wholesale without touching a single world fact.
- The two layers evolve independently: adding a taste dimension never touches ingest,
  and adding a source never touches the taste layer.
- A recommendation query has to join across the two rather than reading one structure.
  That is the intended cost, and at personal scale it is a filter, not a join problem.
- Nothing in the graph records that you like anything, so any future export of "my
  interests" has to compose both layers deliberately.
- *(Added 2026-08-28, issue #85.)* **The recommender can finally do what this ADR promised.**
  "Traverse the world graph and filter through affinity" was unbuilt through ADR 45 because the
  filtering half was on the wrong side of this boundary. `Recommendations.regardFor` now turns the
  scores into the weight per known entity that `CandidateSweep` was already multiplying by, so a
  candidate reached by three things rated 5 outranks one reached by six rated 2.
- *(Added 2026-08-28, issue #85.)* **The score's disclosure is now a property of the tool
  descriptions, not just of the code.** `get_entity` tells a model that the note is deliberately
  withheld and that asking again will not produce it, and `note_affinity` tells it to write the
  words down faithfully and not expect to read them back. A model that is not told this will keep
  trying, and a tool that silently drops a field teaches nothing.
- *(Added 2026-08-28, issue #85.)* **Nothing here has met a real rating.** The `affinity` table
  held zero rows when this was written, so the weighting is demonstrated against invented ratings
  in a scratch database (`AffinityWeightedRecommendationTest`) and against nothing else. The
  ordering behaviour is arithmetic and will hold; whether a 5/3 weighting is the *right* strength
  on a real taste layer is unmeasured, and the honest way to find out is to rate forty things and
  run the two lists side by side, the way ADR 45 chose its floor.
