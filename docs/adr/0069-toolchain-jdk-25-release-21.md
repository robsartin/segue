---
status: Accepted
date: "2026-11-03"
topic: toolchain-jdk-25-release-21
tags: [language, java, build]
supersedes: []
related: [java-conventions, jvm-build-with-gradle]
---
# 69. Pin the toolchain to JDK 25, and keep compiling at release 21

## Context

[ADR 11](0011-java-conventions.md) requires targeting "a **current Java LTS** (21 unless the
project states otherwise), set via the Gradle toolchain", and its Consequences add that bumping
the LTS target later is "a deliberate, superseding decision". [ADR 9](0009-jvm-build-with-gradle.md)
requires the JDK to be pinned by a toolchain at all.

The toolchain is pinned at **25**. No ADR records that, and the 2026-11-01 drift sweep filed
issue #373 against the gap.

### The bump was never a bump

Before the commit named below, `build.gradle.kts` had no `toolchain` block. It set
`options.release` and compiled on whatever JDK the machine offered, so ADR 9's pin was unsatisfied
and ADR 11's "21" had nothing to be set *by*. The commit that supplied the missing pin — titled,
without irony, "Increment 0: build uplift — make the repo satisfy its own ADRs" — chose 25 rather
than the number ADR 11 names, in the same change that first committed the Gradle wrapper and the
CI `java-version`. The project therefore never moved from a pinned 21 to a pinned 25; the first
pin it ever had was 25, and the superseding ADR ADR 11 asks for was not written.

The choice does appear in the plan that change executed — `docs/plans/2026-08-24-increment-0-build-uplift.md`
states "Java toolchain 25, `options.release = 21`. Do not raise `release` in this increment." as a
global constraint, with no reasoning attached. A constraint is not a decision record, and the
difference is this file.

### When it landed, and why no pull request is cited

GitHub records that commit's committer date as **2026-08-24T19:52:43Z**. Derive the commit
yourself rather than taking a hash on trust: `git log -S'JavaLanguageVersion.of(25)' -- build.gradle.kts`
returns exactly one commit, and the same `-S` search over `.github/workflows/ci.yml` for the CI
pin returns the same one.

[ADR 1](0001-record-architecture-decisions.md)'s 2026-09-06 amendment requires a pull request
number *and* a push time, because a squash merge leaves a branch hash unreachable. Here the push
time is solid and **the pull request cannot be cited at all**, which is worth recording rather
than papering over:

- the commit's subject line ends `(#8)`, but pull request 8 in `robsartin/segue` is a Dependabot
  dependency bump merged about twenty minutes later;
- `GET /repos/robsartin/segue/commits/{sha}/pulls` returns an empty list for this commit;
- pull requests 1 through 7 do not exist in this repository — each answers `404`.

Whatever produced those early subject lines, the numbers in them do not resolve here. The commit
itself is on `main` and is served by GitHub's commits API, so the ordering evidence survives; the
pull request does not exist to be cited, and this ADR does not invent one.

## Decision

- **The Gradle toolchain is JDK 25**, which is itself an LTS. `build.gradle.kts`'s `java { toolchain { … } }`
  block is the authority on the configuration; this ADR decides the major and does not restate it.
- **Compilation stays at `release 21`.** The language level and the bytecode target are 21; the
  toolchain is only what the compiler and the test JVMs *run on*. The `JavaCompile` configuration
  in `build.gradle.kts` is the authority.
- **CI installs the same major explicitly**, from a named distribution rather than the runner
  image's default — `.github/workflows/ci.yml`.
- **The committed wrapper is coupled to this choice.** Gradle launches on the machine's JVM, which
  on the only machine that builds this repository is also a 25, so the wrapper must be a Gradle
  release that runs there. `gradle/wrapper/gradle-wrapper.properties` is the authority on which
  one; naming a version here would go stale at the next wrapper bump.
- **This supersedes ADR 11's language-level clause and nothing else in ADR 11.** Its
  google-java-format, ArchUnit and prefer-modern-Java clauses are untouched and still binding.

### The front matter cannot say "partially"

`supersedes` and `superseded-by` are flat lists of ADR topic slugs with no clause-level scope, and
this repository's convention pairs `superseded-by` with a `status` of `Superseded by NNNN` —
[ADR 10](0010-jvm-quality-and-tests.md) is the worked example. Filling in either field would
announce that the whole of ADR 11 is retired, which is false for three of its four clauses. So:

- this ADR's `supersedes` is empty, and the sentence above is the supersession;
- ADR 11 keeps `status: Accepted` and an unchanged front matter, and carries a dated amendment
  pointing here.

Nothing in the build reads those two fields. `AdrIndexTest` reads `status`, the number and the
title, and `status` is exactly the field a wrong answer here would have corrupted.

## Alternatives considered

- **Pin at 21, the number ADR 11 names.** Needs no ADR and keeps ADR 11 literally satisfied. It
  lost on the machine: no JDK 21 is installed, and `/usr/libexec/java_home -v 21` answers with a
  25 and exit status 0 rather than failing, so a contributor who believes they selected 21 has
  not. Gradle would have had to auto-provision a download or fail outright, and the suite would
  then be proving the code on a JVM nobody runs the tool on. The support argument that usually
  favours the older number does not apply, because 25 is an LTS too.
- **Raise `release` to 25 as well, and actually adopt it as the language level.** The honest
  version of "we moved to Java 25", and it buys nothing today: a grep of `src/main` for post-21
  constructs finds none, while `release 21` keeps the artefact runnable on the previous LTS for
  free. Raising it is a one-line change on the day some feature earns it; this is not that day,
  and pre-emptively giving up the lower floor would be paying now for a benefit nobody has asked
  for.
- **Track the newest release, LTS or not.** Features arrive soonest, and in exchange the support
  window closes roughly every six months and the upgrade stops being a decision and becomes a
  chore. The point of a pinned toolchain is that the number moves when somebody decides it does.
- **Leave the toolchain unpinned and rely on `release` alone** — the state this project was
  actually in. ADR 9 already rejected it, and the reason is sharper here than in the abstract:
  `release` constrains what the compiler *accepts*, not which JVM runs the tests, so what the
  suite proves varies by machine.

## Consequences

- **The bump bought no language features, and this ADR says so rather than letting a reader
  assume otherwise.** With `release 21` set, javac rejects anything newer, so a grep of `src/main`
  for post-21 constructs — module import declarations, stream gatherers, `ScopedValue`,
  `StructuredTaskScope`, `StableValue`, implicit classes and instance `main` — returns nothing,
  and could not return anything else. What the toolchain bought is operational, not expressive.
- **The tests run on the JVM the tool is actually launched under.** TinkerPop and Jena both target
  older JDKs; that they work on this one is proved by every `GraphStoreContract` run rather than
  assumed.
- **The restricted-native-access rules introduced after 21 are live.** `build.gradle.kts` grants
  `--enable-native-access` to the JVMs it forks because sqlite-jdbc loads a native library; the
  comment there is the authority on why. A toolchain of 21 would not have needed it.
- **A contributor cannot tell 21 from 25 by asking this machine**, because `java_home -v 21`
  answers with a 25 and succeeds. The toolchain is the mitigation — Gradle resolves the JDK itself
  — so the operational rule is to run plain `./gradlew` and never to set `JAVA_HOME` from a
  `java_home -v 21` that is lying.
- **The major is written in two places and they must move together**: the toolchain, and CI's
  explicit JDK setup. CI pins the distribution as well as the major so a runner image's default
  cannot drift the merge gate away from the local build.
- **A wrapper downgrade is not a free change.** A Gradle too old for the toolchain major cannot
  launch at all.
- **ADR 11 remains the authority on everything else it decides.**

## What this does not settle

- **Raising `release` past 21.** It needs a feature that wants it, and that is its own decision.
- **Whether the toolchain tracks future LTS releases.** This ADR pins a number. Moving it is
  another deliberate decision — which is exactly what ADR 11 said, and what this file is.
