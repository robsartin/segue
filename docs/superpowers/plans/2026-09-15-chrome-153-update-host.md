# Chrome 153's startup component-update ask, stopped by a flag — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to
> implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** close issue #336. `HeadlessChromeNetworkTest` is red on this machine because Google
Chrome `153.0.8010.47` posts the component updater's `/service/update2/json` to
`update.googleapis.com` **at startup**, 168 ms into the guard's own NetLog, although
`--disable-component-update` is on the command line. This plan adds the one launch switch measured
to stop that check being dispatched, corrects the two javadoc paragraphs that said the host "stays
out" for a reason that no longer holds, and records the measurement in
`docs/loopback-only-evidence.md` §5. **No host is added to `KNOWN_ATTEMPTS`.**

**Architecture:** two test-source files and one document. No production code, no ADR, no new
dependency, no new test class.

- `src/test/java/com/robsartin/segue/rate/HeadlessChrome.java` — one comment line reworded and one
  flag (with its measurement comment) added to `flags`.
- `src/test/java/com/robsartin/segue/rate/HeadlessChromeNetworkTest.java` — two javadoc paragraphs
  on `KNOWN_ATTEMPTS` replaced. **The constant itself does not change, and neither does
  `PHONE_HOME_CONTROL`.**
- `docs/loopback-only-evidence.md` — one dated note appended to §5.

**Tech Stack:** Java 25, Gradle (plain `./gradlew`), JUnit 5, AssertJ, ArchUnit, google-java-format
via Spotless.

**Spec:** `docs/superpowers/specs/2026-09-15-chrome-153-update-host-design.md` — read it first,
especially §4 (every flag tried and what it did, including the two that also work and were
rejected), §5 (why loopback and why port 1), and §6 (what this means on Linux, and why the Linux
record is not touched).

## Global Constraints

- **No writing dev task, ever.** Never run `own`, `ownClaim`, `retractEntity`, `rate`,
  `expandPromotions`, `resolveNames`, `evaluate`, `graphCensus` against a real database, or any
  seeding task. **`~/.segue/segue.db` is never read, written, copied or created.** Nothing in this
  plan runs any dev tool at all.
- **Plain `./gradlew`, and every Gradle run is BLOCKING — never backgrounded.** Only JDK 25 is
  installed and Gradle launches on it; never `/usr/libexec/java_home -v 21`, which silently returns
  25.
- **Every test run in this plan sets `SEGUE_REQUIRE_BROWSER=true`**, so a missing Chrome fails
  rather than skips. A skipped guard has proved nothing.
- **Pure TDD, and the red comes first.** This is a bug the existing test already reproduces: Task 1
  observes that red, in full, before anything is edited. **A compile error is never a red.**
- **Every guard this plan relies on gets a positive control that is actually run** — the plant, the
  observed failure quoted in the task report, then the plant removed and the green re-observed. A
  control that did not fire has proved nothing: stop and report.
- **Mikado: green at every committed step.** Stage by explicit path — **never `git add -A`, never
  `2>/dev/null` on `git add`, git stderr always visible.** Read `git status` before every commit.
- Commits end, after a blank line, `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- Gate before the final commit, **blocking**:
  `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`, with the
  test count taken **once** from `build/test-results/test/*.xml`.
- **No `.superpowers/` path and no scratchpad path in any committed file.** The NetLog this work
  measured is cited only as `build/reports/netlog/shouldContactOnlyLoopbackWhenTheDeckPageIsDriven.json`
  together with the date 2026-09-15.
- **No ADR change and no file under `docs/adr` touched.** ADR 52's decisions are being followed, not
  changed; see the spec §8. No commit hash anywhere under `docs/adr`.
- **No invented host.** Every hostname written into a comment, a javadoc or the document is one the
  measured NetLog or the trial launches actually produced. No qid is added, so ADR 58's leading-zero
  rule does not arise.
- **No new assertion, and no wall-clock assertion.** This plan adds no `assertThat` anywhere; the
  timings it writes are prose in comments and in the evidence document, never a bound a test checks.
- **Markdown links stay whole on one line.** The document edit below adds no live markdown link at
  all, which is deliberate: `DocumentationLinksTest` resolves a relative link against the linking
  file's own directory, and this plan's own path is two directories deeper than `docs/`.
- **Spotless reflows javadoc.** Write the prose as given, then run `./gradlew spotlessApply`; the
  committed form is the formatted one. Keep every `{@code …}` span short enough to sit on one
  source line after formatting; if the formatter splits one, break the sentence rather than fight
  it.
- **`docs` is a declared input of the `test` task** (`build.gradle.kts:150`), so the document edit
  re-runs the suite. Per-task verification therefore runs **without** `--rerun-tasks`, and each
  report says the task **ran** rather than printed `UP-TO-DATE`. Only the final gate adds
  `--rerun-tasks`.
- Work only in `/Users/sartin/code/segue/wt-336`, on branch `336-ready`. You are the sole committer
  there.

---

## Task 1 — the flag that stops the ask

**Files:** `src/test/java/com/robsartin/segue/rate/HeadlessChrome.java`

### Step 1 — reproduce the defect (RED)

- [ ] Run, blocking:

```
SEGUE_REQUIRE_BROWSER=true ./gradlew test --tests '*HeadlessChromeNetworkTest'
```

- [ ] **Observe a real assertion failure** in
      `shouldContactOnlyLoopbackWhenTheDeckPageIsDriven` — an AssertJ `isSubsetOf` diff ending
      `but found these extra elements: ["update.googleapis.com"]`. Read it out of
      `build/test-results/test/TEST-com.robsartin.segue.rate.HeadlessChromeNetworkTest.xml` and
      **quote it in the task report**.
- [ ] If instead the run is green, **stop and report**: the machine's Chrome is not the one this
      issue is about, and nothing below should be applied blind. Record `"/Applications/Google
      Chrome.app/Contents/MacOS/Google Chrome" --version` in the report either way.
- [ ] Confirm the same run's `build/reports/netlog/shouldContactOnlyLoopbackWhenTheDeckPageIsDriven.json`
      contains `update.googleapis.com` and that no line of it carries a `TCP_CONNECT`, `SSL_` or
      byte event for that host — the zero-reached assertion passed, and the report says so. A
      one-liner is enough: `grep -c update.googleapis.com` on that file.

### Step 2 — reword the group's opening line (GREEN, part 1)

There are about to be three switches in the "on top of the guarantee" group, not two.

- [ ] Replace exactly this line in `src/test/java/com/robsartin/segue/rate/HeadlessChrome.java`:

```
        // ON TOP OF THE GUARANTEE: the two flags that measurably stop an *attempt* being made,
```

with:

```
        // ON TOP OF THE GUARANTEE: the flags that measurably stop an *attempt* being made,
```

### Step 3 — add the flag and its measurement (GREEN, part 2)

- [ ] Replace exactly this line in the same file:

```
        "--disable-features=NetworkTimeServiceQuerying,SafeBrowsingHashPrefixRealTimeLookups",
```

with:

```
        "--disable-features=NetworkTimeServiceQuerying,SafeBrowsingHashPrefixRealTimeLookups",
        // ADDED LATER, for a browser that changed: Chrome 153.0.8010.47 on macOS 26.6.2 posts
        // https://update.googleapis.com/service/update2/json at *startup* — 168 ms into the
        // guard's kept NetLog, in a log spanning 227 ms, headers X-Goog-Update-Interactivity: fg
        // and X-Goog-Update-Updater: chrome-153.0.8010.47 — although --disable-component-update
        // above is on the command line and is meant to be exactly that. It dies at DNS like every
        // other attempt, so nothing is reached; but it is an attempt, and this is the group for
        // attempts a flag removes (issue #336).
        //
        // What this flag does, measured rather than reasoned: the component updater's update-check
        // URL is a launch switch, and this build dispatches NO check at all when it is given a
        // source that is not HTTPS. Measured both ways — with url-source=https://127.0.0.1:1/ the
        // check IS dispatched, to that loopback address; with the http form below no request is
        // made to any host, in 3 launches of 3 and in this guard's own scenario. It is the scheme
        // that decides, not the host: url-source=http://update.invalid.test/ stopped it too.
        //
        // Loopback rather than an invented hostname, because a future Chrome that accepts an http
        // source could then only ever talk to this machine — an invented host would turn into an
        // ask for a name no list carries, reddening the guard on the harness's own flag.
        //
        // Measured against this same request first, each alone, and each removed NOTHING:
        // --simulate-outdated-no-au="Tue, 31 Dec 2099 23:59:59 GMT", --check-for-update-interval,
        // --disable-component-extensions-with-background-pages, --component-updater=fast-update,
        // --component-updater=test-request, and --disable-features= for MaskedDomainList,
        // EnableIpProtectionProxy, PrivacySandboxAttestations, TpcdMetadataGrants,
        // OptimizationHints, ComponentUpdaterOnDemand and CrxDownload.
        "--component-updater=url-source=http://127.0.0.1:1/",
```

- [ ] Run, blocking: `./gradlew spotlessApply`
- [ ] Run, blocking:
      `SEGUE_REQUIRE_BROWSER=true ./gradlew test --tests '*HeadlessChromeNetworkTest'`
- [ ] **Observe it pass**, and confirm from the fresh
      `build/reports/netlog/shouldContactOnlyLoopbackWhenTheDeckPageIsDriven.json` that
      `update.googleapis.com` no longer appears at all. Quote both in the report.

### Step 4 — positive control: remove the flag and watch it redden

The whole reason this is a flag rather than an allowlist entry is that a flag has a local red. Prove
it.

- [ ] Delete the line `"--component-updater=url-source=http://127.0.0.1:1/",` (leave its comment in
      place — this is a control, not an edit).
- [ ] Run, blocking:
      `SEGUE_REQUIRE_BROWSER=true ./gradlew test --tests '*HeadlessChromeNetworkTest'`
- [ ] **Observe the same assertion failure as Step 1**, naming `update.googleapis.com` as the extra
      element. **Quote it in the report.** If it passes, the flag is not what is stopping the
      request and the whole basis of this task is wrong: **stop and report.**
- [ ] Restore the line exactly where it was. Re-run the same command and see it green again.

### Step 5 — the neighbours, since they share this browser

- [ ] Run, blocking:

```
SEGUE_REQUIRE_BROWSER=true ./gradlew test --tests '*HeadlessChrome*' --tests '*DeckBehaviourTest' --tests '*NetLogTest'
```

- [ ] **Observe it pass.** In particular `shouldObserveTheStartupFlushWhenThePageIsOpened` must
      still decide the flush question inside its bound, and `DeckBehaviourTest` must still deal and
      rate its cards: this flag changes what Chrome does at startup, which is exactly where that
      flush lives. Report the test counts.

### Step 6 — commit

- [ ] `git status`
- [ ] `git add src/test/java/com/robsartin/segue/rate/HeadlessChrome.java`
- [ ] `git diff --cached --stat` — exactly one file.
- [ ] Commit, subject and trailer:

```
Stop Chrome 153's startup component-update check at the launch switch (#336)
```

---

## Task 2 — the javadoc that said the host stays out, corrected

**Files:** `src/test/java/com/robsartin/segue/rate/HeadlessChromeNetworkTest.java`

No behaviour changes here and **no test is written for it** — this is the honest exception this
project allows for prose, stated rather than left implied. What verifies it, named: the `javadoc`
task inside `check` (the javadoc must still build, and `{@code}`/`{@link}`/`{@value}` references
must still resolve), `spotlessCheck`, and the guard itself re-running because its own source
changed.

### Step 1 — confirm the anchors

- [ ] `grep -n "standing example\|is not a flake to be silenced" src/test/java/com/robsartin/segue/rate/HeadlessChromeNetworkTest.java`
- [ ] Confirm exactly one occurrence of each, both inside the javadoc on `KNOWN_ATTEMPTS`.

### Step 2 — the standing-example paragraph (GREEN)

- [ ] Replace exactly this text:

```
   * <p><b>The rule for what belongs here: what this test's own scenario asks for, on the platforms
   * it runs on, and nothing else.</b> Hosts that only <em>other</em> scenarios ask for are recorded
   * in {@code docs/loopback-only-evidence.md} §5, not admitted here — an entry taken on evidence
   * from a different scenario widens an {@code isSubsetOf} allowlist by one host for a red nobody
   * can produce, and it is silent forever after. {@code update.googleapis.com}, first named at
   * 2839–3090 ms across 80 deck-scenario NetLogs, is the standing example and stays out. A host a
   * <em>different platform</em> asks for in this same scenario is a different case, and it is
   * admitted — with the platform named, because the alternative is a guard that cannot be green on
   * both.
```

with:

```
   * <p><b>The rule for what belongs here: what this test's own scenario asks for, on the platforms
   * it runs on, and nothing else.</b> Hosts that only <em>other</em> scenarios ask for are recorded
   * in {@code docs/loopback-only-evidence.md} §5, not admitted here — an entry taken on evidence
   * from a different scenario widens an {@code isSubsetOf} allowlist by one host for a red nobody
   * can produce, and it is silent forever after. A host a <em>different platform</em> asks for in
   * this same scenario is a different case, and it is admitted — with the platform named, because
   * the alternative is a guard that cannot be green on both.
   *
   * <p><b>That rule's standing example was {@code update.googleapis.com}, and Chrome 153 ended
   * it.</b> The host stayed out because only the deck scenario asked for it, first named at
   * 2839–3090 ms across 80 deck-scenario NetLogs — past the life of the browser this guard
   * launches. On <b>Chrome 153.0.8010.47, macOS 26.6.2</b>, this guard's own scenario asked for
   * it: the component updater's {@code /service/update2/json}, <b>168 ms</b> into the kept NetLog,
   * in a log spanning 227 ms, at startup and nowhere near 2.8 s, with {@code
   * --disable-component-update} on the command line the whole time (issue #336). It died at DNS
   * like every other attempt and reached nothing. So it is no longer a host that only another
   * scenario asks for, and this rule now has no standing example.
   *
   * <p><b>It is still not in this list, and that is a flag rather than a judgement.</b> {@link
   * HeadlessChrome} now also passes {@code --component-updater=url-source} pointed at loopback,
   * measured on that build to stop the check being dispatched at all — so the guard's own scenario
   * asks for nothing this list would have to admit. Remove that flag and this test reddens naming
   * the host, which is the local positive control an allowlist entry would never have had. See the
   * comment on {@code HeadlessChrome.flags} for what else was measured and removed nothing.
```

### Step 3 — the "not a flake" paragraph (GREEN)

- [ ] Replace exactly this text:

```
   * <p>So <b>a red naming a host from §5 is not a flake to be silenced by adding it</b> — it means
   * this scenario has changed again, most likely by keeping the browser alive longer still, and the
   * list has to be re-derived against that scenario rather than extended to fit the failure. <b>A
```

with:

```
   * <p>So <b>a red naming a host from §5 is not a flake to be silenced by adding it</b> — it means
   * either this scenario has changed again, by keeping the browser alive longer still, or the
   * browser itself has, as Chrome 153 did; and the list has to be re-derived against the scenario
   * as it now runs rather than extended to fit the failure. <b>A
```

### Step 4 — format, and see the guard still green

- [ ] Run, blocking: `./gradlew spotlessApply`
- [ ] Run, blocking:
      `SEGUE_REQUIRE_BROWSER=true ./gradlew test --tests '*HeadlessChromeNetworkTest'`
- [ ] **Observe it pass**, and confirm the task **ran** rather than reporting `UP-TO-DATE`.
- [ ] Run, blocking: `./gradlew javadoc` — **observe it succeed**. This is the check that the
      `{@link HeadlessChrome}` and `{@code}` spans just written still resolve.

### Step 5 — positive control: the javadoc guard can fail

A prose edit that nothing can redden on is a prose edit nobody is checking. Prove `javadoc` reads
this file.

- [ ] Temporarily change `{@link HeadlessChrome}` in the paragraph added in Step 2 to
      `{@link HeadlessChromeThatDoesNotExist}`.
- [ ] Run, blocking: `./gradlew javadoc`
- [ ] **Observe a real failure** naming `HeadlessChromeNetworkTest.java` and the unresolved
      reference. **Quote it in the report.** If it succeeds, this file is not in the javadoc task's
      inputs and the verification claimed above is false: **stop and report.**
- [ ] Restore the text exactly. Re-run `./gradlew javadoc` and see it succeed.

### Step 6 — commit

- [ ] `git status`
- [ ] `git add src/test/java/com/robsartin/segue/rate/HeadlessChromeNetworkTest.java`
- [ ] `git diff --cached --stat` — exactly one file, javadoc only. Confirm with
      `git diff --cached` that neither `KNOWN_ATTEMPTS` nor `PHONE_HOME_CONTROL` has a changed
      element.
- [ ] Commit:

```
Correct the standing-example paragraph the guard's own scenario falsified (#336)
```

---

## Task 3 — the measurement, recorded in the evidence file

**Files:** `docs/loopback-only-evidence.md`

Same honest exception: prose, no behaviour, no test written for it. What verifies it, named:
`DocumentationLinksTest` (proved to read this file by the control in Step 1) and the full gate.

### Step 1 — positive control: the link guard reads this file (RED)

- [ ] Append this line temporarily to the end of `docs/loopback-only-evidence.md`:

```
See [ADR 999](adr/0999-does-not-exist.md) for nothing.
```

- [ ] Run, blocking: `./gradlew test --tests '*DocumentationLinksTest'`
- [ ] **Observe a real assertion failure** naming `loopback-only-evidence.md` and the missing file
      `docs/adr/0999-does-not-exist.md`. **Quote it in the report.** If it passes, this guard is not
      reading this file: **stop and report.**
- [ ] Delete the line. Re-run the same command and see it green.

### Step 2 — the dated note (GREEN)

- [ ] In §5, "What the browser reached, and what it still asks for", find these lines, which are the
      last lines of the note added 2026-09-02 (PR #194) and the end of the section:

```
> **How the next one will be derived.** Not from an assertion message, which is all this one had:
> the guard now copies its NetLog to `build/reports/netlog/<test>.json` on every run, green or red,
> and the CI workflow's artifact `path:` carries that directory. The next platform's host set is
> read off the file.
```

- [ ] Insert this text immediately after them, leaving a blank line between, and leaving the `---`
      that follows where it is:

```
> **Note added 2026-09-15 (issue #336, Chrome 153).** The `update.googleapis.com` row above — and
> the paragraph under it keeping that host out of `KNOWN_ATTEMPTS` — was measured on **Chrome
> 152.0.7977.65**. On **Chrome 153.0.8010.47 / macOS 26.6.2** the same guard, unchanged, went red
> naming it, and **not** for the 2.8 s reason this section gives:
>
> | | Chrome 152, 80 deck-scenario NetLogs | Chrome 153, the guard's own scenario |
> |---|---|---|
> | first named at | 2839–3090 ms | **168 ms** |
> | in a log spanning | seconds | **227 ms** |
> | asked for | `/service/update2/json` | `/service/update2/json`, as a `POST` |
> | reached anything | no | **no** — `ERR_NAME_NOT_RESOLVED` at the resolver |
>
> Read off the guard's own kept log,
> `build/reports/netlog/shouldContactOnlyLoopbackWhenTheDeckPageIsDriven.json`, from the red run of
> 2026-09-15. The request carries `X-Goog-Update-Interactivity: fg`,
> `X-Goog-Update-Updater: chrome-153.0.8010.47` and one component id,
> `X-Goog-Update-AppId: ceofaddefefcbblgcgnibnonglccbfja` — which component that id names was not
> established and is not claimed here. It ends `ERR_NAME_NOT_RESOLVED` in six places
> (`HOST_RESOLVER_MANAGER_REQUEST`, `TCP_CONNECT_JOB_CONNECT`, `SSL_CONNECT_JOB_CONNECT`,
> `SOCKET_POOL`, `URL_REQUEST_START_JOB`, `REQUEST_ALIVE`), and the log's own
> `clientInfo.command_line` shows `--disable-component-update` was passed on that launch.
> **Nothing was reached**: in that red run the zero-reached assertion and the instrument control
> both passed, and only the inventory failed.
>
> **This is the browser changing, not the scenario.** Nothing in the tree moved between the green
> runs earlier the same day and the red one; what moved was Chrome. The host is therefore no longer
> an attempt "a different scenario provokes and the guard's scenario does not", and the sentence
> above that says so describes Chrome 152.
>
> **A flag stops it, so no host was admitted.** `HeadlessChrome.flags` — which is the list of flags,
> rather than this page — now also passes a `--component-updater=url-source` pointed at loopback.
> The component updater's update-check URL is a launch switch, and this build dispatches no check at
> all when the source is not HTTPS; with an `https://` loopback source the check *is* dispatched, to
> loopback. Measured both ways, and the http form removed the request in 3 launches of 3 and in the
> guard's own scenario, where the host set returns to `accounts.google.com`, `www.google.com`,
> `~notfound` and `2001:4860:4860::8888`. Tried first against this same request, each alone, each
> removing nothing: `--simulate-outdated-no-au`, `--check-for-update-interval`,
> `--disable-component-extensions-with-background-pages`, `--component-updater=fast-update`,
> `--component-updater=test-request`, and `--disable-features=` for `MaskedDomainList`,
> `EnableIpProtectionProxy`, `PrivacySandboxAttestations`, `TpcdMetadataGrants`, `OptimizationHints`,
> `ComponentUpdaterOnDemand` and `CrxDownload`. `--disable-background-networking` and
> `--disable-component-update` were already on the command line and stop neither.
>
> **Linux is not touched by this.** Everything above was measured on macOS. The `redirector.gvt1.com`
> note and the Linux host set stand exactly as written, measured on `ubuntu-latest` with its own
> Chrome; a list is not trimmed on evidence from a platform that did not produce it. The new flag may
> well remove that host too — it is the same component updater's download redirector, and a check
> that is never dispatched downloads nothing — but that would leave the entry **over-listing**, which
> `isSubsetOf` keeps green rather than red. Whether it does is read off the next CI run's `reports`
> artifact, not decided here.
```

- [ ] Confirm the inserted text adds **no** markdown link — every path and flag in it is inside
      backticks. `DocumentationLinksTest` therefore has nothing new to resolve, and the control in
      Step 1 is what says the file is read at all.

### Step 3 — verify

- [ ] Run, blocking: `./gradlew test --tests '*DocumentationLinksTest'` — **observe it pass**, and
      confirm it **ran** (the `docs` directory is a declared input, so the edit invalidates it).
- [ ] Run, blocking: `SEGUE_REQUIRE_BROWSER=true ./gradlew test --tests '*HeadlessChromeNetworkTest'`
      — **observe it pass**.

### Step 4 — commit

- [ ] `git status`
- [ ] `git add docs/loopback-only-evidence.md`
- [ ] `git diff --cached --stat` — exactly one file, and the diff is additions inside §5 only.
- [ ] Commit:

```
Record the Chrome 153 startup component-update measurement in §5 (#336)
```

---

## Task 4 — the gate

**Files:** none.

### Step 1 — the full gate, blocking

- [ ] Run, blocking and in the foreground:

```
SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks
```

- [ ] **Observe `BUILD SUCCESSFUL`.** If anything fails, fix it under the same TDD discipline —
      red first — or stop and report; do not commit over a red.

### Step 2 — count the tests once

- [ ] Run:

```
grep -ho 'tests="[0-9]*"' build/test-results/test/*.xml | grep -o '[0-9]*' | paste -sd+ - | bc
```

- [ ] Report that single number as the suite's test count. **Do not** add the numbers Gradle prints
      per task to it, and do not report a second count from the HTML report — one count, from the
      XML, once.

### Step 3 — final state

- [ ] `git status` — clean, three commits ahead of `origin/main`.
- [ ] `git log --oneline origin/main..HEAD` — three subjects, each naming #336.
- [ ] `git log -1 --format=%B` — confirm the trailer line is exactly
      `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- [ ] Report: the three commit hashes, the test count, the red quoted in Task 1 Step 1, and each
      positive control's observed failure text.
