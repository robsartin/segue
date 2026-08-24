---
status: Accepted
date: "2026-08-23"
topic: observability-in-spring-boot
tags: [interaction, observability, spring-boot]
supersedes: []
related: [observability-baseline, spring-boot-testing-and-operability]
---
# 17. Observability in Spring Boot

## Context

The observability baseline (structured logs, metrics, OpenTelemetry tracing, correlation)
needs concrete Spring Boot wiring. Spring Boot already exposes Actuator and Micrometer; this
ADR records how they realize the baseline's three pillars. Selecting both observability and
Spring Boot settles it.

## Decision

- **Metrics** via **Micrometer** (already enabled by the Spring Boot conventions) exporting
  to an OTLP/Prometheus backend; meaningful custom meters for domain operations.
- **Tracing** via **Micrometer Tracing bridged to OpenTelemetry**, with context propagated
  across HTTP/messaging boundaries; sampling configured per environment.
- **Structured JSON logging** with the **trace and span ids** injected into the MDC so log
  lines correlate with traces.
- **Actuator** exposes `health`, `metrics`, and `prometheus`/OTLP endpoints, secured and
  limited to what operators need.

## Alternatives considered

- **A vendor-specific tracing agent (e.g. Datadog, New Relic) instead of Micrometer Tracing
  bridged to OpenTelemetry** — rejected because it locks instrumentation to one backend,
  against the goal of a swappable OTLP/Prometheus export path.
- **Plain SLF4J/Logback JSON logging without trace/span ids in the MDC** — rejected because
  logs and traces could no longer be correlated during an incident.
- **Custom Spring AOP-based instrumentation instead of Micrometer's standard
  abstractions** — rejected because it reinvents what Micrometer already provides and loses
  portability across Spring Boot's ecosystem of auto-configured integrations.

## Consequences

- The three pillars are wired through Spring's standard abstractions and correlate via
  trace ids in the MDC.
- Backends stay swappable because instrumentation is OpenTelemetry/Micrometer, not
  vendor-specific.
- Sampling and endpoint exposure must be tuned per environment to balance signal and cost.
