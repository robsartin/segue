# Claim tools require an explicit database

Issue #179. Written 2026-09-01.

## What happened

An agent verifying a javadoc correction ran:

```
./gradlew own --args="mint --kind WORK --label x"
```

expecting `Task 'own' not found`. It expected wrong, twice over, and the two mistakes compounded:

1. **Gradle matches abbreviated task names by camel-case hump.** `own` resolved to `:ownClaim`.
2. **`--db` defaults to the owner's real database.** With no `--db`, the tool appended to
   `~/.segue/segue.db`.

One row landed in the real log: `seq 318117 | LOCAL | Q001 | WORK | "x" | owner`. The owner chose to
leave it, so `Q001` is permanently consumed and the next genuine mint takes `Q002`.

The blast radius was bounded by luck. The same slip on `retractEntity` would have appended a
retraction of a real entity, and the log is append-only — the mistake would not have been undoable,
only appendable-over.

## The decision

**`retractEntity` and `ownClaim` refuse to run unless `--db` explicitly names a database.** Every
other tool — `exportGraph`, `hoverableSvg`, `listRatings`, `recommend`, `rate`, `resolveNames` —
keeps today's default unchanged.

The friction lands where the consequence is permanent and the use is rare. `retractEntity` and
`ownClaim` are the two tools that append a **first-person claim about the world**; each invocation
is a deliberate act taken a handful of times. `rate` writes too, but writes affinity, which is
recoverable by re-rating, and it is the tool the owner uses most — a required flag there would tax
every session to guard the least dangerous write.

Three properties make the rule worth stating precisely:

- **The flag is required even with `--dry-run`.** A dry run appends nothing, so the narrow argument
  says it is safe. Uniformity is worth more: the refusal then fires before any database is opened,
  there is no second path to reason about, and no one has to remember which invocations are exempt.
- **`SEGUE_DB` does not satisfy the requirement.** It is tempting to let the environment variable
  stand in for the flag, since the owner could set it once. It must not, because **an agent's shell
  is initialised from the owner's profile** and would inherit it. An environment variable cannot
  distinguish the owner from an agent running as the owner. The flag can, because it is typed per
  invocation.
- **The refusal names the flag and the path it would have used**, so the owner's next command is a
  copy-paste rather than a lookup.

## One resolution, not six

`ExportCli`, `OwnCli`, `RateCli`, `RatingsCli`, `RecommendCli` and `RetractCli` each carry their own
copy of the same resolution — `SEGUE_DB` if set, otherwise `${user.home}/.segue/segue.db` — and each
javadoc restates it in prose. Several of those javadocs already say "stated here as well as in…",
which is the duplication admitting itself.

Six copies of one rule is a drift generator. This work replaces them with a single
`support.DefaultDatabase`, following the precedent `ArchitectureTest` already records for
`QidList`: shared reader logic moves into `support` rather than creating a dependency between two
dev tools. Four dev-tool packages already depend on `support`.

**The two claim tools do not use it at all** — they have no default to resolve. That absence is the
fence: an ArchUnit rule forbids `..retract..` and `..own..` from depending on `DefaultDatabase`, so
the default cannot be reintroduced there by a later edit that looks locally reasonable. The rule
needs a positive control, planted from both packages, per this codebase's standing practice.

## What this does not fix

**Gradle's abbreviation matching stays.** There is no per-project switch to disable it, and renaming
tasks to have no unambiguous prefix would treat the symptom while making the tasks harder to type.
`./gradlew own` will still resolve to `:ownClaim` — it will simply refuse to do anything, which is
the outcome that matters. The developer guide should say so plainly, because the next person to
expect `Task 'own' not found` deserves to be told it will not happen.

**The stray `Q001` row stays.** The owner decided that; it is recorded in #179 and is not this
change's business.

## Alternatives rejected

- **Refuse when not attached to a terminal.** Attractive in principle — it separates *owner at a
  keyboard* from *agent in a harness*, which is the real distinction — and rejected on a measurement
  rather than a hunch. Only one Gradle task wires `standardInput` (the interactive `rate`);
  `retractEntity` and `ownClaim` do not, so `System.console()` is null for the owner too. The rule
  would refuse everyone always, making it a worse-explained version of "require `--db`".
- **Require `--db` for every writing tool, `rate` included.** Consistent, easier to fence, no
  judgement about which writes are serious. Rejected because it taxes the daily driver to protect
  the recoverable write.
- **Keep the default; add a confirmation flag such as `--my-real-log`.** Equivalent protection and
  the error can name the flag exactly. Rejected as the same typing cost with an extra concept: two
  flags meaning "which database" instead of one.
- **Require `--db` but let `SEGUE_DB` satisfy it.** Rejected above: an agent inherits the profile
  that sets it.
- **Rename the tasks so no short prefix resolves.** Treats the symptom, and the abbreviation is only
  half the fault.

## Testing

Every behaviour below is driven from a test that is seen to fail first.

- `retractEntity` and `ownClaim` each refuse with no `--db`, and the message names `--db` and the
  path that would have been used.
- Both refuse with `--dry-run` and no `--db`.
- Both refuse when only `SEGUE_DB` is set, which is the case that would silently re-open the hole.
- Both proceed when `--db` names an existing database.
- The four unchanged tools still resolve `SEGUE_DB`, then `${user.home}/.segue/segue.db` — pinned so
  that consolidating six copies into one cannot quietly change any of them.
- The ArchUnit fence goes red when a dependency on `DefaultDatabase` is planted in `retract`, and
  again when planted in `own`; both controls are quoted in the report.

## Documentation

The two Gradle task descriptions carry `--args` examples that would now fail; both need `--db`. The
developer guide needs the rule, the reason, and the abbreviation warning. `RetractCli` and `OwnCli`
javadocs currently promise the default they no longer have.

**ADR 60** records the decision, these five rejected alternatives with the reason each lost, and what
it does not settle — that the abbreviation remains, and that no rule here protects the four reading
tools, which do not need protecting.
