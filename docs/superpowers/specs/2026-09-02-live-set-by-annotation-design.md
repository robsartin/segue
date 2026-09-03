# The guide's live-test set is derived from the annotation, not the text

Issue #209. Written 2026-09-02.

## The defect

`DeveloperGuideEnumerationsTest` derives the guide's "Live, tagged and excluded" row by searching test
*sources* for the string `@Tag("live")`. A non-live class whose comment quotes that string is counted
as live (#167's `MusicBrainzProbeTest` hit it and reworded a comment rather than write a false row);
a live class tagged through a constant or an imported name would be *missed*, and the row would
silently omit it — the "grep narrower than the claim" shape. #165 already replaced a text derivation
with ArchUnit's class graph for the dev-tool packages; this is the same move one test over.

## The decision

**Derive the set from the compiled classes**: ArchUnit's `JavaClasses` (imported as `ArchitectureTest`
and `PackageListsTest` do) selecting classes annotated with `@Tag` whose value is `live`, at class or
method level, including meta-annotations if any exist (measure). The guide row is compared to that set
as today. `probe`-tagged classes (#167) are live too and appear in the row; the row's prose says which
tag excludes them from `liveTest`.

**Positive controls in both directions, definition of done:** a comment quoting `@Tag("live")` in a
non-live class does not enrol it (red on today's derivation, green after); a live class tagged through
a constant (`@Tag(LIVE)`) is enrolled (red on today's, green after); a live class removed from the
guide row → red naming it (the existing direction, kept). Vacuity: the set is non-empty.

## Rejected

- **Tighten the regex.** A regex over source is the defect; the class graph is the fact.
- **Both derivations, cross-checked.** Two instruments that can disagree with no third to arbitrate.

## Recorded

No ADR; ADR 32's principle (the tree is the list, the test the check) is applied one more time.
