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
