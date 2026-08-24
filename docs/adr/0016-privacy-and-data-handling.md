---
status: Accepted
date: "2026-08-23"
topic: privacy-and-data-handling
tags: [concern, privacy]
supersedes: []
related: [observability-baseline, security-baseline]
---
# 16. Privacy and data handling

## Context

Handling personal data carries legal and ethical obligations, and the cheapest way to
reduce risk is to hold less data and handle it deliberately. The specifics vary by
jurisdiction and product, so this decision sets principles and is adapted per project
rather than prescribing a fixed regime.

## Decision

Where a project handles personal or sensitive data:

- **Data minimization** — collect and retain only what a clear purpose requires; default to
  not collecting.
- **Purpose and retention are explicit** — each category of personal data has a documented
  purpose and a retention period, after which it is deleted or anonymised.
- **Protect data in transit and at rest** — encryption in transit always; encryption at
  rest for sensitive data; access on a need-to-know basis.
- **No PII in logs or telemetry** — consistent with the observability baseline.
- **Support data-subject rights** — the design allows locating, exporting, and deleting an
  individual's data.
- **Privacy by design** — new features consider data impact up front; significant data
  flows are recorded (and this ADR superseded/extended as the regime firms up).

## Alternatives considered

- **Collect broadly and decide later ("just in case" retention)** — preserves optionality
  for future features, but rejected because it maximizes breach exposure and legal risk
  for data that may never be used.
- **A single fixed compliance regime (e.g. GDPR-only rules)** — simpler to implement once,
  but rejected because it doesn't generalize across the jurisdictions and product types
  this ADR needs to serve.
- **A third-party consent/privacy-management platform with its own fixed rules** — offloads
  implementation effort, but rejected in favor of project-adapted principles so the
  regime can be tightened as it firms up rather than boxed in early.

## Consequences

- Exposure is limited by holding less data for less time.
- Data-subject requests are feasible because the design anticipated them.
- These are principles to be made concrete per jurisdiction/product; a project handling no
  personal data can note this concern as not applicable.
