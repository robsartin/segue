---
status: Accepted
date: "2026-08-24"
topic: jvm-quality-gates-junit-6-spotless-jacoco-archunit
tags: [language, jvm, testing]
supersedes: [jvm-quality-and-tests]
related: [jvm-quality-and-tests]
---
# 34. JVM quality gates: JUnit 6, Spotless, JaCoCo, ArchUnit

## Context

ADR 10 set the JVM quality baseline from the shared toolkit pack, written before this
repository existed. Two of its specifics do not survive contact with segue.

**It specifies JUnit 5.** JUnit 6.1.3 is the current release, its baseline is Java 17
and this project targets 21, and `archunit-junit6` exists — so the architecture tests
ADR 10 itself calls for run on it. Starting a new codebase on the previous major
version would be a deliberate choice to begin behind.

**It specifies Testcontainers for integration tests, giving Postgres as the example.**
ADR 24 chose SQLite for the assertion log precisely so an MCP server launched as a
subprocess needs no running daemon. Integration tests therefore exercise a real SQLite
file in a temp directory. That satisfies ADR 10's actual intent — exercise real
dependencies, not mocks — while the container it names has nothing left to hold.

ADRs are immutable, so this supersedes rather than edits.

## Decision

Configured in Gradle and run in CI:

- **Formatting** via **Spotless 8.10.0** with **google-java-format 1.36.1**, failing
  the build on violations.
- **Testing with JUnit 6.1.3** and AssertJ 3.27.7. Fast unit tests are the default
  source set.
- **Integration tests exercise real dependencies, not necessarily containerised ones.**
  SQLite runs against a temp file; the Wikidata adapter runs against a stub server on
  the JDK's own `HttpServer`. Testcontainers is available if a future dependency needs
  it, and is not required where a real dependency is already cheap to start.
- **One live smoke test against real Wikidata**, tagged and excluded from CI. Recorded
  fixtures cannot detect an upstream API change — they pass forever against a dead
  endpoint. This is the positive control.
- **Coverage** via **JaCoCo 0.8.15**, holding ADR 10's thresholds unchanged:
  **line/instruction > 80%, branch > 65%**, failing the build below them.
- **Architecture tests** via **ArchUnit 1.5.0** (`archunit-junit6`), enforcing the
  rules ADR 32 enumerates and failing the build on violation.

Everything ADR 10 decided beyond these points carries over unchanged.

## Alternatives considered

- **Stay on JUnit 5 for consistency with the toolkit pack** — one less deviation to
  explain, and it starts a new codebase a major version behind for no benefit, with a
  migration to schedule later.
- **Adopt Testcontainers anyway, running SQLite or a throwaway Postgres in a container**
  — restores literal compliance with ADR 10, and it reintroduces the Docker dependency
  ADR 24 chose SQLite to avoid, slowing the test loop to satisfy a word rather than an intent.
- **Amend ADR 10 in place** — simpler to read, and it breaks the immutability rule ADR 1
  establishes, erasing the fact that the baseline said something different and that the
  difference was considered.
- **Lower the coverage thresholds for a young codebase** — easier to stay green early,
  and thresholds that move when inconvenient stop being gates.

## Consequences

- The architecture tests ADR 10 asked for can actually run, since `archunit-junit6`
  pairs with the chosen test framework.
- Integration tests start in milliseconds and need no daemon, which keeps them in the
  ordinary loop rather than behind a flag people stop setting.
- **Testcontainers is not wired up.** A future dependency that genuinely needs a
  container (a real Postgres, a message broker) has to add it, and this ADR does not
  stand in the way.
- The live Wikidata test can fail for reasons unrelated to our code, which is why it is
  excluded from CI and run deliberately.
- This repository now differs from the toolkit baseline in a recorded way, so a future
  regeneration of the pack ADRs will not silently reintroduce JUnit 5.

---

*Correction, 2026-08-24: "enforcing the rules ADR 32 enumerates" reads as
claiming full enforcement already exists. As of increment 0, not all of
them are — see ADR 32's own Status column for which rules are enforced,
partially enforced, or arrive with a later increment's packages, rather
than restating the breakdown here. The gate configuration described here
(JUnit 6, Spotless, JaCoCo, ArchUnit) is unaffected.*
