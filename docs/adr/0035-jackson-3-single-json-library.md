---
status: Accepted
date: "2026-08-25"
topic: jackson-3-single-json-library
tags: [project, dependencies, mcp, ingest]
supersedes: []
related: [mcp-tool-surface, mcp-protocol-conformance, layering-and-archunit, jvm-build-with-gradle, source-adapter-spi]
---
# 35. Use Jackson 3 as the single JSON library

## Context

Increment 4a left two Jackson major versions on the classpath, and the project used both.

`SegueApplication` and the MCP SDK spoke Jackson 3 (`tools.jackson`, via
`mcp-json-jackson3`). `ToolResults` and the whole Wikidata parsing chain spoke Jackson 2
(`com.fasterxml.jackson`). Nobody decided this; it accumulated, because Jackson 2 was
declared for the Wikidata client in increment 3 and the MCP starter brought its own
Jackson 3 in increment 4a.

The split was not cosmetic. `ToolResults` built the tool surface's JSON with a bare
Jackson 2 `ObjectMapper`, and Jackson 2 keeps `java.time` support in a separate module
that was never on the classpath. `ProvenanceView.assertedAt` is an `Instant`, so
`find_paths` — the only tool that returns provenance, and the payoff feature of the
whole project — threw on every call from a live client while the other four tools
worked. Jackson 3, sitting right next to it, serialises an `Instant` natively.

Two majors also mean two mental models in one codebase: two `ObjectMapper` types, two
exception hierarchies, and per-file knowledge of which import to reach for.

## Decision

- **Jackson 3 (`tools.jackson`) is the only JSON library.** The Wikidata chain and the
  MCP tool surface both use it. Jackson 2's `core`, `databind` and `datatype` packages
  are not used anywhere.
- **An ArchUnit rule enforces it** (`onlyJackson3`), so the split cannot re-accumulate
  the way it did the first time. `com.fasterxml.jackson.annotation` is deliberately
  exempt: Jackson 3 kept its annotations on the old coordinates, so `@JsonValue` is a
  Jackson 3 import despite how it reads.
- **The tool-surface mapper stays unconfigured.** `JsonMapper.builder().build()` — no
  module registration, no `WRITE_DATES_AS_TIMESTAMPS`. Jackson 3 writes ISO-8601 by
  default, and configuration that restates a default is a thing to keep in step later.
- **Jackson's version is not named in the build.** It resolves from the managed BOM,
  per the rule that versions live in `libs.versions.toml` or a BOM and never in prose.

## Alternatives considered

- **Stay on Jackson 2 and add `jackson-datatype-jsr310`.** This is what the fix for the
  bug did, and it works. It also keeps two majors, keeps the `ObjectMapper` ambiguity,
  and leaves the next `java.time` field on the surface one forgotten module away from
  the same failure. It treats the symptom.
- **Keep Jackson 2 for Wikidata and Jackson 3 for MCP, deliberately.** Defensible if
  the two were genuinely independent, but they are not: `ClaimMapper` output flows into
  the view records that `ToolResults` serialises, so a developer moves between the two
  in a single change. The boundary would be a trap, not a seam.
- **Do nothing and document the split.** The cost is paid by whoever hits it next, and
  the first person to hit it lost the project's headline feature to it.

## Consequences

- Jackson 2's `databind` and `core` leave `runtimeClasspath` entirely — nothing else on
  the classpath wanted them; segue was the only reason they were there.
- The JSR-310 module and the mapper configuration added for the bug are both deleted.
  The bug class disappears rather than being configured around.
- **Jackson 3's exceptions are unchecked, and that is a behaviour change, not an import
  change.** `WikidataClient` caught `IOException` around `readTree`; Jackson 2's parse
  failure was an `IOException` and fell into that handler by accident of the type
  hierarchy, while Jackson 3's `JacksonException` would escape it. The client now names
  that case, so callers still get one failure type — `WikidataUnavailableException`,
  which `expand_entity` reports as `sourceUnavailable` — from this adapter. A
  characterisation test pins it.
- `JsonNode.fieldNames()` is `propertyNames()` in Jackson 3, returning a `Collection`
  rather than an `Iterator`. One loop in `ClaimMapper` got simpler.
- Anyone reading a Jackson recipe online has to notice which major it targets; the
  ArchUnit rule is what catches the mistake if they do not.
