---
status: Accepted
date: "2026-08-24"
topic: source-adapter-spi
tags: [project, ingest, extensibility]
supersedes: []
related: [wikidata-identity-and-vocabulary, assertion-log-source-of-truth, quarantine-model-generated-assertions, layering-and-archunit]
---
# 25. Split ingest into a SourceAdapter and an EntityResolver SPI

## Context

`CLAUDE.md` specifies a single `SourceAdapter` SPI with `id()`, `supports(kind)` and
`expand(seed, ctx)`, under the design rule that adding a source must not require
touching the graph layer.

Writing out the MCP tool flows shows that two of the six tools — `search_entities`
and `add_entity` — need something that SPI does not offer: resolving a free-text
query to a candidate entity, and fetching one entity by identifier. Folding those
methods into `SourceAdapter` would force every future source to implement them, and
a statistical similarity source such as last.fm has nothing to resolve. It expands
and nothing else.

## Decision

- **Two ports, not one.** `SourceAdapter` keeps exactly the shape `CLAUDE.md`
  specifies. `EntityResolver` adds `search(query, kind, limit)` and `fetch(qid)`.
- **A source implements whichever it can honour.** Wikidata implements both; a
  similarity source implements only `SourceAdapter`.
- **Both live in `port`**, alongside `GraphStore`, because both are seams rather than
  implementations.
- **Adapters emit assertions and know nothing about storage.** They never see
  `GraphStore` or `AssertionLog`; `IngestService` is the only writer.
- **Adapters are plain Java with no Spring dependency**, constructed by `@Bean`
  methods in `app`. ArchUnit enforces this.

## Alternatives considered

- **One SPI with optional resolution methods** — fewer types, at the cost of every
  source implementing methods it cannot honour, or throwing `UnsupportedOperation`,
  which turns a compile-time distinction into a runtime surprise.
- **Resolution as a concern of the MCP layer** — would keep the SPI as specified, but
  puts Wikidata-specific knowledge in `mcp` and breaks the rule that adding a source
  touches only the adapter.
- **A capability-flags interface (`canResolve()`)** — expressive, and it reintroduces
  runtime checks for something the type system already models with two interfaces.

## Consequences

- Adding a source is implementing one or both interfaces plus a `@Bean` method. No
  graph, storage or MCP code changes.
- The fixture-backed test adapter implements the same ports, so the SPI is exercised
  by more than one implementation from the start.
- `wikidata` is testable against a stub HTTP server with no application context.
- Two interfaces to keep coherent instead of one, and a source that implements both
  must keep its `id()` consistent across them, since provenance is keyed on it.

**Amendment (2026-08-30, issue #91): the first consequence above is optimistic, and a second source
has now measured by how much.**

Nothing above is withdrawn, no decision changes and no sentence above is edited. The two-port split
is confirmed by the exercise that could have refuted it:
[ADR 54](0054-musicbrainz-as-the-second-source.md) records a second production source ingesting with
**no change to `domain`, `port`, `tinker`, `jena` or `ingest`** — and none to `mcp/SegueService`
either, which iterates the adapters and was not asked to. The seven production files the branch
touches are enumerated there. *"No graph, storage or MCP code changes"* held exactly as written.

**What did not hold is the sentence before it.** *"Adding a source is implementing one or both
interfaces plus a `@Bean` method"* describes the compile-time seam and nothing else, and the rest of
the cost is neither small nor discoverable. Measured on this branch, and enumerated rather than
totalled in ADR 54: eight `ArchitectureTest` rule changes (five bodies changed, two widened and
renamed, one new, taking the file from 35 rules to 36); an MBID-to-QID identity bridge that fits in
neither adapter package and had to be placed in `app` under ADR 32's one permitting sentence; a
complete HTTP client, because `WikidataClient` lives in `wikidata` and ADR 32 forbids reaching for
it, and because MusicBrainz needs a proactive rate throttle where Wikidata needs reactive backoff;
and a decision about `ExpandContext`'s single bound, which `SegueService` now shares between two
adapters and spends in list order.

**The reason this is worth an amendment rather than a note is the failure mode.** Those
architecture rules name adapter packages as literal strings, so a new adapter package inherits none
of them and **nothing goes red to say so**. One of the five is
`theWorldFactLayerNeverTouchesAffinity`, which is ADR 33's privacy fence in a public repository. It
passed for as long as the new package existed unnamed by it, because it was not looking. A reader
who takes this ADR's first consequence at face value will not go looking either, which is what this
amendment exists to prevent.

**What it does not change.** The split itself, the rule that a source implements whichever port it
can honour, that adapters emit assertions and never see a store, and that adapters are plain Java
constructed by `@Bean` methods in `app` — all four were exercised by MusicBrainz and all four stand.
The correction is to the *cost*, not to the design.
