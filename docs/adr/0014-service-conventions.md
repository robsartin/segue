---
status: Accepted
date: "2026-08-23"
topic: service-conventions
tags: [app-shape, service]
supersedes: []
related: [security-baseline, observability-baseline]
---
# 14. Backend service conventions

## Context

A long-running backend service has to be configurable across environments, observable,
safely deployable, and evolvable without breaking clients. These are cross-cutting
expectations worth stating regardless of language or framework.

## Decision

- **Config from the environment** (12-factor): no environment-specific values baked into
  the build; secrets supplied per the security baseline.
- **Health and readiness endpoints** so orchestrators can tell "started" from "ready to
  serve".
- **Graceful shutdown** — drain in-flight work and release resources on SIGTERM rather
  than dropping requests.
- **Explicit API versioning** so contracts can evolve without breaking existing clients.
- **Structured logging with correlation/trace ids**, and metrics exposed for scraping, so
  the service is observable in aggregation.
- **Statelessness** — request-scoped state is not held in process memory; shared state
  lives in a datastore or cache.

## Alternatives considered

- **Config baked into the build per environment** — rejected because it requires a
  separate build per target and risks leaking one environment's values into another.
- **In-memory session/request state** — rejected in favor of statelessness; in-process
  state ties a client to one instance and blocks horizontal scaling behind a load balancer.
- **Liveness checks only, no readiness endpoint** — rejected; without a readiness signal an
  orchestrator can route traffic to an instance that is up but not yet able to serve.

## Consequences

- The service deploys and scales predictably under an orchestrator.
- Operators can observe health and diagnose issues across instances.
- Versioning and statelessness add up-front design effort that keeps the service evolvable
  and horizontally scalable.
