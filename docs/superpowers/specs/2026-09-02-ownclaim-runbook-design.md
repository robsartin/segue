# The `ownClaim` runbook, and the check that its examples run

Issue #183. Written 2026-09-02.

## The gap

`retractEntity` has a runbook chapter in `docs/developer-guide.md` ("Taking something back out").
`ownClaim` has none, and has had none since #92 added it. It is the more consequential of the two
tools that require `--db` (ADR 60): three subcommands, a permanently consumed id band, a merge whose
two halves apply at different times, and an undo story that differs by which id you retract.

The guide's "The layering" section already carries the `--db` rule and the `./gradlew own` trap
(the abbreviation resolves to `:ownClaim` and runs). A runbook does not repeat that; it points at it.

## What the tree says, measured on 2026-09-02

- `OwnCli.USAGE`: `mint --kind <…> --label "<name>" | assert --from <Q…> --to <Q…> --type <CODE> |
  merge --local <Q00…> --canonical <Q…>  --db <segue.db> [--dry-run]`. `OwnCli.parse(String[],
  envDatabase, userHome)` is the package-private seam `OwnCliTest` drives; it refuses a missing
  `--db` before any file is touched, refuses `SEGUE_DB` as a substitute, refuses a flag given twice,
  and refuses a flag belonging to another operation.
- **A mint consumes its id forever.** `OwnRun.anIdNothingHasNamed`: `Q00` + the smallest number no
  row in the log has *ever* named — membership, not a high-water mark, and never recycled, because a
  retracted row still names its id and re-issuing it would make every earlier row ambiguous.
- **A merge is an appended `SameAs`, and it applies in two places at two times.** Ingest and every
  boot replay *carry* it (`IngestService.carry` copies the node's edges onto the canonical id; the
  `IdentityMerge` port carries the rating; the local node stays exactly where it was).
  `Equivalences` *resolves* it at read time for a single run of `recommend` or `rate`, so two
  affinity rows become one view; last surviving merge wins for the rating. The graph half — two
  nodes carrying the same edges, inflating every neighbour's degree — is open as #178 and no fold
  reaches it. A second merge of one local id is *said, not refused*: that is how a wrong merge is
  corrected.
- **Undo is by entity, by qid, backwards only** (ADR 44). `RetractRun` counts a `LocalEntity` as a
  node claim, an `OwnerEdge` at either end as an edge claim, and a `SameAs` naming the qid on
  *either* side as an edge claim, matching `Retractions.survives`. So retracting a merged local id
  drops its node, its owner edges and the merge; retracting the canonical id drops the world entity's
  whole expansion *and* the merge, and leaves the local node standing with its own edges. There is no
  edge-level retraction: a wrong `assert` is undone only by retracting an endpoint, which is the
  heaviest act in the guide, and the chapter must say so rather than imply a lighter one.
- **Fences.** `theOwnerClaimToolOpensNothingElse`, `theClaimToolsHaveNoDefaultDatabase`,
  `theClaimToolsTakeTheirDatabaseFromTheFlagAlone`, `ownerClaimsAreMadeThroughTheirFactories`. There
  is deliberately *no* `…WritesOnlyOwnerClaims` rule; `ArchitectureTest` ~line 1221 says why, and the
  chapter states that reason rather than inventing a rule. `ToolSurfaceTest` has no `ownIsNotATool`;
  the "not an MCP tool" reasoning is ADR 59's last decision bullet (a model could launder structure
  into the one tier exempt from corroboration), beside `assertEdgeIsNotAToolYet`.

## The decision

**Write the chapter, and make its examples executable.** Two deliverables in one red→green loop:

1. **A test that runs the guide's examples through the tool's parser.** It extracts every line of
   the guide matching `./gradlew ownClaim --args="…"`, splits the quoted argument string the way a
   shell would (single quotes preserved as one argument; `$HOME` replaced by an invented home),
   and calls `OwnCli.parse(args, null, invented-home)`. Every example must parse to an `Options`
   without a usage error, and `parse` is what enforces `--db`, so an example that forgot it is a
   red by construction. Two further assertions: **no example contains `~`** (the guide's own
   "Write `$HOME`, not `~`" rule, which `parse` cannot see because a tilde is a valid path
   character), and **the count of examples is at least one per subcommand** (`mint`, `assert`,
   `merge`) — the vacuity guard, and the assertion that is red on `main` today. The same treatment
   covers `retractEntity`'s two existing examples if `RetractCli` exposes the same `parse` seam;
   if it does not, the test says so in its javadoc and covers `ownClaim` alone. **`--dry-run`
   examples parse like any other; nothing is opened, nothing is run.**
2. **The chapter**, in the retraction runbook's shape and placed by the guide's chronological
   order — after "Rating one card at a time" (ADR 46) and before "How to read an ADR against the
   code" — with a Contents entry. It covers exactly what the issue lists: the three subcommands and
   when each is right; that `--db` is required and `SEGUE_DB` does not satisfy it, with `$HOME`
   examples and a pointer to the layering section rather than a restatement; what a mint costs;
   what a merge does and does not move, in both places, with #178 named as the open half; how to
   undo each, and why retracting the local id and retracting the canonical id are different acts.
   Then the "things this is not allowed to do" table citing the four rules by name, and "why this is
   not an MCP tool" citing ADR 59, in the retraction chapter's shape. Everything the chapter says
   about what the tool prints is quoted from `OwnRun`, not paraphrased from memory.

**Positive controls, definition of done.** On `main` the new test is red for "no `ownClaim`
examples" (quote it). With the chapter in place, change one example's flag to one belonging to a
different operation (`merge … --kind WORK`) → red naming the line and the parser's message; put a
`~` in one → red naming it; drop `--db` from one → red with `RequiredDatabase`'s refusal. Revert each.
Every prose claim about behaviour is checked against the class the spec names for it.

## Rejected

- **A chapter with no executable check.** It would drift the moment a flag is renamed, and the
  guide already has the #145 precedent that a document is checked against the code, not trusted.
- **Running the examples end to end against a temporary database.** More faithful, but it would
  mint into a database on every `check`, and the runbook's value is that its lines are correct to
  type — `parse` is exactly the boundary that decides that, and it is already the tested seam.
- **Folding the runbook into the existing `--db` section of "The layering".** That section is about
  a rule shared by two tools; a runbook is about one tool's verbs and their consequences, and the
  retraction precedent keeps those apart.

## Recorded

No ADR: a guide chapter records no decision. ADR 59 and ADR 60 are cited, not amended.
