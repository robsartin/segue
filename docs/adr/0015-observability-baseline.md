---
status: Accepted
date: "2026-08-23"
topic: observability-baseline
tags: [concern, observability]
supersedes: []
related: [observability-in-js-ts, observability-in-spring-boot, privacy-and-data-handling]
---
# 15. Observability baseline

## Context

When something goes wrong in production, we need to understand it without redeploying to add
print statements. Observability — logs, metrics, traces — has to be designed in. This
concern is opt-in (a small CLI may not need it); the language/framework mechanics live in
the relevant interaction ADR.

## Decision

When a project adopts observability, it covers the three pillars:

- **Structured logs** — JSON (or logfmt), with consistent fields and a correlation/trace id
  on every entry; no secrets or PII in logs.
- **Metrics** — RED/USE-style metrics (rate, errors, duration / utilization, saturation,
  errors) exposed for scraping, via a vendor-neutral facade.
- **Distributed tracing** — **OpenTelemetry** as the instrumentation standard, so traces,
  metrics, and log correlation are portable across backends.
- **Correlation** — a trace/request id is generated at the edge, propagated across service
  boundaries, and included in logs, so the three pillars join up.

## Alternatives considered

- **A vendor-specific APM agent (Datadog, New Relic)** — quick to set up with rich
  dashboards out of the box, but rejected as the instrumentation layer because it locks
  traces and metrics to one backend.
- **Logs only, without structured metrics or tracing** — the lowest-effort option, but
  rejected as insufficient for diagnosing latency and errors across service boundaries.
- **A bespoke internal telemetry library** — avoids a new dependency, but rejected in
  favor of OpenTelemetry's standard so the backend stays swappable and instrumentation
  isn't reinvented per project.

## Consequences

- Production issues can be diagnosed from telemetry rather than by adding instrumentation
  after the fact.
- Standardising on OpenTelemetry keeps the backend (Grafana, Datadog, etc.) a swappable
  detail.
- Instrumentation is ongoing work, and log/metric hygiene (no PII, bounded cardinality)
  must be maintained.

**Amendment (2026-11-03, issue #372): segue adopted half of this opt-in concern, on purpose.**

This ADR says the concern is opt-in and that the mechanics live in the relevant interaction ADR.
For segue that ADR is [17](0017-observability-in-spring-boot.md), and its 2026-08-26 amendment
(issues #28 and #29) took two of these four pillars off the table with reasons, and named what
would put them back. Those reasons are not restated here; one argument in two documents is the
drift this amendment exists to prevent.

- **Structured logs** and **correlation** are built as specified —
  [ADR 30](0030-structured-logging.md) and [ADR 29](0029-request-correlation.md).
- **Metrics exposed for scraping via a vendor-neutral facade** and **distributed tracing on
  OpenTelemetry** are not built, and are not pending work. Nothing in the tree declares
  OpenTelemetry, an OTLP exporter or a Prometheus endpoint, and no meter registry is exported;
  Micrometer's observation API is present transitively and publishes nothing, as ADR 17 records.
