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

Invariants this decision commits to defending mechanically, each naming the decision it
defends:

| Invariant | Defends | Status |
|---|---|---|
| Only `ingest` writes a claim — `GraphStore.record`, `GraphStore.upsertNode` and `AssertionLog.append` | ADR 19 | enforced |
| Nothing in `src/main` references `System.out` | ADR 28 | enforced |
| No `System.err.println`, `Throwable.printStackTrace()`, or `java.util.logging`; SLF4J only | ADR 30 | enforced |
| Adapters depend on `port` and `domain` only, never each other or upward | this ADR | partially enforced — the sibling (`tinker`/`jena` don't depend on each other) and upward (adapters don't depend on `ingest`/`mcp`/`app`) halves are written; the "port and domain only" downward restriction is not |
| `domain` depends on nothing outside `java.*` and itself | ADR 18 | enforced |
| Domain types are records, enums or sealed | ADR 11 | enforced |
| `wikidata` must not depend on any Spring package | ADR 25 | enforced |
| Spring annotations only in `app` and `mcp` | ADR 12 | enforced |

**`ArchitectureTest` is the list, not this table.** `src/test/java/com/robsartin/segue/arch/ArchitectureTest.java`
holds every rule and is the authority on which exist; the table above records which
*decisions* this ADR undertakes to defend, and one of them may be spread across several
rules (adapter siblinghood is one row here and four rules there). Rules have been added
without amending this ADR before and will be again, so read the test file for the roster
and read this table for the intent. Each rule's Javadoc names its ADR, which is the link
back.

Three checks belong to the same set and are deliberately not ArchUnit rules, because
ArchUnit's structural rules read types and dependencies rather than values:
`NodeKind.values().length == 6` (ADR 21) is a plain unit test, and MCP's tool-name charset
and length rules (ADR 26) are checked by reflection over the `@McpTool` annotations in
`ToolSurfaceTest` — annotation *attribute values* are not what a structural rule can see.
ADR 16's rule that a rating or note never reaches a log line is likewise a test rather than
a rule (`AffinityIsNeverLoggedTest`), and for the same reason: it is about what a log event
carries, not about who depends on whom.

**Invariants that resist mechanical checking are named, not pretended away:** ADR 20's
separation of valid time from assertion time, and ADR 23's confidence conventions. These
stay review concerns.

## Alternatives considered

- **Code review alone** — no tooling to maintain, and it depends on a reviewer remembering
  every invariant in the table above on every PR, which is exactly the failure mode
  enforcement exists for.
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
- The invariants that are not machine-checked are named rather than assumed, which is the
  honest position and is written down so nobody credits a rule that does not exist.

---

*Correction, 2026-08-24: the rules table originally presented all nine rows as
"enforced by ArchUnit" without qualification. As of increment 0, four rows
(ADR 19, ADR 25, ADR 12, ADR 26) cannot be enforced yet because their packages
(`ingest`, `wikidata`, `app`, `mcp`) do not exist, and the adapter-layering row
only has its sibling and upward halves written, not the downward "port and
domain only" restriction. Added a Status column recording this. The decisions
are unaffected; the packages' rules arrive with the increments that add them.*

*Correction, 2026-08-25 (issues #44 and #46): the rules table had drifted from the test file in four
ways, none of them a change of decision. (1) Row 1 credited the ADR 19 rule with covering
`AssertionLog.append` when `onlyIngestAppliesClaimsToTheGraph` matched `GraphStore.record` alone —
documented, believed and unenforced. Issue #44 widened the rule to all three writes rather than
narrowing this row, because ADR 19 is right: a graph write that skipped the log would be gone at the
next boot. The row now also names `GraphStore.upsertNode`, which was unguarded and unmentioned.
(2) Four rows still read "arrives with `ingest`/`wikidata`/`app`/`mcp`"; all four packages exist and
all four rules are written, so their status is now "enforced". (3) The row claiming ArchUnit checks
MCP tool names was wrong about the mechanism — `ToolSurfaceTest` checks them by reflection, as ADR
26's own amendment already recorded — so it has moved out of the table into the paragraph about
value-shaped checks, alongside the ADR 16 logging check that the "resists mechanical checking"
sentence had also outlived. (4) The table was written as a complete list of ArchUnit rules and had
not stayed one; it now records the decisions defended and points at `ArchitectureTest` for the
roster, so the next rule added does not silently make this ADR false again.*

*Amendment (2026-09-02, issue #165): the roster the correction above points at — `ArchitectureTest`'s
`DEV_TOOL_PACKAGES` and `ADAPTER_PACKAGES` — was itself the only source of truth for every sibling
fence that reads it, and a package neither constant named was fenced by nothing. Issue #165 measured
it: an eighth dev tool planted under `src/main`, reaching `export`, `recommend` and `IngestService`,
left every one of `ArchitectureTest`'s 36 rules green.*

*Both sets are now derived from the tree rather than typed by hand, and the constants are checked
against the derivation instead of being it. The dev-tool set is derived two ways: the `mainClass`
package of every `JavaExec` registration in `build.gradle.kts`, parsed strictly enough that an
unrecognised registration form or a non-literal `mainClass` reds naming the line rather than being
silently skipped, and the packages of every class named `*Cli` that declares `main`, read from
ArchUnit's imported class graph. The adapter set is derived one way, also from the class graph: the
packages of every class assignable to an interface in `port`, minus `port` itself. `PackageListsTest`
asserts `DEV_TOOL_PACKAGES` and `ADAPTER_PACKAGES` equal their derivations in both directions — a
package the tree has that a constant does not name reds, and a constant naming a package the tree no
longer has reds too. `build.gradle.kts` is now a declared input of the `test` task, so an edit to a
`mainClass` line cannot go unseen by the check that reads it.*

*The constants stay: they remain the readable list this ADR's table, twenty other javadocs, and the
developer guide cite, and adding or removing an entry is still a deliberate one-line edit. What
changed is which side of the equality is authoritative.*

*One exception is deliberately not derived. `otherDevToolsAnd`'s `rate → recommend` allowance (ADR
46) says which sibling tools may see each other; that is a decision about the tools, not a fact the
tree states, so it stays hand-written rather than derived.*

*Three alternatives were rejected. Deriving the sets and dropping the constants would turn the list
twenty javadocs and the guide cite into a computed value nobody can read without running a test, and
would let a predicate that stops matching silently un-fence everything rather than red. A single
dev-tool signal — the Gradle registrations alone, or the `*Cli` classes alone — is sufficient today,
but discards the disagreement between the two as information: a tool with a task and no `*Cli`, or a
`*Cli` nobody can run, is itself a finding a single signal cannot surface. Grepping `src/main`'s text
for patterns like `implements GraphStore` was rejected as the same parser hole this repository keeps
finding elsewhere — ArchUnit already holds the typed class graph, so the derivation reads that
instead of source text.*
