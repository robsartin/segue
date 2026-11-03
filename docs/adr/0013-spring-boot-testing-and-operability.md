---
status: Accepted
date: "2026-08-23"
topic: spring-boot-testing-and-operability
tags: [framework, spring-boot, testing]
supersedes: []
related: [jvm-quality-and-tests, spring-boot-conventions, observability-in-spring-boot]
---
# 13. Spring Boot testing and operability

## Context

A full `@SpringBootTest` for every test is slow and blunt, and a service that ships without
health and metrics endpoints is hard to operate. Spring Boot provides sharper tools for
both; we adopt them deliberately on top of the JVM baseline's layered-test approach.

## Decision

- **Test at the narrowest useful slice.** Prefer plain unit tests (constructor injection
  makes this easy) and Spring **test slices** — `@WebMvcTest`, `@DataJpaTest`,
  `@JsonTest` — over `@SpringBootTest`. Reserve full `@SpringBootTest` for genuine
  end-to-end wiring checks.
- **Integration tests use Testcontainers** for real backing services (per the JVM
  baseline), not in-memory substitutes that behave differently from production.
- **Spring Boot Actuator** is enabled, exposing health and metrics; metrics are published
  via Micrometer.
- **Structured (JSON) logging** with correlation/trace ids so logs are queryable in
  aggregation.

## Alternatives considered

- **`@SpringBootTest` for every test** — simplest to write uniformly, but boots the full context
  each time, making the suite slow — the opposite of what the narrowest-slice rule targets.
- **In-memory substitutes for backing services** — faster than Testcontainers, but the JVM
  baseline already rejects them for behaving differently from production dependencies.
- **Plain text logging** — simpler to read locally, but doesn't support correlation ids or
  queryable aggregation the way structured JSON logging does.

## Consequences

- The test suite stays fast because most tests avoid booting the whole context.
- Integration tests are trustworthy because they run against real services.
- The service is observable and operable from day one (health, metrics, structured logs),
  at the cost of configuring Actuator and log formatting up front.

**Amendment (2026-11-03, issue #372): the operability half of this decision was never built here,
and both deviations were already decided elsewhere — this amendment is the route to them, not a
second copy of them.**

Read on its own this ADR still promises a Spring Boot service with Actuator on. Two of its four
bullets do not describe segue.

- **"Spring Boot Actuator is enabled … metrics published via Micrometer."** Neither holds.
  Actuator is not declared in `build.gradle.kts` or `gradle/libs.versions.toml`, is not on the
  runtime classpath, and no actuator key appears in `src/main/resources/`; no meter registry or
  exporter is declared either. (Micrometer's observation API does arrive transitively, and
  publishes nothing — ADR 17 already says exactly that, which is why this amendment states it the
  same way instead of a different way.) That absence is a decision rather than an omission, and it
  was taken in [ADR 17](0017-observability-in-spring-boot.md)'s 2026-08-26 amendment (issues #28
  and #29), which also names what would reopen it. Its argument is not reproduced here. A second
  copy is what drifts.
- **"Integration tests use Testcontainers … (per the JVM baseline)."** That bullet borrows its
  authority from the JVM baseline, and the JVM baseline no longer says it:
  [ADR 34](0034-jvm-quality-gates-junit-6-spotless-jacoco-archunit.md) supersedes the identical
  clause in ADR 10 with *integration tests exercise real dependencies, not necessarily
  containerised ones*, for reasons ([ADR 24](0024-sqlite-assertion-log.md)'s daemon-free SQLite
  store) that apply to this ADR word for word. There is no Testcontainers dependency in the tree
  and no test references it.

The other two bullets hold. Full `@SpringBootTest` is the exception rather than the default across
the test sources, and structured JSON logging with a correlation id is built — see
[ADR 30](0030-structured-logging.md) and [ADR 29](0029-request-correlation.md).
