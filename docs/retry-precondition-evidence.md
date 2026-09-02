# The retry-precondition measurement

**Note (2026-09-01, issue #169, round 2).** The control this page measures failed again under load
after the fix below shipped. Round 2's trace study,
[the retry pool-flush evidence](retry-pool-flush-evidence.md), found the residual: a browser-wide
socket-pool flush that closes every socket Chrome holds, which produces no exchange and so is
invisible to the counter this page's fix added. §8's "incidental observation" below, filing the
`clients2.google.com` traffic as unrelated to the flake, is corrected: round 2 saw the flush fire
in the same millisecond that request completed (#186). See ADR 46's 2026-09-01 round-2 amendment.

**This page is a finished measurement, kept because an amendment rests on it.** On 2026-09-01 a
positive control in `DeckBehaviourTest` — that Chrome retries a POST whose connection died — had
failed seven times in two days and passed on every rerun. The test was traced sixty times with
per-request server logging and Chrome's NetLog, the mechanism was identified, and the failure was
then forced on demand. The decision that rests on this is the 2026-09-01 amendment to
[ADR 46, the rating deck](adr/0046-the-rating-deck.md); this page is the evidence behind it.

Nothing here is needed to *use* segue — start at the [user guide](user-guide.md) for that. Read
this if the retry control fails, or before changing what `DeckBehaviourTest` treats as *loaded*.

The raw handler traces and NetLogs (sixty runs, three forced failures, two probes) were not kept in
the repository; the counts and excerpts below are a dated measurement, on one machine (macOS,
Chrome 152.0.7977.65, JDK 25), and nothing regenerates them — exactly as ADR 46's 2026-08-30
figures are not regenerated. What the build enforces is the precondition the measurement found,
not the attempt count.

---

## 1. Headline numbers

| | |
|---|---|
| Runs under the specified protocol (single test, `--rerun`, sequential, blocking) | **60** |
| Failures observed in those 60 | **0** |
| Runs with a per-request port trace | 59 (run 27's trace was lost to a shell glob bug) |
| Runs with a Chrome NetLog | 34 (runs 27–60) |
| Failures **forced** by a controlled probe | 3 of 3 (100%), exact reported signature |

**The test did not flake once in 60 isolated runs on this machine.** The root cause was
nonetheless established, from the passing runs' NetLogs plus a controlled probe that
reproduces the exact failure deterministically.

Machine load during the runs (1-minute average, `sysctl -n vm.loadavg`) fell from ~30 at the
start to ~8 by run 60 on a 28-core machine — i.e. the machine was **substantially loaded
throughout**, which is if anything the condition the sightings came from, and it still did not
flake.

---

## 2. Instrumentation used (all reverted)

`DeckBehaviourTest`:
- a `com.sun.net.httpserver.Filter` on all three contexts, recording for **every** request a
  monotonic ms timestamp, method, URI, `exchange.getRemoteAddress().getPort()`, protocol and
  `Connection` header, on entry and on completion/throw;
- inside `rate(...)`, an extra line recording the port and the exact body, **alongside**
  `posts.add(...)`, which was left exactly as it was;
- `TEST` marks around the keypresses and one at the assertion point;
- teardown holds the server open for a further **2500 ms after every assertion has run**, then
  writes the trace to `$SEGUE_TRACE_DIR`, so a *late* retry would be recorded.

`HeadlessChrome.launch()`: `--log-net-log=$SEGUE_NETLOG_DIR/netlog-<ts>.json` when that env var
is set. Default capture mode.

Filter validity was checked: each run's `TEST-...DeckBehaviourTest.xml` reports `tests="1"`, so
the filter really selected the one test and did not pass by matching nothing.

---

## 3. What a passing run looks like

Typical (run 29, 3 attempts at `"rating":1`), ports from the handler trace:

```
1.267  TCP_CONNECT socket A            (Chrome's first socket)
1.269  GET /              -> socket A
1.288  TCP_CONNECT socket B            (preconnect; never carries a request yet)
1.307  GET /api/card?i=0  -> socket A  (SOCKET_POOL_REUSED_AN_EXISTING_SOCKET idle_ms=19)
1.328  GET /favicon.ico   -> socket A  (REUSED idle_ms=18)
1.370  POST /api/rate     -> socket A  (REUSED idle_ms=41)   attempt 1  ->  net_error=-100
1.787                                   HTTP_TRANSACTION_RESTART_AFTER_ERROR net_error=-100
1.787  POST /api/rate     -> socket B  (BOUND_TO_SOCKET, no connect job)  attempt 2 -> -324
2.200                                   HTTP_TRANSACTION_RESTART_AFTER_ERROR net_error=-324
2.200  POST /api/rate     -> socket C  (SOCKET_POOL_BOUND_TO_CONNECT_JOB + fresh TCP_CONNECT)
2.607                                   net_error=-324, REQUEST_ALIVE END — no restart
```

`order = [1, 1, 1, 4]`.

Across the 59 traced runs:

| observation | result |
|---|---|
| attempts at `"rating":1` reaching the handler | **3 in 54 runs, 2 in 5 runs** |
| the first POST's client port had already served an earlier request | **59 / 59 (100%)** |
| retries ever reuse the first POST's port | **0 / 59** — every retry is on a different port |
| any request arriving after the assertion point (2500 ms window) | **0 / 59** |
| `order` | `[1,1,1,4]` ×54, `[1,1,4]` ×5 |

---

## 4. The rule, as the NetLog states it

Three independent confirmations give one rule, and it is **not** about the error code and **not**
about whether the socket had previously carried a request:

> Chrome restarts the POST **iff** the attempt was bound to a socket **already in the pool**
> (`SOCKET_POOL_BOUND_TO_SOCKET` with no connect job — whether `REUSED_AN_EXISTING_SOCKET` or a
> never-used preconnect). An attempt bound via `SOCKET_POOL_BOUND_TO_CONNECT_JOB` (a socket the
> pool had to connect for this request) is **never** restarted.

Evidence:

| run | attempt | how the socket was obtained | net_error | restarted? |
|---|---|---|---|---|
| 38 (PASS, 2 attempts) | 1 | REUSED_AN_EXISTING_SOCKET idle_ms=4 | −100 `ERR_CONNECTION_CLOSED` | **yes** |
| 38 | 2 | BOUND_TO_CONNECT_JOB (fresh) | −324 `ERR_EMPTY_RESPONSE` | no |
| 29 (PASS, 3 attempts) | 1 | REUSED_AN_EXISTING_SOCKET idle_ms=41 | −100 | **yes** |
| 29 | 2 | BOUND_TO_SOCKET, **preconnect, never used** | −324 | **yes** |
| 29 | 3 | BOUND_TO_CONNECT_JOB (fresh) | −324 | no |
| favicon probe (PASS, 2) | 1 | BOUND_TO_SOCKET, preconnect never used | −324 | **yes** |
| favicon probe | 2 | BOUND_TO_CONNECT_JOB (fresh) | −324 | no |

Note run 29 attempt 2 and the favicon probe attempt 1: **−324 on a pooled socket still
restarts**. So `ERR_EMPTY_RESPONSE` is not the discriminator — the socket's provenance is. The
error code merely covaries: a socket the server had already accepted and then killed reports
−100; a socket freshly connected reports −324.

Therefore:

> **attempts at `"rating":1` = 1 + (number of established sockets in Chrome's pool for that
> origin at the moment of the POST).**

The measured distribution — 3 in 54 runs, 2 in 5 — is exactly that count being 2 or 1. **The
failure is that count being 0**, which yields exactly one attempt and a red positive control.

---

## 5. The hypothesis under test

> "Chromium's `ShouldResendRequest()` resends only when the connection was a **proven, reused
> keep-alive socket** ... On a **fresh** socket the same failure is treated as a real error and is
> not retried."

**Substantially confirmed, with one correction.** The prediction that a passing run shows the POST
on a socket that already served a request holds in 59/59 runs. The correction is that "proven,
reused" is too strong: a **preconnected socket that has never carried a request** is also
resendable (run 29 attempt 2, favicon probe attempt 1). The line is drawn at *pooled* versus
*connected-for-this-request*, not at *used* versus *unused*.

The second half of the hypothesis — that a failing run shows the POST on a first-time socket —
could not be checked against a natural failure, because none occurred. It is confirmed against a
forced one (§6).

---

## 6. Forced reproduction — the exact failure, on demand

Prediction from §4: empty the pool at the moment of the POST and the count must be 1.

Probe (instrumentation only, gated on env vars, reverted): hold `/favicon.ico` for 1500 ms so
socket A stays busy, and from the page issue three concurrent `fetch('/hold-x')` that the same
handler also stalls, so every pooled socket including the preconnect spare is occupied. Then
press "1" as the test always does.

Result: **3 runs, 3 failures**, message identical to the reported one:

```
[the abandoned rating must actually have been retried, or there is nothing to order — ...]
Expecting actual:
  1L
to be greater than:
  1L
        at DeckBehaviourTest.aRetriedRatingCannotOverwriteAReRating(DeckBehaviourTest.java:633)
```

Handler trace (probe run 1) — note the POST's port 53309 is **first-seen**, unlike every one of
the 59 unforced runs:

```
1371  GET /             port=53304
1387  GET /api/card?i=0 port=53304
1402  GET /favicon.ico  port=53304   (held)
1406  GET /hold-a       port=53305   (held)
1407  GET /hold-b       port=53306   (held)
1407  GET /hold-c       port=53307   (held)
1566  TEST probe occupied the pool
1579  POST /api/rate    port=53309   <- first-seen port
1581  BODY port=53309 body={"qid":"Q0900001","rating":1}
1981  THREW (handler stalls 400ms then throws)
      ... no second attempt at rating 1 ever ...
2032  POST /api/rate    port=53310  body={"qid":"Q0900001","rating":4}
2672  ASSERTION-POINT order=[1, 4]
2743  TEARDOWN-BEGIN, observing for 2500ms more
5253  TEARDOWN-OBSERVATION-END        <- nothing arrived in the window
```

NetLog for that same POST:

```
1.170 URL_REQUEST REQUEST_ALIVE BEGIN url=http://127.0.0.1:53295/api/rate
1.170 SOCKET      TCP_CONNECT   BEGIN address_list=['127.0.0.1:53295']   <- fresh connect
1.171 HTTP_STREAM_JOB SOCKET_POOL_BOUND_TO_CONNECT_JOB
1.171 URL_REQUEST HTTP_TRANSACTION_SEND_REQUEST_HEADERS line=POST /api/rate HTTP/1.1
1.577 URL_REQUEST HTTP_STREAM_PARSER_READ_HEADERS END net_error=-324
1.577 URL_REQUEST REQUEST_ALIVE END net_error=-324        <- no RESTART_AFTER_ERROR
```

---

## 7. Answers to the specific questions

**In a failing run, how many attempts at `"rating":1` reached the handler? Really 1, not
2-arriving-late?**
Really 1. In the forced failure the handler recorded one body, and the NetLog shows the whole
life of that `URL_REQUEST`: one `SEND_REQUEST_HEADERS`, one error, `REQUEST_ALIVE END`, no
`HTTP_TRANSACTION_RESTART_AFTER_ERROR`. Chrome never sent a second attempt at all.

**Was the POST's client port already used, or first-seen?**
Forced failure: **first-seen** (53309; earlier requests used 53304–53307). Unforced passing
runs: **already used**, 59/59.

**In passing runs, do both attempts share a port?**
**No, never** — 0/59. Every retry goes out on a new port. The retry itself always uses a
different socket; what matters is the provenance of the socket each attempt is *given*.

**Any other difference between failing and passing runs?**
The number of established sockets Chrome holds for the origin at POST time — 2 (→3 attempts,
54 runs), 1 (→2 attempts, 5 runs), 0 (→1 attempt, the failure). Elapsed time from page load to
POST was ~40 ms in every unforced run and is not itself the variable; what the timing changes is
whether an earlier request is still occupying a pooled socket when the POST is issued. In the
5 two-attempt runs, `GET /` and `GET /api/card?i=0` had landed on *different* sockets, so only one
pooled socket was free at POST time. The favicon is the closest natural hazard: it is requested
only ~6–20 ms before the POST in these traces, because `@BeforeEach` waits for `#card h1` — which
appears when `/api/card` answers — and not for the favicon, so the two genuinely race.

**Does a second `"rating":1` ever arrive after the assertion point?**
**No.** 0/59 unforced runs and 0/3 forced failures recorded any request in the 2500 ms window
after the assertion. The reasoning in the test's own comment holds: Chrome's retries happen
inside the one `fetch`, and `busy`/`current` are still held until it settles. A retry cannot
arrive late.

**The alternative considered: did the first POST die on a reused socket before arrival (a
reset the handler never saw), or go out fresh and simply not get retried?**
In the forced failure, unambiguously the **second**: the NetLog shows the fresh `TCP_CONNECT` for
that very request, one attempt, no restart. There is no preceding, unlogged attempt — the
`URL_REQUEST` source's whole history is in the NetLog and contains exactly one
`SEND_REQUEST_HEADERS`. The "died on a stale pooled socket before the handler parsed it" path was
also probed directly, with a 35 s idle gap between page load and the keypress: the JDK server did
**not** close the idle connection in that time, Chrome reused it, and the run passed with 3
attempts. So that path was not reachable here, and no evidence supports it. It cannot be ruled out
for the natural failures, since none was captured — but it requires an idle socket to be reaped,
and 35 s of idleness was not enough to reap one.

---

## 8. Environment

- Google Chrome **152.0.7977.65**, macOS 26.6.2 (25G83), 28 cores.
- Launched by `HeadlessChrome.launch()` via `ProcessBuilder`: `--headless=new --disable-gpu
  --no-sandbox --no-first-run --no-default-browser-check --disable-extensions
  --disable-background-networking --disable-component-update --remote-debugging-port=0
  --user-data-dir=<fresh temp dir> about:blank`, driven over the DevTools WebSocket. A brand-new
  throwaway profile per test, so the socket pool starts empty every time.
- JDK 25 toolchain, Gradle 9.7.1. `./gradlew test --tests '*DeckBehaviourTest.aRetriedRating*'
  --rerun`, `SEGUE_REQUIRE_BROWSER=true`, sequential and blocking.
- Machine load: 1-min average between ~30 and ~6 across the 60 runs (28 cores).
- Incidental observation, not related to the flake: despite `--disable-background-networking`,
  each launch still reaches `clients2.google.com/time/...` and `accounts.google.com/ListAccounts`.

## 9. Artefacts

The per-run artefacts listed in the original working notes were not retained; see the note at the top of this page.

---

## 10. The undrained-body question, settled (2026-09-02, issue #188)

§6 above forces this page's failure by holding a socket, and `untilQuiet()`'s javadoc names a second
way the precondition could be lost: `deck.html` returns on `!response.ok` without reading the body,
on both its card path and its rating path, and the stub answers refusals with a body. Chrome keeps a
socket checked out until a response body has been read, so the javadoc reasons that such a refusal
would strand a socket while the stub's counter — which brackets the *exchange* — reported quiet.
The precondition would then hold only because every refusal in that file is set up after the load
wait has returned, an ordering nothing asserts.

**Measured, and it does not.** Method, in two sentences: the deck page was driven to issue seven
consecutive refused card fetches (503 with a twenty-byte JSON body), and the stub recorded the
client port of every exchange it served, a reused port being a socket that went back to Chrome's
pool. The same run was then repeated with the two draining `await response.text()` lines added to
the page, and again with the refusal body grown to 4 MB.

| page | refusal body | card exchanges | distinct client ports |
|---|---|---|---|
| unchanged | 20 B | 7 | **1** |
| unchanged + both drain lines | 20 B | 7 | **1** |
| unchanged | 4 MB | 7 | 2 |

Row 1 is the finding: seven undrained refusals in a row came back on one and the same pooled socket,
reused immediately each time. Row 2 is the control that matters — draining changes nothing
observable. Row 3 is the positive control for the instrument, without which row 1 would be a dead
measurement rather than an observation: at a body large enough to still be streaming, the same
diagnostic does report a socket lost.

The mechanism is that a small response is already buffered when `fetch` resolves, and the page drops
its reference to the `Response` at once, so Chrome releases the socket without the body ever being
read. Stranding needs a body still in flight, and no refusal in this codebase produces one.

**What this changes.** The javadoc's reasoning is kept, because it is right in general and right
about the counter's limits; what is now known is that the specific hazard does not bite at the body
sizes this suite uses. The page was left unchanged (issue #188 chose its second option on this
evidence), and the javadoc remains the guard. Anyone tempted to add the draining lines as a *fix*
should read this section first: there is no red to be had, and a test that can only be green is not
a guard.

The diagnostic itself is not in the repository, deliberately. It asserts on pooled-socket identity,
which is the assertion `shouldServeOneCompletedExchangeWhenTheWarmUpRuns`'s javadoc records failing
twice — at about one run in ten, and one in sixty — because which pooled socket Chrome hands a
request is Chrome's choice. Committing it would trade a documented assumption for an intermittent
red. Like the traces above, this is a dated measurement on one machine, not something the build
regenerates; §8's environment applies, on Chrome 152.0.7977.65.
