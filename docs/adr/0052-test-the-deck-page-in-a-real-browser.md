---
status: Accepted
date: "2026-08-29"
topic: test-the-deck-page-in-a-real-browser
tags: [project, testing, tooling]
supersedes: []
related: [the-rating-deck, jvm-quality-gates-junit-6-spotless-jacoco-archunit, use-test-driven-development, ci-is-the-merge-gate, graph-exporter-views-and-formats]
---
# 52. Test the deck page in a real headless browser, driven over DevTools, with no new dependency

## Context

`src/main/resources/rate/deck.html` is the page [ADR 46](0046-the-rating-deck.md) serves on
loopback, and it writes the `affinity` table — the one thing in segue with no source to regenerate
it from. It was, until now, the only part of the project with no executable test. Its assertions
lived in `DeckPageTest`, which reads the file as text.

Issue #103 measured what that was worth. Mutation-testing the shipped page against those
assertions: a **deleted** guard was caught, and a **defective** one was not.

| Mutation | `DeckPageTest` |
|---|---|
| Delete the whole `if (!response.ok)` branch | fails ✓ |
| Keep the branch, delete its `return` | **passes ✗** |
| Delete the `if (response === null)` branch | **passes ✗** |
| Delete `if (event.repeat) return;` | fails ✓ |
| Replace it with a comment naming `event.repeat` | **passes ✗** |
| Replace the modifier guard with a comment naming `ctrlKey`/`metaKey`/`altKey` | **passes ✗** |
| Delete `if (busy) return;` from `skip()` | fails ✓ |
| Replace both `busy` guards with a `// busy` comment | **passes ✗** |

Row 2 is the one that decided this. The assertion pins that the token `response.ok` appears before
`rated++`; deleting one `return` reinstates the exact silent-data-loss defect issue #101 fixed — a
refused rating counted as saved and the deck dealt on — and the suite stays green. ADR 46 gives no
way to withdraw a rating, so a rating lost that way is lost.

A token-presence assertion cannot see the difference between a guard and a guard-shaped comment.
Only running the page can.

### What running it costs

The page is a hundred lines of `async`/`await` over `fetch`, using `replaceChildren`, `Map` and
`KeyboardEvent.repeat`. Whether a runtime can execute it was **measured, not assumed**.

## Decision

**The five guards are asserted against the real page running in a real headless Chrome, driven
over the DevTools protocol from a test-only class, with no new dependency in
`gradle/libs.versions.toml`.** `DeckBehaviourTest` launches the browser, serves `deck.html` from a
stub that can refuse, stall or die mid-request, and asserts on what the owner would see: which card
is on screen, how many ratings the session claims, what actually reached the server.

**Every test was verified to fail against the *defective* mutation, not merely against the guard's
absence** — all eight rows above, with the failure recorded in the issue.

**Where no browser is installed the tests skip, and CI is made unable to skip them.** `tasks.test`
forwards `SEGUE_REQUIRE_BROWSER`; with it set, a missing browser is an assertion failure rather than
a skip, and the CI workflow sets it and installs Chrome. This is issue #93's lesson applied a
second time — the Graphviz install CI already carries, for the hover test
[ADR 41](0041-graph-exporter-views-and-formats.md) describes: the one check
standing between this page and a silent regression must not be able to report success by never
having run. **The guard was itself positively controlled** — pointed at a non-existent browser, the
suite fails with the property set and reports ten skipped tests without it.

`DeckPageTest` keeps only what a running page cannot answer: that the page reaches no external
host, that the ratings are real `<button>` elements, that the region a screen reader is told to
watch is the region the script rewrites, that the card is built as text rather than markup, and
that the revision banner has a background fill rather than merely a colour.

**Amendment (2026-09-02, issue #186): the test browser's network posture is loopback-only, and
enforced rather than commented — and the #169 flush was measured against it.**

Until now this class carried `--disable-background-networking` and `--disable-component-update`
under a comment saying "nothing here should reach the network: the deck is offline by design."
Every NetLog captured for issue #169 shows that comment was false: Chrome reached
`clients2.google.com`, `accounts.google.com` and `gstatic.com` on each launch, opened QUIC sessions
to them, and — the reason it stopped being a hygiene question — tore all of that down at once when
it settled, taking the loopback sockets with it
([the retry pool-flush evidence](../retry-pool-flush-evidence.md) §4–§5).

**The posture.** `HeadlessChrome.launch()` adds
`--host-resolver-rules="MAP * ~NOTFOUND, EXCLUDE localhost, EXCLUDE 127.0.0.1"`. Every hostname
outside loopback fails at DNS, so no socket, no TLS handshake and no QUIC session to a non-loopback
host can exist. `EXCLUDE 127.0.0.1` is load-bearing and was not obvious: the spec for #186 assumed
an IP literal is never resolved, and it is — with `EXCLUDE localhost` alone Chrome 152 maps
`127.0.0.1` to `~NOTFOUND` like any other name and the deck never deals a card. That is the first of
two claims this work falsified by measurement.

**The flags, and only the flags a NetLog justified.** On top of the resolver rule, the flags that
stop an attempt being *made* were added one at a time against the NetLog, keeping only those that
removed something. Of the **28 candidates** measured that way — Puppeteer's set and a dozen more,
each named in the comment on `HeadlessChrome.flags`, which is the list rather than this page —
**one** removed anything:
`--disable-features=NetworkTimeServiceQuerying,SafeBrowsingHashPrefixRealTimeLookups`, which removes
`clients2.google.com/time` (the request round 2 caught sharing the flush's millisecond) and
`www.gstatic.com/ohttp_gateway/…`. That is the second falsified claim: the spec expected Puppeteer's
set to suppress these attempts, and not one of them did. No flag is kept on belief; a flag that
removes nothing is a flag nobody can explain later.

**The guard.** `HeadlessChromeNetworkTest` launches the harness's Chrome exactly as the deck test
does, loads the stub page, issues one warm-up, closes the browser, parses the NetLog through
`NetLog` and asserts two things of different strengths, because "reaches nothing but loopback" is
not one fact:

- **Zero reached** — no `TCP_CONNECT`, no `SSL_` event, no QUIC session and no byte in either
  direction to any host but `127.0.0.1`. This is the property the flush and the offline claim
  depend on, and the one the positive control makes fail: remove the resolver rule and the test
  names `clients2.google.com`.
- **An exact allowlist of what is asked for** — `KNOWN_ATTEMPTS`, checked with `isSubsetOf`, naming
  the attempts that survive every flag and die at DNS: `accounts.google.com`, `www.google.com`,
  `android.clients.google.com`, `~notfound` (the rule's own target, which Chrome logs in the host
  position) and `2001:4860:4860::8888` (Chromium's hardcoded IPv6 reachability probe — a UDP
  `connect()` on :443 with no byte or packet event, and not Secure DNS). The list is a **debt, not
  a design**: it can only over-list, an attempt that stops happening will never announce itself, and
  it is re-derived when the browser changes rather than trimmed on a hunch. The comment on the
  constant carries the per-host measurement; it is not restated here.

The claim in this ADR's own `DeckPageTest` paragraph above — that the *page* reaches no external
host — is unchanged and is about `deck.html`. What is new is that the *browser* the suite launches
is now held to the same standard, by an assertion instead of a comment.

**What the measurement found, and why no wait was added.** Round 2 could not tell whether the flush
was driven by the phone-home or fired anyway. With the phone-home dead at DNS, 80 traced launches
running the retry scenario say **both, separately**: the
`QUIC_SESSION_POOL_MARK_ALL_ACTIVE_SESSIONS_GOING_AWAY` marker still fires, once per launch, in
80 of 80 — so the notification does not need the phone-home — and it closed **nothing** in 80 of 80,
because by then there is no Google socket to tear down and the page's own sockets do not yet exist.
No `SOCKET_POOL_CLOSING_SOCKET` burst occurred in any run; the marker preceded the page's first
socket in every one; the abandoned rating was attempted three times in every one; and
`aRetriedRatingCannotOverwriteAReRating` passed 60 of 60 under load.

**So the destructive event is gone from this harness's timeline, and it is not gone from the
browser.** A control that plants the page load on Chrome's own command line — so a loopback socket
is idle in the pool when the marker fires — had that socket closed by the marker in **16 of 20
runs**, each close carrying Chrome's own reason for it, `"Cert verifier changed"`, with two
`CERT_VERIFY_PROC_CREATED` events in the same millisecond ahead of it — which names the handler
round 2 could only describe. The flush is alive; what saves the deck tests is that the page now
loads 57–140 ms after the marker in 69 of the 80 traced runs, and 574–683 ms after it in the other
eleven, and nothing enforces that ordering: the gap is about one 50 ms poll interval of
`HeadlessChrome`'s own DevTools handshake. **No wait was added**, because the licensed shape of one
is a condition rather than a bound and the two candidate conditions came out as follows —
`Network.enable` on Chrome 152's browser target does not exist (`-32601`, 3 probes of 3), while
tailing the NetLog live *does* work and is recorded, with its measured latency, for whoever needs it
if that margin ever narrows. Adding a wait now would also be adding one that cannot be shown red in
the fixture it protects: nothing in the harness produces a late flush on demand.

The full study, with every raw per-run figure behind the numbers above, is
[docs/loopback-only-evidence.md](../loopback-only-evidence.md), a dated measurement kept the same
way as its two siblings. [ADR 46](0046-the-rating-deck.md)'s second retry amendment carries a dated
note pointing at it.

**What this amendment does not do.** No production change: `deck.html` and `RateServer` are
untouched, and `HeadlessChrome` is test-only. No claim that the phone-home *caused* the flush — the
measurement says it did not. No new dependency, and no NetLog capture on the ordinary launch path:
`HeadlessChrome.launch()` without a path builds exactly the command line it always did.

**Note (2026-09-02, issue #186, Task 3): the page is now loaded only after the flush has passed.**
This note corrects two sentences in the amendment above — "**No wait was added**", and "no NetLog
capture on the ordinary launch path: `HeadlessChrome.launch()` without a path builds exactly the
command line it always did". Both stopped being true the same day. Nothing else in the amendment
moves: the posture, the flags, the guard and every figure it reports stand as written.

**The condition.** `HeadlessChrome.open` does not send `Page.navigate` until Chrome's startup
cert-verifier flush has passed, and it learns that from Chrome's own NetLog — the
`QUIC_SESSION_POOL_MARK_ALL_ACTIVE_SESSIONS_GOING_AWAY` line, or a second `CERT_VERIFY_PROC_CREATED`
— matched by a name resolved through the log's own `constants` block, never a hardcoded id, and read
from the tail of a file that is not valid JSON until the browser exits (`NetLog.Tail`). That is why
`launch()` now writes a NetLog: to a temporary file it owns and deletes on `close()`, on a command
line otherwise identical to the one the guard measures, deliberately so that the guard keeps
measuring the browser the deck tests run.

**In Chrome's default capture mode.** This note's first draft kept the `--net-log-capture-mode=
IncludeSensitive` the guard had been passing since the amendment above, on that flag's stated
reasoning — the default "strips URLs and headers it judges private". Measured on this flag set the
two modes name the **identical** host set and both carry the flush marker, because every parameter
`NetLog` reads is in the default capture. So the sensitive mode was buying nothing and costing every
launch a temporary file that may hold cookies and credentials; it is gone, and the sentence in
`HeadlessChrome` that claimed otherwise is corrected there.

**What the wait is worth, given that it never fired on this machine.** In 20 measured launches the
marker was already in the file at the first poll, so the wait confirmed an ordering that already
held (§9 says so plainly). Its value is the case this machine did not produce: §6 saw the marker
take **1262 ms** to become visible once in five, and any machine slower than this one — or any
change that speeds the DevTools handshake — narrows the 57 ms of slack §6 measured until it is gone.
The wait costs 16 ms to remove that dependence on luck.

**The fallback bound, and that it is a bound.** **2500 ms**, counted from the browser's launch,
after which `open` **proceeds** and the launch prints which of the two ended the wait. The value is
the measured p100 of the marker's visibility in the file (§6 of the study) plus about 45%. It is
deliberately **not** a timeout: a Chrome that never creates a certificate verifier has no flush to
wait for, and that is the outcome this whole line of work wants — failing there would make good news
red. Planted and observed, the line it prints is `proceeded on the 2500 ms fallback bound after
2512 ms — this NetLog never showed the flush, which is the good outcome, not an error`.

**The red.** The wait was made to fail before it was written, on the shape §4's independent driver
used: the stub's URL on Chrome's own command line, a bare page with neither favicon nor preconnect
in the race for the pool, and the harness's own flags read by reflection so they could not drift.
**20 runs of 20 had the page's loopback socket closed by the marker**, in the marker's own
millisecond, each carrying `SOCKET_POOL_CLOSING_SOCKET {"reason": "Cert verifier changed"}` — and
none carried the `"Socket generation out of date"` that §4 attributes to the deck driver's own race,
which is what removing the favicon and the preconnect was for. With the wait in place and the page
loaded through `open`, **0 of 20**: the page's socket outlives the flush and is closed only by the
browser exiting.

**What it costs.** 15–20 ms per launch over those 20 runs, **p50 16.5 ms, p100 20 ms**, and **no
launch reached the bound**. Most of it is the single parse of the log's constants block: by the time
the DevTools handshake is done, the marker is already in the file. The raw list is in §9 of the
study.

**What this note does not do.** No production change, and no new dependency. It does not claim the
flush is gone — §4's mechanism is exactly as alive as it was, and 20 of 20 above is the proof. What
it removes is the **luck**: the 57 ms of accidental slack §6 measured is no longer what stands
between Chrome's cert verifier and the deck's sockets. Separately, `android.clients.google.com` came
**out** of `KNOWN_ATTEMPTS` on the rule the constant's own javadoc now states — the list is what
*this test's scenario* asks for, and hosts that only other scenarios ask for are recorded in the
study's §5, where a red naming one means the guard's scenario changed rather than that the list is
short.

## Alternatives considered

**HtmlUnit — a pure-Java browser, needing nothing installed.** The preferred answer, and it does
not work. Measured against the real page on **5.4.0**, the current release: `fetch` is `undefined`
and the JavaScript engine does not parse `async`, so the entire `<script>` block is one syntax
error and the deck never leaves "loading…". A runtime that cannot run the page cannot test it. The
4.13.0 release behaves identically; this is not a version to wait out.

**Playwright, or Selenium.** Both work, and both were rejected for the same reason: once a real
browser is required anyway, they buy an API over a protocol the JDK already speaks. Playwright's
Java client also carries a driver bundle of over a hundred megabytes and, by default, downloads its
own browsers — a large addition to a build whose only test-scope dependencies are JUnit, AssertJ,
ArchUnit and Boot's test starter. `ProcessBuilder` launches Chrome, `java.net.http.WebSocket`
carries the commands, and Jackson — already a dependency — reads the answers. Chrome is
**discovered, never downloaded**. If the driver class ever grows past what one file can carry, this
is the decision to revisit.

**A JavaScript engine plus a DOM shim (GraalJS).** More scaffolding than either browser route, and
it would assert against the shim rather than against a browser. The page's whole risk is what a
browser does with a held key and a refused POST; a shim is where those answers would be invented.

**Stronger structural assertions.** A nesting-aware check could raise the bar — requiring the
`return` to be inside the guard's own block rather than merely following the token. It cannot
verify runtime behaviour, and it would still be a statement about the file. It stays for the two
things that genuinely are statements about the file (markup construction, and the CSS fill), and is
described as such.

**Restructuring the page so the guards are directly exercisable.** Extracting the logic still needs
a JavaScript runtime to exercise it, so it buys nothing over running the page — and it would change
production code to suit a test, on the one page where a behaviour-preserving edit is hardest to
prove. **No production code was changed.**

## Consequences

- `./gradlew check` stays green on a machine with no browser. It reports ten skipped tests, which
  is visible, and CI cannot reach that state.
- **The suite costs about fifteen seconds of `check`** — measured at 15.3s for the ten tests, a
  fresh browser per test. That is the price of per-test isolation: a stub server and a profile that
  no previous test can have left in a state. Sharing one browser across the class would recover
  most of it and is the first thing to try if the build time starts to matter.
- The suite now depends on a browser being present in CI. That is a real new failure mode, and it
  is the one deliberately chosen over a check that quietly stops running.
- **Chrome retries a POST whose connection dies before any response arrives.** Found by this suite,
  not reasoned about: one unanswered rating reached the stub three times. It is the browser's
  behaviour and not this page's, and it is a second reason the page may not treat an unanswered
  rating as written — it cannot know how many of those attempts landed.
- **One mutation is not caught, and it is equivalent rather than missed.** Deleting the `busy` half
  of `rate()`'s own `if (busy || !current)` changes no behaviour: every path that sets `busy` nulls
  `current` first, and the one window where `current` is set while `busy` is still true contains no
  `await`, so no keystroke can be handled inside it. It is defence in depth, and a test asserting
  it would be asserting nothing.
- **The tests are wall-clock by design, and a maintainer chasing an intermittent failure needs to
  know exactly where.** The page's guards are about *timing* — a key held down, a keypress arriving
  while a POST is in flight — so there is no version of this suite with no clock in it. What the
  clock may never do is decide an assertion. Every wait that a result depends on is a **condition**:
  `HeadlessChrome.until` polls the page for up to 15s, and `DeckBehaviourTest.untilSent` waits up to
  15s for a POST to reach the stub. The residual, in full:
  - **Timing as stimulus, never as verdict.** A stalled response is held 400ms and the competing
    keypress is sent at 80ms — 320ms of slack — and a held key is repeated at a 33ms cadence. Slip
    any of these on a slow runner and the test goes **red**, loudly, not green.
  - **A 600ms `settle()` remains in four tests**, and in each one the assertions it precedes are
    already anchored by a `chrome.until` on something visible — the failure message appearing, the
    deck moving. It is belt over braces, not the thing being trusted.
  - **The two tests that assert something was *not* sent have no residual at all**, and that is
    deliberate. Gated on a sleep they were the one shape that fails *silently*: a leaked POST a
    slow runner had not yet delivered is indistinguishable from a POST the guard suppressed, so the
    build would go green asserting a guard that is not there — this issue's own defect, in the test
    closing it. Each now drives a later action that must post and waits for **that** to land, so a
    leak is either already in the list ahead of it or has swallowed the sentinel and failed the
    wait. Verified by making the leaked POST arrive three seconds late at the stub: the sleep-gated
    version passes with the guard deleted, this one fails.
- The driver is test-only and lives beside the test that uses it. It is not a general browser
  harness and should not grow into one without a decision.
