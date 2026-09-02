# Loopback-only Chrome — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The test browser provably reaches nothing but loopback, a test says so, and the effect on the #169 startup-clock flush is measured before any further wait is added.

**Architecture:** `HeadlessChrome.launch()` gains `--host-resolver-rules` plus measured attempt-suppressing flags and an optional NetLog capture; `HeadlessChromeNetworkTest` asserts the NetLog names no non-loopback host; a 60-launch measurement decides whether a startup wait is needed and in what shape; ADR 52 records it.

**Tech Stack:** Java (toolchain 25, `release 21`), Gradle 9.7.1, JUnit, AssertJ, headless Chrome 152 over CDP, Chrome NetLog JSON.

**Spec:** `docs/superpowers/specs/2026-09-02-loopback-only-chrome-design.md`

## Global Constraints

- **Pure TDD, one behaviour per red→green loop**, reds quoted. The guard test has a genuine red on today's flags; use it.
- **Every flag kept is one the NetLog showed removing an attempt** — add one at a time, before/after, list what each removed; drop the rest.
- Test names `should<Expected>When<Condition>` with `@DisplayName`. **ADRs immutable**: dated amendments to ADR 52 (and a note on ADR 46) only. **Never `git add -A`.** No production change.
- Gate, **blocking, never backgrounded**: `SEGUE_REQUIRE_BROWSER=true ./gradlew check --rerun-tasks`. Baseline on `main` is 1026 tests — measure it.
- **Only JDK 25 is installed and Gradle 9.7.1 launches on it.** Do NOT set `JAVA_HOME=$(/usr/libexec/java_home -v 21)` — it returns the JDK 25 path with exit 0. Plain `./gradlew`.
- **Never run a writing dev task** (`ownClaim`, `retractEntity`, any abbreviation). `~/.segue/segue.db` is never read, written, or created.
- **No new fixed wait unless Task 2's measurement licenses it**, and then only in the shape it licenses.

---

### Task 1: Enforce loopback-only, and prove it with a test

**Files:**
- Modify: `src/test/java/com/robsartin/segue/rate/HeadlessChrome.java` (launch flags; an optional `netLog(Path)` capture; the corrected comment)
- Create: `src/test/java/com/robsartin/segue/rate/HeadlessChromeNetworkTest.java`
- Create: `src/test/java/com/robsartin/segue/rate/NetLog.java` (package-private: parse the JSON; list hosts seen in `HOST_RESOLVER_IMPL_REQUEST` / `URL_REQUEST` / `SOCKET` / `QUIC_SESSION` events)

**Interfaces:**
- Produces: `HeadlessChrome.launch(Path netLog)` (or a builder flag) writing `--log-net-log`; `NetLog.hostsContacted(Path)` → `Set<String>`; both consumed by Task 2's measurement.

- [ ] **Loop A — the guard, red on today's flags.** `HeadlessChromeNetworkTest.shouldContactOnlyLoopbackWhenTheDeckPageIsDriven`: launch with NetLog, load the stub page, one warm-up fetch, quit, parse, assert the host set ⊆ {`127.0.0.1`}. **Run it before changing any flag**: it must go red naming `clients2.google.com` (and the others). Quote.
- [ ] **Loop B — the resolver rule.** Add `--host-resolver-rules="MAP * ~NOTFOUND, EXCLUDE localhost"`. Green? If the NetLog still shows *attempts* (resolution failures, `URL_REQUEST` to Google that die at DNS), the assertion on hosts *contacted* passes while attempts remain — decide, and state, whether the guard asserts "no socket to a non-loopback host" or "no request to one"; the spec wants **no request, resolution or socket**. Make the assertion say which.
- [ ] **Loop C — attempt suppression, one flag at a time.** For each candidate flag: NetLog before, NetLog after, the attempts removed. Keep only flags that removed something; record the table in the report and in a comment beside the flags. Green with a NetLog showing zero non-loopback events of any kind.
- [ ] **Step 4 — positive control.** Remove the resolver rule (keep the others): the test must go red naming a Google host. Quote. Restore.
- [ ] **Step 5 — the comment.** Replace "Nothing here should reach the network…" with what is now true and enforced, citing the test.
- [ ] **Step 6 — gate and commit.**

---

### Task 2: Measure the flush with loopback enforced; decide the wait

**Files:**
- Create: `docs/loopback-only-evidence.md` (register of `docs/retry-pool-flush-evidence.md`)
- Modify (only if licensed): `HeadlessChrome.java` (a startup condition or a labelled bound in `launch()`)
- Modify: `README.md`, `docs/user-guide.md` (links), `docs/adr/0052-*.md` (dated amendment), `docs/adr/0046-*.md` (dated note)

- [ ] **Step 1 — 60 launches with NetLog** (Task 1's capture), each loading the stub page and running the retry scenario as the test does; per run: whether the `MARK_ALL_ACTIVE_SESSIONS_GOING_AWAY` / `SOCKET_POOL_CLOSING_SOCKET` burst occurred, its offset from launch, the page's socket creation offset, attempts. Under the load protocol of round 2. Report the distribution beside round 2's 667–884 ms.
- [ ] **Step 2 — the retry test 60 times** under the same load, tally. **Do not** conclude a 1/81 rate is gone from 60 green runs; state what 60 runs bound.
- [ ] **Step 3 — decide by outcome**, per the spec §3: gone → record; persists on the startup clock → a *condition* in `launch()` if observable (say what event), else a bound past the p100 flush offset with margin, **labelled a bound and cited**; changed → measure again, do not fit a story. Any wait added gets its own loop: red without, green with, on a planted early page load if the flush persists.
- [ ] **Step 4 — record.** ADR 52 dated amendment (the posture, each flag with its NetLog reason, the guard test by name, the outcome of Step 3 and its basis); ADR 46 dated note pointing at the outcome; the page linked in both places its siblings are. `git diff -- docs/adr/ | grep '^-' | grep -v '^---'` empty. `AdrIndexTest` green.
- [ ] **Step 5 — gate and commit.**

---

## Self-Review

**Spec coverage.** Enforcement → Task 1 Loop B. Attempt suppression measured per flag → Loop C. The guard with a real red and a control → Loops A/Step 4. Comment corrected → Step 5. The flush measurement → Task 2 Steps 1–2. The three-outcome decision, wait only in the licensed shape → Step 3. ADR 52 + ADR 46 note + page + links → Step 4. Rejected alternatives → spec.

**Placeholders.** None: each loop names what is quoted and what decides the next step; Task 2's wait is conditional on a stated measurement, not a TODO.

**Type consistency.** `HeadlessChrome.launch(Path)` / `NetLog.hostsContacted(Path)` named in Task 1 and consumed by Task 2.
