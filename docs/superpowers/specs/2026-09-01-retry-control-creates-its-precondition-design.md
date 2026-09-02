# The retry control creates its precondition

Issue #169, round 2. Written 2026-09-01. Follows PR #189 and ADR 46's amendment of the same date.

## What round 1 fixed, and what it could not

`DeckBehaviourTest.aRetriedRatingCannotOverwriteAReRating` asserts Chrome retried an abandoned POST.
Chrome resends only when the attempt was bound to a socket **already in its pool**, so the number of
attempts is one plus the pooled sockets free at keypress, and the control fails when that is zero.
Round 1 found the favicon racing the keypress and made `@BeforeEach` wait for `document.readyState
=== 'complete'` and then for the stub to see nothing in flight and 200 ms of silence — a bound on
favicon issuance, documented as a bound.

The control failed again with that fix in place, under load, during another branch's gate.

## What round 2 saw

Eighty-one runs, NetLog per run, load average up to 145. **One failure, and the cause is visible.** In
the 225 ms of genuine silence after `untilQuiet()` returned, Chrome closed **every socket it held —
six, across five origins — in one millisecond**, with `QUIC_SESSION_POOL_MARK_ALL_ACTIVE_SESSIONS_GOING_AWAY`
alongside: a pool-wide flush, the shape of Chrome's network-change handling. It correlated with
network churn from another Chrome on the machine, not with CPU load; the heaviest batch was the
cleanest.

**A flush is not an exchange.** The stub's in-flight counter saw nothing; `lastArrived` never moved.
The precondition round 1 enforces — *no request in flight, 200 ms of silence* — was genuinely met.
The precondition the control actually needs — *a pooled socket exists at keypress* — is a fact about
Chrome's socket pool, which no server-side counter can observe.

Everything else is ruled out on all eighty-one runs. Favicon issuance under load: 6.00–21.25 ms,
median 10.08, p95 16.13, none above 200 ms — the bound was never the weak point. NetLog origin
requests reconcile with the counter's exchanges exactly. And the dose-response is clean:
**attempts = 1 + pooled sockets alive at the POST, in 61 of 61 traced runs.** The measurement is
`docs/retry-pool-flush-evidence.md`.

## The decision

**Create the precondition instead of inferring it, then press at once.** After `untilQuiet()`
returns, the test issues a warm-up `GET` **from the page** (`fetch`, same origin, body drained, so
the socket returns to the pool as a used, idle socket), waits for the stub to see that exchange end
and the page's promise resolve, and presses the key immediately. A pooled socket then exists by
construction, and the window in which a flush can undo it shrinks from a couple of hundred
milliseconds of deliberate silence to the few milliseconds between the warm-up's completion and the
POST.

Three properties of this decision are worth stating exactly:

- **`untilQuiet()` stays.** It closes the favicon race, which round 2 confirmed is real and bounded
  (p95 16 ms). The warm-up closes a different hole. Removing the wait would reopen the first.
- **The residual is measured, not hidden.** A flush can still land in the few milliseconds after
  the warm-up. From round 2's rate — one flush in eighty-one runs over a ~225 ms window — a ~30 ms
  window gives on the order of one failure in five hundred runs under the same churn. The ADR
  records that number and its basis.
- **The control's failure is classified.** The stub records every client port it has served. When
  the control fails, the message says which of two things happened: the POST arrived on a port
  **never seen before** — the pool was flushed between the warm-up and the keypress, an
  environmental event the test cannot prevent — or on a port that **had served a request** — Chrome
  was bound to a pooled socket and still did not resend, which is the browser changing. The first
  is a rerun; the second is the fact ADR 46 wants to be told. Today the message cannot tell them
  apart, so every red costs someone the investigation this round just did.

## What this does not do

- **No retry loop.** A test that re-runs its own scenario on a classified environmental failure
  would be honest in principle and would hide the rate in practice. The rate is the thing worth
  seeing; a red with a message that says "flushed, rerun" is cheaper than a hidden one.
- **No production change.** The warm-up request is issued by the test through the page, not by
  `deck.html`.
- **No attempt to suppress the flush.** No Chrome switch was found that disables network-change
  handling; the notifier is driven by the OS. #186 (Chrome reaching Google despite
  `--disable-background-networking`) is a confound — the flush fired in the same millisecond the
  `clients2` request completed — and removing it is worth doing separately, but it is not the cause.

## Rejected

- **Widen the silence window.** The bound was never the weak point (0 of 81 above 200 ms), and
  every millisecond of silence is a millisecond in which a flush can land. Widening it makes the
  race worse.
- **Observe the pool through CDP before pressing.** The DevTools protocol exposes no socket-pool
  state. Round 2 saw the flush only through `--log-net-log`, which is a per-run capture, not a
  query.
- **Warm up without draining the body.** An undrained response keeps the socket checked out
  (#188); the socket must be *idle* in the pool at keypress, or the POST needs a second one.
  *Corrected 2026-09-01 by measurement during Task 1:* at the committed 4-byte warm-up body an
  undrained response passed 3/3 — Chrome drains a body that small itself — and draining becomes
  load-bearing only around 200 KB. The drain is kept as insurance against a larger body, and is
  labelled as such in the code, not as the thing that makes the test pass.
- **Warm up and then wait for silence again.** Re-creates the window the warm-up exists to close.

## Testing

*Corrected 2026-09-01 during Task 1:* the warm-up does not need an idle socket — Chrome connects one
and the drained body returns it — and it may land on the **never-used preconnect spare** (6 of 60
runs), whose port is not yet in the served set. A test asserting the warm-up's port was previously
seen is therefore a 1-in-10 flake; the committed test asserts two consecutive warm-ups land on the
same port (0 of 40 failures). The control's classification is unaffected: the spare is pooled, so
a POST bound to it is resent, and its port becomes *seen* the moment the warm-up uses it.

- Red: with the §6 occupancy probe from round 1, remove the warm-up and observe the existing
  failure; with the warm-up in place, observe green — then, the sharper red: **plant a pool flush**
  by having the page close its own sockets between quiescence and keypress if Chrome allows it, and
  otherwise by holding the warm-up's response so the socket is not idle — and observe the
  classified message naming a never-seen port.
- The classification itself has a positive control in each direction: a POST on a reused port
  reads as the browser case; a POST on a fresh port reads as the flush case.
- The single test twenty times under CPU load and, if a second machine or a network toggle is
  available, under network churn. Report the tally.

## Recorded

ADR 46 gains a second dated amendment: the round-2 measurement, why the counter could not see the
flush, the created precondition, the classified message, the measured residual, the four rejected
alternatives, and the correction to round 1's §8 about `clients2`. The measurement page is committed
beside `docs/retry-precondition-evidence.md` and linked where that page is linked.
