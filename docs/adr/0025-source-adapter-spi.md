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
