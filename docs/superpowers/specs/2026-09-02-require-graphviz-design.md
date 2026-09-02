# A missing Graphviz can fail the build, the way a missing Chrome can

Issue #164. Written 2026-09-02.

## The defect

`SEGUE_REQUIRE_BROWSER=true` turns a missing Chrome from a quiet skip into a build failure, added after
#93 shipped a test that passed by not running. The Graphviz-dependent tests copied #93's skip and not
its guard: when `dot` is absent they skip, and CI — which installs Graphviz precisely so DOT output is
executed rather than asserted — reports success if that install degrades. #99 doubled the tests affected.

## The decision

**`SEGUE_REQUIRE_GRAPHVIZ`, mirroring `SEGUE_REQUIRE_BROWSER` in mechanism and placement.** Unset, the
Graphviz tests skip as now, visibly; set, a missing `dot` throws. The same helper shape the browser flag
uses (read it, do not reinvent), set in the CI workflow beside the browser flag, and documented beside it
in the developer guide wherever `SEGUE_REQUIRE_BROWSER` is documented. **Enumerate, do not assume, every
other test that skips on a missing external dependency** (grep `assume`, `Assumptions`, `@EnabledIf`,
`@Disabled`, `skip` across `src/test`): each one is either behind a require-flag, or listed in the report
as a decision with a reason, or fixed in this pass if it is the same shape.

**Positive controls in both directions, definition of done:** with `dot` unavailable (a `PATH` without
it) and the flag unset, the tests skip and the skip is visible in the report XML (`skipped` count); with
the flag set, the build fails naming Graphviz and the flag; with `dot` present and the flag set, green.
Quoted, reverted.

## Rejected

- **Make the tests fail on a missing `dot` unconditionally.** A developer without Graphviz could not run
  the suite; the browser flag exists for the same reason.
- **Assert Graphviz in the workflow only** (`dot -V`). Catches the install, not the test's own detection;
  the flag makes the test the authority.

## Recorded

No ADR; the mechanism is #93's, extended. The guide records the flag.
