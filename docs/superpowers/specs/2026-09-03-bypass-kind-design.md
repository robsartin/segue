# The stand-in takes the kind the fold gave the node it stands in for

Issue #222. Written 2026-09-03, against `main` at `2e01341`.

## The defect, measured

[ADR 59](../../adr/0059-owner-claims-as-a-third-layer.md)'s amendment records this as the first of
its residuals: *"the stand-in's kind is taken as the claim stated it on the bypass path"*.
`Equivalences.localsOfMerges` reads a merge's local side through `claim.toNode()`
(`Equivalences.java:246`) and `Equivalences.standIns` copies `local.kind()` out of it
(`Equivalences.java:183`), while both folds re-derive the *same claim*'s kind through
`KindMapper.rederive` before they hold it — `LogProjection.java:129` and
`GraphProjector.rederived` (`GraphProjector.java:107-109`), the shared rule ADR 42 put in one place.
So one entity ends the fold under two kinds.

Measured on `2e01341` with a two-test probe over an invented log — a bypass `NodeAssertion` naming
the local id `Q004` as `WORK` while stating one class the whitelist does not know, then
`SameAs Q004 → Q10000900108` — run as `./gradlew test --tests
'com.robsartin.segue.export.ProbeKindTest'`:

| assertion | fold | result |
|---|---|---|
| the local node was re-derived (control) | exporter | **passes** — `CONCEPT` |
| the stand-in's kind equals the local node's | exporter | `AssertionFailedError`, `expected: CONCEPT but was: WORK` |
| the local node was re-derived (control) | boot replay | **passes** — `CONCEPT` |
| the stand-in's kind equals the local node's | boot replay | `AssertionFailedError`, `expected: CONCEPT but was: WORK` |

`2 tests completed, 2 failed`. The controls are what make the two failures mean something: the fold
*did* re-derive `Q004` to `CONCEPT` in both places, and the canonical node beside it kept `WORK`.
Both folds are wrong in the same direction, which is exactly why `BothFoldsAgreeTest` is silent —
it compares the two folds to each other, and they agree.

The probe was reverted; the tree is clean.

### The class the fixture states, and why it is not `Q5`

The javadoc's own example is a bypass claim carrying `["Q5"]`, which re-derives to `PERSON`. That
would work, and it costs an edit to a file this change should not be touching: since issue #216
`StandInQidsDenoteNothingTest.ALLOWED` keys by **site**, so `Q5` in a new file is a new site and
reds until it is allowed there by name. An invented class id in ADR 58's leading-zero shape
(`Q0900109`, the next free number in `InventedGraph`'s own sequence) needs no allowlist entry at
all, and re-derives to `CONCEPT` — which `KindMapper.rederive`'s javadoc is explicit is a real
answer and not a fallback: *"When classes ARE stated, this list is the authority, including when it
answers CONCEPT."* The kind changes either way, which is the whole of what the test needs.

## Where the issue's account of the code does not hold

The issue offers as its first candidate: *"the fold re-derives the stand-in's kind from the local
node it already resolved"*. **Neither fold has resolved any node at the point the stand-in is
built.** That is not a detail; it is the reason this candidate cannot be taken as stated.

| fold | where the stand-in is built | what it has resolved by then |
|---|---|---|
| `LogProjection.of` | `LogProjection.java:115`, seeding `nodes` from `Equivalences.standIns(logged)` | nothing — the `for` over the log starts at line 123, so `nodes` holds only the stand-ins themselves |
| `GraphProjector.project` | `GraphProjector.java:86-88`, `store.upsertNode(standIn)` per entry | nothing — the store is empty; the replay loop starts at line 90 |

The pre-pass is *required* to run first, and `Equivalences.standIns`' own javadoc says why: an edge
claimed **earlier** in the log than the merge that names its endpoint arrives on the canonical id
before that merge's row, and `TinkerGraphStore.record` refuses an endpoint it has never seen. So
"copy from the node the fold resolved" is only reachable as a **post-pass** — a second visit after
the fold, which is a different design and is rejected below.

Everything else in the issue matches the code: `KindMapper.rederive` is the identity on a claim with
no classes (ADR 42), every `LocalEntity` is such a claim, `BothFoldsAgreeTest` cannot see the lag,
and `KindMapper` lives in `wikidata` where `domain` may not reach it.

### What `domain` may depend on

`ArchitectureTest.domainHasNoThirdPartyDependencies` restricts `..domain..` to `..domain..`,
`java..` and `javax..`. It is stricter than `noPackageCycles`, which the issue names: `domain` may
not reach `port` either, so a `port` interface for the re-derivation is out before
`noPackageCycles` is asked. What is left is a `java.util.function` type, and that is what the
design below uses.

## The change

**`Equivalences.localsOfMerges` and `Equivalences.standIns` take the re-derivation as a required
`UnaryOperator<NodeAssertion>` parameter, and each fold passes the `KindMapper::rederive` it already
applies to every node claim.**

```java
  public static Map<String, NodeRecord> standIns(
      List<LoggedAssertion> log, UnaryOperator<NodeAssertion> rederive) {
```

```java
  public static Map<Integer, NodeRecord> localsOfMerges(
      List<LoggedAssertion> log, UnaryOperator<NodeAssertion> rederive) {
    …
        case NodeAssertion claim -> claimed.put(claim.qid(), rederive.apply(claim).toNode());
```

`LogProjection.of` and `GraphProjector.project` each pass `KindMapper::rederive` at the one call
site they already have. `LocalEntity` claims are untouched — the operator is applied only on the
`NodeAssertion` arm, because the owner states a kind and no classes and `rederive` would be the
identity there anyway.

This is the rule the `localsOfMerges` javadoc already names as the only closer — *"only a rule that
moved re-derivation behind a port would close it"* — built with the seam the package rule permits.
`java.util.function.UnaryOperator` is a `java..` type, so `domainHasNoThirdPartyDependencies` holds;
no new cross-package import appears anywhere, because `export` and `ingest` both import `KindMapper`
already, so the guide's layering diagram is unchanged.

**Required rather than defaulted, and no overload.** That is this codebase's own idiom for a
parameter a caller must think about — `Equivalences.NONE` and `IdentityMerge.NONE` are both named at
the call site for the same reason. An overload defaulting to the identity would let a third fold
appear with the lag restored and nothing saying so.

### Measured green

The same probe, with the change applied and `EquivalencesTest`'s eight call sites passing
`UnaryOperator.identity()` (every fixture there states no classes, so identity *is* re-derivation
for them):

```
./gradlew test --tests 'com.robsartin.segue.export.ProbeKindTest' \
  --tests 'com.robsartin.segue.domain.EquivalencesTest' \
  --tests 'com.robsartin.segue.export.BothFoldsAgreeTest' \
  --tests 'com.robsartin.segue.arch.ArchitectureTest' --tests 'com.robsartin.segue.ingest.*'
BUILD SUCCESSFUL in 6s
```

`ArchitectureTest` includes `noPackageCycles` and `domainHasNoThirdPartyDependencies`; both pass.
No existing expected value moved: every fixture in the tree that reaches `standIns` states no
classes, so the operator is the identity on all of them. The probe was reverted.

## The test that sees it

A new file, `src/test/java/com/robsartin/segue/export/StandInKindMatchesTheLocalNodeTest.java`, and
deliberately **not** an addition to `BothFoldsAgreeTest`: this is not a question about the two folds
agreeing with each other. It is a differential *within* one fold — the stand-in against the node it
stands in for — asked twice, once of each fold. It lives in `export` because that is the package
that already reaches both `LogProjection` and `GraphProjector` + `TinkerGraphStore`, and it borrows
`InventedGraph`'s existing `BYPASS`/`STANDING` pair rather than widening `ownedLog()`, whose
expected values two sibling branches are working against.

Each test carries a control assertion — *the local node was re-derived* — beside the differential
one, on `BothFoldsAgreeTest`'s own precedent that two things which are both empty agree perfectly.
Without it, a change that stopped re-deriving anywhere would turn this test green.

**Positive controls, run after the change is green**, one per fold: replace that fold's
`KindMapper::rederive` with `UnaryOperator.identity()`, watch *that fold's* test red and the other
stay green, revert. Two plants, because one plant only proves one of the two assertions is
load-bearing.

A third test belongs in `EquivalencesTest`, in `domain`, with a stub operator (`claim ->
claim.withKind(NodeKind.PERSON)`) and no `wikidata` import: it pins that `standIns` applies the
operator to a `NodeAssertion` local side and leaves a `LocalEntity` one alone. That is the unit
statement of the rule, where the rule lives.

## Alternatives considered

- **The merge event carries the re-derived kind when it is written.** `SameAs` gains a `NodeKind`,
  set by `OwnRun` at the moment of the merge, and both folds read it. No package problem at all.
  **Lost, and not narrowly.** It writes a *derived* value into an append-only log, which is the
  precise thing ADR 42 and issue #60 exist to undo: before #60 the derived kind was the only thing
  kept, and every whitelist improvement needed the entity re-fetched (issue #55). A kind frozen into
  a merge row in September would be immune to every later correction, and a class *removed* because
  it was wrong would never reach it. It also does not fix the defect it was proposed for: every
  `SameAs` already in the log carries no kind, so the fold needs the fallback anyway and the lag
  survives on exactly the rows that already exist.

- **Copy from the resolved local node in a post-pass**, after each fold's loop. `LogProjection`
  could do it — its `nodes` map holds the re-derived local node by then, and rewriting the value in
  place keeps the stand-in's log-order position, which is how a later real claim about a canonical
  id already behaves. **Lost on the other fold.** `GraphProjector` writes into a `GraphStore` and
  cannot ask it which canonical nodes were stand-ins and which were claimed, so it would have to
  keep its own record of the pre-pass — a second answer to "which merges have a local side", which
  is the two-readings-of-one-log shape #178 spent a whole issue removing, and the exact way the two
  folds drifted before. Two post-passes, two rules, one `BothFoldsAgreeTest` that cannot see the
  difference between them.

- **The stand-in carries the local node's classes, so both folds re-derive it like any other node.**
  `NodeRecord` already has an `instanceOf` field and `localsOfMerges` already has the classes in
  hand. **Lost twice over.** Neither fold re-derives a `NodeRecord` — `rederive` takes a
  `NodeAssertion` — so each would need its own conversion, which is the post-pass problem again;
  and it would assert classes *about the canonical entity* that no source ever stated for it, which
  is what the existing comment at `Equivalences.java:179-180` refuses: *"a stand-in carries what it
  was given rather than inventing a class"*.

- **Move `KindMapper` into `domain`.** The rule stops being unreachable and every caller stays as it
  is. ArchUnit would even permit it: `domainValueTypesAreRecordsOrEnums` admits a class with only
  private constructors as a static registry. **Lost on what it puts there.** `KindMapper` is a
  whitelist of Wikidata `P31` class ids, grown from measurements against Wikidata (issues #49, #52,
  #87) and owned by the adapter that fetches them. Moving it makes source vocabulary visible to
  every domain type and inverts ADR 32's direction for a table that has nothing to do with the
  domain's own model — `NodeKind` is the domain's six kinds, and the mapping onto them is the
  source's business.

- **Accept and record, again.** The residual is already documented, this repository has shipped
  documented refusals that beat their fixes, and the path is unreachable from today's sources — no
  source can allocate a `Q00` id. **Lost for the reason the amendment itself gives when it declines
  that defence:** *"the premise that would make the lag unreachable … is exactly the premise spec
  ruling 2 declines to rely on"*. The fold already admits `NodeAssertion` on that path precisely
  because it will not assume the claim came through `OwnCli`; assuming it in the very next paragraph
  would be the same premise, load-bearing in one direction and disowned in the other.

## The four homes: which one this touches

ADR 59's second residual names four homes of the stand-in rule. **This change touches exactly one:
`Equivalences.standIns`** (through `localsOfMerges`).

- `IngestService.standIn` — **unchanged, and it is already the shape this fix gives `standIns`.** It
  copies `kind()` and `label()` off `graph.node(local)` (`IngestService.java:270-272`) — the local
  node as the reader that built it holds it. On the boot path it is a no-op: the pre-pass has
  already given the canonical id a node, so its `graph.node(canonical).isEmpty()` guard is false. On
  the live path it reads a graph that `IngestService.record` filled without re-deriving anything, so
  the local node and the stand-in carry the claimed kind *together*, and the next boot re-derives
  both. That live non-re-derivation is a separate, pre-existing property of ADR 42's placement
  (kinds are re-derived at projection), not something this change introduces, and nothing in
  production reaches it — `OwnRun` appends a merge through `claim()`, which has no graph half.
- `OwnRun.labelsInTheProjection` and `ratings/Labels.forQids` — **unchanged.** Both carry the
  stand-in's *label* and no kind at all; `Labels`' javadoc says so out loud (*"The kind is
  deliberately not re-derived … this is a list of names"*). The label is not what moves here.

## Documentation

- **`Equivalences.localsOfMerges` javadoc.** The paragraph beginning *"Node kinds are taken as the
  claim stated them, and on the bypass path that is a known lag"* (~lines 208-224) is the statement
  of the defect and is replaced by the statement of the rule: the caller supplies the re-derivation,
  each fold supplies the one it already applies, and the package rule is respected because the
  parameter is a `java.util.function` type rather than a `wikidata` one.
- **`Equivalences.standIns` javadoc**, the four-homes paragraph: one sentence saying that the kind
  now comes through the caller's operator while the other three homes are unchanged and why. This
  paragraph is also #220's subject — see below.
- **`LogProjection` and `GraphProjector` class javadoc**, the *"node kinds are re-derived"*
  paragraph in each: one clause saying the stand-in goes through the same rule.
- **`docs/developer-guide.md`**, the *"A merge is said, not done"* section, in the sentence that
  already says the canonical id gets a node built by `Equivalences.standIns`: the node carries the
  merged entity's label and the kind that fold re-derived for it.

## The ADR amendment

ADR 59 gets a **dated amendment in its own section at the end of the file**, headed
`**Amendment (2026-09-03, issue #222): …**`, closing the first of the residuals the 2026-09-02
amendment listed. The original decision and the 2026-09-02 amendment are **not edited** — not the
residual bullet, not a word of it. The new section says which residual it closes, quotes nothing
from the owner's real graph (all figures here are from an invented fixture, so ADR 51 does not
bite), states the rule, records the rejected alternatives above with the reason each lost, and says
what it does *not* settle: the live path's non-re-derivation, and whether the four homes should ever
become one caller (which is #220's subject and stays open).

Frontmatter is untouched, so `docs/adr/README.md` needs no row change and `AdrIndexTest` compares
number, title and status unchanged.

## Concurrency with #220 and #221

Both run on sibling branches off the same base and both touch `Equivalences.standIns` and ADR 59.

- **Keep the change to the smallest region.** One new test file, one new `EquivalencesTest` method,
  one new `InventedGraph` constant, two signature lines and one expression in `Equivalences`, one
  argument at each of the two fold call sites. `BothFoldsAgreeTest`'s `ownedLog()` is **not**
  widened; `StandInQidsDenoteNothingTest` is **not** touched.
- **The amendment goes in its own dated section at the end of ADR 59**, so a sibling amendment
  appended after it merges as an adjacent block rather than a conflicting edit.
- **#220 pins today's behaviour for the bypass case.** Its guard feeds all four homes a fixture that
  includes *"one whose local side is a bypass `NodeAssertion`"* and compares the stand-in's kind and
  label. If it lands first, the final task of this plan updates its expected kind for that case from
  the claimed kind to the re-derived one — and only that case; the `LocalEntity` cases and every
  label are unmoved, because `rederive` is the identity on a claim with no classes.
  **Say this to whoever reconciles it:** after this change, `Equivalences.standIns` and
  `IngestService.standIn` can legitimately answer differently for a *bypass claim read through the
  live path*, because the live path never re-derives the local node either. #220's guard must
  compare each home at the seam it can answer, and record that difference as the live path's rather
  than as drift.
- **#221 changes which merge names a stand-in** (`putIfAbsent` vs last-wins) and not what kind it
  carries, so the two are orthogonal; a rebase after it needs no expected value here to move.
