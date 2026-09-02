# The retry precondition under load — round 2

**Note (2026-09-02, issue #186, round 3).** §5 below closes by naming Chrome's requests to
`clients2.google.com`, `accounts.google.com`, `www.google.com` and `gstatic.com` as **the trigger**
for the flush. Round 3 measured that directly, with the browser unable to resolve any of them, and
it is wrong: the browser-wide notification fires in 80 of 80 launches with nothing to fetch, and a
planted early page load still had its loopback socket closed by it in 16 of 20 runs. Those requests
were present at the sighting; they are not what drives the flush. What round 3 did find is that the
flush no longer reaches the deck's sockets — it lands ahead of the page's first socket in 80 of 80,
by 57–140 ms in 69 of them and by 574–683 ms in the other eleven — and the rest of this page stands.
**Round 3 also names the handler §4 below leaves open.** On the planted control the closing event
carries Chrome's own reason, `{"reason": "Cert verifier changed"}`, with two
`CERT_VERIFY_PROC_CREATED` events in the same millisecond ahead of it, in 20 runs of 20: the
configuration change is the certificate verifier being created, and every pooled socket goes with
it. Why Chrome creates it on that schedule is still not established. See
[the loopback-only flush measurement](loopback-only-evidence.md) and ADR 52's 2026-09-02 amendment.

**Evidence only. No fix is proposed here, and none was made.** Round 1
([the retry-precondition measurement](retry-precondition-evidence.md)) found that
Chrome resends a POST only when the attempt was bound to a socket already in its pool, identified
`GET /favicon.ico` as the request that was naturally occupying that socket, and PR #189 made
`@BeforeEach` wait for `readyState === 'complete'` and then for 200 ms of silence from the stub.
The test then failed once more, with that fix in place, during a full `check` on the #170 review
gate. This page is the trace study that answers what occupied the pool that time.

On 2026-09-01, `aRetriedRatingCannotOverwriteAReRating` was run 81 times in isolation on branch
`169-retry-under-load` at `4d13d16`, with a per-request handler trace and Chrome's NetLog. **It
failed once, with the reported signature.** The failure's NetLog contains the mechanism, and 61
runs of graded evidence pin it down: the occupier is not a request at all.

Same caveats as round 1: one machine (macOS 26.6.2, 28 cores, Chrome 152.0.7977.65, JDK 25),
loopback only, a dated measurement that nothing regenerates. The raw traces and NetLogs are not
retained in the repository.

---

## 1. Headline

| | |
|---|---|
| Runs (single test, `--rerun`, sequential, blocking, `SEGUE_REQUIRE_BROWSER=true`) | **81** |
| Failures, exact reported signature | **1** (1.2%) |
| Runs with two attempts at `"rating":1` — a partial version of the same event | **4** |
| Runs with three attempts (the healthy case) | **76** |
| 1-minute load average across the runs | **4.85 – 145.22** on 28 cores |
| `attempts == 1 + (pooled sockets alive at the POST)` | **61 / 61 runs with a NetLog** |
| Runs where the favicon arrived more than 200 ms after `/api/card` | **0 / 81** |

Filter validity: every run's `TEST-…DeckBehaviourTest.xml` reported `tests="1"`, so the filter
selected the one test and did not pass by matching nothing.

**The answer: candidate (b).** At the moment of the keypress there was no pooled socket, because
Chrome had closed *every socket it held, for every origin*, in a single browser-wide flush that
landed after the page's sockets were created and before the key was pressed. It is not a request,
so the stub's `Filter` could not have seen it, and no amount of silence implies its absence.

---

## 2. Instrumentation used (all reverted)

`DeckBehaviourTest`, additive alongside the existing logic — `posts`, `untilQuiet()`,
`QUIET_MILLIS` and every assertion were left exactly as they are:

- inside the existing `accounting()` `Filter`, a line per request recording a monotonic ms
  timestamp, method, URI, `exchange.getRemoteAddress().getPort()`, the `Connection` header and the
  in-flight count, on entry and again on completion or throw;
- inside `rate(...)`, the port and exact body, recorded **alongside** `posts.add(...)`;
- marks at `SERVER-PORT`, `CHROME-LAUNCHED`, `OPEN-RETURNED`, `CARD-ON-SCREEN`,
  `READYSTATE-COMPLETE`, `QUIET-RETURNED` (carrying `inFlight` and the elapsed silence at the
  moment it returned), `KEYPRESS-1`, `ASSERTION-POINT` and `TEARDOWN`;
- the trace written to a scratch directory in `@AfterEach`.

`HeadlessChrome.launch()`: `--log-net-log=<file> --net-log-capture-mode=IncludeSensitive`.

**Instrument control.** 20 of the 81 runs were made with the NetLog flags removed, to check the
instrument was not itself the cause. Those 20 were indistinguishable: three attempts in 20/20, the
POST on an already-used client port in 20/20, favicon delay 6.9–18.1 ms. The NetLog neither causes
nor masks the failure. Without it the port trace alone still separates the two cases — a
first-seen POST port is the failure, an already-used one is not.

---

## 3. Load

The sighting came from a full `./gradlew check`. The sibling worktree named for that purpose,
`wt-170`, **no longer exists on disk** (`git worktree list` reports it `prunable`), so no
concurrent `check` was available and none was run; nothing outside `wt-169b` was written to.

Load was applied instead as CPU pressure — `yes > /dev/null` processes, 40 then 70 — and the runs
are reported in four batches by load. Note what the batches show:

| batch | n | 1-min load | attempts | flush landed after page load |
|---|---|---|---|---|
| A (incidental: the owner's own Chrome was busy) | 1 | 46.99 | **1 — the failure** | yes, +53 ms |
| B (quiet box) | 20 | 4.85 – 8.46 | 3 ×20 | 0 / 20 |
| C (40 spinners) | 20 | 17.1 – 80.5 | 3 ×16, **2 ×4** | 4 / 20 |
| D (70 spinners) | 20 | 70.2 – 145.2 | 3 ×20 | 0 / 20 |
| E (control, no NetLog, 40 spinners) | 20 | 91.9 – 117.7 | 3 ×20 | not measured |

**CPU pressure is not the lever, and this is worth saying plainly.** Batch D, the most loaded, was
as clean as the quiet box. The failure and the four near-misses need one specific race to go one
specific way, and spinning the CPU slows both sides of it equally (§5).

---

## 4. The failure

Handler trace. Every page request landed on **one** socket, port 51828; the favicon arrived 7.6 ms
after the card, well inside the window; `untilQuiet()` then waited a further 223 ms and returned
truthfully — the stub was serving nothing and had been asked for nothing. The POST went out on
port **51830**, a port never seen before:

```
  1123.19 REQ  GET /              port=51828 conn=keep-alive inFlight=1
  1132.54 DONE /                  port=51828
  1136.90 REQ  GET /api/card?i=0  port=51828 conn=keep-alive inFlight=1
  1137.77 DONE /api/card?i=0      port=51828
  1144.51 REQ  GET /favicon.ico   port=51828 conn=keep-alive inFlight=1     <- 7.6 ms after the card
  1144.67 DONE /favicon.ico       port=51828
  1155.79 READYSTATE-COMPLETE
  1369.65 QUIET-RETURNED inFlight=0 sinceLastArrived=223ms lastPath=/favicon.ico
  1370.93 KEYPRESS-1
  1373.69 REQ  POST /api/rate     port=51830 conn=keep-alive inFlight=1     <- first-seen port
  1373.80 BODY port=51830 body={"qid":"Q0900001","rating":1}
  1780.73 THREW /api/rate port=51830 ... the handler threw late
  1787.43 REQ  GET /api/card?i=0  port=51831                                <- no retry, ever
  1810.73 BODY port=51831 body={"qid":"Q0900001","rating":4}
  2446.23 ASSERTION-POINT order=[1, 4]
```

The NetLog says what happened in the 225 ms of silence. **This is the single clearest excerpt in
the study** (Chrome's own clock, ms from browser start; `51817` is the stub):

```
 777 SOCKET#108   TCP_CONNECT                     address_list=['127.0.0.1:51817']
 779 URL_REQUEST#109 SEND_REQUEST_HEADERS         line=GET / HTTP/1.1
 779 SOCKET#119   TCP_CONNECT                     address_list=['127.0.0.1:51817']   <- preconnect spare
 799 HTTP_STREAM_JOB#124 SOCKET_POOL_REUSED_AN_EXISTING_SOCKET  idle_ms=3            GET /api/card?i=0
 807 HTTP_STREAM_JOB#132 SOCKET_POOL_REUSED_AN_EXISTING_SOCKET  idle_ms=6            GET /favicon.ico
 807 URL_REQUEST#129 REQUEST_ALIVE END                          <- the page has stopped asking for things

 832 HTTP2_SESSION#104 HTTP2_SESSION_CLOSE                        net_error=0 net::OK
 832 SOCKET#90    SOCKET_POOL_CLOSING_SOCKET                      (accounts.google.com)
 832 SOCKET#119   SOCKET_POOL_CLOSING_SOCKET                      <- the preconnect spare
 832 SOCKET#108   SOCKET_POOL_CLOSING_SOCKET                      <- the socket that served the page
 832 QUIC_SESSION_POOL#28 QUIC_SESSION_POOL_MARK_ALL_ACTIVE_SESSIONS_GOING_AWAY
 832 SOCKET#98    SOCKET_POOL_CLOSING_SOCKET                      (gstatic.com)
 832 HTTP2_SESSION#101 HTTP2_SESSION_CLOSE                        net_error=0 net::OK
 832 QUIC_SESSION_POOL#35 QUIC_SESSION_POOL_MARK_ALL_ACTIVE_SESSIONS_GOING_AWAY
 832 SOCKET#81    SOCKET_POOL_CLOSING_SOCKET                      (www.google.com)
 832 SOCKET#32    SOCKET_POOL_CLOSING_SOCKET                      (clients2.google.com)
 832 QUIC_SESSION_POOL#7  QUIC_SESSION_POOL_MARK_ALL_ACTIVE_SESSIONS_GOING_AWAY

1035 URL_REQUEST#133 REQUEST_ALIVE BEGIN          url=http://127.0.0.1:51817/api/rate
1035 SOCKET#137   TCP_CONNECT                     address_list=['127.0.0.1:51817']   <- fresh connect
1036 HTTP_STREAM_JOB#135 SOCKET_POOL_BOUND_TO_CONNECT_JOB
1036 URL_REQUEST#133 SEND_REQUEST_HEADERS         line=POST /api/rate HTTP/1.1
1444 URL_REQUEST#133 REQUEST_ALIVE END            net_error=-324 ERR_EMPTY_RESPONSE
                                                  <- no HTTP_TRANSACTION_RESTART_AFTER_ERROR
```

In one millisecond, Chrome closed **every socket it held — six, across five origins, four of them
nothing to do with this test** — and marked every QUIC session going away. Two of the six were the
stub's: the socket that had served `/`, `/api/card` and `/favicon.ico`, and the preconnect spare
that had never carried a request. Nothing was in flight, so no exchange began or ended, so the
`Filter` counted nothing and `lastArrived` did not move. `untilQuiet()`'s condition was true and
its conclusion was false.

Two hundred milliseconds later the POST was issued into an empty pool, got
`SOCKET_POOL_BOUND_TO_CONNECT_JOB`, and by round 1's rule was never resent. One attempt, and a red
positive control asserting nothing about the page.

The events sharing that millisecond — `HTTP2_SESSION_CLOSE` at `net::OK`, all QUIC sessions marked
going away, and (two events earlier) `CERT_VERIFY_PROC_CREATED` and the completion of Chrome's own
`clients2.google.com/time` request — are the signature of a browser-wide configuration-change
notification, not of pressure or of an idle timeout. **The loopback pool is collateral damage:
these sockets are 400 ms old, on 127.0.0.1, and have nothing to do with TLS, certificates or
QUIC.** The exact Chromium handler was not identified and this page does not name one.

---

## 5. Why it is a race, and why it is rare

The flush is a one-shot event on Chrome's own startup clock, driven by its background network work
settling. Across the 61 NetLog runs the second `MARK_ALL_ACTIVE_SESSIONS_GOING_AWAY` fell at
**667–884 ms** after browser start. The page's sockets were created at **729–900 ms**. The two
distributions overlap almost exactly, and the whole outcome is the sign of the difference:

| flush − page load | runs | what the flush closed | attempts | result |
|---|---|---|---|---|
| −110 … −3 ms | 56 | nothing of the stub's — the sockets did not exist yet | 3 | pass |
| +1, +1, +1, +2 ms | 4 | one of the two, mid-load | **2** | pass |
| **+53 ms** | 1 | **both** | **1** | **FAIL** |

That is a dose-response across the full range, and it is the reason this page treats one natural
failure as sufficient rather than anecdotal. `attempts == 1 + (loopback sockets alive when the
POST went out)` held in **61 of 61** runs — the four two-attempt runs are the same event landing
one socket short of the failure, and they are *only* seen when the flush lands late.

Why CPU load does not straightforwardly reproduce it: spinners delay Chrome's startup and the
test's navigation together, so both sides of the race move and the sign of the difference is
roughly preserved (batch D, load 145, produced no late flush at all). What pushes the flush late
relative to page load is Chrome's **network** work — the requests to `clients2.google.com`,
`accounts.google.com`, `www.google.com` and `gstatic.com` that it makes despite
`--disable-background-networking`. In the failing run the flush fired in the same millisecond that
`clients2.google.com/time` finished. The one failure came when the owner's own Chrome was
saturating the machine's network and CPU; 80 subsequent runs with a quiet network did not
reproduce it.

**Round 1 recorded those Google requests and set them aside** — §8, "Incidental observation, not
related to the flake". They are the trigger.

---

## 6. Candidates, decided

**(a) the favicon was issued more than 200 ms after the card fetch — RULED OUT.**
Measured on all 81 runs: favicon delay after `/api/card` **6.00 – 21.25 ms**, median 10.08, p95
16.13. **Zero** runs above 200 ms; the worst case is 9× inside the bound. Round 1's 6–20 ms is
reproduced under loads up to 145 (batch D median 11.3 ms, max 21.2 ms — the tail widens by about a
millisecond and nothing more). The failing run's favicon arrived at **7.6 ms**, and `untilQuiet()`
returned 223 ms after it. The documented residual is real but it is not what is happening: 200 ms
is a sound bound on favicon issuance latency, and the test failed anyway.

**(b) the used socket and the preconnect spare were both gone at keypress — CONFIRMED, and the
cause is seen.** `SOCKET#108` (used) and `SOCKET#119` (preconnect, never used) were both closed by
`SOCKET_POOL_CLOSING_SOCKET` at t=832, 203 ms before the POST. They were not reaped for idleness —
they were 25 and 53 ms idle — and they were not closed under memory pressure; they went with every
other socket in the browser in a single browser-wide flush.

**(c) some other request to the origin arrived after quiescence — RULED OUT.**
The NetLog shows exactly **7** `URL_REQUEST`s to the stub's origin in every one of the 61 runs, and
they are the 7 the test causes: `/`, `/api/card?i=0`, `/favicon.ico`, the abandoned POST, the
re-dealt `/api/card?i=0`, the POST carrying 4, and `/api/card?i=1`. Nothing arrived between
`QUIET-RETURNED` and `KEYPRESS-1` in any run — the gap is 0.66–2.63 ms, median 1.33 ms.

**(d) the quiet window was satisfied spuriously by an exchange the Filter never saw — RULED OUT as
a counting error, but the *category* is exactly right.** Per-run, NetLog origin requests vs Filter
exchange count reconciles in all 61 runs and in only three shapes: `(7 requests, 7 exchanges, 1
attempt)`, `(7, 8, 2)`, `(7, 9, 3)`. The difference is always `attempts − 1`, i.e. Chrome's own
resends, which are extra exchanges inside one `URL_REQUEST`. No request reached Chrome that the
Filter did not count; no context-less path, no connection opened and closed without a request.

The Filter's coverage is not the defect. **The instrument's *kind* is.** ADR 46's amendment already
names one way "no exchange in flight" fails to mean "a pooled socket exists" — an undrained
response body. This is a second, and it is not about draining: the socket can be **closed**, by the
browser, for a reason that produces no exchange at all. A counter of exchanges cannot observe it,
and no length of silence excludes it.

---

## 7. Answers to the specific questions

**What was the last request before the keypress, and how long after quiescence did it arrive?**
`GET /favicon.ico`, and nothing arrived after quiescence in any of the 81 runs. In the failure the
favicon completed at 1144.67 ms, `untilQuiet()` returned at 1369.65 ms (223 ms of measured
silence), the keypress followed 1.28 ms later. The wait did its job.

**What socket was the POST bound to?**
`SOCKET_POOL_BOUND_TO_CONNECT_JOB`, with the `TCP_CONNECT` for that very request one millisecond
earlier — a socket the pool had to connect. In the 76 three-attempt runs it was
`SOCKET_POOL_REUSED_AN_EXISTING_SOCKET` (idle 200–260 ms, the quiet window itself).

**Were the used socket and the preconnect spare still open at keypress?**
No. Both were closed 203 ms before the POST by `SOCKET_POOL_CLOSING_SOCKET`, in the flush.

**Which candidate does the evidence pick?**
(b), with the mechanism seen rather than inferred: a browser-wide socket-pool flush, not pressure
and not an idle timeout. (a), (c) and (d) are ruled out on all 81 runs.

---

## 8. Two things worth flagging

**Nothing here is a reason to distrust the #189 fix on its own terms.** The precondition it
enforces is real and it holds: 81/81 runs reached the keypress with the stub genuinely quiet, and
the favicon race round 1 identified is gone. What the fix cannot do is make a socket exist.

**The 200 ms wait is not free, and this is a measurement rather than a claim about the fix.** The
failure needs the flush to land after the page's sockets and before the keypress. The keypress is
now `page load + readyState + ~225 ms` rather than the ~40 ms round 1 recorded, so the interval in
which a flush is fatal is about six times longer than it was before PR #189. Round 1's 60 runs
predate the wait and cannot be compared directly, and one failure in 81 is far too little to
estimate a rate; this is stated because it bears on any fix, not because it is quantified.

## 9. Environment

Branch `169-retry-under-load` at `4d13d16` (= `main`, including PR #189), worktree
`wt-169b`. Chrome 152.0.7977.65, macOS 26.6.2, 28 cores, JDK 25, Gradle 9.7.1.
`./gradlew test --tests '*DeckBehaviourTest.aRetriedRating*' --rerun`,
`SEGUE_REQUIRE_BROWSER=true`, sequential and blocking. All instrumentation reverted; the working
tree was clean at the end of the study.
