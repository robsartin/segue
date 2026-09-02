# The flush, measured with the browser on loopback only — round 3

> **Note added 2026-09-02 (Task 3).** §6's "A wait was considered and not added" no longer describes
> the harness: the wait it names — the NetLog tailed live — was built the same day, and §9 below
> carries its red, its measured cost and the bound it falls back on. Nothing above §9 has been
> changed; §6 is left standing as the state of the question when it was asked.

**Evidence only. No fix is proposed here, and none was made.** Round 1
([the retry-precondition measurement](retry-precondition-evidence.md)) found the resend rule; round 2
([the retry pool-flush evidence](retry-pool-flush-evidence.md)) found the residual — a browser-wide
socket-pool flush on Chrome's own startup clock that closes every socket the browser holds, the
loopback ones included, and that fired in the same millisecond as Chrome's own
`clients2.google.com/time` request. Round 2 asked one question it could not answer: **is the flush
driven by that phone-home, or does it fire anyway with nothing to fetch?** Issue #186 removed the
phone-home's ability to reach anything and measured again. This page is that measurement.

Same caveats as its siblings: one machine (macOS 26.6.2, 28 cores, Chrome 152.0.7977.65, JDK 25,
Gradle 9.7.1), a dated measurement that nothing regenerates, and the raw NetLogs are not retained in
the repository. The scenario driver used to capture them was scratch code, reverted; what the build
keeps is `HeadlessChromeNetworkTest`, which asserts the network posture, not these timings.

The decision this page supports is the 2026-09-02 amendment to
[ADR 52, the headless-browser decision](adr/0052-test-the-deck-page-in-a-real-browser.md), and the
dated note it puts on [ADR 46](adr/0046-the-rating-deck.md)'s second retry amendment.

---

## 1. Headline

| | |
|---|---|
| Launches traced with a NetLog, running the retry scenario as the test does | **80** (60 under load, 20 quiet) |
| Runs where a `SOCKET_POOL_CLOSING_SOCKET` **burst** occurred (≥2 sockets in one millisecond) | **0 / 80** |
| Runs where any socket at all closed within 2 ms of the flush marker | **0 / 80** |
| Runs where the `QUIC_SESSION_POOL_MARK_ALL_ACTIVE_SESSIONS_GOING_AWAY` marker still fired | **80 / 80**, once each — round 2 logged **three** |
| Runs where the marker fired **before** the page's first socket existed | **80 / 80** |
| Attempts at the abandoned rating (three is the healthy case) | **3 in 80 / 80** |
| Non-loopback hosts **reached** — connect, handshake, QUIC session or byte | **0 in 80 / 80** |
| `aRetriedRatingCannotOverwriteAReRating`, run 60 times under load | **60 pass, 0 fail** |

**The answer to round 2's question: both halves, separately.** The configuration-change
notification fires anyway — it does not need the phone-home, and suppressing the phone-home did not
remove it. What it no longer does in this harness is close anything: with no Google sockets to tear
down and the page's sockets not yet created, the marker passes over an empty pool 80 times out of
80. The **destructive event round 2 measured did not occur once**; the **notification that caused it
is still there**, and §4 shows it still closing a loopback socket when one exists at that moment.

So the residual is narrowed, not removed, and §6 says exactly what it is now.

---

## 2. Instrumentation and protocol

Round 2's protocol, with round 2's load lever (§3 of that page): CPU pressure from 40
`yes > /dev/null` spinners, and one `./gradlew test --tests … --rerun` per run, sequential and
blocking, `SEGUE_REQUIRE_BROWSER=true`.

The scenario driver was a scratch JUnit class that reproduces
`DeckBehaviourTest.aRetriedRatingCannotOverwriteAReRating` line for line — the same stub with the
same four contexts and the same accounting filter, `HeadlessChrome.launch(netLog)`, the page loaded
by IP literal, the wait for the first card, `readyState`, `untilQuiet()`, `warmUp()`, the keypress,
the re-rating, `untilSent(4)` and a 600 ms settle — and recorded per run the stub's port, the
ratings that reached it in arrival order, and the NetLog path. It was deleted after the measurement;
nothing in the repository runs it.

Every NetLog was then read for four things: the offset of the going-away marker from the browser's
first logged event, the offset of the first `TCP_CONNECT` to the stub's port, every
`SOCKET_POOL_CLOSING_SOCKET` with its socket source id, and every host named anywhere in the log —
the last using the same reached/asked-for split `HeadlessChromeNetworkTest` applies (`NetLog.Kind`,
and the event list in `reachedTheNetwork`).

**Instrument control.** Not repeated here: round 2 established it (§2 of that page — 20 of 81 runs
with the NetLog flags removed were indistinguishable), and this study changes nothing about how the
NetLog is captured.

---

## 3. The two batches, raw

Offsets are milliseconds from the browser's first logged event, which is Chrome's own clock —
directly comparable with round 2 §5's "flush at 667–884 ms, page sockets at 729–900 ms".

### Under load — 60 runs, 1-minute load average **35.26 – 156.50** on 28 cores

`marker − page socket`, one entry per run, sorted:

```
-683 -667 -667 -656 -654 -140 -139 -127 -121 -117 -117 -115 -114 -114 -112 -112
-112 -111 -110 -110 -109 -108 -107 -107 -107 -107 -106 -106 -105 -102 -102 -102
-102 -102 -102 -101 -101  -98  -97  -97  -97  -96  -96  -96  -96  -95  -95  -95
 -95  -95  -94  -94  -93  -92  -91  -90  -89  -87  -87  -81
```

Marker offsets, sorted:

```
 209  234  235  255  263  716  722  741  742  749  750  750  751  753  754  755
 756  757  760  761  762  762  762  764  765  766  767  768  769  775  777  778
 779  780  780  781  781  781  783  784  785  785  786  786  787  788  789  790
 790  796  797  802  802  803  804  810  811  813  815  838
```

First page-socket offsets, sorted:

```
 803  831  843  846  850  852  852  852  852  855  857  857  858  859  860  863
 865  866  867  868  868  869  870  873  875  875  878  879  879  879  880  887
 889  890  892  893  893  893  893  894  896  896  897  898  899  901  902  902
 902  902  904  905  905  908  910  910  912  922  946  952
```

Bursts, and sockets closed within 2 ms of the marker: **0 in every one of the 60**. Attempts:
**3 in every one of the 60**. Five runs (27, 28, 41, 47, 53) carry a marker at 209–263 ms rather
than in the main band; they are the five margins beyond −600 above, and they are earlier, not later,
so they change nothing about the sign.

### Quiet — 20 runs, 1-minute load average **6.27 – 10.24**

Run by run, so the derived numbers below can be checked against the rows they come from:

| run | marker | first page socket | marker − page | burst | closed at marker | attempts |
|---|---|---|---|---|---|---|
| 1 | 667 | 732 | −65 | 0 | 0 | 3 |
| 2 | 135 | 741 | −606 | 0 | 0 | 3 |
| 3 | 665 | 729 | −64 | 0 | 0 | 3 |
| 4 | 653 | 715 | −62 | 0 | 0 | 3 |
| 5 | 140 | 714 | −574 | 0 | 0 | 3 |
| 6 | 660 | 719 | −59 | 0 | 0 | 3 |
| 7 | 662 | 721 | −59 | 0 | 0 | 3 |
| 8 | 674 | 737 | −63 | 0 | 0 | 3 |
| 9 | 661 | 721 | −60 | 0 | 0 | 3 |
| 10 | 143 | 723 | −580 | 0 | 0 | 3 |
| 11 | 654 | 718 | −64 | 0 | 0 | 3 |
| 12 | 149 | 764 | −615 | 0 | 0 | 3 |
| 13 | 657 | 719 | −62 | 0 | 0 | 3 |
| 14 | 134 | 747 | −613 | 0 | 0 | 3 |
| 15 | 669 | 730 | −61 | 0 | 0 | 3 |
| 16 | 136 | 720 | −584 | 0 | 0 | 3 |
| 17 | 674 | 731 | −57 | 0 | 0 | 3 |
| 18 | 657 | 723 | −66 | 0 | 0 | 3 |
| 19 | 666 | 725 | −59 | 0 | 0 | 3 |
| 20 | 664 | 726 | −62 | 0 | 0 | 3 |

### What the two batches say together

| | round 2 (81 runs) | round 3 loaded (60) | round 3 quiet (20) |
|---|---|---|---|
| flush marker | 667 – 884 | 209 – 838 (716 – 838 excluding 5) | 134 – 674 (653 – 674 excluding 6) |
| page's first socket | 729 – 900 | 803 – 952 | 714 – 764 |
| marker − page socket | −110 … **+53** | −683 … **−81** | −615 … **−57** |
| runs with the flush landing late | 5 of 61 traced (1 fatal) | **0 of 60** | **0 of 20** |
| going-away markers logged per launch | 3 (QUIC pools #28, #35, #7) | **1** | **1** |
| sockets closed by the marker | 6, across 5 origins | **0** | **0** |

The marker count fell for the same reason the burst did, and it is worth saying together: the flush
marks and closes what the browser is holding, and the browser is now holding almost nothing. Six
sockets across five origins became none, and three QUIC session pools with live sessions became one
pool marked on an empty browser. Neither number is the flush getting weaker — §4 is the test of
that.

**The margin is one-sided now, and it is also narrow and incidental.** In the 69 runs whose marker
falls in the main band it is 57–66 ms quiet and 81–140 ms loaded; the other 11 runs — the five and
six early markers above — are 574–683 ms clear, and are not what any bound should be read off. Both sides move together with load —
round 2 §5's finding, reproduced. Nothing in the harness *enforces* that ordering: the gap is
roughly one poll interval of `HeadlessChrome`'s own DevTools handshake (`devToolsPort` and
`pageEndpoint` sleep 50 ms between attempts), which is to say the page is late enough by accident,
not by design. §6 says what follows from that.

---

## 4. The mechanism is intact — a planted early page load

The 80 runs above cannot say whether the flush *would* still close a loopback socket, because in
none of them did one exist when the marker fired. So a control was run that puts one there: the
stub's URL was passed to Chrome on its own **command line** instead of `about:blank`, so the page
loads as early as Chrome can load it and its socket is idle in the pool well before the marker. Same
flags, read out of `HeadlessChrome.flags` by reflection so they could not drift from the harness's.
Twenty runs, quiet box:

| run | marker | first page socket | socket the marker closed |
|---|---|---|---|
| 1 | 736 | 709 | SOCKET 19 |
| 2 | 762 | 738 | SOCKET 19 |
| 3 | 709 | 686 | SOCKET 19 |
| 4 | 729 | 705 | SOCKET 19 |
| 5 | 748 | 715 | SOCKET 19 |
| 6 | 749 | 711 | — |
| 7 | 734 | 191 | SOCKET 18 |
| 8 | 786 | 746 | — |
| 9 | 731 | 705 | SOCKET 19 |
| 10 | 769 | 740 | SOCKET 19 |
| 11 | 744 | 712 | SOCKET 19 |
| 12 | 742 | 717 | SOCKET 19 |
| 13 | 777 | 752 | SOCKET 19 |
| 14 | 731 | 707 | SOCKET 19 |
| 15 | 762 | 736 | SOCKET 19 |
| 16 | 748 | 717 | SOCKET 19 |
| 17 | 751 | 716 | — |
| 18 | 754 | 727 | SOCKET 19 |
| 19 | 762 | 735 | SOCKET 18 |
| 20 | 753 | 202 | — |

**20 of 20 put a loopback socket in the pool before the marker; 16 of those 20 had it closed by the
marker, in the marker's own millisecond — and the log says why.** Round 2 wrote that "the exact
Chromium handler was not identified and this page does not name one." These logs name it: the
closing event carries a reason, verbatim, and two `CERT_VERIFY_PROC_CREATED` events land in the same
millisecond ahead of it, in 20 runs of 20.

```
 736 CERT_VERIFY_PROC_CREATED
 736 CERT_VERIFY_PROC_CREATED
 736 SOCKET_POOL_CLOSING_SOCKET      src=19   {"reason": "Cert verifier changed"}
 736 SOCKET_ALIVE                    src=19   (end)
 736 QUIC_SESSION_POOL_MARK_ALL_ACTIVE_SESSIONS_GOING_AWAY
```

**The configuration change is Chrome's certificate verifier being created, and a pooled connection
whose certificate validation is no longer trusted goes.** That is what the reason string says and
what the ordering shows; it is also why the loopback pool is collateral damage, since the reason
applies to the pool and not to any certificate the stub ever presented. **Why Chrome creates the
verifier on this schedule — and twice — is not established here**, and this page does not guess.
`"Cert verifier changed"` accounts for exactly the 16 closes at the marker; the other closing
reasons in these 20 logs are `"Socket pool destroyed"` (36, the browser exiting) and `"Socket
generation out of date"` (4). That is round 2's flush, on a browser that reaches
nothing at all: no Google socket, no QUIC session, no certificate to re-verify for a remote host —
and it still closes the one loopback socket in the pool. The phone-home is not the flush's cause;
the flush is a notification that fires on the startup clock either way, and round 2's socket burst
was six sockets wide only because there were six sockets.

The four runs where nothing closed carry `"Socket generation out of date"` on their page socket
instead, and are most likely a property of this driver rather than of the mechanism: it loads the
whole deck page, so a favicon and a preconnect spare are in the race for the pool at that moment. A
second, independent driver with neither — run during review of this page — closed the planted socket
in **6 of 6**. What both agree on is the claim being made: the flush still closes a live loopback
socket.

---

## 5. What the browser reached, and what it still asks for

Across all 80 NetLogs, hosts named anywhere in the log, split the way the guard splits them:

| host | runs naming it | reached anything |
|---|---|---|
| `accounts.google.com` | 80 / 80 | no |
| `www.google.com` | 80 / 80 | no |
| `~notfound` (the resolver rule's own target, not a host) | 80 / 80 | n/a |
| `2001:4860:4860::8888` | 80 / 80 | no — `UDP_CONNECT` on :443, no byte or packet event |
| `update.googleapis.com` | 70 / 80 | no |
| `android.clients.google.com` | 64 / 80 | no |

**Nothing was reached in 80 of 80 runs.** No `TCP_CONNECT`, no `SSL_` event, no QUIC session, no
byte in either direction, to any host but `127.0.0.1`. That is the property the loopback rule
guarantees and the one `HeadlessChromeNetworkTest` asserts.

**One host here is not in that test's `KNOWN_ATTEMPTS`, and deliberately stays out.**
`update.googleapis.com` — the component updater's `/service/update2/json`, asked for despite
`--disable-component-update` — is first named at **2839–3090 ms** across the runs that see it, and
`android.clients.google.com` at 2254–3015 ms. The guard's browser does not live that long, and it
does not merely *miss* the update check: held open for six seconds it still did not produce it, in
3 runs of 3, and the one NetLog captured from the guard's own scenario names
`android.clients.google.com` and never `update.googleapis.com`. So this is not a red the guard is
failing to notice; it is an attempt the *deck* scenario provokes and the guard's scenario does not.
`KNOWN_ATTEMPTS` is an inventory of what the guard itself sees, it is checked with `isSubsetOf`, and
adding a host on evidence from a different scenario would widen it by one host for a red nobody can
produce. Recorded here instead, which is where the next person will look.

> **Note added 2026-09-02 (Task 3).** "The guard's browser does not live that long" is now
> *conditional*, and the condition is this task's own. `HeadlessChrome.open` waits for the startup
> cert-verifier flush before it navigates, and its fallback bound is 2500 ms — so a launch that
> waits the bound out carries the guard's browser past the 2254 ms at which
> `android.clients.google.com` is first named. Measured on these flags, a browser held open for four
> seconds asks for it. `android.clients.google.com` is therefore back in `KNOWN_ATTEMPTS`, under the
> same rule that took it out: the list is what *this test's own scenario* asks for, and the scenario
> changed. `update.googleapis.com`, first named at 2839–3090 ms, is still outside the reach of a
> browser bounded at 2500 ms and stays out. The guard's own scenario names
> `accounts.google.com`, `www.google.com`, `~notfound` and `2001:4860:4860::8888` and neither of
> these two, in 3 runs of 3 under Chrome's default capture mode.

---

## 6. What is left

**Round 2's failure mode cannot happen in the window this study measured, and its mechanism is
alive one poll interval away.** The flush no longer finds the page's sockets, in 80 of 80 runs
across a load range of 6 to 157 — but it still closes them when it finds them (§4), and what keeps
it from finding them is 57 ms of accidental slack at quiet load. Anything that makes the harness's
DevTools handshake faster, or Chrome's cert-verifier settling slower, closes that gap. This page is
the number to compare against if the retry control ever goes red again.

**A wait was considered and not added.** [ADR 52's amendment](adr/0052-test-the-deck-page-in-a-real-browser.md)
carries the decision; the two candidate conditions were measured here so that nobody has to re-derive
them:

- **Chrome's own network activity on the browser target, over CDP — not available.** `Network.enable`
  on the browser-level target (`GET /json/version` → `webSocketDebuggerUrl`) answers
  `{"code":-32601,"message":"'Network.enable' wasn't found"}` on Chrome 152, in 3 probes of 3. The
  browser target has no `Network` domain to idle. This is round 2's "the DevTools protocol exposes
  no socket-pool state", confirmed for the browser target too.
- **The NetLog, tailed live — works.** Chrome writes the log one event per line as it runs and the
  going-away marker's own type id is in the `constants.logEventTypes` block at the head of the file,
  so a launcher can read the id and then watch for a line carrying it. Measured over 5 launches: the
  marker line became visible to a 5 ms poll at **921, 938, 979, 1009 and 1718 ms** of wall clock
  after `ProcessBuilder.start()`, for markers Chrome recorded at 728–797 ms on its own clock. The lag
  — process spawn plus Chrome's file buffering — is a few hundred milliseconds and once was 900, and
  it only ever makes such a wait *longer*, never shorter. Anyone adding the wait should start here.

**60 green runs do not prove the residual gone.** §7.

---

## 7. What 60 runs bound

`aRetriedRatingCannotOverwriteAReRating` was run 60 times, one `./gradlew test --tests
'*DeckBehaviourTest.aRetriedRating*' --rerun` per run, sequential and blocking, under the same
spinners, at a 1-minute load average of **137.23 – 200.83**. **60 passed and none failed.** Filter
validity, checked the same way round 2 checked it: every run's
`TEST-…DeckBehaviourTest.xml` reported `tests="1"`, so the filter selected the one test and did not
pass by matching nothing.

**That is not evidence the residual is gone, and the arithmetic says how far it is from it.** Zero
failures in 60 runs is consistent, at 95%, with a true failure rate of anything up to about **4.9%**
— roughly one run in twenty. Round 2's own observed rate was 1 in 81, or 1.2%; a browser that still
failed at exactly that rate would have produced a clean 60 about **48%** of the time. So this tally
cannot distinguish "fixed" from "unchanged" at round 2's rate, and no number of green runs at this
scale could. What carries weight here is §3 and §4 — the mechanism, seen directly, 80 times — and
this tally is the sanity check beside it, not the finding.

---

## 8. Environment

Branch `186-loopback-only`, worktree isolated from the main checkout, on top of Task 1's enforcement
commits (`cc68a59..0ad4d96`). Chrome 152.0.7977.65, macOS 26.6.2, 28 cores, JDK 25, Gradle 9.7.1,
`SEGUE_REQUIRE_BROWSER=true` throughout. All scratch instrumentation was deleted; the working tree
carried only this page and the records it supports at the end of the study.


---

## 9. Task 3 — the wait, built and measured

*Added 2026-09-02, after this page's §6 was written. Same machine, same Chrome build, quiet box, **no
load lever** — §3's two batches show that load moves both sides of the margin, and nothing here
reproduces that.*

`HeadlessChrome.open` now waits for the flush before it sends `Page.navigate`, reading the marker
out of a NetLog every `launch()` writes to a temporary file of its own. The decision and its shape
are in [ADR 52](adr/0052-test-the-deck-page-in-a-real-browser.md)'s second dated note; the raw runs
are here.

### The red — a planted early load, 20 runs

The shape §4's *independent* driver used, not §4's own: the stub's URL on Chrome's command line, a
**bare page** — no favicon link, no preconnect — served by a loopback stub, and the harness's flags
read out of `HeadlessChrome.flags` by reflection. Offsets are milliseconds from the browser's first
logged event, as everywhere on this page.

| run | marker | first page socket | what closed the page's socket |
|---|---|---|---|
| 1 | 671 | 657 | `Cert verifier changed` |
| 2 | 669 | 654 | `Cert verifier changed` |
| 3 | 673 | 661 | `Cert verifier changed` |
| 4 | 666 | 652 | `Cert verifier changed` |
| 5 | 671 | 653 | `Cert verifier changed` |
| 6 | 668 | 656 | `Cert verifier changed` |
| 7 | 684 | 666 | `Cert verifier changed` |
| 8 | 675 | 660 | `Cert verifier changed` |
| 9 | 669 | 655 | `Cert verifier changed` |
| 10 | 664 | 653 | `Cert verifier changed` |
| 11 | 667 | 651 | `Cert verifier changed` |
| 12 | 152 | 139 | `Cert verifier changed` |
| 13 | 673 | 655 | `Cert verifier changed` |
| 14 | 672 | 659 | `Cert verifier changed` |
| 15 | 668 | 652 | `Cert verifier changed` |
| 16 | 158 | 147 | `Cert verifier changed` |
| 17 | 688 | 672 | `Cert verifier changed` |
| 18 | 146 | 134 | `Cert verifier changed` |
| 19 | 684 | 666 | `Cert verifier changed` |
| 20 | 675 | 662 | `Cert verifier changed` |

**20 of 20**, each in the marker's own millisecond. Run 3, the three events that matter, with the
offsets and the socket source id the driver read out of that log:

```
 661 TCP_CONNECT                     src=19   (the page's socket to the stub)
 673 SOCKET_POOL_CLOSING_SOCKET      src=19   {"reason": "Cert verifier changed"}
 673 QUIC_SESSION_POOL_MARK_ALL_ACTIVE_SESSIONS_GOING_AWAY
```

`"Socket generation out of date"` — the second reason string §4 records, which four of §4's twenty
runs carried instead — appeared in **0 of these 20**. That is the point of the bare page: §4 read
those four as its own driver's favicon and preconnect racing for the pool, and removing both removed
them, which is as close as this study comes to testing that reading. Nothing in this batch is
counted in both columns, because nothing landed in the second one.

### The green — the same assertion through the harness, 20 runs

The page loaded by `HeadlessChrome.launch()` and `open()`, wait in place. Same stub, same bare page.

| run | marker | first page socket | what closed the page's socket | wait added (ms) |
|---|---|---|---|---|
| 1 | 684 | 756 | `Socket pool destroyed` (browser exit) | 15 |
| 2 | 689 | 773 | `Socket pool destroyed` | 16 |
| 3 | 676 | 761 | `Socket pool destroyed` | 16 |
| 4 | 702 | 787 | `Socket pool destroyed` | 17 |
| 5 | 676 | 760 | `Socket pool destroyed` | 17 |
| 6 | 701 | 786 | `Socket pool destroyed` | 17 |
| 7 | 684 | 769 | `Socket pool destroyed` | 20 |
| 8 | 678 | 741 | `Socket pool destroyed` | 16 |
| 9 | 693 | 771 | `Socket pool destroyed` | 16 |
| 10 | 699 | 784 | `Socket pool destroyed` | 16 |
| 11 | 688 | 770 | `Socket pool destroyed` | 15 |
| 12 | 169 | 783 | `Socket pool destroyed` | 17 |
| 13 | 684 | 761 | `Socket pool destroyed` | 17 |
| 14 | 702 | 790 | `Socket pool destroyed` | 18 |
| 15 | 700 | 787 | `Socket pool destroyed` | 17 |
| 16 | 689 | 773 | `Socket pool destroyed` | 16 |
| 17 | 679 | 755 | `Socket pool destroyed` | 15 |
| 18 | 685 | 768 | `Socket pool destroyed` | 17 |
| 19 | 688 | 772 | `Socket pool destroyed` | 16 |
| 20 | 680 | 777 | `Socket pool destroyed` | 18 |

**Closed by the flush: 0 of 20.** Every page socket survives to the browser's own exit. The margin
`marker − page socket` runs −63 … −97 ms, with run 12's early marker at −614.

### The cost, and the bound

The added wait per launch, sorted:

```
15 15 15 16 16 16 16 16 16 16 17 17 17 17 17 17 17 18 18 20
```

**p50 16.5 ms, p100 20 ms. Launches that reached the fallback bound: 0 of 20**, and a launch that
did would say so in its own line — the wait prints which of the two ended it, and the runs above all
print `flush marker seen after …`.

**Almost all of that is one parse, not one wait.** The tail resolves the marker's event id out of
the log's `constants` block on its first look, and by the time `HeadlessChrome`'s DevTools handshake
has finished, Chrome has long since written the marker — so in these 20 runs the condition was
already true at the first poll and the cost is the cost of reading the block. **Which is to say the
ordering these runs show is the ordering §3 already showed; what is new is that it is now checked
rather than assumed.** The 24–41 ms §6 leads one to expect is the same number seen from the other
side, and the fat tail it records — 1262 ms once — is the case this wait exists for and which this
batch did not produce.

**The bound, planted.** A NetLog that never receives the marker — the constants block and one
ordinary socket event, nothing else — pointed at the wait with the harness's real bound. It
proceeds, and says so:

```
[HeadlessChrome] proceeded on the 2500 ms fallback bound after 2512 ms — this NetLog never showed
the flush, which is the good outcome, not an error
```

That is a **bound and not a timeout** by design: a Chrome that stops creating a certificate verifier
on startup is the outcome §6 hopes for, and a wait that failed on it would turn good news into a red
gate. It is set at 2500 ms against §6's measured p100 of 1718 ms for the marker's visibility in the
file, plus about 45%, counted from the browser's launch rather than from the wait's own start —
because that is how §6 measured it, and because a launch whose handshake was slow has had *longer*
for the marker to arrive, not less.

### One thing these runs were not

The 40 launches above were captured with `--net-log-capture-mode=IncludeSensitive`, which the
harness passed at the time and no longer does: measured on this flag set after the fact, the default
mode names the identical host set and carries the same marker, so the sensitive capture was buying
nothing and leaving a cookie-bearing temp file behind on every launch. Nothing in the tables depends
on the difference — the marker and the closing reasons are in both — but the harness these numbers
were taken from is one flag away from the harness that shipped.

### What this section does not show

No load lever, so nothing here speaks to the −683 … −81 ms spread §3 measured under load, nor to how
much of the bound a loaded machine would use. Twenty runs each side, on one machine and one Chrome
build, on one day — the same caveat as every other section of this page, and nothing regenerates
these numbers either.

