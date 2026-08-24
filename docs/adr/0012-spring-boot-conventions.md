---
status: Accepted
date: "2026-08-23"
topic: spring-boot-conventions
tags: [framework, spring-boot]
supersedes: []
related: [jvm-build-with-gradle, jvm-quality-and-tests, spring-boot-testing-and-operability]
---
# 12. Spring Boot application conventions

## Context

Spring Boot offers several ways to do most things — wire dependencies, bind configuration,
select environment-specific behavior. Left unconstrained, a codebase drifts into a mix of
styles that are hard to test and reason about. We pin a consistent set on top of the JVM
baseline.

## Decision

- **Constructor injection only.** No field or setter injection — dependencies are
  explicit, final, and the class is testable without the container.
- **Typed configuration** via `@ConfigurationProperties` bound to immutable records, not
  scattered `@Value` lookups. Configuration is validated at startup.
- **Profiles** (`application-<profile>.yml`) carry environment-specific settings; code does
  not branch on the environment.
- **Layered structure** with clear boundaries (web / service / persistence), enforced by
  the JVM baseline's architecture tests.
- Auto-configuration is preferred over hand-wiring; custom `@Bean` definitions are the
  exception, not the default.

## Alternatives considered

- **Field or setter injection (`@Autowired` on fields)** — less boilerplate, but hides required
  dependencies, allows partially-constructed objects, and blocks unit testing without Spring.
- **Scattered `@Value` lookups** — quick for a single setting, but string-keyed and unvalidated,
  unlike typed, startup-validated `@ConfigurationProperties` records.
- **Environment checks in code (`if (env == "prod")`)** — simple at first, but branches
  accumulate and drift from the profile mechanism Spring already provides for this.

## Consequences

- Components are unit-testable without starting Spring, because dependencies arrive through
  the constructor.
- Configuration is discoverable, typed, and validated rather than resolved by string keys
  at scattered call sites.
- The team follows one wiring/config style, so Spring code reads consistently.
- A class with many collaborators grows a correspondingly large constructor, surfacing the
  too-many-responsibilities signal rather than hiding it behind field injection.
