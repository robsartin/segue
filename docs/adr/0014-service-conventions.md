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

**Amendment (2026-11-03, issue #372): segue is not the application this ADR describes. It is
amended rather than marked superseded, because the document that should supersede it does not
exist yet — and that is itself the finding.**

Three of the six bullets above do not hold here, and they fail for one reason rather than three:
this is an app-shape ADR about a deployed backend service under an orchestrator, and segue is a
single-user tool bound to loopback whose lifecycle belongs to the MCP client that spawns it.
[ADR 17](0017-observability-in-spring-boot.md)'s 2026-08-26 amendment (issues #28 and #29) is
where that shape and its consequences are argued. It is cited here, not repeated.

- **Health and readiness endpoints** — none exist. Declined as part of ADR 17's Actuator bullet
  (issue #29): a readiness signal answers an orchestrator, and segue has none.
- **Explicit API versioning** — the tool surface ([ADR 26](0026-mcp-tool-surface.md)) carries no
  version discriminator. The compatibility question a client actually asks is answered one level
  down, by the pinned protocol revision in
  [ADR 27](0027-mcp-protocol-conformance.md).
- **Statelessness is not an unmet clause. It is a clause this project decided against.** Recording
  it as a gap would misdescribe the design. The authoritative state is in a datastore — the SQLite
  assertion log of [ADR 24](0024-sqlite-assertion-log.md) — but the structure every request reads
  is not: [ADR 64](0064-fold-the-log-once-per-boot.md) folds that log into an in-process graph once
  at boot and hands the single projection to every reader, deliberately, for the reasons it gives.
  A second instance could not share it. "Shared state lives in a datastore or cache" is bought to
  get horizontal scaling behind a load balancer; with one process and one user there is nothing to
  buy and a per-request round trip to pay.

The other three bullets hold and stay in force. Configuration comes from the environment with
nothing environment-specific baked into the build (`SEGUE_DB`, `SEGUE_HTTP_PORT`). Structured
logging with a correlation id is built (ADRs 30 and 29). And **graceful shutdown holds, which the
issue that prompted this amendment had recorded as a gap** — it was read from the absence of a
`server.shutdown` key in `application.yaml`, but Spring Boot 4 defaults that property to
`graceful`, so Tomcat drains in-flight requests on the HTTP transport with no configuration at all.
The repository's own test output shows it: `org.springframework.boot.tomcat.GracefulShutdown` logs
*Commencing graceful shutdown. Waiting for active requests to complete* during `./gradlew check`.
On the stdio profile there is no server to drain, by construction
([ADR 28](0028-mcp-transports.md)).

### Why this is an amendment and not `Superseded by 0017`

Issue #372 asked for the choice to be argued rather than picked. Supersession was considered and
refused on three counts.

1. **ADR 17 does not decide most of what this ADR decides.** It is an *interaction* ADR, about how
   Spring Boot wires observability; this is an *app-shape* ADR, about what kind of application is
   being built. ADR 17 reaches two of the six bullets above — health endpoints, and the
   metrics-for-scraping half of the observability bullet — and is silent on graceful shutdown, API
   versioning, 12-factor configuration and statelessness. `Superseded by 0017` would send a reader
   to a document that answers a third of the question it was offered as the answer to.
2. **Three of the six bullets are still live**, and one is load-bearing beyond this file:
   [ADR 8](0008-security-baseline.md) names this ADR as related and pairs its own
   secrets-from-the-environment rule with the 12-factor bullet above. Retiring the file retires
   that pairing for no gain.
3. **Supersession needs a replacement decision, and there is not one.** ADR 1 makes `Superseded by`
   a pointer at the decision taken instead;
   [ADR 34](0034-jvm-quality-gates-junit-6-spotless-jacoco-archunit.md) is this repository's model
   of doing it properly — a new decision on the same axis that says what changed, what carries over
   unchanged, and why editing in place was refused. Nothing on the app-shape axis does that here.
   The honest superseder would be a **new app-shape ADR stating what segue actually is**: a
   single-user, loopback-bound, client-spawned MCP server holding an in-process projection of a
   local append-only log. Every bullet above would then follow from it or fall away as a
   consequence, in one place, instead of being chased down four other ADRs. That ADR is not
   written, and writing it was out of scope for this issue. Until it is, this amendment is the
   route from here to the reasoning; when it is, it should supersede this ADR, and this paragraph
   is the note saying so.
