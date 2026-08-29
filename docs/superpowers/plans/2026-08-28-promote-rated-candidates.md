# Promote Rated Candidates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An entity rated 4 or 5 counts as something the owner has, whether or not it is on the known-list file — so 87 real interests stop being invisible to the recommender.

**Architecture:** One pure union function in `domain`, called by the two tools that take `--known`. No new package, no schema change, no new store method — both tools already read the file and the ratings.

**Tech Stack:** Java 21 (release 21, toolchain 25), Gradle Kotlin DSL, JUnit 5 + AssertJ, ArchUnit.

## Global Constraints

- **Issue #106.** Branch `106-promote-rated-candidates`. Read the issue — its measurement comments are the argument.
- **Never open `~/.segue/segue.db`.** Copy it and point `--db` at the copy; report the real file's mtime unchanged. It holds 973 irreplaceable personal ratings.
- Stage commits **by explicit path**. NEVER `git add -A` — an untracked `mad.vcf` must never be staged.
- `./gradlew check` green before every commit; run long commands **blocking**.
- `domain` has **no third-party dependencies**. Keep it that way.
- No rating value in any log line (ADR 33). Counts and paths only.
- TDD: failing test first, run it, watch it fail for the right reason, report what it said.

## Why, and the measurement behind it

The known-list came from a concert history, so it means *"acts I have seen live"* — not *"things I have"*, which is what `--known` is for. The gap shows up directly in the data: of 167 rated entities not on the file, **87 are rated 4 or 5** — bands and performers plainly liked but never attended. The recommender has been treating those as strangers, and can still recommend them back to the owner.

The distribution also decides the shape: `1×(1) 1×(2) 78×(3) 51×(4) 36×(5)`. **Promotion has 87 data points; suppression has two.** So this plan implements promotion only, and the ADR must record that suppression was deliberately not built rather than forgotten — with the count as the reason.

---

### Task 1: The union, and the two callers

**Files:**
- Create: `src/main/java/com/robsartin/segue/domain/KnownList.java`
- Modify: `src/main/java/com/robsartin/segue/recommend/RecommendRun.java`, `src/main/java/com/robsartin/segue/rate/RateCli.java`
- Test: `src/test/java/com/robsartin/segue/domain/KnownListTest.java`, plus the existing tests of both callers

**Interfaces:**
- Produces: `KnownList.promoted(List<String> fromFile, Map<String, Integer> ratings) → List<String>` and `KnownList.PROMOTION_RATING = 4`.

- [ ] **Step 1: Write the failing test**

Create `KnownListTest`. Cover, at minimum:

```java
  @Test
  @DisplayName("an entity rated at or above the threshold joins the list")
  void promotesAHighRating() {
    assertThat(KnownList.promoted(List.of("Q900001"), Map.of("Q900002", 5)))
        .containsExactlyInAnyOrder("Q900001", "Q900002");
  }

  @Test
  @DisplayName("a rating below the threshold does not join, and 3 is below it")
  void leavesTheRestAlone() {
    assertThat(KnownList.promoted(List.of("Q900001"), Map.of("Q900002", 3, "Q900003", 1)))
        .containsExactly("Q900001");
  }

  @Test
  @DisplayName("an entity already on the file is not duplicated by its own rating")
  void doesNotDuplicate() {
    assertThat(KnownList.promoted(List.of("Q900001"), Map.of("Q900001", 5)))
        .containsExactly("Q900001");
  }

  @Test
  @DisplayName("the file's order is preserved and promotions follow, so two runs agree")
  void isDeterministic() {
    List<String> first = KnownList.promoted(List.of("Q900002", "Q900001"), Map.of("Q900003", 5, "Q900004", 4));
    List<String> second = KnownList.promoted(List.of("Q900002", "Q900001"), Map.of("Q900004", 4, "Q900003", 5));

    assertThat(first).startsWith("Q900002", "Q900001");
    assertThat(first).isEqualTo(second);
  }
```

That last one matters: `Map` iteration order is not guaranteed, and `recommend`'s output is meant to be diffable between runs (ADR 45 makes the same argument for its tiebreak). Sort the promoted portion.

- [ ] **Step 2: Run and watch it fail.** Record the compile error.

- [ ] **Step 3: Write `KnownList`**

A final class with a private constructor, in `domain`, no third-party imports. Javadoc must carry the argument, not just the mechanism:

```java
/**
 * What the owner has, which is the file plus what they have rated highly.
 *
 * <p><b>The known-list file means "acts I have seen live", and that is not what {@code --known} is
 * for.</b> It was produced from a concert history (ADR 40), so it omits everything liked but never
 * attended. Measured on the real graph: of 167 rated entities absent from the file, <b>87 are rated
 * 4 or 5</b>: bands and performers plainly liked but never attended. The recommender treated
 * strangers and could recommend them back.
 *
 * <p><b>Promotion only, and the distribution is why.</b> The same 167 hold exactly two ratings below
 * neutral, so a suppression rule would ship against two data points. Issue #106 records that as
 * deliberately not built rather than overlooked.
 */
```

- [ ] **Step 4: Wire both callers**

`RecommendRun` and `RateCli` each read the file and the ratings already. Pass both through `KnownList.promoted`. **Read each call site before changing it** — `RecommendRun` takes `Options` and its own `regard`; `RateCli` holds the map for `alreadyRated`. Do not change what either does with the result beyond the substitution.

**One consequence to get right:** a promoted entity is now on the known-list, so `CandidateSweep` will exclude it from candidates. That is the intent — the owner should stop being recommended things they have said they love — but confirm it happens rather than assuming, and say so in your report.

- [ ] **Step 5: Update the callers' existing tests** so the new behaviour is asserted where each tool is tested, not only in `KnownListTest`.

- [ ] **Step 6: Run the gate and commit**

Run: `./gradlew check`

```bash
git add src/main/java/com/robsartin/segue/domain/KnownList.java \
        src/main/java/com/robsartin/segue/recommend/RecommendRun.java \
        src/main/java/com/robsartin/segue/rate/RateCli.java \
        src/test/java/com/robsartin/segue/domain/KnownListTest.java \
        <the caller tests you touched>
git commit -m "Count what you rated highly as something you have (#106)"
```

---

### Task 2: Measure it on the real graph, on a copy

Issue #106's acceptance says this explicitly, and it is there because three plausible theories in a row have failed against this data: *re-run `recommend` against the real graph afterwards and record whether the top 25 actually moves.*

**Files:** none committed. This task produces a report.

- [ ] **Step 1: Copy the database**

```bash
mkdir -p /tmp/promote && cp ~/.segue/segue.db /tmp/promote/copy.db
```

**Never open `~/.segue/segue.db` itself.** Report its mtime unchanged at the end.

- [ ] **Step 2: Run `recommend` twice against the copy** — once at the merge-base commit (before promotion), once at your branch head — with the same `--known` file and `--top 25`, writing both outputs outside the repository.

- [ ] **Step 3: Report, honestly**

- how many entities the promotion added (expected ~87 — verify, do not assume)
- how many of the previous top 25 **left** because they were promoted into the known-list (these should be exactly the ones the owner rated 4-5, which is the fix working, not a regression)
- how many genuinely new entities entered
- the before/after top 10, side by side

**If the ranking barely moves, say so plainly.** That has been the outcome three times on this data and it is a perfectly good result. Do not present a small movement as a large one.

- [ ] **Step 4: Write the numbers to `.superpowers/promotion-measurement.md`** for Task 3 to draw on. Nothing to commit.

---

### Task 3: The ADR

**Files:**
- Create: `docs/adr/00NN-*.md` (confirm the next number with `ls docs/adr/ | tail -3`)
- Modify: `docs/adr/README.md`, `docs/developer-guide.md`, `CLAUDE.md`

- [ ] **Step 1: Write it.** Read a recent ADR for shape and voice first.

It must record:

- **The decision:** an entity rated at or above 4 counts as known, in both tools.
- **That this reopens ADR 40**, which put the seeding list in a file outside the repository and made it the sole authority for `--known`. It is no longer sole. Say what survives: the file is still the authority for what was *seeded*, and nothing on the MCP surface can see it.
- **The measurement**, with the distribution `1/1/78/51/36` and the 87 figure, and the observation underneath it: the file means "acts I have seen live", not "things I have".
- **Why suppression was not built** — two data points below neutral against 87 above. Recorded as a deliberate omission with its reason, so a later reader knows it was considered.
- **The feedback property**, plainly: a promoted entity contributes its own connections to future candidate scores, so the deck becomes self-feeding. That is intended, and it means the candidate pool grows as the owner rates. Say whether anything bounds it.
- **What Task 2 measured**, whatever it was.
- The threshold is 4 and that is a judgement, not a measurement — say so.

**One warning from this repo's recent history:** issue #101 produced six false generalisations in a row, every one a sentence about a *group* written from memory rather than from the files. Any sentence claiming something about a set must be verified against every member by opening the file, or rewritten so it does not span a set. **Cite code as the authority; do not mirror it.**

- [ ] **Step 2: Run the gate and commit**

---

## Self-Review

**Spec coverage.** #106's acceptance maps to tasks: the ADR with alternatives → 3; the 167 candidates used rather than left inert → 1; demonstrated on invented ratings → 1's tests; re-run against the real graph and record whether the top 25 moves → 2; ADR 46's known-gap section amended → 3.

**Verified against source rather than inferred.** `RecommendRun` calls `QidList.read(options.known())`; `RateCli` calls `QidList.read(known)` and holds `readRatings()` for `alreadyRated`; `Recommendations.regardFor` weights known-list qids only, which is why promotion is what makes a rating count. `QidList` lives in `support` and dedupes.

**A consequence worth watching in review.** Promotion removes an entity from the candidate pool as well as adding it to the known-list. Task 1 Step 4 calls this out; if a reviewer thinks the two effects should be separable, that is a real design question and better raised than assumed.
