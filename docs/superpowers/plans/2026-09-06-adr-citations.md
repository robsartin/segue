# Plan: guard commit-hash citations in `docs/adr/` (issue #274)

Design: `docs/superpowers/specs/2026-09-06-adr-citations-design.md`.
Branch `274-ready`, off `main` at `7e2651c`. Three tasks.

## Global constraints

- **Pure TDD.** Every behaviour: write the failing test, **run it and observe a real assertion
  failure**, then the minimum code, then run it green. A compile error is not a red. Every step
  below that says *observe* means: run the command, read the message, and quote it in the report.
- **Every guard gets a positive control.** Plant the defect, watch the check fire, remove the plant.
  Written out as steps in tasks 1 and 2.
- Test names `should<Expected>When<Condition>`, each with `@DisplayName`.
- **Mikado: green at every committed step.** Task 2 lands the amendment *and* the allowlist entries
  it forces in one commit.
- Java 25 is the only JDK; plain `./gradlew`. Long Gradle runs are **blocking** — never backgrounded.
- **Never** run `./gradlew own`, `ownClaim`, `retractEntity` or any writing dev task. **Never** read,
  write, copy or create `~/.segue/segue.db`.
- Stage by explicit path. Never `git add -A`, never hide git's stderr.
- Do not edit ADR 1's existing Decision or its 2026-09-01 amendment. Append only.
- Do not cite any `.superpowers/` path in a committed file.
- Formatter is google-java-format via spotless; keep lines within 100 columns.

Fast loop for tasks 1 and 2 (blocking):

```
./gradlew test --tests 'com.robsartin.segue.arch.AdrCitationsTest'
```

---

## Task 1 — `AdrCitationsTest`: the scan, the controls, and the allowlist of today's corpus

### 1.1 RED — the planted citation, against a stubbed scanner

Create `src/test/java/com/robsartin/segue/arch/AdrCitationsTest.java` with `hashesIn` stubbed so the
failure is an assertion, not a compile error:

```java
package com.robsartin.segue.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AdrCitationsTest {

  /** Stub: replaced in step 1.2. */
  static List<String> hashesIn(String markdown) {
    return List.of();
  }

  @Test
  @DisplayName("a backticked hex run of seven to forty characters is read as a commit citation")
  void shouldFindAPlantedHashWhenAdrProseIsScanned() {
    assertThat(hashesIn("the index row was fixed in `deadbeef`, before the reading existed"))
        .containsExactly("deadbeef");
  }
}
```

Run it. **Observe** a real assertion failure of the shape:

```
Expecting actual: [] to contain exactly (and in same order): ["deadbeef"] but could not find ...
```

Not a compile error. If it does not fail, stop and find out why.

### 1.2 GREEN — the regex

Replace the stub, adding the imports:

```java
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
```

```java
  /**
   * A commit hash cited in prose: seven to forty hex characters, inside a code span. The backticks
   * are part of the pattern rather than a word boundary — a hash written as bare prose in a shell
   * example is not a citation, and requiring the code span is what keeps issue numbers and ADR
   * numbers out. Case-insensitive: git writes lowercase, and a citation pasted in uppercase must
   * not slip past unseen. See {@code docs/adr/0001-record-architecture-decisions.md}'s 2026-09-06
   * amendment.
   */
  private static final Pattern CITATION = Pattern.compile("`([0-9a-fA-F]{7,40})`");

  /** Every commit citation in one document, in the order it appears, duplicates included. */
  static List<String> hashesIn(String markdown) {
    List<String> found = new ArrayList<>();
    Matcher matcher = CITATION.matcher(markdown);
    while (matcher.find()) {
      found.add(matcher.group(1));
    }
    return found;
  }
```

Run it. **Observe** it pass.

### 1.3 RED → GREEN — the false-positive control, proved by a planted defect

Add the second test:

```java
  @Test
  @DisplayName("issue numbers, ADR numbers, filenames and bare hashes are not commit citations")
  void shouldFindNothingWhenTheProseIsNotAHashCitation() {
    assertThat(
            hashesIn(
                """
                ADR `45`, issue #274, `main` at 7e2651c, `seed`, `NodeKind`, `INFLUENCED_BY`,
                `0045-recommend-by-normalised-lift-with-routes.md`, `123456`,
                `12345678901234567890123456789012345678901`
                """))
        .isEmpty();
  }
```

This is green the moment it is written, so its red is proved by planting the defect it exists to
catch. Widen the pattern's floor:

```java
  private static final Pattern CITATION = Pattern.compile("`([0-9a-fA-F]{2,40})`");
```

Run it. **Observe** it fail naming the two-character token — `Expecting empty but was: ["45"]`.
Restore `{7,40}`, run, **observe** both tests green.

The three near-misses that carry the most weight, and why each is refused: `#274` is not backticked
and `#` is not hex; `` `45` `` is two characters, below the floor; the ADR filename has four hex
characters and then a hyphen, so the closing backtick is never adjacent. The forty-one-character
token is over the ceiling and the unbackticked `7e2651c` has no code span. A backticked `deadbeef`
*is* matched, deliberately — step 1.1 is that behaviour, committed.

### 1.4 RED — the corpus, with an empty allowlist

Add the imports:

```java
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
```

and, in the class:

```java
  private static final Path ADR = RepositoryTree.root().resolve("docs/adr");

  /** One citation: the hash, and the ADR file it is written in. */
  record Citation(String file, String hash) {}

  private static final Comparator<Citation> BY_FILE_THEN_HASH =
      Comparator.comparing(Citation::file).thenComparing(Citation::hash);

  /** Filled in by step 1.5. */
  private static final List<Citation> REACHABLE_FROM_MAIN = List.of();

  /** Filled in by step 1.5. */
  private static final List<Citation> UNREACHABLE_RESOLVED_BY_ADR_1 = List.of();

  @Test
  @DisplayName("every commit hash cited in docs/adr is allowlisted, in the file it is cited in")
  void shouldAllowlistEveryCitationWhenTheAdrsAreScanned() {
    assertThat(citedInTheAdrs()).containsExactlyElementsOf(allowlist());
  }

  /**
   * Exact equality, both directions. A hash cited in an ADR that is not here reds — that is the
   * point of the guard. An entry here that is no longer cited reds too, so a stale line cannot sit
   * in this list pretending to describe the tree.
   */
  private static List<Citation> allowlist() {
    List<Citation> all = new ArrayList<>(REACHABLE_FROM_MAIN);
    all.addAll(UNREACHABLE_RESOLVED_BY_ADR_1);
    all.sort(BY_FILE_THEN_HASH);
    return all;
  }

  /**
   * Every {@code *.md} in {@code docs/adr}, the index included — a hash pasted into
   * {@code README.md} is a citation too, which is why {@link AdrIndexTest}'s narrower filename
   * filter is not reused here: it exists there to exclude the index.
   */
  private static List<Citation> citedInTheAdrs() {
    List<Citation> found = new ArrayList<>();
    try (Stream<Path> entries = Files.list(ADR)) {
      for (Path file : entries.sorted().toList()) {
        String name = file.getFileName().toString();
        if (!name.endsWith(".md")) {
          continue;
        }
        for (String hash : hashesIn(RepositoryTree.read(file))) {
          Citation citation = new Citation(name, hash);
          if (!found.contains(citation)) {
            found.add(citation);
          }
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    found.sort(BY_FILE_THEN_HASH);
    return found;
  }
```

Run it. **Observe** it fail, enumerating all fourteen real pairs against an empty expectation —
`Expecting actual: [Citation[file=0001-…, hash=0a29f45], …] to contain exactly (and in same order):
[]`. That failure is also the proof the scan reaches every ADR file.

### 1.5 GREEN — the fourteen pairs

Thirteen distinct hashes; `a7c3455` is cited in two files, so fourteen pairs. Replace the two empty
lists:

```java
  /**
   * Cited hashes {@code main} can reach: the citing branch's work was squashed onto {@code main},
   * but these particular objects are on {@code main} themselves.
   */
  private static final List<Citation> REACHABLE_FROM_MAIN =
      List.of(
          new Citation("0024-sqlite-assertion-log.md", "a79c6ca"),
          new Citation("0024-sqlite-assertion-log.md", "fd88813"),
          new Citation("0044-retraction-as-a-new-claim.md", "0783492"),
          new Citation("0044-retraction-as-a-new-claim.md", "a7c3455"),
          new Citation("0058-stand-in-identifiers-cannot-be-allocatable.md", "cd1d8dc"),
          new Citation("0059-owner-claims-as-a-third-layer.md", "2e01341"),
          new Citation("0059-owner-claims-as-a-third-layer.md", "a7c3455"));

  /**
   * Cited hashes {@code main} cannot reach, because a squash merge left the branch commit out of
   * its history. Each is resolved to the pull request that carried it, and the time it was pushed,
   * in {@code docs/adr/0001-record-architecture-decisions.md}'s 2026-09-06 amendment (issue #274) —
   * which is where those two facts live. This list holds only hash and file; the amendment holds
   * only hash, pull request and push time. Neither restates the other.
   */
  private static final List<Citation> UNREACHABLE_RESOLVED_BY_ADR_1 =
      List.of(
          new Citation("0001-record-architecture-decisions.md", "0a29f45"),
          new Citation(
              "0045-recommend-by-normalised-lift-with-routes.md",
              "33a0dd63c5fca13efc5cfdd38ecac6dab67df4ba"),
          new Citation("0045-recommend-by-normalised-lift-with-routes.md", "3c2171e"),
          new Citation("0045-recommend-by-normalised-lift-with-routes.md", "74e757f"),
          new Citation("0045-recommend-by-normalised-lift-with-routes.md", "9937f86"),
          new Citation("0045-recommend-by-normalised-lift-with-routes.md", "e0e398b"),
          new Citation("0059-owner-claims-as-a-third-layer.md", "fdd420d"));
```

Run it. **Observe** all three tests pass.

### 1.6 Positive control on the allowlist

Delete this line:

```java
          new Citation("0058-stand-in-identifiers-cannot-be-allocatable.md", "cd1d8dc"),
```

Run it. **Observe** the corpus test red, naming exactly that pair as unexpected in the actual list.
Restore the line, run, **observe** green. Quote the failure in the report.

### 1.7 Class Javadoc

Add above `class AdrCitationsTest`:

```java
/**
 * No commit hash may be cited in {@code docs/adr/} unless this class names it and the file it is
 * cited in — issue #274.
 *
 * <p>This repository squash-merges. A branch commit is rewritten into a single commit on {@code
 * main} and the original object is never in {@code main}'s history, so a hash an amendment cited as
 * evidence of ordering — "the rule was committed as … before the reading existed" — resolves to
 * nothing in a fresh clone. Read on 2026-09-06 against {@code main} at {@code 7e2651c}, seven of the
 * thirteen hashes then cited were unreachable, and one of the seven had never been pushed at all: it
 * is on GitHub nowhere and on {@code main} nowhere.
 *
 * <p><b>The list is (file, hash) pairs, and the assertion is exact in both directions.</b> A new
 * citation reds, which is the guard. A pair that is allowlisted but no longer in the tree reds too,
 * so an entry cannot rot here describing a citation somebody deleted. The cost is that every new
 * citation is an edit somebody made on purpose — which is the rule ADR 1's 2026-09-06 amendment
 * states.
 *
 * <p><b>What is deliberately NOT checked</b>, so nobody reads more assurance into this class than it
 * gives: whether a hash still resolves anywhere. That needs the network and a remote, and the answer
 * changes without this repository changing. The amendment records the answer as it was read on
 * 2026-09-06; this class only controls what may be written.
 */
```

Run the suite once more; **observe** green.

### 1.8 Commit

```
git add src/test/java/com/robsartin/segue/arch/AdrCitationsTest.java
git status
git commit
```

Message: `Guard the commit hashes cited in docs/adr (#274)`, then a blank line, then
`Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

**A new arch test class needs no guide row.** `DeveloperGuideEnumerationsTest`'s testing-strategy
assertions cover `@Tag("live")` classes and stub HTTP servers, and its ArchUnit table covers
`ArchitectureTest`'s rules; `AdrCitationsTest` is none of those, so nothing in the guide has to
enumerate it. `JavadocCitationsTest` reads citations *from* `src/main`, and this class is cited by
none. Checked before writing this plan — if either reds, that is a finding, not a formality.

---

## Task 2 — the ADR 1 amendment and the guide sentence

No unit test, **and that is said out loud**: this task changes prose only, and there is no behaviour
to assert. It is verified three ways, each run and observed below — the task-1 guard reds on the
amendment's new citations and goes green only when they are allowlisted (2.2, 2.3);
`DocumentationLinksTest` resolves the guide's new relative link (2.5); `AdrIndexTest` confirms ADR 1's
index row still agrees with the file (2.5).

### 2.1 Append the amendment to ADR 1

Append to the end of `docs/adr/0001-record-architecture-decisions.md`, after the 2026-09-01
amendment. Do not edit anything above it.

````markdown

**Amendment (2026-09-06, issue #274): under a squash merge a commit hash is not an ordering witness.
An amendment cites the pull request and the push time; `AdrCitationsTest` is the authority for which
hashes may appear in `docs/adr/` at all.**

This repository squash-merges. A branch commit is rewritten into one commit on `main` and the
original object is never in `main`'s history, so a hash an amendment cited as evidence of ordering —
"the rule was committed as … before the reading existed" — resolves to nothing in a fresh clone.
Read on 2026-09-06 against `main` at `7e2651c` (a merge commit on `main`, and therefore permanent in
a way a branch commit is not), seven of the thirteen hashes cited across `docs/adr/` were
unreachable. They are resolved here, once, so a reader of any of those ADRs has one place to look.

| Hash | What it witnessed | Pull request | Push time (UTC) |
|---|---|---|---|
| `0a29f45` | ADR 41's index row regaining the backticks its heading carries (issue #170) — cited in this ADR's 2026-09-01 amendment | PR #191 | 2026-09-01 22:21:25 |
| `9937f86` | the calibration rule, written before the first reading was taken (issue #242) — ADR 45 | PR #243 | 2026-09-04 23:56:02 |
| `74e757f` | the second reading's rule, first push (issue #245) — ADR 45 | PR #267, which no longer lists it: the branch was force-pushed when the commit was rebased, and the association went with it | 2026-09-05 00:58:30 |
| `33a0dd63c5fca13efc5cfdd38ecac6dab67df4ba` | the same rule, rebased (issue #245) — ADR 45 | PR #267 | 2026-09-06 15:25:48 |
| `e0e398b` | the folded-table note (issue #270) — ADR 45 | PR #271 | 2026-09-06 17:57:42 |
| `3c2171e` | the ratings-moved note (issue #272) — ADR 45 | PR #273 | 2026-09-06 20:47:30 |
| `fdd420d` | the widened stand-in rule, reproduced in a fix-round review (issue #221) — ADR 59 | **none.** It was never pushed; the work landed on `main` as `0783492` through PR #226 | not on GitHub; committer date on the machine it was made on, 2026-09-03 20:49:26 |

**One of the seven is worse than unreachable.** `fdd420d` is not on GitHub either:
`gh api repos/robsartin/segue/commits/fdd420d` answers `422 No commit found for SHA`, and a commit
search over this repository returns nothing. It exists only in the working clone it was made in, so
ADR 59's citation of it is evidence nobody else can open. The reproduction it witnesses is described
in full in ADR 59's own prose, which is the part that survives; the hash is not, and this is the
clearest possible statement of why the rule below exists.

**The rule from here on.** An amendment that needs to prove *when* something was decided cites **the
pull request number and the push time**. Both survive the squash, both are readable by anyone with
the repository, and together they order an amendment against anything else dated. A commit hash may
appear **only alongside them**, as a convenience for a reader who has the object — never alone, and
never as the sole witness.

**Why the earlier citations are left as they are.** ADRs are immutable; that is this ADR's own
Decision, and it does not have an exception for citations that turned out to be weak. The lines
carrying these thirteen hashes are not edited and not deleted. Nor is the evidence gone: six of the
thirteen are reachable from `main` today (`0783492`, `a7c3455`, `2e01341`, `a79c6ca`, `fd88813`,
`cd1d8dc`), and six of the seven unreachable ones are still served by GitHub's commits API. Only
`fdd420d` is beyond reach, and this amendment says so rather than leaving a reader to discover it.

**What checks this.** `AdrCitationsTest`
(`src/test/java/com/robsartin/segue/arch/AdrCitationsTest.java`) reads every `*.md` in `docs/adr/` on
every build and fails on any backticked hexadecimal run of seven to forty characters that it does not
already name, together with the file it is cited in. As with `AdrIndexTest` above, this ADR does not
restate that list — the test is the list. Nor does the test restate this table: it holds hash and
file, this table holds hash, pull request and push time, and neither is a copy of the other to go
stale against.
````

### 2.2 Observe the guard red

Run the fast loop. **Observe** `shouldAllowlistEveryCitationWhenTheAdrsAreScanned` fail, naming
**eight** pairs it did not expect, all in `0001-record-architecture-decisions.md`: `0783492`,
`33a0dd63c5fca13efc5cfdd38ecac6dab67df4ba`, `3c2171e`, `74e757f`, `7e2651c`, `9937f86`, `e0e398b`,
`fdd420d`. (`0a29f45` is not among them — this ADR already cited it in the 2026-09-01 amendment, and
a pair is counted once.) Quote the failure in the report: this red *is* the verification that the
amendment's citations are seen.

If the count is not eight, the amendment text was altered — reconcile against the failure message
rather than against this plan.

### 2.3 GREEN — allowlist the amendment's own citations

Add a third list to `AdrCitationsTest` and include it in `allowlist()`:

```java
  /**
   * The citations the 2026-09-06 amendment to ADR 1 makes while resolving the others. Seven of them
   * are the unreachable hashes it tabulates, restated in the one place a reader is sent to;
   * {@code 0783492} is where {@code fdd420d}'s work actually landed on {@code main}, and
   * {@code 7e2651c} is the commit the survey was read against — both on {@code main}, both cited
   * beside a pull request number and a time, which is the shape the amendment's own rule asks for.
   */
  private static final List<Citation> IN_THE_ADR_1_AMENDMENT =
      List.of(
          new Citation("0001-record-architecture-decisions.md", "0783492"),
          new Citation(
              "0001-record-architecture-decisions.md", "33a0dd63c5fca13efc5cfdd38ecac6dab67df4ba"),
          new Citation("0001-record-architecture-decisions.md", "3c2171e"),
          new Citation("0001-record-architecture-decisions.md", "74e757f"),
          new Citation("0001-record-architecture-decisions.md", "7e2651c"),
          new Citation("0001-record-architecture-decisions.md", "9937f86"),
          new Citation("0001-record-architecture-decisions.md", "e0e398b"),
          new Citation("0001-record-architecture-decisions.md", "fdd420d"));
```

```java
  private static List<Citation> allowlist() {
    List<Citation> all = new ArrayList<>(REACHABLE_FROM_MAIN);
    all.addAll(UNREACHABLE_RESOLVED_BY_ADR_1);
    all.addAll(IN_THE_ADR_1_AMENDMENT);
    all.sort(BY_FILE_THEN_HASH);
    return all;
  }
```

Run the fast loop. **Observe** green — twenty-two pairs.

### 2.4 The guide sentence

In `docs/developer-guide.md`, in `## How to read an ADR against the code`, append one sentence as a
new paragraph after the paragraph that ends `…where it reads as permission to leave the ADRs
untrue.` and before the `## Where to look next` heading:

```markdown
Because this repository squash-merges, a bare commit hash cited in an amendment resolves to nothing
in a fresh clone, so an amendment proves ordering with the pull request number and the push time and
carries a hash only alongside them — `AdrCitationsTest` holds the allowlist of the hashes already
cited, and [ADR 1](adr/0001-record-architecture-decisions.md)'s 2026-09-06 amendment resolves the
seven of them `main` cannot reach.
```

### 2.5 The document guards

Run, blocking:

```
./gradlew test --tests 'com.robsartin.segue.arch.*'
```

**Observe** green, and specifically that `DocumentationLinksTest` (the new relative link),
`AdrIndexTest` (ADR 1's row still agrees with the file) and `DeveloperGuideEnumerationsTest` all
pass. Note in the report that `build.gradle.kts` already declares `inputs.dir("docs")` on `test`, so
this run genuinely re-read the edited documents rather than reporting `UP-TO-DATE`; if any task
prints `UP-TO-DATE`, stop — the run proved nothing.

### 2.6 Commit

```
git add docs/adr/0001-record-architecture-decisions.md docs/developer-guide.md \
  src/test/java/com/robsartin/segue/arch/AdrCitationsTest.java
git status
git commit
```

Message: `Resolve the unreachable hash citations, and say what to cite instead (#274)`, then a blank
line, then `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

---

## Task 3 — the gate

Run, blocking, from the worktree root:

```
SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks
```

**Observe** `BUILD SUCCESSFUL`. If spotless reformats `AdrCitationsTest.java`, commit the
reformatting as its own commit (`Format AdrCitationsTest (#274)`) and re-run the gate.

Then report, quoting the actual failure text observed at steps 1.1, 1.3, 1.4, 1.6 and 2.2.
