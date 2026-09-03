# The export fold emits nodes in a stable order

Issue #207. Written 2026-09-02.

## The defect

`export/LogProjection` copies its node map with `Map.copyOf`, whose iteration order is unspecified and
salted per JVM. The #178 review printed the same log's nodes in two orders from two JVMs. Nothing
asserts export order, so a DOT or GraphML diff between two runs of one log is noise and a real change
hides in it. ADR 43 made `recommend`'s output byte-identical for exactly this reason, and ADR 59 records
`Map.copyOf` breaking it once already in `Equivalences`.

## The decision

**Nodes and edges are emitted in log order** — the order their first surviving claim appears in the
log — held by a `LinkedHashMap` in the fold. Log order is the one order every reader already agrees on
(`KnownList.promoted`, `Equivalences.resolve`), it is a fact of the data rather than a choice, and it
keeps a diff between two exports of the same log empty. Sorting by qid was the alternative: stable too,
but it would reorder the picture every time an id changes shape (#171 just changed a hundred), and it
puts a choice where a fact would do.

**Positive control, definition of done.** A test projects one log through `LogProjection` and
renders it (DOT and GraphML) twice **in separate JVM conditions** — the same log fed through a map whose
iteration order is deliberately shuffled, and, if the harness allows, a forked JVM with a different
`-XX:hashCode` seed — and asserts byte-identical output; seen red on today's `Map.copyOf` first (quote
the two orders). A second assertion pins the order *to* log order on a fixture with a known claim
sequence, so "stable" cannot silently mean "stable but arbitrary". The `DotWriter`/`GraphMlWriter`
tests that #171 just migrated must not need their expectations reordered — if one does, that is the
order change being visible, and the report says which.

## Rejected

- **Sort by qid at emission.** Stable, but a choice; reorders on id changes; hides log order.
- **Leave it and document.** The exporter is the owner's picture of the graph; a diff that lies is
  the silent-no-op family this repo files issues to close.

## Recorded

No ADR: ADR 43's byte-identical contract is extended to the exporter, and its own text already states
the principle; the guide's "Looking at the graph" chapter gains one sentence.
