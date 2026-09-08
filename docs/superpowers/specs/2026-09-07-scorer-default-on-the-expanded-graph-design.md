# The scorer default on the expanded graph — design (#291)

Issue #291. The decision is already taken and this document does not re-open it:
`Recommendations.DEFAULT_SCORER` becomes `Scorer.RESOURCE_ALLOCATION`, `MIN_CANDIDATE_DEGREE` does
not move, and ADR 45 gains a dated amendment recording the move as a decision taken **outside** the
rule of issue #245 rather than as a ruling made by it.

What this document settles is everything the issue leaves to the code: which tests go red and which
stay green by construction, what a fixture that can tell `resource-allocation` apart from every
other scorer has to look like and why the shipped one cannot, the exact amendment text, and every
sentence in the tree that names the default and must follow the constant.

**Authorities, cited and never restated.** ADR 45 and its amendments; in particular the
2026-09-07 amendment for issue #289, which is the eighth reading and this decision's whole evidence.
No cell of that table and no figure derived from it appears below. ADR 46 for the deck, ADR 65 for
the harness, ADR 51 for what may be quoted, and
`docs/superpowers/specs/2026-09-04-second-reading-rule-design.md` for the rule. The code is the
authority for arithmetic: `Scorer.score`, `CandidateSweep.over`, `PathRanking.isHub`.

---

## 1. Where the issue's description of the code is not quite the code

Four findings. Each changes what the plan does.

1. **`RateRun`'s javadoc needs no edit.** The issue lists it among "every sentence that names the
   default". It does not name one: `grep -in lift src/main/java/com/robsartin/segue/rate/RateRun.java`
   is empty, and its class javadoc says the sweep's scorer is `Recommendations.DEFAULT_SCORER`, by
   reference. It follows the constant with no edit, and the plan says that out loud rather than
   editing it to say so.

2. **`RecommendCli`'s usage text needs no edit either.** `USAGE` is built from
   `Recommendations.DEFAULT_SCORER.spelling()` at line 83, and `RecommendCliTest`'s
   `shouldSpellTheDefaultScorerFromTheConstantWhenItRefusesAnything` asserts against that same
   expression. Both follow the constant. Issue #244 already bought this.

3. **No test asserts `lift` by literal where it should read the constant.** The issue's shape
   section anticipates one; there is none. `RecommendCliTest` line 29 reads
   `Recommendations.DEFAULT_SCORER` and line 94 reads its `spelling()`. Every remaining
   `Scorer.LIFT` in `src/test` is a scorer passed **explicitly** to a sweep, a report or a CLI
   (`CandidateSweepTest`, `RoutesTest`, `RecommendationReportTest`, `RecommendRunTest`,
   `MergedIdIsOfferedOnceTest`, `MergeDoesNotInflateDegreeTest`) or an invented `Setting` in the
   evaluate tests (`ReadingTest`, `ScoringTest`, `SuppressionIsPurelySubtractiveTest`,
   `EvaluationReportTest`). None of those is about the default and none of them may be changed to
   read the constant — a test that pins `lift`'s own arithmetic must name `lift`.

4. **`RateRunTest`'s pairing guard asserts less than its fixture's javadoc claims.**
   `oneObscureAndOneFamous`'s javadoc says the graph "separates lift from every other point on the
   dial"; the test compares the default's top candidate against `Scorer.RAW` alone. The claim is
   true of that fixture and the assertion does not check it, so a fixture change that broke it
   would report clean. This design closes that gap while it is in there anyway (§4).

---

## 2. The decision, and what it is not

The eighth reading refused to move the constant, correctly, under a rule that was fixed before the
number existed. This issue moves it anyway, as a decision, and the amendment says so in those words.
The rule is **not** amended, **not** re-read "in the spirit of", and **not** given an exception
clause. From here it applies unchanged, with the new default as "the shipped scorer" — a phrase the
rule spec already resolves by reference to the shipped constant rather than by naming a scorer, so
nothing in the rule needs to change for it to keep meaning what it says.

`Recommendations.MIN_CANDIDATE_DEGREE` does not move. That is the issue's instruction and it is also
#245's rule (at most one constant moves).

---

## 3. What goes red, and what stays green by construction

Moving the constant is a one-line change with a very small blast radius, because #244 made every
consumer read it.

**Green by construction, and each is a property to state rather than assume:**

- `RecommendCliTest.theTwoPathsAreAllItNeeds` — asserts `options.scorer()` equals
  `Recommendations.DEFAULT_SCORER`. It pins the pairing between the flag's default and the constant,
  and would still red if `RecommendCli.parse` grew a literal of its own.
- `RecommendCliTest.shouldSpellTheDefaultScorerFromTheConstantWhenItRefusesAnything` — same, for the
  usage sentence.
- Every test that passes a scorer explicitly (finding 3 above).
- `RateRunTest.ratingsMoveTheCandidates`. Its `twoCandidates` fixture puts `BELOVED` and `CROWDED` at
  the same degree behind intermediates of identical degree, so the only thing separating them is how
  many known entities reach them and what those entities are worth. Under equal regard the crowded
  one wins on any scorer in the dial; under `ratings()` the beloved one wins on any scorer in the
  dial, because every scorer scales the same per-route weight. Verify by running it, not by trusting
  this paragraph.
- The evaluate harness. `Setting.GRID` sweeps every `Scorer` and nothing in `evaluate` reads
  `DEFAULT_SCORER`; `grep -rn 'DEFAULT_SCORER' src/main/java/com/robsartin/segue/evaluate` is empty.

**Red:** `RateRunTest.shouldDealTheRecommendersTopCandidateWhenTheScorersDisagree`, and only it. It
is the guard that pins the deck's sweep to the recommender's default (#244), and it is red for a
reason that is the point of the guard: its fixture was built to separate `lift` from the other
scorers, and it cannot separate `resource-allocation` from anything.

### Why the shipped fixture cannot discriminate the new default

`oneObscureAndOneFamous` reaches `OBSCURE` from the three `LOVED` seeds and `FAMOUS` from the six
`LUKEWARM` seeds, each through its own intermediate, every intermediate at degree 2; `OBSCURE` is
padded to the floor and `FAMOUS` to sixty. Every route carries the same weight `w`
(`CandidateSweep.over` multiplies the same two edge weights and the same equal regard), so with
`d = 2` for every intermediate:

| scorer | OBSCURE | FAMOUS | top |
| --- | --- | --- | --- |
| `raw` | `3w` | `6w` | FAMOUS |
| `adamic-adar` | `3w / ln 2` | `6w / ln 2` | FAMOUS |
| `resource-allocation` | `3w / 2` | `6w / 2` | FAMOUS |
| `lift` | `3w / ln 2 / 5` | `6w / ln 2 / 60` | OBSCURE |

The three non-normalising scorers differ only in how they discount the **intermediate's** degree
(`Scorer.score`: `total += weight / discount(viaDegree)`), and this fixture gives every intermediate
the same degree — so the discount is a constant factor and all three rank identically, always. A
fixture with one intermediate degree can never separate `raw` from `adamic-adar` from
`resource-allocation`. That is the structural reason the rebuild is not cosmetic.

---

## 4. The fixture that discriminates `resource-allocation`

**The discriminating property.** `resource-allocation` is the only point on the dial whose discount
is the intermediate's degree itself. So the fixture must vary the intermediate's degree, and must
put the two candidates on opposite sides of the ratio that `x` and `ln x` disagree about:

- one candidate reached by **few** seeds through **quiet** intermediates,
- one reached by **more** seeds through **busier** ones,
- both candidates at the **same** degree, so `lift`'s divisor is a constant factor and `lift`
  follows `adamic-adar` rather than making a third answer.

With `n` seeds through intermediates of degree `d`, and every route at the same weight `w`:
`raw = nw`, `adamic-adar = nw / ln d`, `resource-allocation = nw / d`, `lift = adamic-adar / D`
where `D` is the shared candidate degree. Choosing `n = 3, d = 3` against `n = 6, d = 7`:

| scorer | quietly reached (3 seeds, vias at degree 3) | busily reached (6 seeds, vias at degree 7) | top |
| --- | --- | --- | --- |
| `raw` | `3w` = 3.000 `w` | `6w` = 6.000 `w` | busy |
| `adamic-adar` | `3w / ln 3` ≈ 2.731 `w` | `6w / ln 7` ≈ 3.083 `w` | busy |
| `resource-allocation` | `3w / 3` = 1.000 `w` | `6w / 7` ≈ 0.857 `w` | **quiet** |
| `lift` | `2.731w / 12` ≈ 0.2276 `w` | `3.083w / 12` ≈ 0.2569 `w` | busy |

`resource-allocation` is alone in preferring the quietly reached candidate, by about 14 % of the
larger score; the closest of the three margins on the other side is `adamic-adar`'s and `lift`'s,
about 11 % each. Nothing here is near a tie and nothing depends on floating-point luck.

**Why the numbers are what they are.** The pair `(3, 3)` against `(6, 7)` is chosen so the seed
counts stay `LOVED` and `LUKEWARM` unchanged (three and six), and so the count ratio 2 sits strictly
between `ln 7 / ln 3 ≈ 1.77` — the ratio Adamic-Adar needs to prefer the busy side — and `7 / 3 ≈
2.33`, the ratio resource allocation needs to prefer the quiet side. That interval is the whole
mechanism; `d = 8` also fits but leaves `adamic-adar` a 5 % margin instead of 11 %.

**Five things the fixture must not accidentally do**, each derived from the code rather than assumed:

1. **A busy intermediate must not be excluded as a hub.** `CandidateSweep.over` drops any
   intermediate `PathRanking.isHub` answers yes to, and that is a *busy `CONCEPT`* or a node stating
   a recognition-institution class. `reaches` mints intermediates as `NodeKind.PERSON`, so degree
   does not make one a hub at any value. Safe.
2. **Filler nodes must not become candidates.** `padDegreeTo` mints fillers as `NodeKind.WORK`, and
   `CandidateSweep.couldBeExplored` keeps only `PERSON` and `GROUP`. Safe at any degree.
3. **Fillers shared between two padded nodes must not matter.** `padDegreeTo` derives its filler
   qids from `already = graph.edges(qid).size()`, so two nodes with different starting degrees pad
   through overlapping ranges. It is harmless here: a filler is never one hop from a seed, so it is
   never an intermediate, and it is `WORK`, so it is never a candidate. Only its own degree changes,
   and nothing reads that. **The helper is therefore left exactly as it is** — no new parameter, no
   per-node range, no churn in the four other tests that call it.
4. **The intermediates must not become candidates.** An intermediate's neighbours are its seed (on
   the known-list, excluded), its candidate, and its fillers. No intermediate is two hops from a
   seed, so none is offered. Safe.
5. **Both candidates must clear the floor.** Both sit at 12, above `MIN_CANDIDATE_DEGREE`. The
   fixture pads to a named constant rather than to the floor, because 12 has to exceed the busy
   candidate's own six route-edges and the point of it is that the two are *equal*, not that they
   are at the floor.

**The guard's assertion becomes what its javadoc always claimed.** Instead of comparing the default's
top candidate with `Scorer.RAW`'s, it compares it with **every** other `Scorer.values()` entry. That
makes the check non-vacuous by construction and self-maintaining: a fifth scorer added to the enum
joins the comparison without anybody remembering to add it, and a fixture edit that let any scorer
agree with the default reds here instead of reporting clean forever. The deck half of the assertion
follows: the deck contains the default's top candidate and none of the others' tops.

---

## 5. The order the changes have to land in

**The fixture and the constant cannot be separated into two green commits, and this is not a choice.**
With the old fixture and the new constant the guard is red (§3). With the new fixture and the old
constant it is red too: under `lift` the new fixture's top candidate is the busily reached one, which
is also `raw`'s and `adamic-adar`'s, so the discrimination assertion fails. Only the pair is green.
Mikado's rule is green at every *committed* step, and one commit carrying both satisfies it.

That gives the RED→GREEN loop its shape, and it is a real one rather than a re-labelled cascade:

1. **RED.** Rebuild the fixture and generalise the assertion. The test now says *the shipped default
   is the one this graph separates from every other point on the dial*. Run it: it fails on that
   assertion, with the message the guard already carries about rebuilding the fixture if the default
   has moved.
2. **GREEN.** Move the one constant.
3. **Control.** Plant `Scorer.LIFT` back into the constant, watch the same assertion fail, remove the
   plant.
4. **Second control.** Plant a `Scorer.LIFT` literal into `RateRun`'s sweep call — the exact
   divergence #244 exists to prevent — and watch the *deck* half of the assertion fail on a graph
   where the default and `lift` disagree. Remove the plant.

The doc cascade rides in the same commit: a commit that moves the default while the developer guide
still explains the shipped ranking as `lift`'s is the drift this repository writes ADRs to avoid.

---

## 6. Every sentence that has to follow the constant

`src/main`:

- **`Recommendations.DEFAULT_SCORER`** — the value, and the first paragraph of its javadoc. "**Measured,
  not chosen.**" is now false of this constant: it was measured, then it was decided against the
  measurement's own rule. The replacement says that in one sentence and cites ADR 45's 2026-09-07
  amendment for issue #291 as the authority for the reasoning, restating none of it. The "one copy,
  because two tools apply it" and "it is a default and not a verdict" paragraphs are untouched and
  both stay true.
- **`Recommendations.MIN_CANDIDATE_DEGREE`** — one sentence appended, no change of value. Its
  argument opens "a floor is not optional under a normalised score", and the shipped default is no
  longer a normalised score. Leaving it would leave a constant explained by a property the shipped
  tool does not have. The sentence records that the floor is kept, that `--scorer lift` is one flag
  away and still needs it, and that whether this floor is the right one for the new default is a
  question for a reading rather than for this issue.
- **`Scorer.LIFT`'s javadoc** — "The measured default:" is the phrase the issue names. It becomes a
  statement about what ADR 45 measured and shipped, in the past tense, pointing at the amendment for
  why it is no longer the default. The rest of the sentence — what the normalisation buys — is ADR
  45's finding and stands.
- **`Scorer.RESOURCE_ALLOCATION`'s javadoc** — gains the shipped-default sentence, pointing at
  `Recommendations.DEFAULT_SCORER` and the amendment.
- **`Scorer`'s class javadoc** — "Why a dial and not simply `{@link #LIFT}`" now reads backwards: it
  offers `RESOURCE_ALLOCATION` as what another domain "may well want", and it is what this domain
  ships. Minimally reworded so the paragraph still makes the dial's argument — the failure mode at
  each end is real — without asserting a shipped value it no longer describes. The "second knob is
  the finding" paragraph above it is ADR 45's measurement and is **not** touched.
- **`RecommendCli`** — nothing. Derived (finding 2).
- **`RateRun`** — nothing. Derived (finding 1).

`src/test`:

- **`RateRunTest`** — the fixture, its constants, its javadoc, and the guard's assertion (§4).
- **`MergedIdIsOfferedOnceTest`**'s class javadoc calls `lift` "the shipped `lift`" while the test
  itself passes `Scorer.LIFT` explicitly at line 113. The word "shipped" becomes false; the figures
  it quotes were measured under `lift` and stay, because the test still runs under `lift`. One word
  changes.
- **`MergeDoesNotInflateDegreeTest`** passes `--scorer lift` on the command line and its javadoc
  names `Scorer.LIFT` as the thing that divides by degree. Both stay: the test is about that
  arithmetic and pins the scorer explicitly.

`docs/developer-guide.md`:

- The "What to explore next" code block's comment calls both constants "the measured defaults". It
  names them by reference, so only the word "measured" is wrong. The `./gradlew` lines in that block
  are not edited (`GuideExamples` parses lines of that shape elsewhere in the guide, and a changed
  flag there is a changed example).
- **"The score, in one formula"** — the paragraph after the formula explains the ranking with the
  candidate-degree normalisation and then derives the floor from it. Both sentences describe `lift`.
  They gain a sentence naming which point is shipped and stating that it does not divide by the
  candidate's degree, and the floor sentence is qualified to the scorer it is an argument about. The
  enumeration of the four spellings is unchanged and still correct.
- **"Expanding a top candidate demotes it"** — the section's mechanism is `lift`'s divisor, and the
  measured evidence behind it (issue #117) was taken under `lift`. It gains the qualification: the
  warning applies to `--scorer lift` runs; the shipped default does not divide by the candidate's own
  degree, so expanding a candidate does not lower its score by that route — which is arithmetic from
  `Scorer.score`, not a measurement, and the section says which it is. It also gains the pointer to
  the amendment, because this section's mechanism is exactly why the expansion moved the default.
- The merge chapter's "which `lift` divides by (ADR 45)" is left alone: it explains why
  `MergeDoesNotInflateDegreeTest` guards what it guards, and that test pins `lift` explicitly.
- `docs/user-guide.md` and `README.md` name no scorer. Nothing to do; the plan greps to prove it
  rather than asserting it.

`docs/adr`:

- ADR 45 gains the amendment of §7. **Its title, its front matter and its Decision section —
  including "defaulting to lift" — are not edited**, and neither is ADR 50's sentence calling `LIFT`
  "the measured default". Both are history, and the amendment names them as history. `docs/adr/README.md`
  is untouched: no ADR's number, title or status changes, which is all `AdrIndexTest` compares.
- **No commit hash anywhere in the amendment.** `AdrCitationsTest` reds on any new backticked
  seven-to-forty-character hex run in `docs/adr/`. Pull requests and issue numbers are cited instead.

---

## 7. The amendment

Appended to the end of `docs/adr/0045-recommend-by-normalised-lift-with-routes.md`, after the
2026-09-07 amendment for issue #289, verbatim:

```markdown
**Amendment (2026-09-07, issue #291): the default scorer moves to `resource-allocation`. A decision
taken outside the rule, on the graph the expander left, with the eighth reading as its evidence and
its post hoc position on the record.**

Nothing above is withdrawn and no decision above is edited, including the eight amendments
immediately above this one. This ADR's title and its Decision section's "defaulting to lift" are
history: they record what was decided, and measured, on a graph that no longer exists, and they are
not edited to match what the code now does.
[ADR 50](0050-suppress-a-candidate-you-have-rejected.md)'s sentence naming `LIFT` "the measured
default" is history in the same way. What changes is one constant,
`Recommendations.DEFAULT_SCORER`, and every sentence in the tree that named its value.

**This is a decision, and it is openly post hoc.** The rule of issue #245 was written so that no
single reading could move a constant on a near miss, and on the eighth reading — the amendment
immediately above, for issue #289 — it refused to move this one. That refusal was correct and it is
not overturned here: no clause is edited, no threshold is softened, and nothing below re-reads the
rule "in the spirit of" anything. What is recorded here is a decision taken outside it, with the
eighth reading as evidence, and with the admission that amendment's own last section demanded — a
rule written today cannot be blind to the table it would be written after. Deciding in the open is
the honest form of that; writing a new rule that happens to reach the answer already known is not.

**What the eighth reading showed.** The amendment immediately above is the authority, its table is
quoted there once, and no cell or figure of it is restated here. By reference: at the shipped floor
every other scorer in the grid is above the shipped scorer's hit rate for the first time, the best
of them is `resource-allocation`, and it fell short of the rule's margin by less than a point while
clearing the rule's dominance condition at every floor in the derived range. Its two halves, split
by rating age, land near each other where the shipped setting's now miss both nearly alike. That
amendment also records the hazard this one acts on: the rating deck deals its candidate cards from
the shipped setting, so what the table says about that setting is what the deck deals until a
decision is taken.

**Why the expansion did this to `lift` and to no other point on the dial.** `Scorer.score` divides
by the candidate's own degree for exactly one point: `normalisedByCandidateDegree` is true for
`LIFT` alone. `Scorer`'s own javadoc says of it that it "rewards a thin entity whose whole presence
in the graph is a list of influences", which is why this ADR paired it with a degree floor rather
than shipping it alone. The promotion expander
([ADR 66](0066-expand-every-promotion-from-a-dev-tool.md), issue #284) added neighbours to every
promotion; the census on that issue is the authority on what it added. A thin node adjacent to
several of the owner's highest-rated entities is precisely what that divisor rewards, and it is
precisely what the three scorers that do not divide by the candidate's degree do not reward. The
eighth amendment reached this explanation from the aggregates and declined to rule on it. This
amendment acts on it, and names it as the reason it acts rather than as a finding the table
establishes.

**Alternatives, each rejected, with the reason each lost.**

- **Keep `lift` and raise the floor to twelve.** The eighth reading leaves `lift` at the highest
  floor in the grid still some points behind the best challenger at the shipped floor, so this buys
  back less than the move does. It also spends the harness's *other* knob to repair the first, and
  #245's rule allows one constant to move; spending it on the floor leaves the scorer question open
  and nothing left to move next time.
- **Keep the setting and rate through it.** The deck deals from the shipped setting
  ([ADR 46](0046-the-rating-deck.md), `RateRun`), so this spends a deck session rating the top
  twenty-five of a setting that the reading shows finding almost nothing at the shipped floor. The
  eighth amendment recorded that hazard in as many words so the owner would not rate through it
  unknowing; reading that and doing it anyway is the one option the record forbids.
- **Retract the expansion.** Reach rose for every scorer on that reading, so the expansion improved
  the instrument's coverage rather than damaging it. And the log is append-only by design
  ([ADR 24](0024-sqlite-assertion-log.md), [ADR 44](0044-retraction-as-a-new-claim.md)): retracting
  is a new claim, not an undo, and there is nothing here worth spending one on.
- **Write a new rule and take a ninth reading.** The harness is deterministic — an unchanged
  database prints the same block, which is `HeldOut`'s design and the eighth amendment's own
  consequence — so a ninth reading taken before anything moves is the eighth reading. A rule written
  now would be written by somebody who has read that table. That is a decision wearing a rule's
  costume, and it is worse than a decision that says what it is.

**What this costs, and it is a real cost.** This ADR chose `lift` for one property: connected to you
more than its size predicts — surprise rather than popularity — and that property is given up at the
default. `resource-allocation` discounts the busy intermediate and nothing else, and this ADR's own
argument against stopping there is not withdrawn: a candidate connected to everything shares its
intermediates with everything. What the eighth reading says is that on the graph as it now stands,
the point that keeps the property finds almost nothing at the shipped floor and the point that gives
it up finds the most. The property is one flag away — `--scorer lift` at the unchanged floor is
exactly the setting shipped until today — which is why `--scorer` exists, and the honest way to
disagree with this amendment is to run both and read the two lists.

**What it does to the harness, and what the ninth reading must say.** The deck's sweep scores with
`Recommendations.DEFAULT_SCORER` by reference (issue #244), so from the owner's next session the
deck deals `resource-allocation`'s top twenty-five. The population the harness reads is what the
owner has rated, so the bias issue #272's amendment named — the deck's own cards shaping the
population the next reading judges — does not go away with this move; it moves with it. **The ninth
reading is the first taken after a deck session dealt from this default, and its note must say so
before the reading exists.** `Setting.GRID` and `HeldOut.EVERY` are unchanged, and
`evaluate` reads no default at all: it sweeps every scorer, so the harness needed no edit for this.

**The rule of issue #245 applies unchanged from here.** "The shipped scorer" is whatever
`Recommendations.DEFAULT_SCORER` holds, which is now `RESOURCE_ALLOCATION`; the shipped floor is
unchanged. Every clause, the fifteen-point margin, the dominance range, the void check, the
negatives deciding nothing, at most one constant moving, and a near miss standing — all as written.
This amendment is not a precedent for deciding outside the rule a second time: it records one
decision, the reason for it, and the fact that the rule declined to make it.

### What this does and does not establish

- **It does not establish that `resource-allocation` is the best scorer**, and no reading has said
  so under the rule. It establishes that the owner moved the default on the evidence of one table,
  knowing the rule refused to.
- **It does not establish why the shipped rate fell.** The explanation above is an explanation the
  aggregates are consistent with and an argument from `Scorer.score`'s arithmetic; it is the reason
  for the decision, not a finding.
- **It changes what the next reading is a reading of.** The deck deals from the new default from the
  next session, so the ninth reading's population is shaped by it.
- **It says nothing about the floor.** `Recommendations.MIN_CANDIDATE_DEGREE` is unchanged and
  unexamined here. Whether the floor measured for a normalised scorer is the right floor for one
  that does not normalise is a question for a reading, and it is not this amendment's.

### Consequences of this amendment

- **One constant moves.** `Recommendations.DEFAULT_SCORER` becomes `Scorer.RESOURCE_ALLOCATION`.
  `RecommendCli`'s `--scorer` default, the usage sentence it prints and `RateRun`'s sweep all read
  that constant (issue #244) and follow it without being edited — which is the property #244 bought
  and this amendment is the first to spend.
- **The ranking, the deck and the recommender's output all change**, together and by construction.
  `RateRunTest`'s guard is what holds them together: it reads the constant, and its fixture
  discriminates the shipped default from every other point on the dial.
- **`Recommendations.MIN_CANDIDATE_DEGREE`, `Setting.GRID` and `HeldOut.EVERY` are unchanged.**
- **This is unit-testable, and it was tested that way.** The guard's fixture was rebuilt to separate
  `resource-allocation` from every other scorer, seen red on the old constant, seen green on the
  new, and seen red again with the old constant planted back and with a `lift` literal planted into
  `RateRun`'s sweep. The rest of the gate — `AdrIndexTest`, `AdrCitationsTest`,
  `DocumentationLinksTest` for the relative links above, and `javadoc -Werror` inside
  `./gradlew check` — covers this amendment itself.
- **No commit hash is cited above.** The ordering facts this amendment leans on are the eighth
  reading's, and pull request #290 carries them.
- **The ninth reading is a follow-up issue**, taken after the owner's next deck session, with a note
  fixed before it that records the deck now deals from this default.
```

---

## 8. Alternatives considered for this design (not for the decision)

- **Split the fixture rebuild and the constant move into two commits.** Impossible while keeping
  every committed step green (§5), and faking it — a temporary second assertion, or an `@Disabled` —
  would be a broken guard on `main` for the length of one commit, which is exactly the window #244's
  guard exists to close.
- **Keep the fixture and weaken the guard to "the deck agrees with the recommender".** That is the
  vacuous version: with both sides running the same constant it passes on any fixture, including one
  where every scorer agrees. The guard's value is entirely in the fixture's power to discriminate.
- **Add a `fillerRange` parameter to `padDegreeTo` so no two padded nodes share a filler.** Not
  needed: fillers are `WORK` and never one hop from a seed, so sharing one changes no score (§4.3).
  A parameter added ahead of a need is the thing the working agreement names.
- **Move the floor as well, to twelve.** Rejected by the issue, by #245's rule, and recorded as a
  rejected alternative in the amendment rather than as a decision this design may take.
- **Retire `LIFT` from the enum.** Nothing asks for it, several tests pin its arithmetic on purpose,
  and the amendment's cost paragraph depends on it remaining one flag away.

## 9. Out of scope

The floor. The deck's candidate order. A new scorer. Any change to `evaluate`, to `Setting.GRID`, or
to the rule spec of issue #245 — including appending a note to it, which belongs to the ninth
reading's issue and must be dated the day that reading is taken. The ninth reading itself.
