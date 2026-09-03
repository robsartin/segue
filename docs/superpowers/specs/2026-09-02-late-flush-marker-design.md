# A flush marker that lands after the page's first socket is recorded, not lost

Issue #193. Written 2026-09-02.

## The gap

`HeadlessChrome.open()` (#186) waits for Chrome's startup cert-verifier flush marker in the NetLog tail
before `Page.navigate`, with a bounded fallback after which it proceeds and prints that it did not see
the marker. If the bound fires and the marker then lands *after* the page's first socket — the #169 race
recurring under a load the measurements did not reach — the run is silently green: the precondition the
retry control depends on did not hold, and nothing says so. Reddening on it would red on machine speed,
which is the trap the bound exists to avoid; the reviewer's reasoning stands.

## The decision

**Keep tailing the NetLog after navigate, and record whether the marker landed after the page's first
socket.** `FlushWait` already carries `sawMarker()` as the seam; it gains the observation "marker seen
after the first socket" (with the two positions), exposed so that (a) the harness prints it in the same
place it prints the bound firing, and (b) a test that depends on the precondition can assert on it and
*skip with the reason* rather than pass vacuously — a skip is visible in the report XML; a silent green
is not. No test reds on it; the harness's own unit test proves the observation is made.

**Positive control, definition of done:** a unit test of the NetLog parsing over a fixture tail (the
parser is constants-driven; build the fixture from a real NetLog captured under `build/reports/netlog/`
by #186's guard) with the marker placed *after* the first socket event → the observation is true and
names both positions; with the marker before → false; with no marker → the existing "not seen" path.
Seen red before the recording exists. Then the retry-control test consults it: forced late (fixture or
injected) → skipped with the reason, quoted from the XML.

## Rejected

- **Fail loudly when the marker is late.** Reds on slow machines and CI; the reviewer's argument.
- **Raise the bound.** Moves the window, does not close it, and costs every launch.

## Recorded

No ADR. `docs/loopback-only-evidence.md` (or the #186 evidence page — find which records the flush
wait) gains a dated paragraph saying the late case is now observed and how it surfaces.
