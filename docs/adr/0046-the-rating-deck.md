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
exactly against `{127.0.0.1, localhost, ::1}`; it does not compare the raw header text with
`String.startsWith`. `"http://127.0.0.1.evil.com"` *starts with* the allowed origin
`"http://127.0.0.1"` as a string, while naming a completely different host — a `startsWith` check
would have let it through. The literal string `"null"` — what a browser sends as the `Origin` of a
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
- **A rating can be changed but never withdrawn.** There is no un-rate anywhere in segue; going
  back and re-rating is the only correction this tool — or any tool — offers.
- **The taste layer now has two dev-side readers of every rating at once**, `recommend` and
  `rate`, both reached through the same narrowed fence. A third caller needing the same map is the
  signal to revisit whether the fence should widen again or whether the map itself belongs
  somewhere more central; nothing about this decision pre-empts that.
- **`affinity` is still empty the day this lands.** This tool exists to change that, and until it
  is run for real, `Recommendations.regardFor`'s weighting remains demonstrated only against
  invented data, exactly as ADR 45 left it.
