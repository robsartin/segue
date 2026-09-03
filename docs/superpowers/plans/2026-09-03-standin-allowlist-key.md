# Stand-in allowlist keyed by site — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `StandInQidsDenoteNothingTest`'s `ALLOWED` names each id's **sites** — the files it may appear in, and whether the sighting sits inside an annotation — so an allowlisted id in a new context reds. No id migrates; the inventory is unchanged.

**Architecture:** One test class changes. The lexer learns an annotation bit (Task 1); the allowlist key widens to (id, file, context) and every entry is re-expressed from the guard's own output (Task 2); the developer guide's row follows (Task 3).

**Tech Stack:** JUnit 5, AssertJ, plain `./gradlew` on JDK 25.

**Spec:** `docs/superpowers/specs/2026-09-03-standin-allowlist-key-design.md`

## Global Constraints

- **Pure TDD / red first**: every behaviour is seen red for the right reason — a real assertion failure, never a compile error — before the code that makes it green. Every control is planted, quoted from the actual output, and reverted. Test names `should<Expected>When<Condition>` with `@DisplayName`.
- **Mikado**: the gate is green before every commit. **Stage by explicit path, git stderr visible.** Commits end `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. Never cite a `.superpowers/` path from a committed file.
- Gate, **blocking**: `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`. Fast loop for controls: `./gradlew test --tests 'com.robsartin.segue.arch.StandInQidsDenoteNothingTest'` (~12s). Run `./gradlew spotlessApply` before the gate — google-java-format owns the layout of the entries.
- **Only JDK 25 is installed; Gradle 9.7.1 launches on it.** Plain `./gradlew`; never `java_home -v 21`.
- **Never run a writing dev task** (`own`, `ownClaim`, `retractEntity`); `~/.segue/segue.db` is never read, written, or created.
- **No ADR amendment** and **no id migrates** — see the spec. Do not touch `Fixture`, `Qid`, or any `src/main` file.

---

### Task 1: The lexer reads an annotation apart from code

**Files:** Modify: `src/test/java/com/robsartin/segue/arch/StandInQidsDenoteNothingTest.java` (the `Literal` record and `literals(String)`, ~lines 363–406). Read: the class javadoc's *"Why a lexer rather than two regular expressions"* paragraph — the four states are the thing being extended.

- [ ] **Step 1 — the stub, so the red is an assertion and not a compile error.** Add the enum and give `Literal` a context that is always `CODE`:

```java
  /** Where a sighting sat. An allowlist entry names this, so one reason cannot cover both. */
  enum Context {
    /** A string literal or text block in Java code — and, in a non-Java file, the file's own text. */
    CODE,
    /** A string literal inside an annotation's arguments, where a {@code @DisplayName} lives. */
    ANNOTATION
  }
```

  `private record Literal(int start, String text, Context context) {}`, and both `literals.add(...)` calls pass `Context.CODE`.

- [ ] **Step 2 — RED.** Add to the class, and run the fast loop:

```java
  @Test
  @DisplayName("a literal inside an annotation is read apart from one in code")
  void shouldReadALiteralAsAnAnnotationWhenItSitsInsideAnAnnotationsArguments() {
    String source =
        """
        class Probe {
          @DisplayName("Q1: a question number")
          void answers() {
            store.upsertNode(new NodeRecord("Q1", PERSON, "a node id"));
          }
        }
        """;

    assertThat(literals(source).stream().filter(l -> l.text().startsWith("Q1")).map(Literal::context))
        .as("the question number is an annotation's argument; the node id is code")
        .containsExactly(Context.ANNOTATION, Context.CODE);
  }
```

  Expect `expected: [ANNOTATION, CODE] but was: [CODE, CODE]`. **Quote it.** (The text block's own `Q1`s are sighted in this file and are allowed by id — the sweep must stay green, and does.)

- [ ] **Step 3 — GREEN.** In `literals`, keep a `Deque<Boolean> parens = new ArrayDeque<>();`. In the final `else` branch, before `at++`: `if (c == '(') parens.push(opensAnnotation(source, at)); else if (c == ')' && !parens.isEmpty()) parens.pop();`. Both `literals.add(...)` calls pass `parens.contains(Boolean.TRUE) ? Context.ANNOTATION : Context.CODE`. Add:

```java
  /** Whether this {@code (} closes an {@code @Ident}, which is what starts an annotation's args. */
  private static boolean opensAnnotation(String source, int paren) {
    int at = paren - 1;
    while (at >= 0 && Character.isWhitespace(source.charAt(at))) {
      at--;
    }
    while (at >= 0
        && (Character.isJavaIdentifierPart(source.charAt(at)) || source.charAt(at) == '.')) {
      at--;
    }
    return at >= 0 && source.charAt(at) == '@';
  }
```

  Re-run: green, `4 tests completed`.

- [ ] **Step 4 — prove the sweep agrees with the spec's measurement.** Temporarily print (or assert) the annotation-context sightings from `SWEEP`: expect **8**, all `Q1`–`Q4`, twice each, all in `src/test/java/com/robsartin/segue/port/GraphStoreContract.java`. Paste the list in the report; revert the scaffolding.

- [ ] **Step 5 — commit.** `spotlessApply`, full gate blocking, `git add` the one path, commit.

---

### Task 2: `ALLOWED` names sites, not just ids

**Files:** Modify: `src/test/java/com/robsartin/segue/arch/StandInQidsDenoteNothingTest.java` (`ALLOWED`, `Sighting`, `sweep`, `collect`, the offending test and the dead-entry test, and the two javadoc paragraphs named in Step 8). Read: the spec's measurement table — 245 sites, 113 entries, 8 annotation sightings.

- [ ] **Step 1 — reproduce the defect (it is a green).** At `src/test/java/com/robsartin/segue/export/DotWriterTest.java:46`, change `new ViewNode("Q0901",` to `new ViewNode("Q1",`. Fast loop → `BUILD SUCCESSFUL`. **Quote it. Leave the plant in place** — it is the failing case Steps 2–4 must turn red.

- [ ] **Step 2 — the new shape.** Thread the context through the sweep and re-key the list:

```java
  /** One place the tree may carry an allowed id. */
  private record Site(String file, Context context) {
    @Override
    public String toString() {
      return context == Context.ANNOTATION ? file + " (in an annotation)" : file;
    }
  }

  /** One deliberately real id: why it is real, and every site allowed to carry it. */
  private record Allowance(String reason, Set<Site> sites) {}

  private static Allowance real(String reason, Site... sites) {
    return new Allowance(reason, Set.of(sites));
  }

  private static Site code(String file) {
    return new Site(file, Context.CODE);
  }

  private static Site annotation(String file) {
    return new Site(file, Context.ANNOTATION);
  }

  private static String relative(Path file) {
    return ROOT.relativize(file).toString();
  }

  /** This file declares ids; every other file has to name the site. */
  private static boolean isAllowed(Sighting sighting) {
    Allowance allowance = ALLOWED.get(sighting.id());
    return allowance != null
        && (sighting.file().equals(SELF)
            || allowance.sites().contains(new Site(relative(sighting.file()), sighting.context())));
  }

  private static String report(Sighting sighting) {
    Allowance allowance = ALLOWED.get(sighting.id());
    return allowance == null
        ? sighting.describe()
        : sighting.describe()
            + "  — allowed, but only at "
            + allowance.sites().stream().map(Site::toString).sorted().collect(Collectors.joining(", "));
  }
```

  `Sighting` becomes `record Sighting(Path file, int line, String id, Context context)` and `describe()` appends `" (in an annotation)"` when the context is `ANNOTATION`; `collect` takes a `Context` and `sweep` passes `literal.context()` for `.java` files and `Context.CODE` for every other file. `ALLOWED` becomes `Map<String, Allowance>`; the offending test filters on `!isAllowed(s)` and maps `StandInQidsDenoteNothingTest::report`, and its `.as(...)` message gains: *"or, if the id is already allowed elsewhere, add this file to its sites — an id is allowed at the sites its entry names and nowhere else"*.

- [ ] **Step 3 — take the inventory from the guard itself.** Re-express all 113 entries as `entry("Q…", real("<today's reason, verbatim>"))` — **reasons unchanged, no sites yet**. Fast loop: the guard now reds listing every sighting outside this file as `path:line  id`, with `(in an annotation)` on the eight `GraphStoreContract` ones. That list is the site inventory. Fold it in — one `code("…")` or `annotation("…")` per distinct id-and-file — expecting the spec's **245** sites and these worked shapes:

```java
          entry(
              "Q1",
              real(
                  "not an identifier — the question number in GraphStoreContract's @DisplayName",
                  annotation("src/test/java/com/robsartin/segue/port/GraphStoreContract.java"))),
          entry(
              "Q328",
              real(
                  "entity — a real value in the recorded proposition-claims.json",
                  code("src/test/resources/wikidata/proposition-claims.json"))),
```

  **Do not add a site for the planted `Q1` at `DotWriterTest:46`.**

- [ ] **Step 4 — RED, for the right reason.** Fast loop. The only offending line left must be the plant:
  `src/test/java/com/robsartin/segue/export/DotWriterTest.java:46  Q1  — allowed, but only at src/test/java/com/robsartin/segue/port/GraphStoreContract.java (in an annotation)`. **Quote it** — this is the defect from Step 1, now caught. Revert the plant; fast loop green.

- [ ] **Step 5 — the annotation half earns itself.** In `src/test/java/com/robsartin/segue/port/GraphStoreContract.java`, change both `"Q0100004"` (lines 95 and 97) to `"Q1"`. Fast loop → red naming `GraphStoreContract.java:95` and `:97`. **Quote it.** Then, with the plant still in, weaken `isAllowed` to compare the file only (`allowance.sites().stream().anyMatch(s -> s.file().equals(relative(sighting.file())))`) → green, which is exactly what a (id, file) key would have done; restore the real `isAllowed` → red again. Revert the plant.

- [ ] **Step 6 — the dead-site check.** Replace the dead-entry test, keeping its shape:

```java
  @Test
  @DisplayName("the allowlist names no site the tree no longer carries the id at")
  void shouldCarryNoDeadSiteWhenTheAllowlistIsCheckedAgainstTheTree() {
    Set<String> live =
        SWEEP.sightings().stream()
            .filter(s -> !s.file().equals(SELF))
            .map(s -> s.id() + " @ " + new Site(relative(s.file()), s.context()))
            .collect(Collectors.toCollection(LinkedHashSet::new));

    List<String> dead =
        ALLOWED.entrySet().stream()
            .flatMap(
                e ->
                    e.getValue().sites().isEmpty()
                        ? Stream.of(e.getKey() + " @ (no site at all)")
                        : e.getValue().sites().stream().map(s -> e.getKey() + " @ " + s))
            .filter(claim -> !live.contains(claim))
            .sorted()
            .toList();

    assertThat(dead)
        .as(
            "sites named by ALLOWED that no longer carry the id they were written about, in the"
                + " context they were written for. A reason that outlives its site is a reason"
                + " nobody can check; delete the site, or the entry with its last one")
        .isEmpty();
  }
```

  **Note (final review, F5):** the shipped version keys `live` and `dead` on a `private record
  Claim(String id, Site site)` rather than on the `"id @ site"` string shown above — the
  comparison is on values, and the string above is now only how a `Claim` renders for the
  message. Same shape, narrower key.

  Controls, each quoted and reverted: (a) add `code("src/test/java/com/robsartin/segue/domain/QidTest.java")` to `Q5`'s sites → red naming it; (b) change `Q1`'s `annotation(...)` to `code(...)` → red, proving the context is part of the site; (c) empty one entry's sites → red `(no site at all)`.

- [ ] **Step 7 — the checks kept from #213.** Delete the `Q328` entry → red on `src/test/resources/wikidata/proposition-claims.json`; restore. Confirm the vacuity test is untouched and green (files non-empty, sightings non-empty, `SELF` among the files read).

- [ ] **Step 8 — javadoc.** In the `ALLOWED` javadoc, replace the *"An id is allowed whole or not at all, and one group straddles that"* paragraph with one that says an entry names its sites, why (`GraphStoreContract` numbers its questions **and** mints node ids in the same file — issue #216, measured green on `07d8e2f`), and the two consequences: a moved test file reds twice and the message names both paths, and a new file's first use of a real class id has to be declared. Add a paragraph saying **this file is a declaration site**: its own sightings are matched by id alone, no entry names it, and an allocatable id typed into this class that is not an entry still reds. Extend the *"What this class cannot see"* paragraph with the limit the spec states: a site key cannot tell a node id from a class id inside a file that already declares the id in code, and only a parser could.

- [ ] **Step 9 — commit.** `spotlessApply`, full gate blocking (expect no test-count change beyond Task 1's fourth test), `git add` the one path, commit.

---

### Task 3: The developer guide's row says the site

**Files:** Modify: `docs/developer-guide.md` (the *Stand-in identifiers* row of the testing-strategy table, ~line 1039). Read: neighbouring rows for the citation style.

- [ ] **Step 1.** Change `so an id that is deliberately real has to be allowed by name with the reason it is real` to `so an id that is deliberately real has to be allowed by name, at the files it may appear in and in the context it appears — a string literal in code, or an annotation's argument — with the reason it is real ([issue #216](https://github.com/robsartin/segue/issues/216))`. Restate no count; leave the ADR links intact.
- [ ] **Step 2 — verify.** This is prose with no unit-testable behaviour; it is verified by the build gate, which runs `DocumentationLinksTest` (every relative link still resolves) and `DeveloperGuideEnumerationsTest` (no derived set moved). Say so in the report rather than letting test-after arrive by implication.
- [ ] **Step 3 — commit.** Full gate blocking, `git add docs/developer-guide.md`, commit.
