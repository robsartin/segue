# Labels folds retractions

Issue #181. Written 2026-09-01.

## The gap

`ratings/Labels` states an invariant in its own javadoc:

> **Last claim wins**, matching `GraphStore.upsertNode` and the boot replay, so a label here is the
> label `get_entity` would return.

That is false for a retracted entity. `listRatings` prints a confident label for something
`get_entity` reports as gone.

The awkward part is not that nothing was folded. `forQids` **already builds** `Retractions.in(logged)`
and already uses it — in exactly one place, deciding whether a `SameAs` merge survives. It never asks
whether the `NodeAssertion` or `LocalEntity` that names the entity was itself retracted. The object
that answers the question is constructed one line above the loop that ignores it.

*(The issue body claims `Labels` holds no `Retractions` reference at all. That was true mid-branch
during #92 and false by the time #92 merged; corrected in a comment on the issue.)*

## The decision

**Apply `Retractions.survives` to the claims that name an entity, exactly as `GraphProjector` does.**

`GraphProjector:70` is the precedent and the shape to copy:

```java
if (!retractions.survives(i, assertion)) {
  continue;
}
```

Three consequences follow from `survives` being **per-assertion**, and none of them needs new logic:

- A retracted entity contributes no label, so its row falls to `AffinityRow.NO_LABEL`.
- **A claim made *after* the retraction still counts.** Re-claiming an entity restores its name here,
  because `survives` compares the claim's index against the last retraction of that qid. This comes
  for free; it is not a case to special-case.
- A retraction *before* a claim does not suppress it, for the same reason.

## What a retracted row shows

`AffinityRow.NO_LABEL` — `"(not in the graph)"` — unchanged, and no new string.

Its javadoc already says it exists for *a rating that outlived its node*. A retraction is precisely
that, and the case it was written for. The irony worth recording: #92 had to remove `NO_LABEL` from
two situations it did not describe (a rated minted entity, and a carried canonical row — both of
which *were* in the graph), while the situation it does describe never reached it.

**Rejected: a distinct `(retracted)` marker.** The log can tell a retracted qid from one nothing ever
claimed, and a retraction was the owner's own decision, so naming it would be more informative. It
loses on cost: a second string, a second meaning to explain, and `NO_LABEL` left covering only the
rarer never-claimed case. One string with one meaning is worth more than the distinction here.

## What does not change

- **Both rows still list after a merge.** `IdentityMerge.carryingRatings` moves the score and never
  the note, so the owner's own words survive only on the local row, and `listRatings` is the one tool
  that reads a note (ADR 43). That is settled and is not what this issue is about.
- **The merge fold's own retraction check** stays where it is.
- **The kind is still not re-derived** (ADR 42).
- **No new ADR.** This restores an invariant the code already claims; it decides nothing new. ADR 44
  governs retraction, ADR 41 the read-from-the-log choice, ADR 43 the note. The `Labels` javadoc,
  however, currently asserts the invariant as though it held — it must say what is true.

## Testing

Each behaviour driven by a test seen to fail first:

- A rating on a retracted entity lists as `(not in the graph)`.
- A rating on an entity **re-claimed after** its retraction lists under the new label.
- A rating on a never-retracted entity is unaffected — the case that must not regress.
- The merge fold still works, and a retracted *merge* still does not carry a label.
- A retracted **local** entity (`LocalEntity`, not `NodeAssertion`) is folded too. Both claim types
  name an entity (#92), so a fold that handles only one is the same silent half-fix twice over.

The last is the one a narrow reading would miss, and it is exactly the shape #92 kept producing.
