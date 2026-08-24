# Architecture Decision Records

## Universal

- [1. Record architecture decisions with ADRs](0001-record-architecture-decisions.md) — _Accepted_
  Architecturally significant decisions — choices that shape structure, dependencies, interfaces, or the way the team works — need a durable record.
  Related: [6. Keep developer and user documentation current](0006-keep-documentation-current.md)
- [2. Develop with Test-Driven Development](0002-use-test-driven-development.md) — _Accepted_
  We want a fast feedback loop, a regression safety net, executable documentation of behavior, and the freedom to refactor without fear.
- [3. Integrate via a PR-based trunk workflow](0003-pr-based-trunk-workflow.md) — _Accepted_
  We want `main` to stay releasable at all times, changes to be reviewable in coherent units, and history to be legible.
  Related: [4. Use the Mikado Method to keep the build green](0004-mikado-method-for-changes.md), [5. Make CI the merge gate](0005-ci-is-the-merge-gate.md), [6. Keep developer and user documentation current](0006-keep-documentation-current.md)
- [4. Use the Mikado Method to keep the build green](0004-mikado-method-for-changes.md) — _Accepted_
  Large refactorings, and changes that ripple across a codebase, tempt us into long stretches where nothing compiles and nothing is committable.
  Related: [3. Integrate via a PR-based trunk workflow](0003-pr-based-trunk-workflow.md)
- [5. Make CI the merge gate](0005-ci-is-the-merge-gate.md) — _Accepted_
  Standards that are not enforced erode.
  Related: [10. Enforce JVM quality gates and layered tests](0010-jvm-quality-and-tests.md)
- [6. Keep developer and user documentation current](0006-keep-documentation-current.md) — _Accepted_
  Documentation that lags the code is worse than none — it misleads.
  Related: [1. Record architecture decisions with ADRs](0001-record-architecture-decisions.md), [3. Integrate via a PR-based trunk workflow](0003-pr-based-trunk-workflow.md)
- [7. Declare an explicit license and copyright](0007-license-and-copyright.md) — _Accepted_
  A repository with no license is "all rights reserved" by default — others (and future us) have no clear terms for use, and intent is ambiguous.
- [8. Maintain a security baseline](0008-security-baseline.md) — _Accepted_
  Secrets committed to a repository are effectively public and permanent — history preserves them even after deletion.
  Related: [16. Privacy and data handling](0016-privacy-and-data-handling.md), [14. Backend service conventions](0014-service-conventions.md)

## Language

- [9. Build JVM projects with Gradle](0009-jvm-build-with-gradle.md) — _Accepted_
  JVM projects need a consistent build tool, dependency management, and package organization so repositories are predictable to build and navigate, and so shared tooling (formatting, coverage, arch tests) can be applied the same way everywhere.
  Related: [10. Enforce JVM quality gates and layered tests](0010-jvm-quality-and-tests.md), [11. Java language conventions](0011-java-conventions.md)
- [10. Enforce JVM quality gates and layered tests](0010-jvm-quality-and-tests.md) — _Superseded by 0034_
  The universal CI-gate decision requires enforced formatting, tests, and coverage, and this project's baseline also calls for architecture tests and real-dependency integration tests.
  Related: [9. Build JVM projects with Gradle](0009-jvm-build-with-gradle.md), [11. Java language conventions](0011-java-conventions.md), [5. Make CI the merge gate](0005-ci-is-the-merge-gate.md), [34. JVM quality gates: JUnit 6, Spotless, JaCoCo, ArchUnit](0034-jvm-quality-gates-junit-6-spotless-jacoco-archunit.md)
- [11. Java language conventions](0011-java-conventions.md) — _Accepted_
  Java builds on the shared JVM baseline (Gradle, Spotless, JaCoCo, layered tests) and needs its language level and formatting standard pinned so Java repositories are consistent.
  Related: [9. Build JVM projects with Gradle](0009-jvm-build-with-gradle.md), [10. Enforce JVM quality gates and layered tests](0010-jvm-quality-and-tests.md)
- [34. JVM quality gates: JUnit 6, Spotless, JaCoCo, ArchUnit](0034-jvm-quality-gates-junit-6-spotless-jacoco-archunit.md) — _Accepted_
  ADR 10 set the JVM quality baseline from the shared toolkit pack, written before this repository existed.
  Related: [10. Enforce JVM quality gates and layered tests](0010-jvm-quality-and-tests.md)

## Framework

- [12. Spring Boot application conventions](0012-spring-boot-conventions.md) — _Accepted_
  Spring Boot offers several ways to do most things — wire dependencies, bind configuration, select environment-specific behavior.
  Related: [9. Build JVM projects with Gradle](0009-jvm-build-with-gradle.md), [10. Enforce JVM quality gates and layered tests](0010-jvm-quality-and-tests.md), [13. Spring Boot testing and operability](0013-spring-boot-testing-and-operability.md)
- [13. Spring Boot testing and operability](0013-spring-boot-testing-and-operability.md) — _Accepted_
  A full `@SpringBootTest` for every test is slow and blunt, and a service that ships without health and metrics endpoints is hard to operate.
  Related: [10. Enforce JVM quality gates and layered tests](0010-jvm-quality-and-tests.md), [12. Spring Boot application conventions](0012-spring-boot-conventions.md), [17. Observability in Spring Boot](0017-observability-in-spring-boot.md)

## App shape

- [14. Backend service conventions](0014-service-conventions.md) — _Accepted_
  A long-running backend service has to be configurable across environments, observable, safely deployable, and evolvable without breaking clients.
  Related: [8. Maintain a security baseline](0008-security-baseline.md), [15. Observability baseline](0015-observability-baseline.md)

## Concern

- [15. Observability baseline](0015-observability-baseline.md) — _Accepted_
  When something goes wrong in production, we need to understand it without redeploying to add print statements.
  Related: [17. Observability in Spring Boot](0017-observability-in-spring-boot.md), [16. Privacy and data handling](0016-privacy-and-data-handling.md)
- [16. Privacy and data handling](0016-privacy-and-data-handling.md) — _Accepted_
  Handling personal data carries legal and ethical obligations, and the cheapest way to reduce risk is to hold less data and handle it deliberately.
  Related: [15. Observability baseline](0015-observability-baseline.md), [8. Maintain a security baseline](0008-security-baseline.md)

## Interaction

- [17. Observability in Spring Boot](0017-observability-in-spring-boot.md) — _Accepted_
  The observability baseline (structured logs, metrics, OpenTelemetry tracing, correlation) needs concrete Spring Boot wiring.
  Related: [15. Observability baseline](0015-observability-baseline.md), [13. Spring Boot testing and operability](0013-spring-boot-testing-and-operability.md)

## Uncategorized

- [18. Use Gremlin/TinkerPop as the graph engine](0018-graph-engine-gremlin.md) — _Accepted_
  Segue is a provenance-first affinity graph whose payoff feature is a citable explanation: given two entities, return the route between them with every hop attributable to a source.
  Related: [19. Make the append-only assertion log the source of truth](0019-assertion-log-source-of-truth.md), [20. Keep valid time and assertion time independent](0020-bitemporal-time-model.md)
- [19. Make the append-only assertion log the source of truth](0019-assertion-log-source-of-truth.md) — _Accepted_
  Segue records what *sources say*, not what is true.
  Related: [18. Use Gremlin/TinkerPop as the graph engine](0018-graph-engine-gremlin.md), [20. Keep valid time and assertion time independent](0020-bitemporal-time-model.md), [23. Quarantine model-generated assertions until corroborated](0023-quarantine-model-generated-assertions.md)
- [20. Keep valid time and assertion time independent](0020-bitemporal-time-model.md) — _Accepted_
  "Blixa Bargeld was a Bad Seed from 1983 to 2003" and "we learned that on 3 March 2026" are different facts, and conflating them produces questions the system cannot answer.
  Related: [19. Make the append-only assertion log the source of truth](0019-assertion-log-source-of-truth.md), [18. Use Gremlin/TinkerPop as the graph engine](0018-graph-engine-gremlin.md)
- [21. Model six node kinds; express roles as edges](0021-six-kind-ontology.md) — _Accepted_
  Segue spans any domain — music, film, literature, speakers, places, ideas.
  Related: [22. Anchor identity and vocabulary to Wikidata](0022-wikidata-identity-and-vocabulary.md), [19. Make the append-only assertion log the source of truth](0019-assertion-log-source-of-truth.md)
- [22. Anchor identity and vocabulary to Wikidata](0022-wikidata-identity-and-vocabulary.md) — _Accepted_
  A cross-domain graph needs one answer to "is this the same thing" that works across music, film and literature at once.
  Related: [21. Model six node kinds; express roles as edges](0021-six-kind-ontology.md), [19. Make the append-only assertion log the source of truth](0019-assertion-log-source-of-truth.md), [18. Use Gremlin/TinkerPop as the graph engine](0018-graph-engine-gremlin.md)
- [23. Quarantine model-generated assertions until corroborated](0023-quarantine-model-generated-assertions.md) — _Accepted_
  A language model is very good at proposing plausible connections between entities and has no way to distinguish the ones it knows from the ones it has constructed.
  Related: [19. Make the append-only assertion log the source of truth](0019-assertion-log-source-of-truth.md), [20. Keep valid time and assertion time independent](0020-bitemporal-time-model.md), [18. Use Gremlin/TinkerPop as the graph engine](0018-graph-engine-gremlin.md)
- [24. Persist the assertion log in SQLite and project the graph at boot](0024-sqlite-assertion-log.md) — _Accepted_
  ADR 19 makes the append-only assertion log the source of truth and the graph a derived projection, but slice 0 held both in memory.
  Related: [19. Make the append-only assertion log the source of truth](0019-assertion-log-source-of-truth.md), [18. Use Gremlin/TinkerPop as the graph engine](0018-graph-engine-gremlin.md), [33. Keep the taste layer separate from the world-facts layer](0033-taste-layer-separation.md)
- [25. Split ingest into a SourceAdapter and an EntityResolver SPI](0025-source-adapter-spi.md) — _Accepted_
  `CLAUDE.md` specifies a single `SourceAdapter` SPI with `id()`, `supports(kind)` and `expand(seed, ctx)`, under the design rule that adding a source must not require touching the graph layer.
  Related: [22. Anchor identity and vocabulary to Wikidata](0022-wikidata-identity-and-vocabulary.md), [19. Make the append-only assertion log the source of truth](0019-assertion-log-source-of-truth.md), [23. Quarantine model-generated assertions until corroborated](0023-quarantine-model-generated-assertions.md), [32. Enforce the layering with ArchUnit](0032-layering-and-archunit.md)
- [26. Expose six MCP tools, and hold back assert_edge](0026-mcp-tool-surface.md) — _Accepted_
  The open risk this project is built to test is whether MCP is a pleasant *authoring* interface or whether a UI is wanted within ten minutes.
  Related: [27. Pin the MCP protocol revision and follow its error conventions](0027-mcp-protocol-conformance.md), [28. Ship both transports, and keep stdout for the protocol alone](0028-mcp-transports.md), [33. Keep the taste layer separate from the world-facts layer](0033-taste-layer-separation.md), [23. Quarantine model-generated assertions until corroborated](0023-quarantine-model-generated-assertions.md), [31. Rank paths by weakest confidence, not by hop count](0031-path-ranking-by-confidence.md)
- [27. Pin the MCP protocol revision and follow its error conventions](0027-mcp-protocol-conformance.md) — _Accepted_
  The MCP specification is moving quickly and the Java tooling lags it.
  Related: [26. Expose six MCP tools, and hold back assert_edge](0026-mcp-tool-surface.md), [28. Ship both transports, and keep stdout for the protocol alone](0028-mcp-transports.md), [29. Correlate every request with a UUIDv7 and W3C Trace Context](0029-request-correlation.md)
- [28. Ship both transports, and keep stdout for the protocol alone](0028-mcp-transports.md) — _Accepted_
  A local MCP server is normally launched as a subprocess by its client and speaks over standard streams.
  Related: [27. Pin the MCP protocol revision and follow its error conventions](0027-mcp-protocol-conformance.md), [30. Emit structured logs to stderr](0030-structured-logging.md), [26. Expose six MCP tools, and hold back assert_edge](0026-mcp-tool-surface.md), [16. Privacy and data handling](0016-privacy-and-data-handling.md)
- [29. Correlate every request with a UUIDv7 and W3C Trace Context](0029-request-correlation.md) — _Accepted_
  When a tool call goes wrong, the failure surfaces inside a conversation.
  Related: [30. Emit structured logs to stderr](0030-structured-logging.md), [27. Pin the MCP protocol revision and follow its error conventions](0027-mcp-protocol-conformance.md), [15. Observability baseline](0015-observability-baseline.md), [28. Ship both transports, and keep stdout for the protocol alone](0028-mcp-transports.md)
- [30. Emit structured logs to stderr](0030-structured-logging.md) — _Accepted_
  ADR 15 commits to an observability baseline, and ADR 28 establishes that stdout belongs to the MCP protocol and nothing else.
  Related: [29. Correlate every request with a UUIDv7 and W3C Trace Context](0029-request-correlation.md), [28. Ship both transports, and keep stdout for the protocol alone](0028-mcp-transports.md), [15. Observability baseline](0015-observability-baseline.md), [16. Privacy and data handling](0016-privacy-and-data-handling.md)
- [31. Rank paths by weakest confidence, not by hop count](0031-path-ranking-by-confidence.md) — _Accepted_
  ADR 23 records this as a known open issue.
  Related: [23. Quarantine model-generated assertions until corroborated](0023-quarantine-model-generated-assertions.md), [18. Use Gremlin/TinkerPop as the graph engine](0018-graph-engine-gremlin.md), [26. Expose six MCP tools, and hold back assert_edge](0026-mcp-tool-surface.md)
- [32. Enforce the layering with ArchUnit](0032-layering-and-archunit.md) — _Accepted_
  Several decisions in this repository are invariants rather than preferences: only the ingest layer may write, the domain carries no third-party dependencies, nothing writes to stdout, adapters do not know about each other.
  Related: [19. Make the append-only assertion log the source of truth](0019-assertion-log-source-of-truth.md), [25. Split ingest into a SourceAdapter and an EntityResolver SPI](0025-source-adapter-spi.md), [28. Ship both transports, and keep stdout for the protocol alone](0028-mcp-transports.md), [10. Enforce JVM quality gates and layered tests](0010-jvm-quality-and-tests.md), [21. Model six node kinds; express roles as edges](0021-six-kind-ontology.md)
- [33. Keep the taste layer separate from the world-facts layer](0033-taste-layer-separation.md) — _Accepted_
  Segue holds two kinds of claim that look superficially alike and behave nothing alike.
  Related: [19. Make the append-only assertion log the source of truth](0019-assertion-log-source-of-truth.md), [24. Persist the assertion log in SQLite and project the graph at boot](0024-sqlite-assertion-log.md), [26. Expose six MCP tools, and hold back assert_edge](0026-mcp-tool-surface.md), [16. Privacy and data handling](0016-privacy-and-data-handling.md), [20. Keep valid time and assertion time independent](0020-bitemporal-time-model.md)
