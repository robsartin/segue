# The retry control creates its precondition — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `DeckBehaviourTest`'s retry control creates the pooled socket it depends on immediately before the keypress, and its failure message says whether the pool was flushed or the browser stopped resending.

**Architecture:** After `untilQuiet()`, the test issues a page-side warm-up `fetch` (same origin, body drained), waits for the stub to see it end and the promise to resolve, and presses at once. The accounting `Filter` records every client port served; the control's message classifies a failure by whether the POST's port was previously seen.

**Tech Stack:** Java (toolchain 25, `release 21`), Gradle 9.7.1, JUnit, AssertJ, headless Chrome over CDP, `com.sun.net.httpserver`.

**Spec:** `docs/superpowers/specs/2026-09-01-retry-control-creates-its-precondition-design.md`

## Global Constraints

- **Pure TDD, one small behaviour per red→green loop**; every red observed and quoted. Where a behaviour cannot be driven red without an instrument (a pool flush), say so and use the control the spec names instead of inventing a false red.
- Test names `should<Expected>When<Condition>` with `@DisplayName`. **ADRs are immutable** (ADR 1): a dated amendment to ADR 46, never an edit. **Never `git add -A`.**
- Gate, **blocking, never backgrounded**: `SEGUE_REQUIRE_BROWSER=true ./gradlew check --rerun-tasks`. Baseline on `main` is 1019 tests — measure it.
- **Only JDK 25 is installed and Gradle 9.7.1 launches on it.** Do NOT set `JAVA_HOME=$(/usr/libexec/java_home -v 21)` — it returns the JDK 25 path with exit 0. Plain `./gradlew`.
- **Never run a writing dev task** (`ownClaim`, `retractEntity`, any abbreviation — `./gradlew own` runs `:ownClaim`). `~/.segue/segue.db` is never read, written, or created.
- **No production change:** `deck.html` is untouched. **No retry loop.**

---

### Task 1: Create the precondition, classify the failure

**Files:**
- Modify: `src/test/java/com/robsartin/segue/rate/DeckBehaviourTest.java` (`accounting()`, `@BeforeEach`, `aRetriedRatingCannotOverwriteAReRating`, the control's assertion message)

**Interfaces:**
- Consumes: `untilQuiet()`, `inFlight`, `lastArrived`, `lastPath`, `HeadlessChrome.eval/until/press`, `posts`.
- Produces: a `warmUp()` helper (page-side `fetch` of a same-origin GET, body drained, waited to completion on both sides) and a `portsServed` record in the `Filter`; the control's message names `flushed` vs `not resent`.

- [ ] **Loop A — the accounting `Filter` records every client port.** Test: after a page load, the set of ports served is non-empty and contains the port the card fetch arrived on. Red first (no such field). Green.

- [ ] **Loop B — `warmUp()` leaves a used idle socket.** Test: after `warmUp()`, the stub has seen one more exchange on a port already in `portsServed`, `inFlight == 0`, and the page reports the promise resolved with the body read. Red first. Green. **Drain the body** — an undrained response keeps the socket checked out (#188).

- [ ] **Loop C — the retry test presses immediately after `warmUp()`.** Sequence in the test: `untilQuiet()` (unchanged) → `warmUp()` → `chrome.press("1")` with no wait between. **Control:** with the §6 occupancy probe from `docs/retry-precondition-evidence.md` and the warm-up removed, the existing failure; with the warm-up in place and the probe still active, judge and report what happens — the probe holds sockets, so say whether the warm-up can still obtain an idle one, and quote the outcome either way.

- [ ] **Loop D — the classified message.** The control's failure now reports the POST's client port and whether it was previously seen: never seen → *"the pool was flushed between the warm-up and the keypress: the POST arrived on a fresh connection (port N, never seen); this is Chrome's network-change handling, not the browser ceasing to resend — rerun"*; seen → *"Chrome was bound to a pooled socket (port N, which served …) and still did not resend"*. **Positive controls in both directions:** hold the warm-up's response so the socket is not idle → the POST goes out fresh → the message reads as the flush case, quote it; then force `count == 1` on a reused socket if you can construct it (e.g. by making the stub answer the first POST with headers, which suppresses Chrome's resend) → the message reads as the browser case, quote it. If one direction cannot be constructed, say so and say why.

- [ ] **Step 5 — twenty runs.** The single test 20× sequentially under CPU load (report the load average); tally. If a network toggle is available (`networksetup -setairportpower` or a VPN reconnect), 10× with churn and report separately; if not, say so.

- [ ] **Step 6 — gate and commit.**

---

### Task 2: Record it — the measurement page and ADR 46's second amendment

**Files:**
- Create: `docs/retry-pool-flush-evidence.md` from the prepared copy at `/private/tmp/claude-501/-Users-sartin/8aa8be3e-94ef-4084-8cc6-80675ed60ecd/scratchpad/169b-evidence-for-docs.md` (header in `docs/engine-bake-off.md`'s register; no `.superpowers` or local paths; §1–§8 otherwise verbatim — diff it against the gitignored original and say so)
- Modify: `docs/adr/0046-the-rating-deck.md` (dated amendment, addition only), `README.md` (documentation table row beside the two sibling measurement pages), `docs/user-guide.md` (further-reading entry), `docs/retry-precondition-evidence.md` (**addition only**: a dated note at its top pointing at the round-2 page and correcting its §8 claim that `clients2` traffic was unrelated)

- [ ] **Step 1:** commit the measurement page; link it in the two places; verify all links resolve.
- [ ] **Step 2:** ADR 46's second amendment (2026-09-01, issue #169, round 2): what round 2 saw (the flush, six sockets, one millisecond), why the counter could not see it, the created precondition, the classified message, the measured residual (~1 in 500 under the same churn, with its basis), the four rejected alternatives, and the `clients2` correction — citing the page as a dated measurement. `git diff -- docs/adr/ | grep '^-'` empty. Index count unchanged; `AdrIndexTest` is not on this branch yet, so count by hand: 60/60.
- [ ] **Step 3:** gate and commit.

---

## Self-Review

**Spec coverage.** Warm-up → Loop B/C. `untilQuiet()` stays → Loop C's sequence. Classified message with both controls → Loop D. Residual measured → Step 5 + Task 2 amendment. No retry loop, no production change, no flush suppression → Global Constraints. Rejected alternatives → spec and amendment. `clients2` correction → Task 2 Step 1's note. Measurement page linked where its sibling is → Task 2 Step 1.

**Placeholders.** None: each loop names its red or its control and what the message must say.

**Type consistency.** `warmUp()` and `portsServed` named once in Task 1 and consumed only there; Task 2 cites the test's name and the page's path.
