---
status: Accepted
date: "2026-08-24"
topic: layering-and-archunit
tags: [project, architecture, testing]
supersedes: []
related: [assertion-log-source-of-truth, source-adapter-spi, mcp-transports, jvm-quality-and-tests, six-kind-ontology]
---
# 32. Enforce the layering with ArchUnit

## Context

Several decisions in this repository are invariants rather than preferences: only the
ingest layer may write, the domain carries no third-party dependencies, nothing writes
to stdout, adapters do not know about each other. Each is one careless import away from
being violated, and each violation is quiet — the code still compiles, the tests still
pass, and the property is simply gone.

ADR 5 makes CI the merge gate and ADR 10 calls for architecture tests. This decision says
which invariants are mechanically defended and admits which are not.

## Decision

Layering:

```
domain  <- port <- adapters (tinker, jena, sqlite, wikidata)
                <- ingest <- mcp <- app
```

`app` is the only package permitted to depend on everything, because wiring is its job.

Rules enforced by ArchUnit, each naming the decision it defends:

| Rule | Defends | Status |
|---|---|---|
| Only `ingest` calls `GraphStore.record` or `AssertionLog.append` | ADR 19 | arrives with `ingest` |
| Nothing in `src/main` references `System.out` | ADR 28 | enforced |
| No `System.err.println`, `Throwable.printStackTrace()`, or `java.util.logging`; SLF4J only | ADR 30 | enforced |
| Adapters depend on `port` and `domain` only, never each other or upward | this ADR | partially enforced — the sibling (`tinker`/`jena` don't depend on each other) and upward (adapters don't depend on `ingest`/`mcp`/`app`) halves are written; the "port and domain only" downward restriction is not |
| `domain` depends on nothing outside `java.*` and itself | ADR 18 | enforced |
| Domain types are records, enums or sealed | ADR 11 | enforced |
| `wikidata` must not depend on any Spring package | ADR 25 | arrives with `wikidata` |
| Spring annotations only in `app` and `mcp` | ADR 12 | arrives with `app`/`mcp` |
| Tool names match MCP's charset and length rules | ADR 26 | arrives with `mcp` |

`NodeKind.values().length == 6` (ADR 21) is a plain unit test rather than an ArchUnit rule,
but belongs to the same set.

**Invariants that resist mechanical checking are named, not pretended away:** ADR 20's
separation of valid time from assertion time, ADR 23's confidence conventions, and ADR 16's
rule that affinity notes never reach a log. These stay review concerns.

## Alternatives considered

- **Code review alone** — no tooling to maintain, and it depends on a reviewer remembering
  nine invariants on every PR, which is exactly the failure mode enforcement exists for.
- **Java modules (JPMS)** — compiler-enforced and stronger than a test, and it cannot express
  the rules that matter most here, which are about specific method calls and annotations
  rather than package exports.
- **Separate Gradle modules per layer** — genuinely enforces the dependency direction at
  build time, and it is a large restructuring whose benefit over ArchUnit is mostly the
  subset of rules ArchUnit already covers. Worth revisiting if the codebase grows.
- **Enforcing everything, including the untestable** — appealing, and it produces brittle
  rules that approximate an invariant and then fail on unrelated changes, which teaches
  people to weaken them.

## Consequences

- The invariants fail the build rather than eroding, and each rule cites its ADR, so a
  failure explains itself.
- New packages must be placed deliberately, because a misplacement fails immediately.
- The rules need maintenance as the codebase moves; a rule nobody updates gets deleted
  rather than weakened.
- Three invariants are explicitly unguarded, which is the honest position and is written
  down so nobody assumes otherwise.

---

*Correction, 2026-08-24: the rules table originally presented all nine rows as
"enforced by ArchUnit" without qualification. As of increment 0, four rows
(ADR 19, ADR 25, ADR 12, ADR 26) cannot be enforced yet because their packages
(`ingest`, `wikidata`, `app`, `mcp`) do not exist, and the adapter-layering row
only has its sibling and upward halves written, not the downward "port and
domain only" restriction. Added a Status column recording this. The decisions
are unaffected; the packages' rules arrive with the increments that add them.*
