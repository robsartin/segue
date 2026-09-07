# Plan: `listRatings --promotions-off … --names …` (issue #285)

Design: `docs/superpowers/specs/2026-09-07-names-for-setlist-scout-design.md`.
Branch `285-ready`, off `main` at `4d5d53f`. **Seven tasks.**

## Global constraints

- **Pure TDD.** Every behaviour: write the failing test, **run it and observe a real assertion
  failure**, then the minimum code, then run it green. A compile error is not a red. Every step
  below that says *observe* means: run the command, read the message, quote it in the report.
- **Every guard gets a positive control.** Plant the defect, watch the check fire, remove the plant.
  Written out as steps in tasks 1, 4, 5 and 7.
- Test names `should<Expected>When<Condition>`, each with `@DisplayName`. (The existing
  `RatingsRunTest` methods do not all follow that shape. Leave them alone; every method you add
  does.)
- **Mikado: green at every committed step.** Seven commits, one per task.
- Java 25 is the only JDK; plain `./gradlew`. Long Gradle runs are **blocking** — never backgrounded.
- **Never** run `./gradlew own`, `ownClaim`, `retractEntity` or any writing dev task. **Never** read,
  write, copy or create `~/.segue/segue.db`. Every test here runs against invented data in a
  `@TempDir`.
- **Every qid, label and note you write is invented.** ADR 43's closing consequence and
  `InventedRatings`' javadoc. Reuse the constants that already exist; the two new ones are named in
  task 2.
- No wall-clock assertions anywhere; reuse `InventedRatings.EARLY` / `LATE`.
- Stage by explicit path. Never `git add -A`, never hide git's stderr, read `git status` before each
  commit.
- Do not edit any file under `docs/adr/`. The spec records why no amendment is due.
- Do not cite any `.superpowers/` path in a committed file.
- Formatter is google-java-format via spotless; keep lines within 100 columns.

Fast loop for tasks 1–5 (blocking):

```
./gradlew test --tests 'com.robsartin.segue.ratings.*'
```

Fast loop for task 6 (blocking):

```
./gradlew test --tests 'com.robsartin.segue.ratings.DeveloperGuideRatingsExamplesTest'
```

---

## Task 1 — One log read per run: `Labels` takes the folded log, `RatingsRun` owns the skip

A prerequisite, not a behaviour. The names export needs the merges *and* the labels out of the same
log, and `Labels.forQids` calls `readAll()` itself — so folding separately would read a quarter of a
million assertions twice. This moves the read up one level and changes nothing observable.

**Honest exception, stated out loud:** this step adds no test, because it adds no behaviour. It is
verified by (a) the whole `ratings` suite staying green and (b) the planted control in 1.4, which
proves the one property that could regress — that nothing rated still means the log is never read.

### 1.1 — `Labels.forQids` takes the log that has already been read

In `src/main/java/com/robsartin/segue/ratings/Labels.java`, delete the import of
`com.robsartin.segue.port.AssertionLog`, and change the signature and first lines:

```java
  /**
   * The label the log last claimed for each of {@code qids}, omitting any it has never seen.
   *
   * <p>Filtered to the qids asked for rather than returning the whole map: a real log holds tens of
   * thousands of node claims and a person has rated a few dozen things. Data minimisation (ADR 16)
   * is the reason to prefer it, and the memory is a bonus.
   *
   * <p><b>Handed the log rather than reading it</b> (#285). {@code RatingsRun} needs the merges out
   * of the same list for the names export, and a method that reads for itself would have made that
   * a second {@code readAll} of a quarter of a million rows. It is also the class that knows when
   * there is nothing to name, which is where the "do not read at all" skip now lives. One read per
   * run, in every path — ADR 63 states the same principle for the census: one fold of the log
   * rather than two.
   */
  static Map<String, String> forQids(List<LoggedAssertion> logged, Set<String> qids) {
    Map<String, String> labels = new HashMap<>();
    if (qids.isEmpty()) {
      return labels;
    }
```

Delete the line `List<LoggedAssertion> logged = log.readAll();` that followed. The rest of the method
body is unchanged.

### 1.2 — `RatingsRun` reads the log, and keeps the skip

In `src/main/java/com/robsartin/segue/ratings/RatingsRun.java`, add the import
`com.robsartin.segue.domain.LoggedAssertion` and replace the two statements that read the stores:

```java
    List<AffinityRecord> recorded = ratings.readAll();
    // Skipped entirely when nothing is rated: a real log is a quarter of a million assertions, and
    // there is no name to look up. The skip is here rather than in Labels because this is the class
    // that knows there is nothing to name, and because the names export folds the merges out of
    // this same list — one read per run (#285).
    List<LoggedAssertion> logged = recorded.isEmpty() ? List.of() : log.readAll();
    Map<String, String> labels =
        Labels.forQids(
            logged, recorded.stream().map(AffinityRecord::qid).collect(Collectors.toSet()));
```

### 1.3 — `LabelsProbe` keeps its signature

`src/test/java/com/robsartin/segue/ratings/LabelsProbe.java` is reached from
`com.robsartin.segue.export.StandInAgreesInEveryHomeTest`, which hands it an `AssertionLog`. Keep
that shape so that test is untouched:

```java
  /** {@code ratings/Labels.forQids}, the fourth home of the stand-in rule (ADR 59's residual). */
  public static Map<String, String> forQids(AssertionLog log, Set<String> qids) {
    return Labels.forQids(log.readAll(), qids);
  }
```

Run, blocking:

```
./gradlew test --tests 'com.robsartin.segue.ratings.*' --tests 'com.robsartin.segue.export.StandInAgreesInEveryHomeTest'
```

Expect green.

### 1.4 — Positive control: the skip

In `RatingsRun`, replace the ternary with `List<LoggedAssertion> logged = log.readAll();`. Run:

```
./gradlew test --tests 'com.robsartin.segue.ratings.RatingsRunTest'
```

**Observe** `doesNotTouchTheLogWhenNothingIsRated` fail with an assertion of the shape:

```
[nothing rated: the log is never read, because there is nothing to name]
expected: 0
 but was: 1
```

Restore the ternary. Re-run; green. Quote both the failure and the recovery in the report.

### 1.5 — Commit

```
git status --short
git add src/main/java/com/robsartin/segue/ratings/Labels.java \
        src/main/java/com/robsartin/segue/ratings/RatingsRun.java \
        src/test/java/com/robsartin/segue/ratings/LabelsProbe.java
git commit
```

Message: `Read the log once per listRatings run (#285)`.

---

## Task 2 — `Promotions`: the derivation, through `KnownList`

### 2.1 — Two invented constants and a known-file helper

Append to `src/test/java/com/robsartin/segue/ratings/InventedRatings.java`, beside the other
constants:

```java
  /**
   * An entity the known-list file names <em>and</em> the owner rated highly (#285). It is what a
   * promotion is not: rated at or above the threshold, and already on the file, so it must never
   * reach the names export.
   */
  static final String SEEN_LIVE = "Q0900005";

  static final String SEEN_LIVE_LABEL = "An Ensemble Nobody Booked";
```

and, beside the other factory methods:

```java
  /**
   * A known-list file in the seeding tool's real shape (ADR 40) — the header row {@code
   * SeedFiles.OUTPUT_HEADER} writes, then one row per qid — so a test exercises {@code QidList}'s
   * "first field that is exactly a QID" rule rather than a bare list nobody produces.
   *
   * <p>Every name in it is invented, and the file is written under a {@code @TempDir}.
   */
  static Path knownFile(Path dir, String... qids) throws IOException {
    StringBuilder csv = new StringBuilder("name,kind,status,qid,label,confidence,reason\n");
    for (String qid : qids) {
      csv.append("an invented name,WORK,resolved,")
          .append(qid)
          .append(",an invented label,1.0,invented\n");
    }
    Path file = dir.resolve("known.csv");
    Files.writeString(file, csv.toString(), StandardCharsets.UTF_8);
    return file;
  }
```

Add the imports `java.io.IOException`, `java.nio.charset.StandardCharsets`, `java.nio.file.Files`
and `java.nio.file.Path`.

### 2.2 RED — the ordinary promotion, against a stub

Create `src/main/java/com/robsartin/segue/ratings/Promotions.java` with the method stubbed, so the
failure is an assertion and not a compile error:

```java
package com.robsartin.segue.ratings;

import com.robsartin.segue.domain.Equivalences;
import java.util.List;
import java.util.Map;

/** Stub: replaced in step 2.3. */
final class Promotions {

  private Promotions() {}

  static List<String> offTheKnownList(
      Map<String, Integer> stored, Equivalences merges, List<String> fromFile) {
    return List.of();
  }
}
```

Create `src/test/java/com/robsartin/segue/ratings/PromotionsTest.java`:

```java
package com.robsartin.segue.ratings;

import static com.robsartin.segue.ratings.InventedRatings.CANONICAL;
import static com.robsartin.segue.ratings.InventedRatings.MINTED;
import static com.robsartin.segue.ratings.InventedRatings.NOVEL;
import static com.robsartin.segue.ratings.InventedRatings.QUARTET;
import static com.robsartin.segue.ratings.InventedRatings.SEEN_LIVE;
import static com.robsartin.segue.ratings.InventedRatings.VANISHED;
import static com.robsartin.segue.ratings.InventedRatings.merged;
import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.Equivalences;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one question this class answers: who is promoted and not already on the file. Every id and
 * every score here is invented — see {@link InventedRatings}.
 */
class PromotionsTest {

  private static Map<String, Integer> rated(Object... qidsAndScores) {
    Map<String, Integer> scores = new LinkedHashMap<>();
    for (int i = 0; i < qidsAndScores.length; i += 2) {
      scores.put((String) qidsAndScores[i], (Integer) qidsAndScores[i + 1]);
    }
    return scores;
  }

  @Test
  @DisplayName("a high rating the known file does not name is a promotion")
  void shouldPromoteAnEntityWhenTheKnownFileDoesNotNameIt() {
    assertThat(
            Promotions.offTheKnownList(
                rated(QUARTET, 5), Equivalences.NONE, List.of(SEEN_LIVE)))
        .containsExactly(QUARTET);
  }
}
```

Run the fast loop. **Observe** an assertion failure of the shape:

```
Expecting actual:
  []
to contain exactly (and in same order):
  ["Q0900001"]
but could not find the following elements:
  ["Q0900001"]
```

Not a compile error. If it does not fail, stop and find out why.

### 2.3 GREEN — through `KnownList`, minus the file

Replace the stub body and the class javadoc:

```java
package com.robsartin.segue.ratings;

import com.robsartin.segue.domain.Equivalences;
import com.robsartin.segue.domain.KnownList;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Who the owner has promoted and Setlist Scout does not track yet (#285): everything rated at or
 * above {@code KnownList.PROMOTION_RATING} that the {@code --known} file does not already name.
 *
 * <p><b>Through {@link KnownList#promoted}, not through a predicate of its own.</b> That method is
 * the authority on what a promotion is (ADR 48) and it is what {@code RecommendRun.run} and {@code
 * RateCli.known} compose their known-lists with. A second statement of the rule here — {@code
 * rating >= PROMOTION_RATING && !onFile} — would be a copy that agrees with those two only until
 * somebody adds a clause to one of them, which is the divergence ADR 48 put the composition in
 * {@code domain} to prevent and which this project has already paid for once (issue #109, quoted in
 * {@code KnownList.revisitable}). So this takes {@code promoted}'s answer and removes the file's own
 * entities from it, which is exactly that method's documented second half.
 *
 * <p><b>By set difference, not by index.</b> {@code promoted} appends the promotions after the
 * file's list, so the tail from {@code fromFile.size()} is the same answer today. It would stop
 * being the same answer the day {@code promoted} de-duplicated its first half, and nothing would
 * say so. Asking which of its entries the file does not name is the question the method's name
 * answers.
 *
 * <p><b>Resolved through the merges first</b>, as {@code RecommendCli} and {@code EvaluateCli} both
 * do before they compose a known-list. After a merge two affinity rows name one thing (#92) and
 * {@code promoted} would promote both, so the owner's one opinion would become two names in the
 * upload — and the local id, the one he retired, would be one of them.
 *
 * <p>Not a class name containing "Affinity", deliberately: it holds an {@link Equivalences}, and
 * {@code ArchitectureTest.affinityNeverTouchesTheWorldFactLayer} matches the taste layer by simple
 * name. See {@link Labels}, which is named for the same reason.
 */
final class Promotions {

  private Promotions() {}

  /**
   * The promoted entities the file does not name, in {@code KnownList.promoted}'s own qid order.
   *
   * @param stored the affinity table's score column as it stands, before any equivalence is
   *     applied — resolved here, so this tool's idea of who is promoted is the recommender's
   * @param merges what the owner has merged, folded out of the log
   * @param fromFile the {@code --known} file's qids, as {@code QidList} reads them
   */
  static List<String> offTheKnownList(
      Map<String, Integer> stored, Equivalences merges, List<String> fromFile) {
    Objects.requireNonNull(stored, "stored");
    Objects.requireNonNull(merges, "merges");
    Objects.requireNonNull(fromFile, "fromFile");

    Set<String> onFile = new LinkedHashSet<>(fromFile);
    List<String> promotions = new ArrayList<>();
    for (String known : KnownList.promoted(fromFile, merges.resolve(stored))) {
      if (!onFile.contains(known)) {
        promotions.add(known);
      }
    }
    return List.copyOf(promotions);
  }
}
```

Run the fast loop; green.

### 2.4 RED → GREEN — the four cases that make it the right set

Add these to `PromotionsTest`, one at a time. Each is red before the assertion it names is true;
after 2.3 the first three are already satisfied by `KnownList`, so **run each one and record whether
it was red or green on arrival** — a test that arrives green is a test of somebody else's code being
reached correctly, and the report says so rather than claiming a red that did not happen.

```java
  @Test
  @DisplayName("an entity the known file already names is not promoted, however highly rated")
  void shouldNotPromoteAnEntityWhenTheKnownFileAlreadyNamesIt() {
    assertThat(
            Promotions.offTheKnownList(
                rated(SEEN_LIVE, 5, QUARTET, 5), Equivalences.NONE, List.of(SEEN_LIVE)))
        .as("the file is what the recommender already treats as known (ADR 48)")
        .containsExactly(QUARTET);
  }

  @Test
  @DisplayName("a rating below the promotion threshold is not a promotion")
  void shouldNotPromoteAnEntityWhenItsRatingIsBelowTheThreshold() {
    assertThat(
            Promotions.offTheKnownList(rated(NOVEL, 3), Equivalences.NONE, List.of(SEEN_LIVE)))
        .isEmpty();
  }

  @Test
  @DisplayName("two promotions come back in qid order, so two runs over one table agree")
  void shouldOrderThePromotionsByQidWhenThereIsMoreThanOne() {
    assertThat(
            Promotions.offTheKnownList(
                rated(VANISHED, 5, QUARTET, 4), Equivalences.NONE, List.of(SEEN_LIVE)))
        .containsExactly(QUARTET, VANISHED);
  }

  @Test
  @DisplayName("a merged rating promotes the canonical id and never the id it was retired from")
  void shouldPromoteOnlyTheCanonicalIdWhenAMergeCarriedTheRating() {
    assertThat(
            Promotions.offTheKnownList(
                rated(MINTED, 5),
                Equivalences.in(List.of(merged(MINTED, CANONICAL))),
                List.of(SEEN_LIVE)))
        .as("two rows name one thing after a merge (#92); promoting both uploads one opinion twice")
        .containsExactly(CANONICAL);
  }
```

Then plant a control for the merge clause, which is the one thing here that is *not* `KnownList`'s:
in `Promotions.offTheKnownList`, change `merges.resolve(stored)` to `stored`. Run the fast loop.
**Observe** `shouldPromoteOnlyTheCanonicalIdWhenAMergeCarriedTheRating` fail with:

```
[two rows name one thing after a merge (#92); promoting both uploads one opinion twice]
Expecting actual:
  ["Q00900042"]
to contain exactly (and in same order):
  ["Q10000900042"]
```

Restore `merges.resolve(stored)`. Re-run; green.

### 2.5 — Commit

```
git status --short
git add src/main/java/com/robsartin/segue/ratings/Promotions.java \
        src/test/java/com/robsartin/segue/ratings/PromotionsTest.java \
        src/test/java/com/robsartin/segue/ratings/InventedRatings.java
git commit
```

Message: `Derive the promotions off the known list (#285)`.

---

## Task 3 — `NamesFile`: the header, the names, the order, the fallback

### 3.1 RED — the header, against a stub

Create `src/main/java/com/robsartin/segue/ratings/NamesFile.java`:

```java
package com.robsartin.segue.ratings;

import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Map;

/** Stub: replaced in step 3.2. */
final class NamesFile {

  static final String PERSONAL_DATA_HEADER =
      "# segue promotions off your known list — personal data under ADR 33 and issue #37. Keep this"
          + " file outside the working tree and out of version control: this repository is public.";

  private NamesFile() {}

  static int write(List<String> promotions, Map<String, String> labels, Writer out)
      throws IOException {
    return 0;
  }
}
```

Create `src/test/java/com/robsartin/segue/ratings/NamesFileTest.java`:

```java
package com.robsartin.segue.ratings;

import static com.robsartin.segue.ratings.InventedRatings.CANONICAL;
import static com.robsartin.segue.ratings.InventedRatings.CANONICAL_LABEL;
import static com.robsartin.segue.ratings.InventedRatings.NOVEL;
import static com.robsartin.segue.ratings.InventedRatings.NOVEL_LABEL;
import static com.robsartin.segue.ratings.InventedRatings.QUARTET;
import static com.robsartin.segue.ratings.InventedRatings.QUARTET_LABEL;
import static com.robsartin.segue.ratings.InventedRatings.VANISHED;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What reaches the upload: names, one per line, under one header line that says what the file is.
 * Every label here is invented — see {@link InventedRatings}.
 */
class NamesFileTest {

  private static String written(List<String> promotions, Map<String, String> labels)
      throws IOException {
    StringWriter out = new StringWriter();
    NamesFile.write(promotions, labels, out);
    return out.toString();
  }

  @Test
  @DisplayName("the file says what it is and how many names it holds, on one line")
  void shouldSayItIsPersonalDataAndCountTheNamesWhenItWritesTheHeader() throws IOException {
    String file = written(List.of(QUARTET), Map.of(QUARTET, QUARTET_LABEL));

    assertThat(file.lines().findFirst())
        .as("a file copied, pasted or attached somewhere else still says what it is (ADR 43)")
        .contains(NamesFile.PERSONAL_DATA_HEADER + " 1 name(s), one per line.");
  }
}
```

Run the fast loop. **Observe** an assertion failure of the shape:

```
[a file copied, pasted or attached somewhere else still says what it is (ADR 43)]
Expecting Optional to contain:
  "# segue promotions off your known list — … 1 name(s), one per line."
but was empty.
```

### 3.2 GREEN — the whole writer

Replace `NamesFile` with:

```java
package com.robsartin.segue.ratings;

import java.io.IOException;
import java.io.Writer;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The promotions as a plain list of names, which is the shape the upload wants (#285).
 *
 * <p><b>It sorts as well as renders, for {@link RatingsTable}'s reason:</b> the header states what
 * the file is and how much of it there is, and a writer that announced an ordering somebody else
 * had applied could be made to lie by one refactor.
 *
 * <p><b>Labels and nothing else.</b> No qid column, no rating, no note. The upload wants names; a
 * second column would make this a different kind of file, and a note in it would be free text
 * leaving the machine (ADR 33 as amended by issue #85).
 *
 * <p><b>A promotion the graph cannot name is written as its qid</b>, not skipped and not written as
 * {@code AffinityRow.NO_LABEL}. Skipping it would drop something the owner said yes to out of the
 * file that exists to carry it, leaving a count as the only trace. {@code (not in the graph)} is a
 * phrase for a person reading a table; this file is pasted into a search box, so it would be
 * searched for as an artist. The qid identifies the row and is wrong in an obvious way rather than
 * a quiet one — ADR 43's "honest rather than helpful", reached from the same direction. {@link
 * #write} returns how many there were so the caller can say so in a log line; ADR 39 requires an
 * entity to be in the graph before it can be rated, so the number should be zero, and it is
 * reported rather than assumed.
 *
 * <p><b>Absent means null, exactly as {@link AffinityRow#displayLabel} decides it.</b> A label the
 * log claimed as null is the same "the graph cannot name this" as a qid the log never mentioned,
 * and the listing and this file must not disagree about which rows those are.
 *
 * <p><b>The one header line is a comment by convention only.</b> Nothing here knows whether the
 * uploader on the far side ignores a leading {@code #}; the runbook says to drop the line if it
 * comes back as an artist nobody has heard of. It stays because {@code *.txt} being gitignored is
 * the second lock and this is the third (ADR 43), and a file of names with no provenance is exactly
 * the one that gets attached to an issue.
 *
 * <p><b>{@code CensusIsSafeToPasteTest}'s discipline does not apply here and must not be added by
 * analogy.</b> That property exists because the census and the evaluation report are meant to be
 * pasted into a public issue. This file is personal data that goes to one private upload form and
 * nowhere else; "safe to paste" is not a property it has or wants.
 */
final class NamesFile {

  /** Said on the first line of every file this writes, followed by the count on the same line. */
  static final String PERSONAL_DATA_HEADER =
      "# segue promotions off your known list — personal data under ADR 33 and issue #37. Keep this"
          + " file outside the working tree and out of version control: this repository is public.";

  /** One line of the file, and the qid behind it so the ordering has a total tiebreak. */
  private record Named(String name, String qid, boolean fromTheGraph) {}

  private NamesFile() {}

  /**
   * Write the header and one name per line, sorted.
   *
   * @return how many of {@code promotions} the graph could not name, and so were written as their
   *     qid — the count the caller logs, since no log line may carry the name itself (ADR 33)
   */
  static int write(List<String> promotions, Map<String, String> labels, Writer out)
      throws IOException {
    Objects.requireNonNull(promotions, "promotions");
    Objects.requireNonNull(labels, "labels");
    Objects.requireNonNull(out, "out");

    // compareTo, not a Collator: SortOrder's comparators end in qid so that two runs over an
    // unchanged table produce byte-identical files, and the same argument applies to this file. A
    // case-insensitive order is not total on its own, so it would need the code-point comparison as
    // a second tiebreak anyway, and the audience for this file is an uploader rather than a reader.
    List<Named> named =
        promotions.stream()
            .map(qid -> nameOf(qid, labels))
            .sorted(Comparator.comparing(Named::name).thenComparing(Named::qid))
            .toList();

    out.write(PERSONAL_DATA_HEADER);
    out.write(" " + named.size() + " name(s), one per line.\n");
    for (Named one : named) {
      out.write(one.name());
      out.write("\n");
    }
    return (int) named.stream().filter(one -> !one.fromTheGraph()).count();
  }

  private static Named nameOf(String qid, Map<String, String> labels) {
    String label = labels.get(qid);
    return label == null ? new Named(qid, qid, false) : new Named(label, qid, true);
  }
}
```

Run the fast loop; green.

### 3.3 RED → GREEN — the four remaining behaviours

Add these one at a time. The first three are satisfied by 3.2 and will arrive green; **record that
honestly** rather than claiming a red. The fourth is the one with a control.

```java
  @Test
  @DisplayName("one name per line, and nothing else on the line")
  void shouldWriteOneNamePerLineWhenTheGraphNamesEveryPromotion() throws IOException {
    String file =
        written(
            List.of(QUARTET, NOVEL), Map.of(QUARTET, QUARTET_LABEL, NOVEL, NOVEL_LABEL));

    assertThat(file.lines().skip(1))
        .as("the upload wants names; a qid column would make this a different file")
        .containsExactly(NOVEL_LABEL, QUARTET_LABEL);
  }

  @Test
  @DisplayName("the names are sorted, so two runs over one table produce one file")
  void shouldSortByNameWhenMoreThanOnePromotionIsWritten() throws IOException {
    String file =
        written(
            List.of(QUARTET, CANONICAL), Map.of(QUARTET, QUARTET_LABEL, CANONICAL, CANONICAL_LABEL));

    assertThat(file.indexOf(QUARTET_LABEL))
        .as("\"The Invented Quartet\" sorts after \"The Name A Source Gave It\" by code point")
        .isGreaterThan(file.indexOf(CANONICAL_LABEL));
  }

  @Test
  @DisplayName("no promotion is still a readable file, not an empty one")
  void shouldWriteAHeaderAndNothingElseWhenNothingIsPromoted() throws IOException {
    assertThat(written(List.of(), Map.of()))
        .isEqualTo(NamesFile.PERSONAL_DATA_HEADER + " 0 name(s), one per line.\n");
  }

  @Test
  @DisplayName("a promotion the graph cannot name is written as its qid and counted, never dropped")
  void shouldWriteTheQidWhenTheGraphCannotNameAPromotion() throws IOException {
    StringWriter out = new StringWriter();

    int unnamed = NamesFile.write(List.of(QUARTET, VANISHED), Map.of(QUARTET, QUARTET_LABEL), out);

    assertThat(out.toString().lines().skip(1))
        .as("dropping it would lose something the owner said yes to, leaving only a count")
        .containsExactly(QUARTET_LABEL, VANISHED);
    assertThat(unnamed).isEqualTo(1);
  }
```

Plant a control for the fallback: in `nameOf`, change `label == null ? new Named(qid, qid, false)` to
return `null` for the whole entry and filter nulls out — i.e. make the writer *skip* an unnamed
promotion. Run the fast loop. **Observe**
`shouldWriteTheQidWhenTheGraphCannotNameAPromotion` fail with:

```
[dropping it would lose something the owner said yes to, leaving only a count]
Expecting actual:
  ["The Invented Quartet"]
to contain exactly (and in same order):
  ["The Invented Quartet", "Q0900003"]
```

Restore. Re-run; green.

### 3.4 — Commit

```
git status --short
git add src/main/java/com/robsartin/segue/ratings/NamesFile.java \
        src/test/java/com/robsartin/segue/ratings/NamesFileTest.java
git commit
```

Message: `Write the promotions as a list of names (#285)`.

---

## Task 4 — The command line: two flags, three refusals, and the guard the type change forces

### 4.1 — `Options` grows the pair, and the listing write is guarded

A Mikado step: the type change and every break it causes land together, green.

In `src/main/java/com/robsartin/segue/ratings/RatingsCli.java`:

```java
  /**
   * Where the listing goes, in what order, and — since #285 — which promotions to write as names.
   *
   * @param database the taste layer and the log to read - the same file, per ADR 33's rejection of
   *     a second database. Defaults per {@link DefaultDatabase#resolve} - one rule shared with
   *     {@code ExportCli}, {@code RateCli} and {@code RecommendCli} (issue #179) - rather than a
   *     copy of it stated here.
   * @param out no default, on purpose - see this class's Javadoc. Null when only the names export
   *     was asked for, which is the one case that does not write a listing
   * @param promotionsOff the {@code --known} file to measure the promotions against, read the way
   *     {@code recommend} and {@code evaluate} read it ({@code QidList}); null with {@code names}
   * @param names where the promotions off that file go, one name per line; null with {@code
   *     promotionsOff}
   */
  public record Options(Path database, Path out, SortOrder sort, Path promotionsOff, Path names) {

    public Options {
      Objects.requireNonNull(database, "database");
      Objects.requireNonNull(sort, "sort");
      // The pair is one output and the record cannot hold half of it: parse() produces the
      // readable message, and this is what stops a caller assembling a state the run has no
      // behaviour for.
      if ((promotionsOff == null) != (names == null)) {
        throw new IllegalArgumentException("--promotions-off and --names are given together");
      }
      if (out == null && names == null) {
        throw new IllegalArgumentException("nothing to write: give --out, --names, or both");
      }
    }
  }
```

Update the one construction in `parse` to `new Options(DefaultDatabase.resolve(db, envDatabase,
userHome), out, sort, null, null)` for now, and update `RatingsRunTest`'s helper:

```java
  private List<AffinityRow> run(FakeAffinityStore ratings, FakeAssertionLog log, SortOrder sort)
      throws IOException {
    return new RatingsRun(ratings, log)
        .run(new Options(dir.resolve("segue.db"), out, sort, null, null), this::note);
  }
```

In `RatingsRun.run`, wrap the listing's notes and write — `out` can now be null, and dereferencing it
would be an NPE:

```java
    if (options.out() != null) {
      notes.accept(rows.size() + " rating(s), sorted by " + options.sort().describe());
      long unlabelled = rows.stream().filter(row -> row.label() == null).count();
      if (unlabelled > 0) {
        notes.accept(
            unlabelled
                + " rating(s) name an entity the graph has no claim about, and are listed as \""
                + AffinityRow.NO_LABEL
                + "\" — a rating outlives the graph it was made against");
      }
      try (Writer out = Files.newBufferedWriter(options.out(), StandardCharsets.UTF_8)) {
        RatingsTable.write(rows, options.sort(), out);
      }
      notes.accept("wrote " + options.out());
    }
```

In `RatingsCli.main`, the failure message can no longer assume `--out`:

```java
    } catch (IOException e) {
      throw new UncheckedIOException("could not write " + outputs(options), e);
    }
```

with, beside `note`:

```java
  /** Every path this run was asked to write, for the one message that can name none of them. */
  private static String outputs(Options options) {
    return Stream.of(options.out(), options.names())
        .filter(Objects::nonNull)
        .map(Path::toString)
        .collect(Collectors.joining(" and "));
  }
```

Add the imports `java.util.stream.Collectors` and `java.util.stream.Stream`.

**Honest exception:** the `outputs` message is not unit-tested. Reaching it needs a real `Writer`
failure against a real database, which this suite has no seam for; the behaviour it replaced was
untested for the same reason. Say so in the report rather than letting it pass unremarked.

Run the fast loop; green. The guard added here gets its positive control in step 5.5, where the test
that needs it exists.

### 4.2 RED — the two refusals

Add to `src/test/java/com/robsartin/segue/ratings/RatingsCliTest.java`:

```java
  private static final String KNOWN = "/invented/known.csv";
  private static final String NAMES = "/invented/promotions.txt";

  @Test
  @DisplayName("--names alone is refused: there is nothing to measure the promotions against")
  void shouldRefuseWhenTheNamesPathIsGivenWithoutTheKnownFile() {
    assertThatThrownBy(() -> parse("--out", "/tmp/ratings.txt", "--names", NAMES))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--promotions-off");
  }

  @Test
  @DisplayName("--promotions-off alone is refused: there is nowhere for the names to go")
  void shouldRefuseWhenTheKnownFileIsGivenWithoutANamesPath() {
    assertThatThrownBy(() -> parse("--out", "/tmp/ratings.txt", "--promotions-off", KNOWN))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--names");
  }
```

Run the fast loop. **Observe** both fail on the message, not on the type — of the shape:

```
Expecting throwable message:
  "unknown option --names. usage: --out <file> …"
to contain:
  "--promotions-off"
```

### 4.3 GREEN — the flags and the pairing checks

In `RatingsCli`:

```java
  private static final String USAGE =
      "usage: --out <file> [--sort <"
          + SortOrder.names()
          + ">, default rating] [--db <segue.db>]"
          + " [--promotions-off <known.csv> --names <file>]";
```

and in `parse`, add the two locals, the two switch arms, and the three checks:

```java
    String db = null;
    Path out = null;
    Path promotionsOff = null;
    Path names = null;
    SortOrder sort = SortOrder.RATING;

    for (int i = 0; i < args.length; i++) {
      String flag = args[i];
      String value = valueOf(args, i, flag);
      i++;
      switch (flag) {
        case "--db" -> db = value;
        case "--out" -> out = Path.of(value);
        case "--sort" -> sort = SortOrder.parse(value);
        case "--promotions-off" -> promotionsOff = Path.of(value);
        case "--names" -> names = Path.of(value);
        default -> throw usage("unknown option " + flag);
      }
    }

    // One output in two flags, so half of it is a usage error rather than a silent no-op.
    if (promotionsOff != null && names == null) {
      throw usage("--promotions-off needs --names <file> to write to");
    }
    if (names != null && promotionsOff == null) {
      throw usage("--names needs --promotions-off <known.csv> to measure against");
    }
    // --out stays required when it is the only output there is: a listing must never go to a path
    // nobody chose (ADR 43). The names export names its own path, so it satisfies the same rule.
    if (out == null && names == null) {
      throw usage("--out is required");
    }
    return new Options(
        DefaultDatabase.resolve(db, envDatabase, userHome), out, sort, promotionsOff, names);
```

Also extend the class javadoc with a paragraph on the second output:

```java
 * <p><b>A second output, and the same discipline (#285).</b> {@code --promotions-off <known.csv>
 * --names <out.txt>} writes the entities rated at or above {@code KnownList.PROMOTION_RATING} that
 * the known-list file does not name — the promotions ADR 48 defines and the population {@code
 * evaluate} holds out — as one label per line, for upload to Setlist Scout. The set is derived
 * through {@code KnownList.promoted} so that this tool, {@code recommend} and {@code rate} cannot
 * disagree about who is promoted. The file carries labels and nothing else: the upload wants names,
 * and a qid column would make it something else. Like {@code --out} it has no default, it names
 * itself as personal data on its first line, and the log lines about it are counts. It is never
 * pasted anywhere, so {@code CensusIsSafeToPasteTest}'s property is not one it has or wants.
```

Run the fast loop. **Observe** the two refusals green.

### 4.4 RED → GREEN — `--out` becomes optional

```java
  @Test
  @DisplayName("--out is not required when the names export names its own path")
  void shouldNotRequireAnOutPathWhenTheNamesExportIsAsked() {
    Options options = parse("--promotions-off", KNOWN, "--names", NAMES);

    assertThat(options.out()).isNull();
    assertThat(options.promotionsOff()).isEqualTo(Path.of(KNOWN));
    assertThat(options.names()).isEqualTo(Path.of(NAMES));
  }

  @Test
  @DisplayName("both outputs together is one run, not two")
  void shouldParseBothOutputsWhenTheyAreGivenTogether() {
    Options options =
        parse("--out", "/tmp/ratings.txt", "--promotions-off", KNOWN, "--names", NAMES);

    assertThat(options.out()).isEqualTo(Path.of("/tmp/ratings.txt"));
    assertThat(options.names()).isEqualTo(Path.of(NAMES));
  }
```

If you followed 4.3 exactly these are green on arrival; run them and say so. Then plant the control
that proves the relaxation is real and not vacuous: change the last check to `if (out == null)` —
the rule as it stood before this issue — and run the fast loop. **Observe**
`shouldNotRequireAnOutPathWhenTheNamesExportIsAsked` fail with:

```
java.lang.IllegalArgumentException: --out is required. usage: --out <file> …
```

thrown at the `parse(...)` call in the test — the tool refusing the command this issue exists to
add. Restore `out == null && names == null`. Re-run; green.

### 4.5 — Commit

```
git status --short
git add src/main/java/com/robsartin/segue/ratings/RatingsCli.java \
        src/main/java/com/robsartin/segue/ratings/RatingsRun.java \
        src/test/java/com/robsartin/segue/ratings/RatingsCliTest.java \
        src/test/java/com/robsartin/segue/ratings/RatingsRunTest.java
git commit
```

Message: `Accept --promotions-off and --names on listRatings (#285)`.

---

## Task 5 — The run: read the known file, derive, label, write, count

### 5.0 — The helper the new tests use

In `RatingsRunTest`, beside the existing `run`:

```java
  private List<AffinityRow> runNames(
      FakeAffinityStore ratings, FakeAssertionLog log, Path known, Path names, Path listing)
      throws IOException {
    return new RatingsRun(ratings, log)
        .run(
            new Options(dir.resolve("segue.db"), listing, SortOrder.RATING, known, names),
            this::note);
  }
```

Add the imports `com.robsartin.segue.ratings.InventedRatings.knownFile` (static),
`InventedRatings.SEEN_LIVE`, `InventedRatings.SEEN_LIVE_LABEL`, and `java.util.Map` as the steps
below need them.

### 5.1 RED — the file exists

```java
  @Test
  @DisplayName("the names file is written when the known file and a names path are both given")
  void shouldWriteTheNamesFileWhenBothFlagsAreGiven() throws IOException {
    Path names = dir.resolve("promotions.txt");

    runNames(
        new FakeAffinityStore().rated(QUARTET, 5, null, EARLY),
        new FakeAssertionLog().with(node(QUARTET, QUARTET_LABEL)),
        knownFile(dir, SEEN_LIVE),
        names,
        out);

    assertThat(names).exists();
  }
```

Run the fast loop. **Observe**:

```
Expecting actual:
  /…/promotions.txt
to exist.
```

### 5.2 GREEN — the whole names path

In `RatingsRun`, add the imports `com.robsartin.segue.domain.Equivalences`,
`com.robsartin.segue.support.QidList`, `java.util.LinkedHashMap`, `java.util.LinkedHashSet` and
`java.util.Set`, and rewrite `run` so it reads:

```java
    notes.accept(PERSONAL_DATA_WARNING);

    // Read before anything is written. QidList refuses a missing file and a file with no QID in it,
    // and those refusals must arrive before a file lands on disk rather than between two writes.
    List<String> fromFile =
        options.promotionsOff() == null ? List.of() : QidList.read(options.promotionsOff());

    List<AffinityRecord> recorded = ratings.readAll();
    List<LoggedAssertion> logged = recorded.isEmpty() ? List.of() : log.readAll();

    Set<String> rated =
        recorded.stream().map(AffinityRecord::qid).collect(Collectors.toCollection(
            LinkedHashSet::new));
    List<String> promotions =
        options.names() == null
            ? List.of()
            : Promotions.offTheKnownList(scores(recorded), Equivalences.in(logged), fromFile);

    // The union, and the reason is a promotion this listing has no row for: where a local id was
    // rated and merged onto a canonical id with no row of its own, Equivalences.resolve moves the
    // rating there and THAT is the promotion. Asking only for the stored qids would leave it
    // unlabelled and write a bare qid for an entity the graph can name perfectly well — invisible,
    // because a bare qid is also the honest fallback for an entity the graph really cannot name.
    Set<String> wanted = new LinkedHashSet<>(rated);
    wanted.addAll(promotions);
    Map<String, String> labels = Labels.forQids(logged, wanted);

    List<AffinityRow> rows =
        recorded.stream()
            .map(
                rating ->
                    new AffinityRow(
                        rating.qid(),
                        labels.get(rating.qid()),
                        rating.rating(),
                        rating.note(),
                        rating.updatedAt()))
            .toList();

    if (options.out() != null) {
      // … unchanged from step 4.1 …
    }

    if (options.names() != null) {
      int unnamed;
      try (Writer names = Files.newBufferedWriter(options.names(), StandardCharsets.UTF_8)) {
        unnamed = NamesFile.write(promotions, labels, names);
      }
      notes.accept(promotions.size() + " promotion(s) off the known list, written as name(s)");
      if (unnamed > 0) {
        notes.accept(
            unnamed
                + " of them name an entity the graph has no claim about, and are written as their"
                + " qid instead — a rating outlives the graph it was made against");
      }
      notes.accept("wrote " + options.names());
    }
    return rows;
```

with, at the bottom of the class:

```java
  /**
   * The score column of the rows just read, in the table's own order.
   *
   * <p>Built from the rows this run already holds rather than by calling {@code
   * AffinityStore.readRatings}: that is the note-free bulk read the recommender uses (issue #85),
   * and making a second query for a column already in memory would be a second read of one table.
   * Insertion order is kept because {@code Equivalences.resolve} resolves two rated local ids onto
   * one canonical id by log order, and a run that shuffled the input would shuffle that answer.
   */
  private static Map<String, Integer> scores(List<AffinityRecord> recorded) {
    Map<String, Integer> scores = new LinkedHashMap<>();
    for (AffinityRecord rating : recorded) {
      scores.put(rating.qid(), rating.rating());
    }
    return scores;
  }
```

Extend the class javadoc with:

```java
 * <p><b>A second output, and it is the same read (#285).</b> {@code --promotions-off} plus {@code
 * --names} writes the promotions the known-list file does not name, derived through {@code
 * KnownList.promoted} so this tool cannot disagree with {@code recommend} and {@code rate} about
 * who is promoted (ADR 48), and resolved through the merges first for the reason {@code
 * RecommendCli} resolves before it composes. Both outputs come out of one {@code readAll} of the
 * affinity table and one {@code readAll} of the log.
```

Run the fast loop; green.

### 5.3 RED → GREEN — the right promotions reach the file

```java
  @Test
  @DisplayName("a promotion is written and an entity already on the known file is not")
  void shouldWriteOnlyThePromotionsWhenTheKnownFileNamesSomeOfWhatIsRated() throws IOException {
    Path names = dir.resolve("promotions.txt");

    runNames(
        new FakeAffinityStore()
            .rated(QUARTET, 5, QUARTET_NOTE, EARLY)
            .rated(SEEN_LIVE, 5, null, EARLY)
            .rated(NOVEL, 3, NOVEL_NOTE, LATE),
        new FakeAssertionLog()
            .with(
                node(QUARTET, QUARTET_LABEL),
                node(SEEN_LIVE, SEEN_LIVE_LABEL),
                node(NOVEL, NOVEL_LABEL)),
        knownFile(dir, SEEN_LIVE),
        names,
        out);

    assertThat(Files.readString(names).lines().skip(1))
        .as("the file's own entities are already known; a 3 is not a promotion (ADR 48)")
        .containsExactly(QUARTET_LABEL);
  }
```

Green on arrival after 5.2 — run it and say so. Then plant the control that proves it is not
vacuous: in `RatingsRun`, replace `fromFile` in the `Promotions.offTheKnownList(...)` call with
`List.of()`. Run. **Observe**:

```
[the file's own entities are already known; a 3 is not a promotion (ADR 48)]
Expecting actual:
  ["An Ensemble Nobody Booked", "The Invented Quartet"]
to contain exactly (and in same order):
  ["The Invented Quartet"]
```

Restore `fromFile`. Re-run; green.

### 5.4 RED — the merged promotion gets its name

```java
  @Test
  @DisplayName("a promotion the rating was merged onto is named, not written as a bare qid")
  void shouldNameACanonicalPromotionWhenTheRatingWasMergedOntoAnIdWithNoRowOfItsOwn()
      throws IOException {
    Path names = dir.resolve("promotions.txt");

    runNames(
        new FakeAffinityStore().rated(MINTED, 5, null, EARLY),
        new FakeAssertionLog()
            .with(
                minted(MINTED, MINTED_LABEL),
                node(CANONICAL, CANONICAL_LABEL),
                merged(MINTED, CANONICAL)),
        knownFile(dir, SEEN_LIVE),
        names,
        out);

    assertThat(Files.readString(names).lines().skip(1))
        .as("the promotion is the canonical id, which has no affinity row and so no label lookup")
        .containsExactly(CANONICAL_LABEL);
  }
```

To see the red, **first** replace `Labels.forQids(logged, wanted)` with `Labels.forQids(logged,
rated)` — which is the code as it would have been written without the union — and run. **Observe**:

```
[the promotion is the canonical id, which has no affinity row and so no label lookup]
Expecting actual:
  ["Q10000900042"]
to contain exactly (and in same order):
  ["The Name A Source Gave It"]
```

Restore `wanted`. Re-run; green. That sequence is both the red and the positive control for the
union, and the report quotes it.

### 5.5 RED → GREEN — the unnamed count, the listing guard, and the notes

```java
  @Test
  @DisplayName("a promotion the graph cannot name is counted, and the count says why")
  void shouldCountThePromotionsWhenTheGraphCannotNameThem() throws IOException {
    runNames(
        new FakeAffinityStore().rated(VANISHED, 5, null, EARLY),
        new FakeAssertionLog(),
        knownFile(dir, SEEN_LIVE),
        dir.resolve("promotions.txt"),
        out);

    assertThat(notes).anyMatch(line -> line.startsWith("1 of them name an entity"));
  }

  @Test
  @DisplayName("no note about the names file carries a label, a note or a qid")
  void shouldReportCountsAndNothingPersonalWhenItWritesTheNamesFile() throws IOException {
    runNames(
        new FakeAffinityStore()
            .rated(QUARTET, 5, QUARTET_NOTE, EARLY)
            .rated(VANISHED, 5, null, LATE),
        new FakeAssertionLog().with(node(QUARTET, QUARTET_LABEL)),
        knownFile(dir, SEEN_LIVE),
        dir.resolve("promotions.txt"),
        out);

    assertThat(notes)
        .noneMatch(line -> line.contains(QUARTET_LABEL))
        .noneMatch(line -> line.contains(QUARTET_NOTE))
        .noneMatch(line -> line.contains(QUARTET))
        .noneMatch(line -> line.contains(VANISHED));
  }

  @Test
  @DisplayName("no listing is written when only the names export was asked for")
  void shouldNotWriteTheListingWhenOnlyTheNamesExportIsAsked() throws IOException {
    runNames(
        new FakeAffinityStore().rated(QUARTET, 5, null, EARLY),
        new FakeAssertionLog().with(node(QUARTET, QUARTET_LABEL)),
        knownFile(dir, SEEN_LIVE),
        dir.resolve("promotions.txt"),
        null);

    assertThat(out).doesNotExist();
  }

  @Test
  @DisplayName("both outputs are written when both were asked for")
  void shouldWriteBothFilesWhenBothOutputsAreAsked() throws IOException {
    Path names = dir.resolve("promotions.txt");

    runNames(
        new FakeAffinityStore().rated(QUARTET, 5, QUARTET_NOTE, EARLY),
        new FakeAssertionLog().with(node(QUARTET, QUARTET_LABEL)),
        knownFile(dir, SEEN_LIVE),
        names,
        out);

    assertThat(Files.readString(out)).contains(QUARTET_NOTE);
    assertThat(Files.readString(names)).contains(QUARTET_LABEL).doesNotContain(QUARTET_NOTE);
  }
```

The first is red before the `if (unnamed > 0)` note exists — if you wrote 5.2 exactly, remove that
block, run, **observe**:

```
Expecting any elements of:
  ["this listing is your whole taste layer: …", "1 rating(s), sorted by rating, highest first", …]
to match given predicate but none did.
```

Restore it. The other three arrive green; run them and say so.

Then the **positive control for the listing guard added in 4.1**: remove `if (options.out() !=
null) {` and its closing brace, so the listing block runs unconditionally. Run the fast loop.
**Observe** `shouldNotWriteTheListingWhenOnlyTheNamesExportIsAsked` fail with a
`NullPointerException` out of `Files.newBufferedWriter(null, …)` — the tool trying to write a
listing to a path nobody gave. Restore the guard. Re-run; green.

### 5.6 RED → GREEN — the names path reaches no log line either

Add to `src/test/java/com/robsartin/segue/ratings/RatingsAreNeverLoggedTest.java`:

```java
  @Test
  @DisplayName("the names export reaches the file; not one name of it reaches a log")
  void shouldKeepEveryNameOutOfTheLogWhenTheNamesExportRuns() throws IOException {
    Path db = dir.resolve("scratch.db");
    Path names = dir.resolve("promotions.txt");
    Path known = InventedRatings.knownFile(dir, InventedRatings.SEEN_LIVE);
    try (SqliteAssertionLog log = new SqliteAssertionLog(db);
        SqliteAffinityStore affinity = new SqliteAffinityStore(db)) {
      log.append(node(QUARTET, QUARTET_LABEL));
      affinity.put(new AffinityRecord(QUARTET, 5, QUARTET_NOTE, EARLY));
    }
    captured.list.clear();

    RatingsCli.main(
        new String[] {
          "--db", db.toString(),
          "--promotions-off", known.toString(),
          "--names", names.toString()
        });

    assertThat(Files.readString(names)).contains(QUARTET_LABEL);

    List<String> everyLine =
        List.copyOf(captured.list).stream().map(ILoggingEvent::getFormattedMessage).toList();
    assertThat(everyLine)
        .as("a promotion's label is a rating attributed to an entity, and ADR 33 keeps it out")
        .noneMatch(line -> line.contains(QUARTET_LABEL));
    assertThat(everyLine)
        .as("no log line names the entity, so no line can attribute a promotion to one (ADR 33)")
        .noneMatch(line -> line.contains(QUARTET));
  }
```

Extend that class's javadoc with one sentence saying the second output is held to the same rule.

Run. It is green on arrival — say so — and then **plant the control** where the leak would actually
be written, in `RatingsRun`'s names block: change the count note to

```java
      notes.accept(promotions.size() + " promotion(s) off the known list: " + promotions);
```

Run. **Observe**:

```
[no log line names the entity, so no line can attribute a promotion to one (ADR 33)]
Expecting no elements of:
  [… "1 promotion(s) off the known list: [Q0900001]" …]
to match the given predicate but this element did: …
```

Restore the count-only note. Re-run; green.

### 5.7 — Commit

```
git status --short
git add src/main/java/com/robsartin/segue/ratings/RatingsRun.java \
        src/test/java/com/robsartin/segue/ratings/RatingsRunTest.java \
        src/test/java/com/robsartin/segue/ratings/RatingsAreNeverLoggedTest.java
git commit
```

Message: `Write the promotions off the known list from listRatings (#285)`.

---

## Task 6 — The runbook paragraph, pinned

`listRatings` is the only dev tool whose guide examples nothing runs through its own parser — the
census, retraction, own-claim and evaluate runbooks each have one. This adds the missing test first,
so the new example is pinned by the same mechanism as the rest.

### 6.1 RED — the test, before the paragraph

Create `src/test/java/com/robsartin/segue/ratings/DeveloperGuideRatingsExamplesTest.java`:

```java
package com.robsartin.segue.ratings;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.arch.GuideExamples;
import com.robsartin.segue.arch.GuideExamples.Example;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * "Looking at what you have rated" shows commands the owner is meant to paste, and this runs every
 * one of them through {@link RatingsCli#parse} — the runbook this check was missing, added with the
 * names export it has to cover (#285, on #183's pattern).
 *
 * <p><b>Why {@code parse} and not the tool.</b> Running an example end to end would need a
 * database, and the only one on this machine is the owner's. What a runbook has to get right is the
 * command line, and {@code parse} is what enforces it before any file is opened.
 *
 * <p>In {@code ratings} rather than beside the document tests in {@code arch}, because {@link
 * RatingsCli#parse} is package-private, exactly as every sibling's is.
 */
class DeveloperGuideRatingsExamplesTest {

  private static final GuideExamples RUNBOOK = GuideExamples.of("listRatings");

  @Test
  @DisplayName("the guide shows the names export, not only the listing")
  void shouldShowANamesExportExampleWhenTheGuideDocumentsTheRatingsTool() {
    assertThat(RUNBOOK.examples())
        .as(
            "docs/developer-guide.md, 'Looking at what you have rated' — one ./gradlew listRatings"
                + " --args=\"…\" line carrying --promotions-off and --names. Without it the owner"
                + " has no pasteable command for the output issue #285 exists to produce")
        .anyMatch(
            example ->
                example.arguments().contains("--promotions-off")
                    && example.arguments().contains("--names"));
  }

  @Test
  @DisplayName("no listRatings example writes a tilde where $HOME belongs")
  void shouldWriteHomeRatherThanATildeWhenAnExampleNamesAPath() {
    assertThat(RUNBOOK.withATilde())
        .as(
            "docs/developer-guide.md — a tilde does not expand inside the double quotes of"
                + " --args=\"…\", so the example arrives at the tool as a literal ~ and dies with"
                + " \"no segue database at ~/.segue/segue.db\". RatingsCli.parse cannot see this,"
                + " because a tilde is a valid path character")
        .isEmpty();
  }

  @Test
  @DisplayName("every line naming listRatings is read as a command, or is prose with no --args")
  void shouldNameTheLineWhenAnExampleCannotBeRead() {
    assertThat(RUNBOOK.unreadableExamples())
        .as(
            "docs/developer-guide.md — a line naming listRatings that this test cannot read is a"
                + " line nothing checks, and skipping it silently is the hole this assertion exists"
                + " to close. A line with no --args at all is prose and is allowed")
        .isEmpty();
  }

  @Test
  @DisplayName("every listRatings example parses through the tool's own parser")
  void shouldParseEveryExampleWhenTheGuideShowsACommand() {
    List<String> refused = new ArrayList<>();
    for (Example example : RUNBOOK.examples()) {
      try {
        RatingsCli.parse(
            example.arguments().toArray(String[]::new), null, GuideExamples.INVENTED_HOME);
      } catch (RuntimeException refusal) {
        refused.add(
            "line " + example.line() + ": " + example.text() + "\n    " + refusal.getMessage());
      }
    }

    assertThat(refused)
        .as(
            "docs/developer-guide.md — every listRatings example is run through RatingsCli.parse,"
                + " the boundary that decides whether a line is correct to type. The flag pairing"
                + " is enforced there, so an example giving --names without --promotions-off fails"
                + " here")
        .isEmpty();
  }
}
```

Run the fast loop. **Observe** the first test fail with an assertion of the shape:

```
[docs/developer-guide.md, 'Looking at what you have rated' — one ./gradlew listRatings …]
Expecting any elements of:
  [Example[line=1629, …], Example[line=1632, …]]
to match given predicate but none did.
```

The other three pass, and not vacuously — `RUNBOOK.examples()` already holds the guide's two
existing lines, which they now check for the first time. Record that.

### 6.2 GREEN — the paragraph

In `docs/developer-guide.md`, in the chapter `## Looking at what you have rated`, after the sentence
ending "…but the graph around a rating can be rebuilt and the rating has to outlive it.", insert
the block below. It is quoted here inside a four-backtick fence so that the shell block it contains
survives; what goes into the guide starts at `### ` and ends at "artists page.".

````markdown
### The promotions Setlist Scout does not track yet

The known-list file was produced from a concert history, so it means "acts I have seen live".
Everything rated at or above `KnownList.PROMOTION_RATING` that the file does not name is a
**promotion** — [ADR 48](adr/0048-a-high-rating-counts-as-something-you-have.md) — and those are
acts you would go and see that nothing else of yours tracks. Setlist Scout takes a plain-text
upload of artist names, one per line, so this writes that file:

```bash
# the promotions your known list does not name, as names to upload
./gradlew listRatings --args="--promotions-off $HOME/filtered-qids.csv --names $HOME/promotions.txt"
```

`--promotions-off` and `--names` are one output and are given together; either alone is a usage
error. `--out` is optional alongside them and unchanged when it is given, so one run can write both
files. The promotion set is composed by `KnownList.promoted` and resolved through your merges first,
which is what stops this tool disagreeing with `recommend` and `rate` about who is promoted.

One name per line, the label the graph holds, sorted. A promotion the graph has no claim about is
written as its **qid** rather than dropped: losing something you said yes to out of the file that
exists to carry it would leave a count as the only trace. The log says how many there were.

The first line is a `#` comment naming the file as personal data and counting the names. Whether the
uploader on the far side ignores it is not known here — drop that line before you paste if it comes
back as an artist nobody has heard of. Then upload it on Setlist Scout's artists page.
````

Run the fast loop; green. Then plant the control on the parse check: change the example's
`--promotions-off` to `--promotions-of`. Run. **Observe**
`shouldParseEveryExampleWhenTheGuideShowsACommand` fail naming the line and
`unknown option --promotions-of. usage: …`. Restore the spelling; re-run; green.

Also run the documentation gate, blocking:

```
./gradlew test --tests 'com.robsartin.segue.arch.DocumentationLinksTest'
```

The two ADR links in the new paragraph must resolve.

### 6.3 — Commit

```
git status --short
git add docs/developer-guide.md \
        src/test/java/com/robsartin/segue/ratings/DeveloperGuideRatingsExamplesTest.java
git commit
```

Message: `Document the names export beside listRatings (#285)`.

---

## Task 7 — The gate, and the fence's positive control

### 7.1 — Format

```
./gradlew spotlessApply
git diff --stat
```

If anything moved, commit it with the task it belongs to or as part of 7.4.

### 7.2 — Positive control for `theRatingsToolOnlyReads`

The fence is unchanged, and this issue grew the tool it fences. Prove it still covers what it
claims rather than asserting it does. In `RatingsRun.run`, immediately after
`List<AffinityRecord> recorded = ratings.readAll();`, plant:

```java
    ratings.updateRating(recorded.get(0).qid(), 1, recorded.get(0).updatedAt());
```

Run, blocking:

```
./gradlew test --tests 'com.robsartin.segue.arch.ArchitectureTest'
```

**Observe** `theRatingsToolOnlyReads` fail, naming `RatingsRun` and
`AffinityStore.updateRating`, with the rule's `because` text about ADR 43. Remove the plant. Re-run;
green. Quote both in the report.

### 7.3 — The full gate

Blocking, no backgrounding:

```
SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks
```

Everything must pass, jacoco's line and branch thresholds included. If coverage of `NamesFile` or
`Promotions` falls short, the missing case is a behaviour nobody described — add the test, do not
loosen the threshold.

Watch in particular:

- `ArchitectureTest` — all four `ratings` rules, plus `noPackageCycles`.
- `PackageListsTest` — no new package was added, so it should be untouched.
- `JavadocCitationsTest` — every test named in a new javadoc must exist. The new javadocs name
  `CensusIsSafeToPasteTest`, `KnownList.revisitable`, `RecommendCli`, `EvaluateCli` and
  `AffinityRow.displayLabel`; the first is a test class, the rest are main-source and are skipped.
- `DocumentationLinksTest` and `AdrIndexTest` — the guide edit.
- `tasks.javadoc` — it gates `check`.

### 7.4 — Commit anything the gate moved, and stop

```
git status --short
git add <explicit paths only>
git commit
```

Message: `Keep the gate green for the names export (#285)`.

Do **not** push and do **not** open a PR. Report: every red observed and its message, every planted
control and what it said, the gate's result, and the two places where a test arrived green rather
than red (5.3's promotion filter and 5.6's log check, both of which then had a control planted).
