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

**Amendment (2026-08-26, issues #28 and #29).** This decision was adopted as a general Spring Boot
convention, and every bullet above presumes a **deployed service**: operators to read the endpoints,
a collector to receive the spans, and environments distinct enough that sampling differs between
them. Segue is none of those. It is a single-user tool bound to loopback (ADR 37) whose lifecycle
belongs to the MCP client that spawns it — it has a user, not an operator. So for this project:

- **Tracing is deliberately not built** (issue #28). Nothing sends segue a `traceparent` and there
  is no collector to receive a span, so propagation would run from nobody to nowhere. There is a
  cost as well as an absent benefit: on the stdio transport stdout *is* the JSON-RPC channel
  (ADR 28, ADR 30), so an exporter logging a failed connection to console would corrupt the
  protocol stream.
- **Actuator is deliberately not exposed** (issue #29). A health endpoint answers to an
  orchestrator and segue has none. Exposing one would also mean a second HTTP surface that must
  inherit ADR 37's `Origin`/`Host` allowlist, or become an unguarded door beside a deliberately
  locked one — a real increase in attack surface for telemetry nobody reads.
- **Metrics follow from that.** Micrometer's observation API is on the classpath transitively, but
  there is no meter registry export, because the `prometheus`/OTLP endpoint that would carry it is
  the surface issue #29 declined.
- **Structured JSON logging and request correlation stay exactly as specified.** They are the half
  of this baseline a single-user tool still needs, and they are built: ADR 30 for the logging,
  ADR 29 for the UUIDv7 request id that reaches the user in every `isError` result.

What would reopen all of this: segue becoming reachable beyond loopback, gaining an orchestrator, or
serving more than one user. ADR 28 already makes the first a deliberate change with its own security
review, so none of these can happen by accident.

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
