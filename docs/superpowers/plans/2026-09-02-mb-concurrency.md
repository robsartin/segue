# The MusicBrainz throttle's wall clock — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `MusicBrainzClientTest` drops from 12.4 s to about 3 s with every guard it holds still
firing, by making the request interval and the sleeper per-instance seams whose defaults are
today's production behaviour.

**Architecture:** Two tasks, two red→green loops, one file of production code and one of test code.
Task 1 turns `MIN_REQUEST_INTERVAL` into an instance field injected through a package-private
constructor (the seam `Clock` already occupies at `MusicBrainzClient:113`) and runs the concurrency
test at 50 ms. Task 2 does the same for the existing `Sleeper` interface, which `fetch` currently
reaches only through a private static that hardcodes `Thread::sleep`, and hands a no-op sleeper to
the three retry tests that assert on outcomes rather than on time. No production behaviour change,
no ADR, no new file.

**Tech Stack:** Java (toolchain 25, `release 21`), JUnit 5, AssertJ, google-java-format via spotless.

**Spec:** `docs/superpowers/specs/2026-09-02-mb-concurrency-design.md`

## Global Constraints

- **Pure TDD, red first.** Every step names the failure to be observed *and quoted* before the
  production edit. A planted control that does not fire the assertion is not a red — a build
  failure is a compile error, not evidence.
- **The control is the real #146 defect: read, wait, then write.** `reserve()` computes a delay and
  claims nothing; `fetch` writes `lastRequestAt` *after* `sleep(reserve())`. A narrowed plant —
  swapping the compare-and-set for a plain `set` in the same nanosecond window — was **measured
  passing 1 of 3 runs**. Do not use it. Revert every plant and confirm `git status --short` is empty
  before committing.
- **Production pace never changes.** `SegueConfiguration:139` builds `new MusicBrainzClient()`; that
  path keeps a 1 s interval and `Thread::sleep`. MusicBrainz's ~1 rps is an access condition, not a
  tuning knob.
- Test names `should<Expected>When<Condition>` where new, each with a `@DisplayName`. Existing test
  names and display names are **not** renamed — this change is about their cost, not their prose.
- **No ADR edit.** **Never `git add -A`** — stage by explicit path; other sessions share this machine.
- **Only JDK 25 is installed; Gradle 9.7.1 launches on it.** Plain `./gradlew`, **blocking** — never
  backgrounded, never `java_home -v 21` (it returns JDK 25 with exit 0).
- Gate, blocking: `SEGUE_REQUIRE_BROWSER=true ./gradlew check --rerun-tasks`.
- **Never run a writing dev task.** `~/.segue/segue.db` is never read, written or created.
- **Timings are taken at least three times and reported as a spread**, read out of
  `build/test-results/test/TEST-com.robsartin.segue.musicbrainz.MusicBrainzClientTest.xml`, never as
  a single number. Other agents build on this machine; a single sample is noise.
- Every javadoc sentence this change writes about a measured quantity carries the measurement, in
  the style the class already uses. Do not restate the spec's tables in the code.

**Measured baseline to beat** (2026-09-02, this worktree): class alone **12.40 / 12.42 / 12.44 /
12.47 s**; `concurrentCallersDoNotLeaveTogether` **2.006 / 2.010 / 2.011 / 2.016 s**; class inside a
full `./gradlew test` **12.045 s** of a 60.0 s suite total. Re-measure before touching anything —
the machine is shared and the baseline is the comparison.

---

### Task 1: The request interval becomes an instance seam

**Files:**
- Modify: `src/main/java/com/robsartin/segue/musicbrainz/MusicBrainzClient.java`
- Modify: `src/test/java/com/robsartin/segue/musicbrainz/MusicBrainzClientTest.java`
- Read only: `src/main/java/com/robsartin/segue/app/SegueConfiguration.java` (line 139, the one
  production call site), `src/test/java/com/robsartin/segue/musicbrainz/StubMusicBrainzServer.java`,
  `src/test/java/com/robsartin/segue/musicbrainz/MusicBrainzLiveSmokeTest.java`,
  `src/main/java/com/robsartin/segue/wikidata/WikidataClient.java` (the sibling shape, ADR 32)

**Interfaces:**
- Produces: `MusicBrainzClient(URI baseUri, Clock clock, Duration minRequestInterval)` —
  package-private; `static final Duration DEFAULT_MIN_REQUEST_INTERVAL = Duration.ofSeconds(1)`
  (renamed from `MIN_REQUEST_INTERVAL`); `static Duration throttleDelay(Instant lastRequestAt,
  Instant now, Duration minRequestInterval)` — still pure, still static, one parameter wider;
  a package-private `Duration minRequestInterval()` accessor for control 3.
- Unchanged: `MusicBrainzClient()`, `MusicBrainzClient(URI)`, `MusicBrainzClient(URI, Clock)`,
  `readingFrom(Path)`, `artistRelations(String)`, `retryDelay`, `sleep(Duration, Sleeper)`.
- `MIN_REQUEST_INTERVAL` and `throttleDelay` are referenced **only** from
  `MusicBrainzClient.java` and `MusicBrainzClientTest.java` — verified by
  `grep -rn "MIN_REQUEST_INTERVAL\|throttleDelay" src/ docs/`. Re-run that grep; if it finds a third
  file, stop and report rather than widening the change.

- [ ] **Step 1 — measure the baseline.** Three runs of
  `./gradlew test --tests "com.robsartin.segue.musicbrainz.MusicBrainzClientTest" --rerun`, reading
  the class time and the concurrency test's time from the XML. Record the spread in the report.
- [ ] **Step 2 — RED: prove the control fires at the current 1 s scale, before changing anything.**
  Plant the #146 defect in the shape the Global Constraints name. Run the concurrency test three
  times. It must fail all three, with at least one gap far below the 0.9 s floor — the reference
  measurement is `[1.003433917S, 0.001567458S]`. **Quote the actual failure message and the gaps.**
  Revert the plant; confirm green and `git status --short` empty. This is the "the instrument
  works before I move it" step and it is not optional.
- [ ] **Step 3 — RED: the concurrency test asks for a 50 ms client that does not exist.** Change
  `concurrentCallersDoNotLeaveTogether` to build
  `new MusicBrainzClient(stub.baseUri(), Clock.systemUTC(), Duration.ofMillis(50))`, set
  `SLOT_OVERRUN_ALLOWANCE` to `Duration.ofMillis(10)`, and take the floor from the client's own
  interval rather than the constant. Run it; the failure is a compile error naming the missing
  constructor. **A compile error is the red for a missing seam and for nothing else** — it proves
  the seam is absent, not that the property holds.
- [ ] **Step 4 — GREEN: add the seam.** Add the `Duration` field, the three-argument package-private
  constructor, `DEFAULT_MIN_REQUEST_INTERVAL`, the `minRequestInterval()` accessor, and the third
  parameter on `throttleDelay`; route `reserve()` through the field. Update the two
  `throttleDelay` unit tests to pass an explicit interval — keep their existing arithmetic (500 ms
  owed of 1000 ms; zero once 1500 ms has passed) by passing `Duration.ofSeconds(1)`, so those two
  assertions still say exactly what they said. Update `anInterruptedWaitSurfacesAsUnavailable…`'s
  reference to the renamed constant. Run the class; expect the concurrency test at **≈0.11 s** and
  the class at **≈10.1 s** (the three backoff-bound tests are untouched by this task).
- [ ] **Step 5 — control 1: the guard still fires at the new scale.** Re-plant the same #146 defect.
  Three runs, all red. Reference gaps at 50 ms: `[0.000362541S, 0.053267542S]`,
  `[0.050237292S, 0.002729125S]`, `[0.000361792S, 0.053309708S]` — the offending gap is absolute and
  sub-5 ms, which is why a 40 ms floor separates it. Quote the run's own numbers; revert.
- [ ] **Step 6 — control 2: no flake under load.** Start ≥40 busy-loop spinners
  (`for i in $(seq 1 48); do (while :; do :; done) & done`), confirm `uptime` reports load average
  ≥ 100, run the concurrency test five times — five passes required — then kill the spinners.
  Reference: 5/5 at 0.111–0.114 s. If any run reds, **do not widen the allowance**: report it, and
  the fallback the spec allows is a 100 ms interval with a 20 ms allowance, re-measured the same way.
- [ ] **Step 7 — control 3: production is unchanged.** Add a test asserting that a
  default-constructed client's `minRequestInterval()` is `Duration.ofSeconds(1)`. Watch it red by
  temporarily defaulting the no-argument path to 50 ms; quote the failure; revert.
- [ ] **Step 8 — control 4: `throttleAppliesEvenAfterAConnectionFailure` still reds on its own
  defect.** `reserve` has been edited underneath it. Plant its defect — claim `lastRequestAt` only
  after a successful send — and confirm the elapsed-time assertion fails (the defect finishes in the
  backoff alone, ≈1.4 s, against a 2.5 s floor). Quote it; revert. Leave that test on the default
  1 s interval: at 50 ms it was measured at 1.4166 s, which *is* the defect's number, so a short
  interval would make it vacuous.
- [ ] **Step 9 — gate and commit.** `SEGUE_REQUIRE_BROWSER=true ./gradlew check --rerun-tasks`,
  blocking. Stage `MusicBrainzClient.java` and `MusicBrainzClientTest.java` by explicit path. Report
  the three-run class time next to the Step 1 baseline.

---

### Task 2: The sleeper becomes an instance seam

**Files:**
- Modify: `src/main/java/com/robsartin/segue/musicbrainz/MusicBrainzClient.java`
- Modify: `src/test/java/com/robsartin/segue/musicbrainz/MusicBrainzClientTest.java`

**Interfaces:**
- Consumes: Task 1's three-argument constructor.
- Produces: `MusicBrainzClient(URI baseUri, Clock clock, Duration minRequestInterval, Sleeper
  sleeper)` — package-private; the three-argument constructor delegates with `Thread::sleep`. The
  existing `Sleeper` interface and `sleep(Duration, Sleeper)` are reused unchanged; the private
  static `sleep(Duration)` goes, and `fetch`'s two call sites become `sleep(reserve(), sleeper)` and
  `sleep(retryDelay(retryAfter, attempt), sleeper)`.

- [ ] **Step 1 — RED: prove each of the three tests still catches its own defect *before* the
  sleeper is removed from under them.** One at a time, plant and quote:
  `retriesRateLimit` ← treat 429 as terminal (`isTransient` drops the 429 clause);
  `givesUpEventually` ← `MAX_ATTEMPTS` raised to 12 and the stub given 12 failures, or the
  `attempt < MAX_ATTEMPTS` guard removed so retrying never stops;
  `unparseableBodyIsReportedAsUnavailable` ← let the `JacksonException` escape instead of being
  wrapped. Each must fail for its own reason. Revert each. This is the record of what the three
  tests are worth, taken while they still really wait.
- [ ] **Step 2 — RED: the three tests ask for a client with an injected sleeper.** Rewrite them to
  build `new MusicBrainzClient(stub.baseUri(), Clock.systemUTC(), MusicBrainzClient
  .DEFAULT_MIN_REQUEST_INTERVAL, delay -> {})`. Compile error naming the missing constructor.
- [ ] **Step 3 — GREEN: add the field and the fourth constructor parameter**, delete the private
  static `sleep(Duration)`, and route `fetch`'s two waits through the field. Run the class; expect
  those three tests at ≈0 s and the class at **≈3.1 s**.
- [ ] **Step 4 — control: repeat Step 1's three plants against the no-op sleeper.** All three must
  still red, for the same three reasons. **This is the step that decides whether Task 2 is
  admissible**: a no-op sleeper that turns any of these into a test of nothing is a reason to stop
  and hand the finding back, not to proceed. Quote each; revert each.
- [ ] **Step 5 — control: `throttleAppliesEvenAfterAConnectionFailure` is untouched and still real.**
  Confirm it still constructs its client without a sleeper, still takes ≈3 s, and still reds on the
  plant from Task 1 Step 8. Its elapsed-time assertion is the only place left in the class where the
  throttle's real waiting is verified end to end, and the javadoc must say so in one sentence.
- [ ] **Step 6 — javadoc.** One paragraph on the new constructor recording what the two seams are
  for and what they are not: the defaults are production's behaviour, the short interval exists so a
  real-time property can be asserted at a cheaper scale, and the no-op sleeper is admissible only in
  tests whose assertions are outcomes. Cite the measurement (12.4 s → ≈3.1 s, three runs each) the
  way the class's existing javadoc cites its numbers.
- [ ] **Step 7 — gate, full-suite measurement, commit.** `SEGUE_REQUIRE_BROWSER=true ./gradlew check
  --rerun-tasks`, blocking. Then a full `./gradlew test --rerun`, three times, reporting the class's
  time and the summed suite time against the 12.045 s / 60.0 s baseline. Stage by explicit path.

---

## Self-Review

**Spec coverage.** Seam 1 → Task 1 Steps 3–4; Seam 2 → Task 2 Steps 2–3; interval and allowance
values → Task 1 Step 3, with the fallback at Step 6; `throttleAppliesEvenAfterAConnectionFailure`
kept real → Task 1 Step 8 and Task 2 Step 5; controls 1–6 of the spec → Task 1 Steps 2/5/6/7/8 and
Task 2 Steps 1/4, with the before/after measurement at Task 1 Step 1 and Task 2 Step 7; no ADR →
Global Constraints.

**Placeholders:** none. Every constant, file path, line number, reference gap and reference timing
in this plan was measured in this worktree on 2026-09-02 and is quoted from the report, not
estimated.

**Type consistency:** `throttleDelay` stays `static Duration` and takes `(Instant, Instant,
Duration)`; the constructors take `(URI, Clock, Duration)` and `(URI, Clock, Duration, Sleeper)`;
`Sleeper.sleep(Duration) throws InterruptedException` is unchanged, so `delay -> {}` is a valid
lambda for it.

**Known risk.** Task 2 is a larger share of the win (7.0 s of the 12.0 s) than Task 1 (1.9 s) but a
wider change: it removes real waiting from three tests. Task 2 Step 4 is the gate on that, and the
step says to stop rather than proceed if any of the three stops catching its defect. Task 1 stands
alone and is the whole of what #162 asks for; Task 2 is the controller's call.
