# Javadoc joins the gate, and the tests it cites are checked to exist

Issue #195. Written 2026-09-02.

## The defect, measured on 2026-09-02

`./gradlew javadoc` is not part of `check`, and it already fails: two `error: invalid use of @param`
in `own/OwnCli.java` (lines 93 and 102), where the `Options` sealed interface's javadoc carries
`@param database` and `@param dryRun` — an interface has no parameters, so the tool rejects them. The
prose under them is the `--db` rule and the #179 story; it is worth keeping, in a shape javadoc
accepts. CI runs `./gradlew --no-daemon check`, so nothing in CI sees any of this.

Beside the two errors the tool prints **100 warnings**, and the histogram matters: 99 are
`no @param for <record component>` and one is `no main description` — all in doclint's `missing`
group. With `-Xdoclint:-missing` the output is exactly the two `OwnCli` problems and nothing else.
The `reference`, `syntax`, `html` and `accessibility` groups are clean.

A second fact the issue names: main-source javadoc cites the tests that enforce its rules as
`{@code ArchitectureTest.theExporterOnlyReads}`-style text, because `{@link}` cannot reach the test
source set. Measured by a per-line grep: 31 citation sites. **Corrected 2026-09-02 by the test itself,
which reads whole javadoc spans: 51 sites, 37 distinct, 32 naming a member** — twenty citations are
formatter-wrapped across lines with a `*` inside the span, and a per-line instrument was blind to them
(the "grep narrower than the claim" defect, in the instrument that was measuring for it). Every one
resolves to a real test class and member today. Nothing keeps that true; a renamed rule drifts silently in every
place it is named — the shape this repo keeps finding.

## The decision

Three pieces, each with a positive control.

1. **`javadoc` joins `check`, strict except for `missing`.** `tasks.javadoc` gets
   `-Xdoclint:all,-missing` and `-Werror`; `tasks.check` depends on it. Excluding `missing` is a
   decision, stated in the build comment: 99 undocumented record components exist today, and
   requiring an `@param` for every record field is a separate choice this issue does not take.
   Everything else — a bad `{@link}`, malformed HTML, a misplaced tag — fails the gate. The two
   `OwnCli` errors are fixed by turning the interface's `@param` blocks into paragraphs; no sentence
   is lost. Controls: plant `{@link NoSuchClass}` in a main javadoc → `check` reds in `:javadoc`
   naming file:line; plant a record with an undocumented component → stays green (the exclusion,
   quoted); a `{@code Options}` `@param` put back → red. The guide's "What `./gradlew check` actually
   runs" list gains the fifth item and loses its count word.

2. **`JavadocCitationsTest` in `arch`.** Every `{@code …Test…}` in `src/main` javadoc — with or
   without a package prefix, member joined by `.` or `#`, optional `()` — must name a class under
   `src/test` and, when a member is named, a method or static field declared there. Anything
   `{@code …Test…}`-shaped the strict pattern does not consume is a red naming the line, not a skip
   (the #183/#168 recogniser rule). Vacuity guard: at least one citation site, no count. Failures
   name file:line and what was not found. Control: rename one cited `ArchRule` in a scratch edit →
   red naming the citing file and the rule; plant `{@code ArchitectureTest theRule}` → "unsupported
   citation shape".

3. **ADR 34 gains a dated amendment**: a fifth gate, why `missing` is excluded, what was rejected.

## Rejected

- **Fix the two errors and stop.** Leaves `javadoc` outside the gate; the next error is invisible
  again by next week.
- **Add `javadoc` to `check` with defaults.** 100 warnings on every run bury the next error under
  noise; `-Werror` with the `missing` group on would mean documenting 99 record components first,
  which is a different piece of work and not this issue's.
- **Put the test source set on `:javadoc`'s classpath so `{@link}` reaches tests.** Publishes test
  types into the API documentation's link space and inverts the dependency direction ADR 32
  enforces; the citation test gives the same guarantee without it.
- **Derive the guide's `check` list from `build.gradle.kts`.** A Gradle-DSL parser for one list of
  five; the list is rewritten count-free instead.
- **Also check the ~110 `{@code MainClass.member}` citations of main classes.** Those can become
  `{@link}` and be compiler-checked by the gate this issue adds; converting them is a follow-up.

## Recorded

ADR 34's amendment. No new ADR.
