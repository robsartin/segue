# `settle()` waits for a condition, not 600 ms

Issue #187. Written 2026-09-02.

## The defect

`DeckBehaviourTest.settle()` is `HeadlessChrome.sleep(600)`, there so that a retry arriving after
everything else has finished cannot slip past the ordering assertions in the retried-rating test. The
same file's `untilSent()` javadoc says why a fixed sleep makes an absence assertion meaningless: it says
only "nothing had arrived yet". #169's trace study ruled `settle()` out as the flake's cause — across the
traced runs no request arrived in the 2500 ms after the assertion point — so it costs 600 ms per test and
a false sense of safety, not correctness today.

## The decision

**Replace the sleep with a condition in `untilSent()`'s shape.** The evidence (`docs/retry-precondition-
evidence.md`, #169) shows the last attempt is always spent before the page writes `#problem`, so the
ordering assertions can run as soon as the page reports the re-rating landed: wait, bounded, for that
page state (and for the stub's in-flight count to be zero, which #188 makes true by construction), then
assert. The absence assertion then rests on a *positive* observation — the page said it was done — rather
than on time having passed.

**Positive controls, definition of done:** (1) the condition is seen to wait: with the page state
delayed by the stub (a `Retry-After` the test controls), the wait measurably blocks until the state
appears, and does not return early (quote the three-run spread); (2) the absence assertion still bites:
plant a stub that sends one extra late attempt *after* the page reports done → the ordering assertion
reds (this is the defect `settle()` existed to catch — it must be caught by the condition, not by luck);
(3) the retried-rating test passes N=20 consecutive blocking runs with the sleep gone (report the spread
of wall times; the point is no flake, and a faster test is a side effect, not the goal).

## Rejected

- **Shorten the sleep.** Same vacuity, less of it.
- **Leave it.** It costs 600 ms and teaches the next reader that a fixed sleep is acceptable here, in the
  one file that argues the opposite.

## Recorded

No ADR. The evidence page gains a dated line that `settle()` is now a condition and which one.
