# `corroborated(0)` includes an owner-only edge, and the engines are compared across the range

Issue #176. Written 2026-09-02. Decision by the owner, recorded on the issue.

## The divergence

Seeded identically with one edge whose only provenance is the owner, the two `GraphStore` engines
disagree at `corroborated(0)`: Tinker returns it, Jena does not. `EdgeRecord.corroboration()` counts
distinct **non-owner** sources, so the edge's corroboration is 0, and Tinker's filter — `corroboration()
>= min` — admits it at 0. Jena's SPARQL applies `FILTER (?src != ?owner)` to the *rows* before `GROUP
BY`, so an edge whose only rows are the owner's has no group at all, and `HAVING (COUNT(DISTINCT ?src)
>= 0)` is never evaluated for it. The domain rule says 0; one engine says "absent".

#92 closed the same divergence at `corroborated(1)`; 0 remained, and stayed hidden because the
differential guard (`TinkerGraphStoreContractTest.enginesAgreeOnEdgeSets`) compares the engines at
`corroborated(2)` only — a guard structurally unable to see a disagreement at any other value.

## The decision

**`corroborated(N)` returns every edge whose corroboration is at least N, and an owner-only edge has
corroboration 0 — so at N = 0 it belongs.** This is what the domain object already says and what ADR
59 decided: owner claims are projected to the graph and exempt from the corroboration *count*, not
absent from the corroboration *query*. Tinker is unchanged. **Jena keeps the group and counts non-owner
sources**, so an owner-only edge appears with n = 0 and every other count is what it was.

`corroborated` has no production caller today. The value of the fix is the port's honesty: ADR 18
claims *"Both adapters return identical results on all four"* queries and names Jena the cross-check
that keeps the port honest; at N = 0 that claim was false.

## The guard, widened

The differential comparison runs at **every N the fixture makes meaningful** — 0 through one past the
fixture's maximum corroboration (its most-corroborated edges have two independent sources, so 0..3) —
not at one value. It asserts the two engines' edge-key sets are equal at each N, and it also asserts the
*shape* the fixture guarantees, so the loop cannot pass by comparing two identical mistakes: at N = 0
the owner-only edge is present in both; at N = 1 it is absent from both; at N = 3 both are empty.

**Positive control, definition of done:** reintroduce Jena's row-dropping filter and watch the widened
guard go red *at N = 0 and only there*, naming the owner-only edge; revert. A guard never seen to fail
has never been tested (#93, #139).

## Rejected

- **Forbid `min < 1` at the port.** Makes ADR 18's claim true by narrowing what may be asked; removes a
  legal value from a port that has no caller to have asked for the change.
- **Exclude owner-only edges at every N — fix Tinker.** Makes Jena's current behaviour the rule, but
  contradicts `EdgeRecord.corroboration()`, which reports 0 for that edge rather than "not applicable";
  the domain object would have to change, a larger decision than the issue asks.
- **Keep the guard at `corroborated(2)` and add a single test for 0.** Closes this instance and leaves
  the next divergence — at any other N — as invisible as this one was.

## Recorded

ADR 18 gains a dated amendment: the claim of identical results on Q4 was false at N = 0 from the day
owner claims existed (ADR 59) until this change; what `corroborated(0)` means for an owner-only edge;
that the differential guard now spans the range; and that the corroboration query in Jena counts
non-owner sources without dropping the edge. The port's javadoc for `corroborated` says what 0 means.
