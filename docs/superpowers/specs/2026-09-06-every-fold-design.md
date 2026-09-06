# The harness reads every fold of the split, not one

Issue #268. Written 2026-09-06 against `268-ready` at `f6c509d` (HEAD = `main`). Everything below was
read from the code in this worktree; no real database was opened, and every identifier named here is
invented and carries ADR 58's leading zero.

## The premise the issue states, and what the code actually does

The issue's premise holds against the code, and the two places where it is looser than the code are
recorded here rather than left for the implementer to discover.

- **`HeldOut.every` holds out index `0, 5, 10, …` of the eligible population sorted ascending by
  qid, and `EvaluateRun.run` calls it once.** That is exactly the issue's description. `HeldOut.EVERY`
  is `5`; the interval is passed as an argument and `EvaluateRun` passes the constant.
- **"Four fifths of the eligible population is never held out" is right in the aggregate and not
  exactly four fifths.** With 152 eligible entities the folds are 31, 31, 30, 30, 30 — they differ in
  size by at most one, so each fold leaves a *different* number on the known-list (121 or 122 there).
  Nothing in the design turns on this, but the header line cannot state one number for "what each
  fold leaves on the known-list", and this spec chooses what it states instead (below).
- **The issue says the report's counts become "totals over the folds" and the means become means
  "over every hit across the folds".** That is the decision. What the issue does not say is *how* a
  mean over every hit is formed without rounding twice, and that is the one real design question
  here; it is settled below and it changes `Reading`'s shape.
- **`EvaluationReport.lines` takes plain counts and not the `HeldOut`** (ADR 65's type-level fence).
  The issue does not mention the signature; folding adds counts to it, and the fence is what decides
  they are added as `int`s rather than as a split object.

## What changes, in one sentence each

1. `HeldOut.every` takes an **offset** as well as an interval. Fold `k` holds out eligible positions
   `k, k + interval, …`. Fold zero is today's behaviour, byte for byte.
2. `Reading` carries the **sum of the hit ranks** and the **sum of the negative ranks** as integers,
   where it carried two `OptionalDouble` means. Nothing divides until the report renders.
3. `Reading.summed` is a new pure static: a list of one `Reading` per fold, all of one setting, in;
   one `Reading` out, every field added.
4. `EvaluateRun.run` runs folds `0 … HeldOut.EVERY − 1`, one sweep per setting per fold, one boot and
   one `CandidateSweep`, and reports one summed row per setting.
5. `EvaluationReport`'s split line states folds, and its mean cells divide a sum by a count.
6. ADR 65 gets a dated amendment; the developer guide's protocol gains the fold paragraph.

The grid, the rule of #245, the eligible population, the interval and every constant are untouched.

## Why folding rather than a wider split

ADR 45's 2026-09-06 amendment ends by saying that enlarging the split (`HeldOut.EVERY`) is the only
thing that would separate "the setting is right" from "the split is too small to say". Read
literally, lowering `HeldOut.EVERY` to 3 or 2 holds out more — and **shrinks the known-list the
recommender learns from**, which is the input to the thing being measured. A reading taken that way
cannot be compared with either reading on the record, because two things moved.

Folding moves one. Every fold holds out the same *size* of slice from the same eligible population,
so the known-list each fold sweeps from is the same size today's single fold sweeps from (to within
the one entity by which fold sizes differ), and every eligible entity is held out exactly once
across the run. What grows is the **evidence**, not the perturbation: hits are counted over 152
held-out entities instead of 31.

That is the whole argument for the change, and it is also the boundary of the claim: folding buys
statistical power, not a new question. The question, the rule and the grid are the ones already on
the record.

## The split, folded

`HeldOut.every(interval, offset, ratings, onFile, couldBeOffered)`.

- The eligible population is derived exactly as today — rated at or above
  `KnownList.PROMOTION_RATING`, absent from the `--known` file, and offerable as a candidate — sorted
  ascending by qid. **The offset does not enter the eligibility rule**, so every fold reports the
  same `eligible`, which is what makes the sixteen summed rows share one denominator statement.
- Fold `k` holds out positions `k, k + interval, k + 2·interval, …`.
- `offset` must satisfy `0 <= offset < interval`. Anything else is refused with an
  `IllegalArgumentException`: an offset at or above the interval is a fold that does not exist, and
  silently returning a duplicate of another fold would double-count entities in the total.
- The existing `interval < 2` refusal is unchanged and is checked first, so its message is still the
  one an operator sees for the interval.
- **Fold zero is today's split.** `HeldOut.every(5, 0, …)` returns exactly what `HeldOut.every(5, …)`
  returns today, and the existing `HeldOutTest` cases are the pin for that: they change only by
  passing the new `0`.

**The folds partition the eligible population.** Each entity is held out in exactly one fold, the
union over folds is the whole eligible population, and the folds' sizes differ by at most one. That
is a property of `i = offset; i += interval` over one list, and it gets its own test over invented
ratings rather than a comment.

### No four-argument overload survives

The four-argument `every` is replaced, not kept beside the five-argument one. There is no caller for
"the split, unfolded" once `EvaluateRun` folds, and an overload that means "fold zero" is a second
way to spell one thing — YAGNI, and the sort of convenience that later hides an unfolded run inside
a folded harness.

## The means, and the one thing that could drift

Sixteen rows, each summed over five folds. Counts (`pool`, `in pool`, `hits`, `negatives`) add and
there is nothing to decide. A **mean** does not add, and there are three ways to get one:

1. **Weight the per-fold means by their hit counts.** `Σ(mean_k · hits_k) / Σ hits_k`. Correct in
   exact arithmetic. **Rejected:** `mean_k` is already a rounded `double` — the division happened in
   `Scoring` — so `mean_k · hits_k` recovers the fold's rank sum only to within a few ULPs, and the
   report renders `%.1f`. A true value a hair either side of a `.x5` boundary renders a different
   tenth depending on how it was assembled. An instrument whose entire value is that two readings
   diff row by row cannot have a rendering that depends on the order of its own arithmetic.
2. **Carry the rank lists out of `Scoring` and mean them once at the end.** Exact. **Rejected:** a
   rank list is a per-entity vector. `Reading`'s safety property, and ADR 65's type-level fence, is
   that the types the report is built from have nowhere to put a per-entity anything. A list of
   ranks is not a qid, but it is one join away from being read as one, and it is more than the
   arithmetic needs.
3. **Carry the rank *sum* as an integer, and divide once, in the report.** **Chosen.** It is the
   smallest thing that adds exactly: integer addition, no rounding anywhere until the single
   division that produces the rendered cell. There is no boundary case to argue about, because there
   is no intermediate double at all. It is also *fewer* moving parts than today — the mean is
   computed in exactly one place instead of in `Scoring` per row.

So `Reading` becomes:

```
Reading(Setting setting, int pool, int heldOutInPool, int hits, int hitRankSum,
        int negativesOffered, int negativeRankSum)
```

and the report renders `NO_MEAN` when the count is zero and `%.1f` of `rankSum / (double) count`
otherwise. **The rendered contract is unchanged** — every cell is still an integer, a fixed
one-decimal or the dash, and a mean over nothing is still the dash rather than zero, now because the
*count* is zero rather than because an `OptionalDouble` is empty. ADR 65's output-contract section
therefore still describes the output exactly, and is cited rather than edited.

This is a shape change to a record, and the whole point of the standing "parallel field" manoeuvre —
migrate consumers one at a time — is unavailable and unnecessary here: adding a component to a record
breaks every construction site exactly as replacing one does, because the canonical constructor is
positional. There are two construction sites in `src/main` and three in `src/test`. It lands as one
step with the gate green at the end of it, and the plan writes out the arithmetic each fixture
changes to.

## The sum, as a pure function

`Reading.summed(List<Reading> folds)` — a static on `Reading`, not a new class. The sum of readings
is a reading; giving it a home of its own would be a type whose only job is to call a constructor.

- Every field adds. The `Setting` is carried through unchanged.
- **Guard: an empty list is refused.** A row of the report is a setting over at least one fold, and
  the alternative is inventing a `Setting` from nothing.
- **Guard: readings of two different settings are refused.** This is the transposition bug the
  harness is most exposed to — summing across *settings* instead of across *folds* would produce a
  table that looks entirely plausible and means nothing. The guard costs one comparison.

Both guards get a test, and each test gets a planted control: the guard is removed, the test is seen
to fire, the guard is restored.

`summed` lands with its own RED and is unused by production code for exactly one commit; the fold
loop that calls it is the next task. That is deliberate — the pure arithmetic is proved before the
loop that depends on it, rather than inside it.

## The run

`EvaluateRun.run` keeps its signature and its return type: `List<Reading>`, one per setting, in
`Setting.GRID` order — now the **summed** readings, which are exactly the numbers the report prints,
so a test asserting on them asserts on what a reader sees. Per-fold readings are an intermediate and
do not leave the method. Nothing outside needs them, `Reading` gains no fold field, and inventing an
"all folds, all settings" return value for a caller that does not exist is the abstraction YAGNI
refuses.

The loop:

- One `QidList.read`, one `CandidateSweep`, one boot — unchanged. The sweep's memoised degrees are a
  function of the graph, not of the known-list, so it is reused across folds exactly as it is reused
  across settings today. ADR 65's "do not re-project per setting" becomes "do not re-project per fold
  either".
- For each fold `0 … HeldOut.EVERY − 1`: one `HeldOut.every(HeldOut.EVERY, fold, …)`, and from that
  one map the known-list, the regard function and the suppressed set — ADR 65's "one map, three
  consumers", now per fold. Then one `sweep.over(...)` per setting, suppression withheld, and one
  `Scoring.read`.
- After the folds: `Reading.summed` per setting, then the report.

`EvaluateRun` also accumulates the three counts the header needs: `eligible` (fold-invariant),
`heldOutTotal` (the sum of the folds' held-out counts, which equals `eligible` when the folds
partition), and `leastLeft` (the smallest `eligible − heldOut` over the folds).

**Cost.** `HeldOut.EVERY × Setting.GRID.size()` sweeps — 80 where there are 16 today, five times
today's run. The replay and the degree memoisation are still paid once. There is no wall-clock
assertion anywhere in this issue, and none is added: the machine is loaded and a timing assertion
would be a flake generator. The cost is stated in the ADR amendment and in the developer guide as a
fact an operator plans around.

## The header

Three `#` lines, as today. The first (`EvaluationReport.HEADER`) and the third (`# top N per
setting, over M setting(s).`) do not move. The second becomes:

```
# held out every 5 of 152 eligible entity(ies), in 5 fold(s): 152 held out over all folds, at least 121 left on the known-list in each.
```

- `held out every 5 of 152 eligible entity(ies)` is retained verbatim, so `1 eligible entity(ies)`
  and the shape a reader already knows survive.
- `in 5 fold(s)` is the fold count, passed in rather than read off `HeldOut.EVERY` inside the report:
  the number of folds is a fact about the run that was made, and a report that assumed the identity
  could not report a run that did anything else.
- `152 held out over all folds` is the total. It equals `eligible` whenever the folds partition, and
  it is printed rather than asserted, so a reader can see the two agree.
- `at least 121 left on the known-list in each` is the **smallest** known-list contribution over the
  folds. Folds differ in size by one, so there is no single number; the smallest is the honest bound
  — it is the worst case for what the recommender had to learn from — and it costs one `int` where a
  range would cost two and read worse.

`EvaluationReport.lines` therefore takes `(int eligible, int folds, int heldOutTotal, int leastLeft,
int top, List<Reading> readings)`. Five plain counts where there were three. ADR 65's fence is about
what a parameter *can carry*, not how many there are: every one of these is an `int`, none of them is
a `HeldOut`, and there is still nowhere in the signature to put a qid.

## What quotes the old header shape

Derived by grep over `*.java` and `*.md`, not assumed:

- `src/main/java/com/robsartin/segue/evaluate/EvaluationReport.java` — the source of truth. Changed.
- `src/test/java/com/robsartin/segue/evaluate/EvaluationReportTest.java` — asserts
  `8 left on the known-list`. Changed.
- `src/test/java/com/robsartin/segue/evaluate/EvaluateRunTest.java` — asserts
  `1 eligible entity(ies)`. That substring survives the new line unchanged; the test is edited for
  the new assertions rather than for this one.
- `docs/adr/0045-recommend-by-normalised-lift-with-routes.md` — the two readings quoted whole in the
  2026-09-04 and 2026-09-06 amendments. **Immutable, and never edited.** They are readings taken by
  an instrument that no longer exists in that form; ADR 65's amendment records that they stay
  comparable to each other and are not row-for-row comparable to a folded reading.
- `docs/adr/0065-…md` — the output-contract section describes the *shape* and deliberately does not
  quote the split line. **Immutable, cited, not edited**; the amendment carries what changed.
- `docs/superpowers/plans/2026-09-04-*.md` — historical plan documents that quote the code as it was
  when they were written. Not edited: a plan is a record of what was done on a day.
- `docs/developer-guide.md` — describes the protocol and does **not** quote the header text. It gains
  the fold paragraph and the cost sentence.

## What the safe-to-paste guard must still refuse

`EvaluationIsSafeToPasteTest` needs no edit, and that is the claim to prove rather than assume. It
captures every log line at TRACE from a real `EvaluateCli.main` over a scratch database whose fixture
carries a label, a note, a `Q` id inside that note and a rating, and asserts:

- the table was actually printed (`EvaluationReport.HEADER` present, some line starting `raw`) —
  the anti-vacuity clause, which now also covers "the folded run still produced a table";
- no line carries the label;
- no line carries the note;
- **no line carries anything qid-shaped, wherever it came from** — and the new split line is one of
  the lines it reads.

The planted control for the new line is explicit in the plan: append an invented qid to the split
line in `EvaluationReport`, run the test, see the third assertion fire naming that line, remove the
plant, see it pass. The known limit is unchanged and is not re-argued here: the guard cannot see a
leaked *rating*, because a leaked rating is a bare digit; ADR 65's consequences own that sentence and
the type-level fence is what actually holds it.

## What this issue does not do

- **It does not take the reading.** The first folded run is the owner's, on his own database, and it
  is recorded as an ADR 45 amendment by a follow-up issue, judged by #245's rule unchanged. Nobody
  implementing this plan runs `evaluate`, and `~/.segue/segue.db` is never read, written, copied or
  created.
- **It does not touch the rule.** #245's clauses, including the void clause, are untouched. The one
  thing worth writing into the follow-up issue rather than into this one: the void clause tests the
  smallest `in pool` cell among the rows a clause compares, and on a folded table that cell is a
  total over five folds, so the clause cannot fire for the reason it was written for. Saying so is
  the reading issue's job; changing anything about the rule is nobody's job here.
- **It does not change the grid, the interval, the eligible population or any constant.** Not the
  floor, not the scorer default, not the promotion threshold, not the suppression boundary.
- **It adds no flag.** The fold count is `HeldOut.EVERY` — fixed, for the reason ADR 65 keeps the
  grid off the command line: the value of the tool is one comparable block, and a flag produces a
  stack of runs nobody can line up.
- **It adds no fold column and no per-fold rows.** Sixteen rows stay sixteen rows. Eighty rows is not
  a block a person reads in one sitting, and the per-fold detail is noise at this split size.

## Alternatives considered

- **Lower `HeldOut.EVERY`.** The literal reading of ADR 45's own sentence. Rejected: it shrinks the
  known-list, which is an input to the thing being measured, so the result is comparable to nothing
  on the record.
- **A random split with more draws.** Rejected by ADR 65 already, on ADR 57's finding that a reading
  nobody can re-derive stops being re-derived. Folding needs no seed at all: the folds are the
  offsets of one fixed interval.
- **A `--folds` flag.** Rejected for ADR 65's grid reason, restated: a run whose fold count varies is
  not comparable with the next one, and comparability is the whole product.
- **A fold column, or sixteen rows per fold.** Rejected: eighty rows of five-times-smaller counts is
  a less legible table saying less. The aggregate over folds is the number with the power; a per-fold
  breakdown is what a later issue can add if a fold ever looks anomalous, and nothing suggests one
  does.
- **Weighted per-fold means.** Rejected above: it rounds twice, and the second rounding can move a
  rendered tenth.
- **Rank lists carried out of `Scoring`.** Rejected above: exact, but it gives the harness's types
  somewhere to put a per-entity artefact, which is precisely the fence ADR 65 built.
- **Keep `Reading`'s means and add a fold field.** Rejected: a fold field on a row that is a *sum
  over folds* has no value to hold, and it does not solve the mean problem, which is the only real
  problem here.
- **Sum inside `EvaluateRun`'s loop with running counters.** Rejected: the summing rule would then
  be untestable except through a graph, a boot and eighty sweeps. A pure static gets its own red and
  its own planted controls in milliseconds.
