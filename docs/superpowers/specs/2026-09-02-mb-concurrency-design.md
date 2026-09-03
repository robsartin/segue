# The MusicBrainz throttle's wall clock, and the two seams that shrink it

Issue #162. Written 2026-09-02.

## What was measured, on 2026-09-02

Every number below is the mean of at least three runs of `./gradlew test --tests
com.robsartin.segue.musicbrainz.MusicBrainzClientTest --rerun`, read per test out of
`build/test-results/test/*.xml`, on a 28-CPU machine with other agents building on it.

- **`concurrentCallersDoNotLeaveTogether` takes 2.006–2.016 s, and the number is a constant, not a
  symptom of load.** At load average 9.75 it is 2.006 / 2.010 / 2.011 / 2.016 s. Under 40 busy-loop
  spinners, at load average 73, it is 2.014 / 2.015 / 2.014 s. It is `(callers − 1) ×
  MIN_REQUEST_INTERVAL` plus about 10 ms, and nothing else.
- **The mechanism is one line with no injection point.** `MusicBrainzClient.reserve()` issues each
  caller a slot one `MIN_REQUEST_INTERVAL` after the last; `fetch` waits for it at line 213,
  `sleep(reserve());`, and `sleep(Duration)` at line 349 is `sleep(delay, Thread::sleep)`. The
  interval is `static final Duration MIN_REQUEST_INTERVAL = Duration.ofSeconds(1);` (line 72) — a
  compile-time constant, referenced nowhere outside this class and its test.
- **The issue's stated mechanism is refuted.** #162 attributes the cost to contention descheduling
  callers past their slots so that `reserve()` re-serialises them. No inflation appeared at load
  average 103. The shape it describes is real and is documented in `reserve`'s own javadoc; it is
  not what the clock is spending.
- **386 s cannot be this method's own JUnit time.** `MusicBrainzClientTest:369` is
  `assertThat(finished.await(60, TimeUnit.SECONDS))`, which caps the caller phase of a passing run
  at 60 s; every other test in the class is capped by `MAX_ATTEMPTS = 4` slots plus backoff. The
  figure is not reproducible and is not what this issue should be sized against.
- **The defensible statement of the problem is bigger than the one test.** A whole `./gradlew test`
  is 66 s wall and 60.0 s of summed suite time across 121 suites. `MusicBrainzClientTest` is
  **12.045 s of that — the second-heaviest suite in the repository, 20 % of the whole gate in one
  class**, and every second of it is a real `Thread.sleep`.
- **Where the 12.0 s goes**, measured by dropping `MIN_REQUEST_INTERVAL` to 50 ms in a throwaway
  edit and re-running:

  | test | 1 s | 50 ms | the residual |
  |---|---:|---:|---|
  | `concurrentCallersDoNotLeaveTogether` | 2.006 | 0.107–0.112 | two slot waits |
  | `throttleAppliesEvenAfterAConnectionFailure` | 3.006 | 1.416 | 200+400+800 ms backoff |
  | `givesUpEventually` | 3.009 | 1.416 | the same backoff |
  | `unparseableBodyIsReportedAsUnavailable` | 3.009 | 1.424 | the same backoff |
  | `retriesRateLimit` | 1.006 | 0.213 | one 200 ms backoff |
  | **class** | **12.04** | **5.00** | |

  So ~7.0 s is `MIN_REQUEST_INTERVAL` and ~4.2 s is `BACKOFF_BASE`. Two constants, two seams.
- **The #146 guard is not weakened by a shorter interval, because the defect's signature is
  absolute.** With the real #146 defect planted — `reserve()` returning a delay without claiming,
  and `lastRequestAt` written *after* the wait — the test is red 3/3 at 1 s (offending gaps
  0.000390 s, 0.001567 s, 0.004655 s) and red 3/3 at 50 ms (0.000363 s, 0.002729 s, 0.000362 s).
  The worst offending gap across all six runs is **4.655 ms**. A 40 ms floor is 8.6× that.
- **The fixed client at 50 ms does not flake under load.** Five runs under 48 spinners, load average
  103: 5/5 pass, 0.111–0.114 s.

## The decision

**Give the client two per-instance seams — the request interval and the sleeper — default both to
today's production behaviour, and use them only from tests.** Nothing about how segue paces
MusicBrainz changes: `SegueConfiguration:139` calls `new MusicBrainzClient()`, and that path keeps
`Duration.ofSeconds(1)` and `Thread::sleep`. MusicBrainz's ~1 rps is a condition of anonymous
`ws/2` access (ADR 32's sibling-adapter note, and the field's own javadoc), so the production pace
is not a tuning knob.

**Seam 1 — `minRequestInterval` as an instance field.** A package-private constructor
`MusicBrainzClient(URI, Clock, Duration)`, alongside the `(URI, Clock)` one that already exists at
line 113 for exactly this reason. `MIN_REQUEST_INTERVAL` is renamed
`DEFAULT_MIN_REQUEST_INTERVAL` and stays 1 s. `throttleDelay` **stays a pure static and grows a
third parameter**, `throttleDelay(Instant lastRequestAt, Instant now, Duration interval)` — its
javadoc argues that the rule is what is worth asserting and that asserting it through `reserve`
would mean really waiting a second, and an instance method would give that up for nothing.
`concurrentCallersDoNotLeaveTogether` builds its client at **50 ms** and `SLOT_OVERRUN_ALLOWANCE`
becomes **10 ms**, leaving a 40 ms floor.

**Seam 2 — `Sleeper` as an instance field.** The interface already exists (line 344) and
`sleep(Duration, Sleeper)` is already three-branch tested; what is missing is that `fetch` reaches
it only through the private static `sleep(Duration)` that hardcodes `Thread::sleep`. A fourth
constructor parameter, defaulting to `Thread::sleep`, lets `retriesRateLimit`, `givesUpEventually`
and `unparseableBodyIsReportedAsUnavailable` pass a recorder that returns at once. Those three
assert on the exception type, the relation count and `stub.requestCount()` — never on elapsed time
— so nothing is lost, and 7.0 s is.

**`throttleAppliesEvenAfterAConnectionFailure` keeps the 1 s default and a real sleeper, and this
is not an oversight.** Its assertion *is* elapsed time: `elapsed > 2500 ms` separates "four
attempts each topped up to a full interval" (~3 s) from the defect it guards, which would finish in
the backoff alone (200+400+800 ms ≈ 1.4 s). At a 50 ms interval it was measured at **1.4166 s** —
the backoff, and therefore the defect's own number. Shrinking its interval makes it vacuous. This
is the whole of the criterion-revalidation this change owes: the one test whose criterion is a
duration keeps the duration that makes the criterion mean something.

**The allowance rescales by measurement, not by ratio.** The existing 100 ms is justified in the
test's javadoc by two absolute quantities: an observed send-latency shortfall of 0.34 ms, and slot
overrun from descheduling. Neither scales with the interval. 10 ms is 29× the recorded shortfall
and 8.6× the worst gap the planted defect produced in six runs; it survived five runs at load
average 103.

## Rejected

- **Tag the test and exclude it from `check`** (#162's first option). #146 exists because a rule
  never seen to fail has never been tested; moving its guard out of the default gate is #93's
  lesson pointed backwards. It also fixes 2.0 s of a 12.0 s problem.
- **A separate Gradle task for it.** Same loss, plus a second thing to remember to run.
- **Change the constant itself to 50 ms.** Measured: it reddens
  `throttleDelayWaitsOutTheRemainderOfTheMinimumInterval` (expected `0.5S`, got `0S`) and makes
  `throttleAppliesEvenAfterAConnectionFailure` vacuous — and it would ship a 20× faster pace to a
  third party whose rate limit is an access condition. The point of a seam is that production keeps
  the number it must keep.
- **A fake or fixed `Clock` for the concurrency test.** The property under test is the real-time
  spacing of arrivals at a real socket. Under a fake clock `reserve()` would claim slots instantly,
  all three requests would arrive together, and the test could no longer tell the fixed client from
  the broken one. Only the *scale* of the real interval can be shrunk, not its realness — and
  `MusicBrainzClient(URI, Clock)`'s javadoc already records that a fixed clock changes `reserve`'s
  meaning rather than freezing it.
- **A no-op `Sleeper` for the concurrency test too.** Same objection: with nothing waiting, the
  three sends race and the test measures scheduling instead of throttling.
- **Two callers instead of three.** It would halve the remaining 0.11 s and weaken the control; the
  test's javadoc gives the reason for three (the defect fails every gap at once, so jitter would
  have to fake a full interval twice over to hide it), and that reason is unaffected by the scale.
- **Shrinking `BACKOFF_BASE` as well.** It is a third constant and would need its own controls;
  Seam 2 removes the same 4.2 s without touching a number that governs how hard segue leans on a
  failing third party.

## Controls, and the definition of done

1. **The #146 defect still reds the concurrency test at 50 ms.** Plant it in the shape the original
   had — `reserve()` computes a delay without claiming, and `fetch` writes `lastRequestAt` *after*
   the wait — run three times, quote the gaps, revert. **Not** a narrowed plant: replacing the
   compare-and-set with a plain `set` inside the same nanosecond window was measured passing 1 of 3
   runs, and would certify the guard on a coin flip.
2. **The fixed client passes five consecutive runs under a loaded machine** (≥40 busy-loop spinners,
   load average ≥ 100 reported by `uptime`).
3. **Production is unchanged.** A test asserts a default-constructed client's interval is
   `Duration.ofSeconds(1)` and that `DEFAULT_MIN_REQUEST_INTERVAL` is that value, so a future edit
   to the test-side number cannot silently move the production one.
4. **`throttleAppliesEvenAfterAConnectionFailure` still reds on its own defect** — `lastRequestAt`
   claimed only after a successful send — because `reserve` is being edited underneath it.
5. **The three retry tests still red on theirs** with the no-op sleeper in place: `givesUpEventually`
   against `MAX_ATTEMPTS` raised, `retriesRateLimit` against 429 treated as terminal,
   `unparseableBodyIsReportedAsUnavailable` against the parse error escaping unwrapped. A no-op
   sleeper must not turn any of them into a test of nothing.
6. **The class's measured time is reported before and after**, from the XML, three runs each.
   Baseline 12.40–12.47 s alone, 12.045 s inside a full `test`.

## Recorded

No ADR. ADR 32 (adapters are siblings; no second HTTP style) is cited, not amended — a
package-private constructor for a test is the same seam `Clock` already occupies in this class.
The measured facts about where the suite's time goes belong in this spec and in #162, not in a
document that would go stale.

## Controller rulings (2026-09-02)

1. **Task 2 (the injectable sleeper) is in scope.** The issue's goal is a test whose duration is not
   wall-clock arithmetic; the interval seam removes the caller-phase sleep and the sleeper seam removes
   the rest. One issue, one seam family, two tasks.
2. **The issue is not re-titled.** Its title records what was observed at the time; the measurement
   that refutes it is posted on the issue and stated in the PR, and the branch's commits say what was
   actually found. Correcting the record beats rewriting it.
3. **The positive control is the #146 defect**, faithfully re-planted at both interval scales, red
   every time; a fast test that cannot see the defect it exists for is not a fix.

## Amended 2026-09-02 — what shipped, and where this document is now wrong

Nothing above is deleted: it is the record of what was decided before the seams were built and
measured. Three of its statements did not survive the build, and this section says so rather than
letting the reader find out from the code.

**1. The interval seam shipped at a large scale, not a small one.** "The Decision" specifies a 50 ms
interval with a 10 ms `SLOT_OVERRUN_ALLOWANCE`, and the allowance section argues it is 29× the
recorded shortfall. Both are gone. Task 1 shipped 100 ms/20 ms because 50 ms/10 ms reddened the
*correct* client under load, and Task 1's review then measured the 100 ms/20 ms version 0/5 when
the concurrency test runs alone in a fresh JVM and 5/5 inside the warm class, at load average
125–152 throughout. The variable was never contention: caller 1's first-ever send costs 40–60 ms to
reach the socket where caller 2's, on a path 18 other tests have JIT-compiled, costs about 1 ms. The
allowance was absorbing a term an order of magnitude larger than the arithmetic error it existed to
bound — the repository's own "proxy assertion clean on a small sample" defect. No width of allowance
fixes that, so the assertion was replaced instead of the number. `concurrentCallersDoNotLeaveTogether`
now builds its client at a **five-minute** interval, which costs nothing because nothing waits for
it, and asserts the departure slots `reserve()` claimed — exactly, with no allowance of any kind.

**2. Two entries under "Rejected" are now the design.** "A fake or fixed `Clock` for the concurrency
test" and "A no-op `Sleeper` for the concurrency test too" were both rejected on the same ground:
with nothing waiting, the three sends race and the test measures scheduling instead of throttling.
That objection is sound against the assertion this document assumed — arrival spacing at a real
socket — and it is precisely why that assertion had to go. What shipped is neither of the rejected
things exactly: the clock is **real, and recording** (a fixed clock would still change what
`reserve` means), and the sleeper is a **recorder**, not a no-op — it captures the wait it was asked
for and returns. Together they reconstruct each claimed `sendAt` to the nanosecond, so the test
asserts what the client decided rather than what the JVM then did about it. The real-time property
that remains — that the three callers are concurrent — is a `CyclicBarrier`, not a timing window.

**3. Control 1 and control 5 held; control 2 was answered differently.** The #146 defect, planted in
the shape this document names, reds the new assertion 6/6 (three callers claiming one instant instead
of three slots five minutes apart). The three retry tests still red on their own defects with the
recording sleeper under them. Control 2 asked for five consecutive passes under load; the rewritten
test passes 10/10 alone and 10/10 in the class at load average 178–187, and the reason it cannot
flake is now an argument rather than a margin — any stall long enough to break `reserve`'s
arithmetic would first blow the test's own 60-second liveness assertion.

**4. `throttleAppliesEvenAfterAConnectionFailure` is untouched, as this document requires**, and is
now the only test in the class whose assertion is elapsed time. The three-argument constructor this
document specifies, `MusicBrainzClient(URI, Clock, Duration)`, was superseded by the four-argument
one and deleted when nothing called it.

**Measured, three runs each, on one machine at load average 141–253:** `MusicBrainzClientTest` inside
a full `./gradlew test` went from 12.063/12.054/12.061 s to 3.116/3.105/3.104 s — the 12.045 s
baseline this document records reproduces exactly.
