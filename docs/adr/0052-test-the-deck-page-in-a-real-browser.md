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
suite fails with the property set and reports nine skipped tests without it.

`DeckPageTest` keeps only what a running page cannot answer: that the page reaches no external
host, that the ratings are real `<button>` elements, that the region a screen reader is told to
watch is the region the script rewrites, that the card is built as text rather than markup, and
that the revision banner has a background fill rather than merely a colour.

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

- `./gradlew check` stays green on a machine with no browser. It reports nine skipped tests, which
  is visible, and CI cannot reach that state.
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
- The driver is test-only and lives beside the test that uses it. It is not a general browser
  harness and should not grow into one without a decision.
