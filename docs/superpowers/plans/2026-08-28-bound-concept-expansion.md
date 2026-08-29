# Bound CONCEPT Expansion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One `expand_entity` on a broad CONCEPT can no longer add hundreds of edges. A kind-scoped ceiling bounds it, and the result says so when the ceiling bites.

**Architecture:** A pure rule in `domain` giving the effective bound for a kind, applied in `SegueService.expandEntity` as a **ceiling on whatever was requested** — not a default, because the hazard is a caller asking for a large number. The existing `truncated` → `partial` reporting (issue #65) carries the news.

**Tech Stack:** Java 21 (release 21, toolchain 25), Gradle Kotlin DSL, JUnit 5 + AssertJ, ArchUnit.

## Global Constraints

- **Issue #112.** Branch `112-bound-concept-expansion`. Read the issue — its measurements are the argument.
- **Never open `~/.segue/segue.db`.** Copy it for any measurement and report the real file's mtime unchanged. It holds 973 irreplaceable personal ratings.
- **This repo is PUBLIC and the owner's ratings are personal data** (ADR 33, issue #37). Aggregate figures are fine. **Never name an entity as something the owner rated or likes** — a recent branch did exactly that and its history had to be rewritten before pushing.
- Stage commits **by explicit path**. NEVER `git add -A` — an untracked `mad.vcf` must never be staged.
- `./gradlew check` green before every commit; run long commands **blocking**.
- `domain` has **no third-party dependencies**.
- TDD: failing test first, run it, watch it fail for the right reason, report what it said.

## The measurement behind it

One `expand_entity` on a broad CONCEPT pulls up to the `ReverseClaims` cap: **religion and accounting both hit the 501-row cap**, giving degree 500 in a single call, with *WikiProject Religion* at rank 29 of the kept prefix. The hazard is not hypothetical and not confined to subjects — **Java already expands to 91 edges today** via `P737`/`P361`.

The discipline that has prevented it — *only expand PERSON and GROUP* — **lives in a scratch seeding script, not in the code.** `expand_entity` accepts any QID in the graph.

---

### Task 1: A kind-scoped ceiling

**Files:**
- Create: `src/main/java/com/robsartin/segue/domain/ExpansionBounds.java`
- Modify: `src/main/java/com/robsartin/segue/mcp/SegueService.java`
- Test: `src/test/java/com/robsartin/segue/domain/ExpansionBoundsTest.java`, `src/test/java/com/robsartin/segue/mcp/SegueServiceTest.java`

**Interfaces:**
- Produces: `ExpansionBounds.effective(NodeKind kind, int requested) → int`, and a named constant for the CONCEPT ceiling.

- [ ] **Step 1: Measure the number before choosing it**

The ceiling for CONCEPT is a judgement, but it should be an *informed* one. On a **copy** of `~/.segue/segue.db`, report the in-graph degree distribution of CONCEPT nodes — how many sit below 10, below 25, below 50 — so the number can be chosen to let ordinary CONCEPTs expand fully while bounding the broad ones. ADR 31 records 89 CONCEPTs at degree ≥ 10 out of 123,752 nodes (0.072%); confirm or correct that from the copy.

Put the distribution in your report. **If the measurement argues for a different number than the plan's suggested 25, use the measured one and say why.**

- [ ] **Step 2: Write the failing tests**

```java
  @Test
  @DisplayName("a CONCEPT is capped even when a larger bound is requested")
  void capsAConcept() {
    assertThat(ExpansionBounds.effective(NodeKind.CONCEPT, 200))
        .isEqualTo(ExpansionBounds.CONCEPT_CEILING);
  }

  @Test
  @DisplayName("a request smaller than the ceiling is honoured, so the ceiling is not a default")
  void doesNotRaiseASmallerRequest() {
    assertThat(ExpansionBounds.effective(NodeKind.CONCEPT, 5)).isEqualTo(5);
  }

  @Test
  @DisplayName("every other kind is unbounded by this rule")
  void leavesTheOtherKindsAlone() {
    for (NodeKind kind : NodeKind.values()) {
      if (kind != NodeKind.CONCEPT) {
        assertThat(ExpansionBounds.effective(kind, 200)).isEqualTo(200);
      }
    }
  }
```

That third test iterates `NodeKind.values()` deliberately — a seventh kind would be covered automatically rather than silently escaping. Keep that shape.

- [ ] **Step 3: Run and watch them fail.** Record the compile error.

- [ ] **Step 4: Implement, and wire it into `SegueService.expandEntity`**

`ExpansionBounds` is a final class with a private constructor in `domain`, importing only `java.util` if anything. Its javadoc must carry the argument: the measured 500-edge flood, why a ceiling rather than a default, and that the number is a judgement informed by the distribution you measured.

In `SegueService.expandEntity`, apply it to whatever bound the request resolved to. **Read the method first** — it already computes a `maxNewEdges` and already reports `truncated`. The ceiling must feed the same reporting path so a bitten cap arrives as `partial` (issue #65's rule: a bound that can bite must be reported by the result that hit it, and observed rather than assumed).

- [ ] **Step 5: Test it end-to-end at the service level**

A CONCEPT expansion that would exceed the ceiling must return `partial` and name the true count. Assert on the reported result, not only on the stored edge count.

- [ ] **Step 6: Run the gate and commit**

```bash
git add src/main/java/com/robsartin/segue/domain/ExpansionBounds.java \
        src/main/java/com/robsartin/segue/mcp/SegueService.java \
        src/test/java/com/robsartin/segue/domain/ExpansionBoundsTest.java \
        src/test/java/com/robsartin/segue/mcp/SegueServiceTest.java
git commit -m "Bound what one expansion can add to a CONCEPT (#112)"
```

---

### Task 2: The ADR and the docs

**Files:**
- Create: `docs/adr/00NN-*.md` (confirm the next number with `ls docs/adr/ | tail -3`)
- Modify: `docs/adr/README.md`, `docs/developer-guide.md`, `CLAUDE.md`, and the `expand_entity` tool description if it states a bound

- [ ] **Step 1: Write it**

Record:

- **The decision:** a kind-scoped ceiling, applied as a ceiling on the request rather than a default, so a caller asking for more cannot raise it.
- **The measurement:** religion and accounting both hitting the 501-row cap; degree 500 from one call; *WikiProject Religion* at rank 29; Java already at 91 edges via `P737`/`P361` with no new property involved. Plus the CONCEPT degree distribution you measured in Task 1.
- **The number is a judgement**, informed by that distribution. Say so.
- **The alternatives and why each lost** — refusing CONCEPT expansion outright (forecloses narrow cases, and `supports(kind)` would give a misleading silent zero rather than a refusal); refusing above a projected degree (precise, but costs a round trip and picks an unmeasured number); reporting and requiring confirmation (`expand_entity` is called by a model, so "confirmation" means the model deciding — which is what the guard exists to constrain).
- **What this does not fix:** the discipline of only expanding PERSON and GROUP still lives outside the code. This bounds the damage rather than expressing the policy.
- **The `partial` reporting**, and that it follows issue #65's rule.

**One warning from this repo's recent history.** Issue #101 produced six false generalisations in a row — sentences about a *group* written from memory rather than from the files. Any sentence claiming something about a set must be verified against every member by opening the file, or rewritten so it does not span a set. Cite code as the authority; do not mirror it.

- [ ] **Step 2: Run the gate and commit**

---

## Self-Review

**Spec coverage.** #112's acceptance maps to: broad CONCEPT cannot silently add hundreds → Task 1; PERSON/GROUP path unchanged and demonstrated by test → Task 1 Step 2's `NodeKind.values()` loop and Step 5; the rule lives in code rather than a script → Task 1; ADR → Task 2.

**Verified against source rather than inferred.** `SourceAdapter.supports(kind)` exists and `SegueService.expandEntity` skips adapters that decline it (`SegueService.java:199-202`), but `WikidataSourceAdapter.supports` returns `true` for every kind — so refusing through that seam would produce a silent zero, which is why the ceiling goes elsewhere. `expandEntity` already computes a bound and already reports `truncated`.

**An honest limit.** This bounds one call. Ten calls still add ten times the ceiling. If that matters, it is a different issue and should be filed rather than solved here.
