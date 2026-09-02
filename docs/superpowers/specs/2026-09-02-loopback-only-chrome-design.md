# The test browser reaches nothing but loopback — enforced, and measured against the flush

Issue #186. Written 2026-09-02. Follows #169 rounds 1 and 2 (PRs #189, #192).

## What is wrong

`HeadlessChrome.launch()` passes `--disable-background-networking` and `--disable-component-update`
under a comment that says *"Nothing here should reach the network: the deck is offline by design."*
Every NetLog captured in #169 shows the comment is false: on each launch Chrome reaches
`clients2.google.com`, `accounts.google.com` and `gstatic.com`, opens QUIC sessions to them, and
creates a certificate verifier for them. The comment is a claim about the harness's network surface
that the harness does not keep, and a claim like that is exactly what someone reasons from next time.

## Why it is more than hygiene

`docs/retry-pool-flush-evidence.md` §4–§5 identifies the residual flake in
`aRetriedRatingCannotOverwriteAReRating` as a **one-shot, browser-wide configuration-change flush on
Chrome's own startup clock**: at 667–884 ms after launch, as its background network work settles —
the `clients2.google.com/time` request completing, `CERT_VERIFY_PROC_CREATED`, every QUIC session
marked going away — Chrome closes every socket it holds, including the 400 ms-old loopback sockets
that have nothing to do with TLS, certificates or QUIC. The page's sockets are created at 729–900 ms.
The outcome is the sign of the difference. Round 2 shrank the window the flush can land in; it did
not touch the flush.

If that flush is *driven* by the phone-home settling, removing the phone-home may remove the flush.
If a configuration-change notification fires anyway with nothing to fetch, the flush persists on the
startup clock — and then the right move is to load the page only after it has passed. Nobody knows
which; this work finds out, and does not guess.

## The decision

**1. Loopback only, enforced, not commented.** `HeadlessChrome.launch()` adds
`--host-resolver-rules="MAP * ~NOTFOUND, EXCLUDE localhost"`: every hostname resolution outside
loopback fails at DNS, so no socket, no TLS handshake and no QUIC session to a non-loopback host can
exist. The page is loaded by IP literal (`http://127.0.0.1:port/`). *Corrected 2026-09-02 by measurement
during Task 1:* that literal is **not** exempt — Chrome 152 maps `127.0.0.1` through the rule too,
and with `EXCLUDE localhost` alone the deck never deals a card. `EXCLUDE 127.0.0.1` is load-bearing. On
top of the guarantee, the flags that stop the *attempts* being made — so there is nothing for a
configuration change to tear down and no DNS failure to log: the attempt-suppressing set Puppeteer
launches with (`--disable-features=…`, `--disable-sync`, `--disable-default-apps`,
`--metrics-recording-only`, `--no-service-autorun`, `--disable-domain-reliability`,
`--disable-client-side-phishing-detection`, `--safebrowsing-disable-auto-update`,
`--disable-component-extensions-with-background-pages`, `--disable-breakpad`). **Added one at a time
against the NetLog**, keeping only those that remove an attempt; a flag that removes nothing is a
flag nobody can explain later. *Measured 2026-09-02, Task 1:* of the sixteen candidates named here
and twelve more, **one** removed anything — `--disable-features=NetworkTimeServiceQuerying,
SafeBrowsingHashPrefixRealTimeLookups`, which removes `clients2.google.com/time` (the request that
shared the flush's millisecond) and `www.gstatic.com/ohttp_gateway/…` outright. Three attempts
survive every flag found: `accounts.google.com/ListAccounts`, `android.clients.google.com/checkin`,
`www.google.com/async/folae`. They die at DNS under the rule; no launch flag stops them being asked.

**2. A guard that fails when the claim stops being true.** `HeadlessChrome` gains an optional
NetLog capture (`--log-net-log=<file>`, JSON), and a test — `HeadlessChromeNetworkTest` — launches
the harness's Chrome exactly as the deck test does, loads the stub page, issues one warm-up, closes
the browser, parses the NetLog, and asserts **no request, resolution or socket to any host other
than `127.0.0.1`**. The comment becomes an assertion. *Corrected 2026-09-02:* "no request" is
unreachable with launch flags (three attempts survive, above), so the guard asserts two things with
different strengths — **zero reached** (no connect, handshake, QUIC session or byte to a non-loopback
host: the property the flush and the offline claim depend on) and an **exact allowlist of what is
asked for** (`KNOWN_ATTEMPTS`: the three DNS-dead attempts, so a *new* attempt is a red, and the
list is the documentation of what Chrome still tries). Positive control: remove the resolver rule and
watch the test name `clients2.google.com`.

**3. Measure the flush, then decide.** With loopback enforced, 60 launches with NetLog, the same
protocol as round 2: does `QUIC_SESSION_POOL_MARK_ALL_ACTIVE_SESSIONS_GOING_AWAY` / the
`SOCKET_POOL_CLOSING_SOCKET` burst still occur, and at what offset from launch? And the retry test 60
times under the same load as round 2. Three outcomes, each with its own consequence:
- **The flush is gone.** Record it; the residual documented in ADR 46's second amendment is closed by
  this change, and the amendment says so.
- **The flush persists on the startup clock.** Then the page must not be loaded until it has
  passed. If the settling is observable — a CDP event, a NetLog marker readable live, or Chrome's
  own `Network` idle on the browser target — `launch()` waits for that **condition**. If it is not,
  `launch()` waits a **measured bound** past the latest flush seen (p100 + margin), labelled a bound,
  with the measurement cited, and the residual restated. Either way the retry test's warm-up stays:
  it is cheap and it guards a different window.
- **The flush moves or changes shape.** Measure again; do not fit a story to one run.

## What this does not do

- No production change. `deck.html` and `RateServer` are untouched.
- No claim that the phone-home *causes* the flush until the measurement says so. Round 2 saw them
  share a millisecond; round 2's page names no handler and neither does this spec.
- No suppression flag added on belief. Every flag kept is one the NetLog showed removing an attempt.

## Rejected

- **Correct the comment and leave the flags.** True prose about a false posture; the harness
  would still open three QUIC sessions per launch that a configuration change tears down.
- **Block with `--proxy-server` to a dead port.** Works, but proxies loopback too unless
  bypassed, and the bypass list is a second place the loopback rule lives. The resolver rule
  touches only names, and the page has no name.
- **Only the resolver rule, no attempt suppression.** Guarantees nothing leaves the machine, but
  Chrome still *tries* — DNS failures, retries, and the same configuration-change machinery running
  on empty. The measurement in §3 decides whether that machinery is the flush; suppressing the
  attempts is what makes the measurement answer the question.
- **Fix the flush by waiting a fixed second after launch, now.** It might work today and would be
  the fourth bound in this file. The measurement comes first; a wait is added only in the shape
  the measurement licenses.

## Testing

- `HeadlessChromeNetworkTest` is seen **red on the current flags** — a real red, since the current
  harness reaches Google on every launch — then green with the rule, then red again on the control.
- Each attempt-suppressing flag: NetLog before and after, one flag at a time; the report lists the
  attempts each removed. Flags removing nothing are not kept.
- The 60-launch flush measurement and the 60-run retry tally, under load, with NetLog per run —
  reported in the register of `docs/retry-pool-flush-evidence.md`, as a third page.

## Recorded

ADR 52 (the headless-browser decision) gains a dated amendment: the network posture is loopback-only
and enforced by `HeadlessChromeNetworkTest`, the flags and why each is there (citing the NetLog
measurement, not belief), and — depending on §3's outcome — either that the #169 residual is closed,
or the wait `launch()` now performs and its basis. ADR 46's second amendment gains a dated note
pointing at the outcome. The measurement page is linked where its two siblings are.
