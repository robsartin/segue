---
status: Accepted
date: "2026-08-28"
topic: the-rating-deck
tags: [project, tooling, privacy, domain, mcp]
supersedes: []
related: [taste-layer-separation, affinity-capture-and-read, mcp-tool-surface, mcp-transports, layering-and-archunit, bulk-seeding-as-a-dev-tool, graph-exporter-views-and-formats, listing-your-own-ratings, retraction-as-a-new-claim, recommend-by-normalised-lift-with-routes, streamable-http-transport-on-the-servlet-stack]
---
# 46. Deal a rating deck from a sixth dev-side tool, on its own port, with no way to un-rate

## Context

`Recommendations.regardFor` (ADR 45, amending issue #85) weights every candidate score by the
rating on each known entity — but the `affinity` table it reads has held zero rows since it was
built. The weighting has only ever been exercised against invented data in a scratch database.
Nothing in segue can show what it actually does until the owner rates something.

Two things stand between the owner and a populated table. `note_affinity` (ADR 39) is the only
writer, and it takes one qid, one rating and an optional note per call — workable for the odd
entity mentioned in conversation, not for a known-list that can run into the hundreds. A file of
`qid,rating` pairs read by a bulk-import tool would work, but it moves the cost from "one call per
entity" to "type or generate a rating for every entity before any of it is written," which is the
same cost in a different shape.

The shape of the answer already has five precedents: `seed` (ADR 40), `export` (ADR 41), `ratings`
(ADR 43), `retract` (ADR 44) and `recommend` (ADR 45) are each a `./gradlew` task rather than an
MCP tool. ADR 26 pins the MCP surface at six, and each of those five ADRs put its own tool on the
owner's machine instead, for the reasons argued in that ADR.

Six ADRs have now each considered a proposed seventh MCP tool and each declined it: ADR 39, ADR 40,
ADR 41, ADR 43, ADR 44 and ADR 45. **No one ground is shared by all six**, and the Alternatives
section below states each from that ADR's own file rather than compressing six decisions into a
reason none of them all share — some do borrow from each other, and the list says which. Two of
the six bear on this decision directly. ADR 45 made the fullest case yet for a seventh tool — for
exactly the "what should I explore next" conversational question — and declined it anyway. ADR 39 declined one and answered the underlying question the
other way round: the affinity read it refused a tool for stayed **on** the MCP surface, folded into
`get_entity`, rather than moving to a dev-side tool.

## Decision

### A sixth dev-side tool, `./gradlew rate`. Still six MCP tools.

New package `com.robsartin.segue.rate`: `RateCli` (argument parsing, mirroring `RecommendCli`),
`RateRun` (replay, sweep, deal), `Deck` and `Card` (the one component with a decision in it,
provably pure — no HTTP, no database), and `RateServer` (a `jdk.httpserver` loopback server). It
takes the same `--known` file `recommend` does, replays the log into a throwaway
`TinkerGraphStore` the same way `RecommendRun` does, and serves a page that deals one entity at a
time for a single keystroke — `1`–`5` to rate, `s` or space to skip, `b` to go back.

### Why not the Spring app

The app already serves HTTP on `127.0.0.1:8080` for the Streamable HTTP MCP transport, so the
machinery to do this exists there. That is exactly the objection: it would put a taste-layer
*writer* on the MCP server's own port, and ADR 32 confines Spring annotations to the `app` and
`mcp` packages — landing this there means admitting a third package to that rule for a feature
that has nothing to do with what those two packages are for. The dev-tool placement buys
everything the in-app placement would, at none of that cost.

### Why not a seventh MCP tool

A model driving this conversationally is exactly the shape ADR 26 pins the surface against, and
the precedent is now six ADRs deep — see Context, and the per-ADR list under Alternatives.
`RateRun` still reuses the recommender's own `CandidateSweep`, `Routes` and `Sweep` for the
candidate half of the deck, rather than a second implementation, but reusing that machinery from a
dev-side tool is not the same question as exposing it as a tool call: the input is still ADR 40's
file of everything the owner already has, and handing that file (or its contents, through an
answer) to a model is what ADR 40 already refused.

The reuse extends to what the sweep is weighted by. `RateRun` passes
`Recommendations.regardFor(ratings)` over the same `AffinityStore.readRatings()` map it already
reads for the already-rated exclusion, which is what `RecommendCli` passes its own sweep. It passed
`Recommendations.EQUAL_REGARD` until the final review of issue #101, and the effect was that the
deck's candidate cards and `./gradlew recommend`'s output for the same `--known` file disagreed the
moment anything was rated — the tool whose purpose is collecting ratings was the one tool choosing
candidates as though none had been collected.

### Port 8090, not 8080

`RateCli.DEFAULT_PORT` is 8090 so the deck and the MCP server can run at the same time and nothing
addressed to one arrives at the other. `--port 0` asks the OS to pick one, and the running server
reports back which.

### The Origin allowlist, ADR 28's argument used a second time

`RateServer` binds to `127.0.0.1` and checks the `Origin` header against an allowlist of loopback
hosts before honouring `POST /api/rate`, exactly what ADR 28 already requires of the MCP endpoint
and for the same reason: loopback binding stops another machine reaching the port, and it does
nothing about a hostile page already open in the owner's own browser posting to it — the DNS
rebinding attack ADR 28's `Origin` check exists to close. This endpoint writes the one table in
segue that has no source to regenerate it from, so both halves — the bind and the header check —
are needed together.

The check is stricter than ADR 28's own text implies, and the strictness is deliberate rather than
incidental. `RateServer.originAllowed` parses the header as a `URI` and compares `URI.getHost()`
exactly against `{127.0.0.1, localhost, [::1]}`; it does not compare the raw header text with
`String.startsWith`. `"http://127.0.0.1.evil.com"` *starts with* the allowed origin
`"http://127.0.0.1"` as a string, while naming a completely different host — a `startsWith` check
would have let it through. The IPv6 entry carries its brackets because that is the form
`URI.getHost()` returns for an IPv6 literal — `http://[::1]:8090` yields `[::1]` — and the bare
`::1` this ADR first recorded matched nothing at all, which the final review of issue #101 caught
and fixed in both the code and this sentence (the MCP server's own loopback list,
`SegueConfiguration.loopbackNames`, already spelled it that way). The literal string `"null"` — what a browser sends as the `Origin` of a
sandboxed iframe or a `data:` navigation, and which an attacker can manufacture at will — is
deliberately not in the allowed set, because it is the opposite of an allowlist entry: it is a
shape the attacker chooses, not the browser attesting to a real page. A genuinely *absent*
`Origin` header is allowed, and is a different case from `"null"` being present and unrecognised:
curl and an MCP-style client send no `Origin` at all, and the browser `fetch()` POST this check
guards always sends one, so the absent-header branch never protects an actual browser request and
costs nothing to leave open.

### `readRatings` is now shared with `rate`, and the widening is narrow

`RateCli` must know which entities are already rated so the deck does not deal them again — that
is the whole of its resume mechanism (see `Deck` below). The only method that answers that
question is `AffinityStore.readRatings()`, which `ArchitectureTest.onlyTheRecommenderReadsEveryRating`
reserved to `..recommend..` alone. Its own javadoc says widening the taste layer's readership
"stays an ADR-level decision even though the score is now ordinary data" — this is that decision,
and it now also permits `..rate..`.

The reasoning ADR 45 gave for handing `recommend` this call applies to `rate` unchanged: both are
dev-side tools, off the MCP surface, run by the person who owns the data. Nothing about *what* the
rule protects has moved — `ToolSurfaceTest` still counts MCP tools and would not notice a bulk
read arriving as a field on one, and neither new caller is a tool. The widening is also narrow in
what it hands over: `readRatings` returns `Map<String, Integer>`, qid to rating, nothing else.
Nothing else on the port moves, and the three other ways a note could be reached are each left
exactly as they were. `readAll` stays reserved to `..ratings..`
(`onlyTheRatingsToolReadsEveryRating`). `AffinityRecord.note()` stays reserved to `..ratings..` and
`..sqlite..` (`onlyTheRatingsToolReadsANote`). `find` is reserved to nobody and never was — it is
what `get_entity` and `AffinityOverlay` call — and no rule bans it inside `..rate..` either; the
deck simply does not call it. The conclusion holds without that: `readRatings` returns a
`Map<String, Integer>` with nowhere to carry a note, and `theRatingDeckNeverReadsANote` bans
`AffinityRecord.note()` for **every** class in `..rate..`, `RateServer` included and with no
exception — so a `find` call added here later still could not read the words off what it returns.

### Ratings only: three fences, and one stated exception

`theRatingDeckWritesOnlyAffinity` forbids anything in `rate` from making any of the three writes
`ArchitectureTest`'s `APPLIES_A_CLAIM` predicate names — `GraphStore.record`,
`GraphStore.upsertNode` and `AssertionLog.append` — and forbids nothing else, so
`AffinityStore.put` remains available. The rule's own javadoc names its mirror,
`theRatingsToolOnlyReads`: that tool may read every rating and write none, this one may write a
rating and must not touch the graph or the log.
`theRatingDeckNeverReadsANote` forbids calling `AffinityRecord.note()`; `Card` already has no note
field to put one in, so this is belt on top of the type-level guarantee. `theRatingDeckLogsNoRating`
forbids depending on `AffinityRecord` from a log line's reach at all (ADR 33) — with one named
exception: `RateServer` is excluded by class name, because it is the class that must construct the
`AffinityRecord` it writes (`affinity.put(new AffinityRecord(...))` in its `rate` handler is the
one legitimate write this package exists to make). The rule's own condition — every class in
`..rate..` other than that one name — is the authority for who else it covers, so a class added to
the package later is bound by it automatically rather than by an enumeration here going stale;
`RateServer` itself owns no logger that prints a rating.

### Degree ordering, with the arithmetic

`Deck.deal` sorts known entities by in-graph degree, descending, before dealing them.
`Recommendations.regardFor` centres its weighting on `NEUTRAL_RATING` (3): a rating of 5 weighs
5/3, a rating of 1 weighs 1/3. A 1 against a 5 is therefore a 5× spread; a 4 against a 5 is only
1.25×. The low ratings are where the weighting actually moves, and a known entity's rating reaches
candidate scores only through the intermediates it touches — so rating the busiest entities first
buys the most movement per keystroke, and the card shows the same degree the ordering is built on,
so a card near the top of the deck visibly says why it is there.

`Skip` exists as a separate, non-recording action for the same reason the low end of the scale
matters: an entity the owner does not recognise must not become a low rating by default. Rating
records a `put`; skipping records nothing at all.

### No un-rate

`AffinityStore` has no delete method, by ADR 39's design — one row per entity, the later rating
winning — and the deck adds none. Pressing `b` to go back and choosing a different number is a
second `put` against the same qid, which needs no new port method. The consequence is worth
stating plainly: a first rating can be changed, but it can never be withdrawn — there is no verb
in this tool, or anywhere else in segue, that removes one.

**Amendment (2026-08-28, issue #109): revision, because "everything unrated" made reconsideration
impossible.**

A real session produced 973 ratings — 541 fives, 309 fours, 121 threes, one 2, one 1. Those 973
ratings moved exactly **one** entity in the top 25 of `./gradlew recommend`'s output against
running with no ratings at all, and the last 164 entities in that ranking did not move at all. The
reason is arithmetic, not a bug: `Recommendations.regardFor` centres its weighting on
`NEUTRAL_RATING` (3), so a rating of 3 weighs exactly 1.0 — identical to an entity with no rating at
all. **The 121 threes are no-ops.** They cost a keystroke each and moved nothing, because "no
opinion yet" and "I said 3" produce the same number.

The deck could not reach them. `Deck.deal`'s only mode excluded every already-rated entity —
"`readRatings` is now shared with `rate`" above calls that exclusion "the whole of its resume
mechanism," and the Consequences bullet "a rating can be changed but never withdrawn" said
correction was possible in principle without saying the deck offered no path back to a rating once
given, because nothing dealt an already-rated entity a second time. A 3 recorded on a first pass,
honestly meant as "I don't know" or "it's fine," had no way to become the 2 or 4 it may have
actually meant.

**Decision: `--revise <rating>` deals already-rated entities holding exactly that rating, instead
of unrated ones.** `RateCli` gained the flag, validated against `AffinityRecord.MIN_RATING`/
`MAX_RATING` — the same range check the scale itself uses. `Deck.deal` gained an `OptionalInt
reviseRating` parameter and, when present, runs a separate selection (`dealRevision`) that walks the
known list and keeps only the qids the `ratings` map holds at exactly that value, instead of the
exclusion path above. The default run — no `--revise` — is unchanged: `Deck.deal` with an empty
`OptionalInt` behaves exactly as the rest of this Decision section describes.

**The card must show the rating it already has, and that is non-negotiable.** The one risk a
revision pass introduces that a first pass does not: a considered 2, re-shown blind, becomes a
reflexive 4 on the second look — worse than not offering revision at all, because a rating that
just happened to be typed again reads as fresh judgment rather than as what it is, an unexamined
repeat. `Card` gained a third static factory, `Card.rated(node, degree, currentRating)`, carrying
the existing value; the deck's JSON carries it as `currentRating` beside `degree` (present or
`null`, the same treatment `degree` already gets); and `deck.html` renders it as a filled,
reversed-color banner — the one element on the card with a real background fill, not just colored
text — reading "Currently rated N — this is a revision, not a new card," built with `textContent`
and `document.createElement`, never `innerHTML`, the same way every other label on the card is
built. An unrated card shows no such banner; the gate is `currentRating !== null`.

**Revise mode deals no candidates.** A candidate is by definition something absent from the
known-list and therefore unrated — there is nothing to reconsider about it, and mixing discovery
into a revision pass would change what the pass measures. `dealRevision` selects only from
`knownQids`, never from the candidate sweep, and sorts by the same degree-descending rule the
default deck uses.

Nothing above this amendment is withdrawn — it described, and still describes, `Deck.deal` with no
`reviseRating` supplied. What changed is that "everything unrated, recomputed at startup" is now
one of two modes this tool can deal, not the whole of it.

**Amendment (2026-08-28, issue #109 final review): revising a rating preserves the note, because
`--revise` is the first thing in segue that can reach a note-bearing row.**

The amendment above shipped a silent, irreversible data loss. `RateServer` wrote every rating as
`affinity.put(new AffinityRecord(qid, rating, null, Instant.now()))`, and `SqliteAffinityStore`'s
upsert sets `note = excluded.note` — so every write through the deck put `NULL` in the note column.
Before `--revise` that was harmless, and harmless for a reason worth stating precisely: the deck
could only deal **unrated** entities, a note cannot exist without a rating (`note_affinity` writes
both), so no row carrying a note was reachable from this tool at all. `dealRevision` inverts
exactly that. It selects the already-rated population — which is precisely where the `note_affinity`
MCP tool writes notes. `note_affinity(Q…, 3, "great live, thin on record")`, then `--revise 3`,
then pressing `2`, left `rating = 2, note = NULL`, with no message, no log line, and no source
anywhere to restore the words from.

**Decision: the taste-layer port gains a rating-only write, and the deck uses it — in both modes.**
`AffinityStore.updateRating(String qid, int rating, Instant updatedAt)` is a signature with nowhere
to put a note; `SqliteAffinityStore` implements it with SQL that never names the `note` column, so
an existing note survives an update untouched and a row inserted through it simply has none. It
inserts as well as updates, because the deck's default mode writes **first** ratings through the
same call and a method that could only update would refuse its commoner case.

**The fix is a narrower write, not a wider deck, and that is the point.** The obvious alternative —
have the deck read the existing record and write the note back — was refused: it requires the deck
to read `AffinityRecord.note()`, which `ArchitectureTest.theRatingDeckNeverReadsANote` forbids for
every class in `rate` and should go on forbidding. A tool that never sees a note cannot erase one
either, once the write it makes has no note in it. `RateServer` calls `updateRating` in **both**
modes and could not distinguish them if it wanted to: it holds a `List<Card>` and no mode flag.
`put` stays what it was — the whole-row write, and `SegueService.noteAffinity`'s alone, because
that is the one caller with a note to write.

**Consequential moves, each small and each stated here because this ADR's earlier text names the
old shape.**

- **The scale's bounds moved to `RatingScale` (`domain`), and the Decision above should now be read
  as naming it.** That paragraph says `--revise` is "validated against `AffinityRecord.MIN_RATING`/
  `MAX_RATING`"; the constants are now `RatingScale.MIN`/`MAX`, and `AffinityRecord`'s own compact
  constructor calls `RatingScale.check`. The reason is a fence that was passing for an invisible
  reason: `RateCli` named those constants in its usage string, and `theRatingDeckLogsNoRating`
  saw no violation only because javac inlines a compile-time `int` and the reference never reaches
  the bytecode ArchUnit reads. A class that needs to say "1 to 5" must not have to name the type
  that carries a rating value to do it.
- **`theRatingDeckLogsNoRating`'s named exception is withdrawn.** The section above headed
  **"Ratings only: three fences, and one stated exception"** records that `RateServer` is excluded
  by name because it must construct the record it writes. It no longer constructs one, so the
  exception is gone and the rule now bans `AffinityRecord` across the whole of `rate`, with no
  exception at all. **That heading is stale as written**: read it as "three fences, and no
  exception", and read its `affinity.put(new AffinityRecord(...))` sentence as describing what the
  handler used to do. (The first draft of this amendment cited *"Ratings are the only thing it
  writes"*, which is the developer guide's heading, not this ADR's.)
- **Two `only reads` fences gained `updateRating`.** `theRatingsToolOnlyReads` and
  `theRecommenderOnlyReads` each named `AffinityStore.put` as the write they forbid. A second write
  method on the port would have walked straight through both, so both now name it too.
- **The banner shows what this session wrote, not what the deck was dealt with.** "The card must
  show the rating it already has, and that is non-negotiable" above is the strongest claim in this
  amendment, and `b` broke it. `RateServer` holds `List.copyOf(deck)` from startup, so a card's
  `currentRating` never refreshes: rating a card `2` and pressing `b` re-displayed the same card
  still announcing "Currently rated 3" — a documented key, on a page whose own caption says going
  back re-rates, producing a confident falsehood about the one number this ADR says must never be
  wrong. `deck.html` now keeps a `qid → rating` map of what it has successfully sent, written only
  after a response that was `ok`, and the banner prefers it over the server's snapshot. The page
  needs no round trip to know this: it is what sent the value. It applies to an ordinary card too —
  a card rated a minute ago and returned to *is* a revision of that rating, and showing the value
  given is the same protection against a reflexive second answer the banner exists for. **The
  wording stays as it is**, on review: "this is a revision, not a new card" is literally true of a
  default-mode card the owner rated ten seconds ago and has just pressed `b` to return to — it is a
  revision of that rating, and it is not a new card, because they have already seen it. A second,
  gentler wording for the default mode would mean two sentences to keep true and would soften the
  one message whose bluntness is the entire reason it exists.
- **Two sentences in the Decision above now name the wrong port method, and both should be read as
  `updateRating`.** "Degree ordering, with the arithmetic" ends "Rating records a `put`; skipping
  records nothing at all" — the contrast it draws is between recording something and recording
  nothing, and that still holds exactly; only the method's name has changed. "No un-rate" says
  pressing `b` and choosing a different number "is a second `put` against the same qid, **which
  needs no new port method**." That clause is now false, and it is false for a reason worth keeping
  rather than quietly correcting: re-rating really did need no new port method to *record the
  rating* — `put` did that correctly. What it needed one for is everything `put` also writes. The
  paragraph's actual claim survives untouched: there is still no delete, still one row per entity,
  and a rating can still be changed but never withdrawn.
- **The `Origin` allowlist now guards `GET /api/card` as well as `POST /api/rate`.** "The Origin
  allowlist, ADR 28's argument used a second time" above says the check runs "before honouring
  `POST /api/rate`", and only that endpoint called `originAllowed`. The asymmetry was defensible
  while a card body carried a label, a kind and a degree: under the rebinding scenario a hostile
  page learned at most whether some qid was on the owner's known-list. This amendment's own
  `currentRating` field changed that — a page walking `?i=0,1,2…` could read the ratings
  themselves. Read that section as naming both endpoints.

**Amendment (2026-08-30, issue #127): a retried POST cannot overwrite a re-rating, and the reason
is ordering the page already enforces. The limit that remains is a smaller one, recorded here.**

Issue #103's browser harness saw one unanswered rating reach its stub **three times**: Chrome
retries a POST whose connection died before any response arrived. That raised a question this
section is the right home for, because the answer would be invisible if it went the other way —
the write is last-writer-wins, there is no history table and no un-rate, so a value quietly put
back leaves no trace anywhere. The feared sequence: the owner rates a card, the connection dies,
the page says *"may not have been recorded — nothing has advanced"*, they take that invitation and
rate the same card **differently**, and a late retry of the first POST lands afterwards and
restores the number they had just abandoned.

**It cannot happen, and the measurement says why rather than merely that.** Measured on loopback
against a stub that closes the connection without answering — Chrome 151.0.7922.174, macOS 26.6.2
— across three stall lengths spanning a five-hundred-fold range. The message is timed from inside
the page, by a `MutationObserver` on `#problem`, rather than by polling it from outside:

- **The connection dies at once.** Three attempts reached the server at +1.49, +2.77 and +3.46 ms
  after the keypress; the failure message appeared at +9.42 ms; nothing further arrived in the five
  seconds after it.
- **Each attempt stalls 300 ms before dying.** Three attempts at +1.51, +307.14 and +613.13 ms; the
  message at +919.93 ms; again nothing in the five seconds after it.
- **Each attempt stalls 1500 ms before dying.** Three attempts at +1.53, +1507.80 and +3013.80 ms;
  the message at +4518.84 ms; again nothing in the five seconds after it.

**The reading is in what does not vary.** The attempt count is fixed at three across that whole
range, and the message always lands one full stall-period after the last attempt. Count-independent
and duration-independent together are the signature of retries being exhausted inside the one
`fetch`; a coincidence of timing would not survive a five-hundred-fold stretch.

Every retry is therefore already spent **before the owner is told anything at all**. The window
the issue asked to size is not narrow — it is on the wrong side of the message that opens it. Two
reasons hold it there, both structural rather than lucky. They are **not independently sufficient**,
and that distinction is the most useful thing in this amendment:

- **Chrome's retries happen inside the one `fetch`.** It does not reject until it has stopped
  retrying, and `deck.html` writes the failure message from that rejection. The message is
  downstream of the last attempt by construction.
- **The page could not issue the re-rating early even if it were told early.** `rate()` nulls
  `current` before its first await and only `show()` restores it, and `busy` is held until the
  `finally`. More than that: the `finally`, the `problem(...)` call and `show()`'s own `busy = true;
  current = null` run in one synchronous continuation with no yield between them, so there is no
  observable moment at which the message is up and the page is rateable. The re-rating cannot be
  issued until a whole further card round-trip has completed.

**Neither reason survives alone, and the change that would break the pair is a plausible one.** The
first governs Chrome; the second governs this page. Read the first as sufficient and a client-side
timeout on the rating `fetch` looks safe — it is not. A timeout abandons the request without
cancelling Chrome's retries and releases `busy` and `current` with attempts still to come, which
reopens the window exactly. That is not hypothetical: it is the defective page the test below was
verified red against. **A `fetch` timeout is therefore a constraint on this page, not a free
improvement**, and it is worth naming because the deck as it stands hangs indefinitely if the server
accepts a POST and neither answers nor closes — which is the obvious reason someone would reach for
one. The test enforces this whether or not anyone reads this paragraph.

**Decision: change nothing about the write, and pin the ordering with a test.** `DeckBehaviourTest
.aRetriedRatingCannotOverwriteAReRating` drives the real page against a stub that stalls and then
dies, has the owner re-rate the same card, and asserts that every attempt at the abandoned rating
reached the server before the re-rating and that the re-rating is the last thing the server saw.
It carries its own positive control — the abandoned rating must have been retried more than once,
or an ordering assertion over a sequence with nothing to reorder would pass by having had no work
to do. It was verified red against a defective page (a client-side timeout that abandons the fetch
without cancelling it, releasing `busy` and `current` with retries still to come); against that
page the server saw the abandoned rating, then the re-rating, then the abandoned rating again.

**The committed test is not the probe that produced the figures above.** Those came from throwaway
probes stalling 300 ms and 1500 ms; the test stalls `SLOW_MILLIS`, 400 ms, because it asserts an
ordering rather than a duration and a shorter stall keeps the suite quick. Nothing in the repository
regenerates the numbers recorded here: they are a dated measurement, not a derived value.

**The three alternatives the issue listed alongside "leave it", and why each lost.**

- **Make the write conditional on the rating the client believed it was replacing.** Refused, and
  the sharpest reason is that it would not work: a retry is byte-identical to the original POST and
  carries the same expected-previous value, so no condition can distinguish the two. What it could
  do is refuse the owner's later, correct value when an earlier attempt had landed in between —
  manufacturing a refusal in place of an overwrite that does not occur. It would also need a third
  write method on `AffinityStore`, which `theRatingsToolOnlyReads` and `theRecommenderOnlyReads`
  would both have to name, for nothing.
- **Have the page reconcile on load.** Refused because there is nothing to reconcile.
  `writtenThisSession` is set only after a response that was `ok`, so the page never recorded the
  unanswered rating and its account of what it wrote is already correct. Asking the server instead
  would move more of the owner's ratings into the browser to correct a record that is not wrong.
- **Change the refusal wording so re-rating is not the obvious next move.** Refused, and this is
  the option the measurement most directly kills: re-rating is the **safe** move — it lands last
  and it wins. Wording that discouraged it would steer the owner away from the correction that
  works.

**What is actually left, stated plainly, because it is a real limit and not the one the issue
named.** The retries do reach the store and each one writes. Through `updateRating` the value is
identical, so only `updated_at` moves, and ADR 39 deliberately does not retain that drift. The
consequence is in the wording rather than the data: *"may not have been recorded"* understates the
common case, where the rating almost certainly **was** recorded, more than once. The sentence
stays — it is not a falsehood, and the deck deliberately does not advance on it — but an owner who
reads it and presses `s` instead of re-rating leaves a rating in the table they were unsure of, and
there is still no verb anywhere in segue that takes one out. That is this section's own claim,
reached by a different road.

**What the measurement does not cover, so that nothing more is read into it.** Three stall lengths,
one browser, one operating system, and loopback — which is the only place this endpoint exists,
since `RateServer` binds `127.0.0.1` and the `Origin` allowlist keeps it there. It is not a claim
about every way an HTTP request can be delayed. Nothing above this amendment is withdrawn.

**Amendment (2026-09-01, issue #169): the retry the amendment above measured is not a browser
constant. It is a count of the sockets Chrome happened to have pooled, the test's own fixture was
what supplied them, and the claim that a red positive control "is that fact and not a flake" is
withdrawn.**

The amendment above read *"the attempt count is fixed at three across a five-hundred-fold range"*
as a property of Chrome. It is not. **Three was the size of Chrome's socket pool for the stub's
origin, and the stall length simply had no bearing on it.** The rule, measured over 59 traced runs
and three forced failures and recorded in
[the retry-precondition measurement](../retry-precondition-evidence.md) (2026-09-01, Chrome
152.0.7977.65, macOS 26.6.2, loopback):

> Chrome resends a POST whose connection died **iff** that attempt was bound to a socket **already
> in the pool** — including a preconnected socket that has never carried a request. An attempt
> bound to a socket the pool had to *connect for this request* is never resent on.

So **attempts at the abandoned rating = 1 + the number of pooled sockets free when the key is
pressed**. The measured distribution is exactly that: three attempts in 54 runs, two in five, and —
when the pool is empty — **one**, which is a red positive control asserting nothing about the page.
Note also what the error code is not: a pooled socket is resent on whether it reports
`ERR_CONNECTION_CLOSED` or `ERR_EMPTY_RESPONSE`. The socket's provenance is the discriminator.

**The defect was in what `DeckBehaviourTest.start()` meant by "loaded".** It waited for `#card h1`,
which is `GET /api/card` answering, and for nothing else. Chrome then issues `GET /favicon.ico` off
the same page load — which the stub's `"/"` context cheerfully answers with the deck page — and in
the traces it arrives 6–20 ms before the POST. Two requests racing for the same pooled socket, with
the test waiting on neither. That is the flake: seven sightings across two days and five branches,
including an unmodified baseline, always green on the immediate rerun. Under load the race widens,
which is why it was seen locally far more often than in CI.

**The fix is a precondition, made explicit and enforced.** The stub now counts its own exchanges
through a `Filter` on all three contexts, and `start()` waits — as a condition with a deadline, not
a sleep — for `readyState === 'complete'` and then for the stub to be serving nothing and to have
been asked for nothing for 200 ms. That window is a bound on *issuance* latency and not a settle:
the favicon is Chrome's own request and the page reports nothing about it, so there is no condition
to wait on, only a length of silence after which one has certainly gone out. 200 ms is ten times the
20 ms worst case in the traces. **This is the file's definition of loaded, not one test's**, because
the definition is what was wrong.

**What that instrument cannot see, stated because a true conclusion resting on a false reason is
how this comes back.** The stub counts exchanges; Chrome holds a socket until the response body has
been *read*. A response the page never drains therefore keeps a socket checked out after the
exchange has ended and the count has returned to zero, and the wait would report quiet and be wrong
with no assertion to fire. `deck.html` does leave bodies undrained — it returns on `!response.ok`
without reading them, on both the card path and the rating path, and the stub answers 403 and 404
with a body. The precondition holds today only because no such response is issued before the wait
returns: every refusal in that file is set up after the fixture has finished. A test that made the
stub refuse *during* load would break it silently.

**What a red positive control means now.** The stub was quiet before the keypress, so Chrome had an
idle pooled socket to resend on and did not: the browser has stopped retrying a POST whose
connection died, and the hazard this section guards no longer exists. The assertion's message was
changed to say that. Its previous message asserted the same thing without the precondition holding,
which made it false — it was a flake, seven times.

**Rejected: remove the favicon request at source.** An inline `<link rel="icon" href="data:,">` in
`deck.html` would stop Chrome asking, and it is one line. Refused on two grounds. It changes
production markup to make a test deterministic — the page has no other reason to carry that tag —
and, worse, it hides rather than states the dependency: the control's real requirement is that
*something* be in Chrome's pool and *nothing* be holding it, and a page that happens not to request
a favicon leaves that unwritten and unenforced, ready to break again the next time the fixture
issues anything at all during load.

**Rejected: keep a deliberate favicon stall in the stub permanently**, so that removing the wait
fails loudly instead of flakily. Measured rather than assumed, and it does not do that: with the
favicon held and nothing else occupying the pool, the preconnected spare socket is still there and
the test passed three times in four. A stall would trade one flake for another while adding its
length to every test in the file. Emptying the pool outright takes concurrent page-issued fetches —
which is how the failure was forced, and is not traffic the deck would ever generate.

**The numbers above are a dated measurement.** They are kept at
[docs/retry-precondition-evidence.md](../retry-precondition-evidence.md), the way the engine
bake-off is; the raw traces and NetLogs behind them were not retained, and nothing regenerates
them, exactly as the 2026-08-30 figures are not regenerated. What is enforced in the build is the
precondition, not the count. Nothing else above this amendment is
withdrawn: the ordering finding, the reason a `fetch` timeout is a constraint on this page, and the
limit stated about the wording all stand, and none of them depended on the attempt count being
three.

**Amendment (2026-09-01, issue #169, round 2): the "quiet" precondition above can be true and the
socket still gone, so the test now makes one instead of inferring it, and the residual left behind
is measured.**

The fix above shipped, and the control it protects failed again under load during another branch's
gate. Traced 81 runs with per-request server logging and Chrome's NetLog: **one failure.** In the
225 ms of genuine silence `untilQuiet()` measured before the keypress, Chrome closed every socket
it held — six sockets, across five origins, in one millisecond — with
`QUIC_SESSION_POOL_MARK_ALL_ACTIVE_SESSIONS_GOING_AWAY` alongside: a browser-wide network-change
pool flush, not pressure and not an idle reap. **A flush is not an exchange.** The counter the
2026-08-30 amendment added saw nothing, because nothing was requested; `untilQuiet()`'s condition
was genuinely true and its conclusion was false. The precondition the control needs — a pooled
socket exists at the keypress — is a fact about Chrome's socket pool, and no count of exchanges at
the stub can observe it.

The favicon was checked again and ruled out a second time, with more margin than before: 6.00–21.25
ms across all 81 runs, none above 200 ms. `untilQuiet()`'s 200 ms bound held; the silence itself
was always the window a flush could land in, not the favicon racing it. And the dose-response
across the batches was clean: attempts at the abandoned rating equalled one plus the pooled sockets
alive at the POST in 61 of 61 traced runs. The full study is
[docs/retry-pool-flush-evidence.md](../retry-pool-flush-evidence.md), a dated measurement kept the
same way as the 2026-08-30 and 2026-09-01 figures above it.

**Decision: create the precondition, then press at once.** `DeckBehaviourTest.warmUp()` issues a
same-origin `GET` from the page itself, right after `untilQuiet()` returns, waits for the stub to
see that exchange finish and the page's own promise to resolve, and the keypress follows
immediately with nothing between them. `untilQuiet()` stays exactly as the 2026-09-01 amendment
above left it — it closes the favicon race, which is still real and still bounded, and removing it
would reopen that one. The warm-up closes a different hole: instead of waiting through a window a
flush can land in, it puts a used, idle socket in the pool at the last possible moment before the
key is pressed. Draining the warm-up's response is kept, but as insurance rather than as the thing
that makes the test pass — at the committed 4-byte body an undrained response still passed 3/3,
because Chrome drains a body that small on its own, and draining only becomes load-bearing near
200 KB.

**What a red control now says, and what it does not claim.** `aRetriedRatingCannotOverwriteAReRating`'s
helper — `whyNoRetryHappened()` — reads the client port the stub recorded for the abandoned POST.
A port that had already served a request is Chrome bound to a pooled socket and choosing not to
resend on it: the browser changing, which is the finding this ADR wants to be told about. A port
never seen before is the observable this round adds, and the message names the network-change flush
as the one cause of it ever observed here — while stating plainly that port novelty cannot
distinguish a flush from a socket held open by something else, and does not claim to. See the
javadoc on `warmUp()` and on `whyNoRetryHappened()` for the exact wording; it is not restated here.

**`shouldServeOneCompletedExchangeWhenTheWarmUpRuns` proves what the stub can see, and the
pooled-socket property is established elsewhere, not per run.** Which pooled socket Chrome hands a
request — the one the warm-up used, or a never-touched preconnect spare opened alongside it — is
Chrome's own choice, not the test's to assert. Two attempts to pin it by port each turned into a
flake before this was understood: asserting the warm-up landed on a previously-seen port failed
about 1 run in 10, and asserting two consecutive warm-ups shared a port failed about 1 in 60. The
committed test asserts only what the server observes — one new served exchange per call, nothing
left in flight, the body read to completion — and leaves the pooled-socket property to the Loop C
and D controls (round 1's occupancy probe holding every socket: the retry control fails without the
warm-up and passes with it) and to `aRetriedRatingCannotOverwriteAReRating` itself, which exercises
it on every run by depending on it.

**The residual is measured, not hidden.** A flush can still land in the few milliseconds between
the warm-up finishing and the keypress. From round 2's rate — one flush in 81 runs over a ~225 ms
window of silence — a ~30 ms window gives on the order of one failure in five hundred runs, under
the same network churn that produced the sighting. That qualifier is deliberate: the one failure
coincided with another Chrome on the machine saturating its network, and a NetLog placed the flush
in the same millisecond a `clients2.google.com/time` request completed. But a ninth sighting, on an
untouched baseline, failed during a full gate run at a 1-minute load of 3.99 with nothing else
recorded — so "under the same churn" is the honest qualifier on the rate above, and "under load" is
not. The flush is the only mechanism ever observed to produce this failure; what triggers it on an
otherwise-quiet machine is not established, and the fix above does not depend on knowing.

**Rejected.**

- **Widen the silence window.** The favicon bound was never the weak point — 0 of 81 runs exceeded
  it — and every millisecond of added silence is a millisecond in which a flush can land. Widening
  it makes the race worse, not better.
- **Observe the pool through CDP before pressing.** The DevTools protocol exposes no socket-pool
  state. The flush was seen only through `--log-net-log`, a per-run capture, not a live query the
  test could poll.
- **Warm up without draining the body.** An undrained response keeps the socket checked out (#188);
  measured during this round, draining is not what makes the committed 4-byte warm-up pass — it is
  insurance against a larger body, not load-bearing today.
- **Warm up and then wait for silence again.** Re-creates the exact window the warm-up exists to
  close.

**Correction to §8 of `docs/retry-precondition-evidence.md`.** That page filed Chrome's requests to
`clients2.google.com` as "incidental observation, not related to the flake." Round 2 found they are
related: the flush that caused the one failure fired in the same millisecond the `clients2.google.
com/time` request completed (#186). The page carries a dated note to this effect; #186 — Chrome
reaching Google despite `--disable-background-networking` — remains a confound worth removing
separately, and no attempt was made here to suppress the flush itself, since no Chrome switch was
found that disables network-change handling; the notifier is driven by the OS.

**What this amendment does not do.** No retry loop: a test that reruns its own scenario on a
classified environmental failure would hide the rate this amendment exists to state. No production
change: the warm-up request is issued by the test through the page, not by `deck.html`. Nothing
above either 2026-09-01 amendment is withdrawn; this one narrows what "the stub saw nothing" is
allowed to mean.

**Note (2026-09-02, issue #186): the confound named above was removed, and the residual re-measured
against it.** The test browser now reaches nothing but `127.0.0.1` — every other name fails at DNS —
and the flush was traced again under that posture, 80 launches running this very scenario plus 60
runs of `aRetriedRatingCannotOverwriteAReRating` under load. Two findings bear on the residual
stated above. **The phone-home was not the flush's cause**: the browser-wide notification still
fires, once per launch, in 80 of 80 — so those Google requests were present at the sighting and are
not what drives the flush, and `docs/retry-pool-flush-evidence.md` §5's "they are the trigger"
carries a dated note to that effect. And **the flush no longer finds the deck's sockets**:
it closed nothing in 80 of 80 runs, landing 57–140 ms before the page's first socket every time, and
the retry control passed 60 of 60. So the residual this amendment measured is **closed within what
those runs bound** — 0 failures in 60 is consistent with rates up to about 4.9%, and round 2's own
1-in-81 would have produced a clean 60 about half the time — which is not the same as fixed. It is
also not enforced: a control that plants the page load early had the flush close its loopback socket
in 16 of 20 runs, so the mechanism is intact and the deck tests are protected by an incidental
margin. Nothing above is withdrawn. The measurement is
[docs/loopback-only-evidence.md](../loopback-only-evidence.md) and the decision it supports is
[ADR 52](0052-test-the-deck-page-in-a-real-browser.md)'s 2026-09-02 amendment.

## Alternatives considered

- **A controller in the Spring app** — the server and the port already exist. Refused because it
  puts a taste-layer writer on the MCP server's own port and forces ADR 32's Spring-only packages
  to admit a third member for no benefit the dev-tool placement lacks.
- **A seventh MCP tool** — the natural reading of "let a model help me rate things." Six ADRs
  have declined one before this, and no ground below is shared by all six:
  - ADR 39 refused `get_affinity`: it would spend the tool-count budget on a lookup `get_entity`
    already answers.
  - ADR 40 refused `import_list`: it would hand a model a file path outside the repository and
    make the personal list part of a conversation transcript.
  - ADR 41 refused `export_graph`: the output is a file on a filesystem no model can see.
  - ADR 43 refused `list_affinity`: it is the same bulk read ADR 39 had already declined on ADR
    16's data-minimisation grounds, asked again by a different caller.
  - ADR 44 refused `retract_entity`: it would be the first tool letting a model *remove* what a
    source said, on a surface that deliberately withholds `assert_edge` — a trade deserving its
    own ADR and its own evidence.
  - ADR 45 refused a `recommend` tool on what the question needs: a file of everything the owner
    already knows, or the bulk taste-layer read ADR 39 declined.

  This decision's own ground is the first half of ADR 45's: the input is still ADR 40's file.
- **A terminal deck** — no HTTP at all, and the gesture is a single keypress either way. Refused
  because recognising an entity is the hard part of rating it, and a class list and a route
  rendered as wrapped terminal text reads worse than a card — a card the owner cannot read quickly
  becomes a skip, which defeats the tool.
- **A bulk-import file of `qid,rating` pairs** — moves the writing cost rather than removing it:
  every rating still has to be typed or generated before any of it reaches the table. The deck
  exists specifically to make one keystroke the whole cost of a rating.

## Consequences

- **A candidate's rating is recorded but changes no score, today.** `Deck` deals a recommender
  candidate as roughly every fifth card, and rating one writes a real row to `affinity` — but
  `Recommendations.regardFor` weights only qids on the known-list, and a candidate is by
  definition absent from it. This is accepted rather than fixed here: closing it means deriving
  the known-list from the taste layer itself, which reopens ADR 40 (the seeding list is kept off
  the MCP surface and out of this repository on purpose) and ADR 43 (the bulk read is reserved to
  the owner's own machine). That is its own argument, for its own issue.

  **Amendment (2026-08-29, issue #106): that issue was argued, and the gap is closed —
  [ADR 48](0048-a-high-rating-counts-as-something-you-have.md).** A rating at or above 4 now counts
  as something the owner has, so a candidate rated that highly joins the known-list and its
  connections do reach `Recommendations.regardFor`. This bullet's prediction of the cost was half
  right. It reopens ADR 40 — the file outside the repository is no longer the sole authority for
  `--known`, though it remains the authority for what was *seeded*, and nothing on the MCP surface
  can see either half. It does **not** reopen ADR 43: this amendment's own "`readRatings` is now
  shared with `rate`" section had already widened that read to both dev-side tools, so ADR 48 needed
  no new access and widened no fence. What that ADR deliberately did not build is the other
  direction — a low rating still suppresses nothing, because the same population holds two ratings
  below neutral against 87 above.
- **A rating can be changed but never withdrawn.** There is no un-rate anywhere in segue; going
  back and re-rating is the only correction this tool — or any tool — offers.
- **The taste layer now has two dev-side readers of every rating at once**, `recommend` and
  `rate`, both reached through the same narrowed fence. A third caller needing the same map is the
  signal to revisit whether the fence should widen again or whether the map itself belongs
  somewhere more central; nothing about this decision pre-empts that.
- **`affinity` is still empty the day this lands.** This tool exists to change that, and until it
  is run for real, `Recommendations.regardFor`'s weighting remains demonstrated only against
  invented data, exactly as ADR 45 left it.
