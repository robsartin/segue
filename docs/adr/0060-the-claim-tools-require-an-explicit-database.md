---
status: Accepted
date: "2026-09-01"
topic: the-claim-tools-require-an-explicit-database
tags: [project, tooling, provenance, data]
supersedes: []
related: [assertion-log-source-of-truth, retraction-as-a-new-claim, owner-claims-as-a-third-layer, layering-and-archunit, the-rating-deck, privacy-and-data-handling]
---
# 60. The two claim tools require an explicit `--db`, and the absence of a default is fenced

## Context

An agent set out to verify a javadoc correction and ran:

```
./gradlew own --args="mint --kind WORK --label x"
```

It expected `Task 'own' not found`. It got a minted entity in the owner's real database, and two
independent mistakes had to compound for that to happen.

**Gradle matches abbreviated task names by camel-case hump.** `own` is an unambiguous prefix of
`ownClaim`, so the task resolved and ran. Nothing about that is a bug; it is a documented
convenience, and there is no per-project switch to turn it off.

**`--db` defaulted to the owner's real log.** With no flag, the tool resolved `SEGUE_DB` if set and
`${user.home}/.segue/segue.db` otherwise — the same three-line rule six dev tools each carried their
own copy of. One row landed: `seq 318117 | LOCAL | Q001 | WORK | "x" | owner`. The owner decided to
leave it, so `Q001` is permanently consumed and the next genuine mint takes `Q002`.

The blast radius was bounded by luck rather than by design. The same slip on `retractEntity` would
have appended a retraction of a real entity, and [ADR 44](0044-retraction-as-a-new-claim.md) is
explicit that the log is never edited: the mistake would not have been undoable, only appendable
over. That is the whole point of [ADR 19](0019-assertion-log-source-of-truth.md), reached from the
wrong side.

The interesting half is not the abbreviation. It is that **a default is a decision made on the
operator's behalf, and these two tools are the ones where nobody should be making it for them.**

## Decision

**`retractEntity` and `ownClaim` refuse to run unless `--db` explicitly names a database.** Every
other dev tool keeps today's default, unchanged.

The friction lands where the consequence is permanent and the use is rare. These two are the tools
that append a **first-person claim about the world** — a retraction ([ADR 44](0044-retraction-as-a-new-claim.md))
and the three owner claims ([ADR 59](0059-owner-claims-as-a-third-layer.md)) — and each invocation is
a deliberate act taken a handful of times. `rate` writes too, and deliberately keeps its default: it
writes a rating, which is recoverable by re-rating, and it is the tool the owner uses most. A
required flag there would tax every session to guard the least dangerous write
([ADR 46](0046-the-rating-deck.md)).

Four properties make the rule worth stating precisely.

**The flag is required even with `--dry-run`.** A dry run appends nothing, so the narrow argument
says it is safe. Uniformity is worth more: the refusal fires before any database is opened, there is
no second path through the tool to reason about, and nobody has to remember which invocations are
exempt. `RetractCliTest` and `OwnCliTest` each pin that ordering, and each also asserts that no
database was created under the test's own home — a refusal that opened one first would fail twice.

**`SEGUE_DB` does not satisfy the requirement**, and this is the clause the whole decision turns on.
It is tempting to let the variable stand in for the flag, since the owner could set it once. It must
not, because **an agent's shell is initialised from the owner's profile** and inherits it. An
environment variable cannot distinguish the owner from an agent running as the owner. A flag typed
per invocation can, because typing it is the act.

**The refusal names the flag and the path it would have used**, so the owner's next command is a
copy-paste rather than a lookup, and it names `SEGUE_DB` and refuses it in the same breath, because
an owner who set it will otherwise ask why it did not count. The sentence lives once, in
`support.RequiredDatabase`; both CLIs call it and neither holds a copy. The first cut of this work
did hold two copies, which is how it was found that a fence naming a class cannot see a rule
re-implemented beside it.

**`RequiredDatabase.refusal` returns a `String` and never a `Path`.** It has to resolve the default
to quote it, so it calls `support.DefaultDatabase` — but what it hands back is a sentence, not
something either tool could open. A `String` has to be parsed back into a `Path` by a line somebody
writes and a reviewer can see; a `Path` does not.

### The absence of a default is the safety property, so a rule holds it

No test of behaviour can hold an absence. Every refusal test here would still pass if a later edit
wired the default in behind the refusal — the tool would refuse when asked to refuse, and default
the rest of the time. So two ArchUnit rules in `ArchitectureTest` hold what the tests cannot, and
that file is the authority for exactly what they say:
`theClaimToolsHaveNoDefaultDatabase` and `theClaimToolsTakeTheirDatabaseFromTheFlagAlone`.

**Two rules, because the first forbids a name and the second forbids the capability, and the gap
between them was measured rather than argued.** Both claim tools depend on `support.RequiredDatabase`
for the refusal sentence, and that class resolves the default itself. Add one `Path`-returning method
there, wire it into either tool, and the default is back with the reach it had before this ADR —
while `DefaultDatabase`, the only class the first rule names, is never mentioned. Planted exactly
that way during this work, the first rule stayed green. That is the shape of mistake this codebase
keeps producing: a fence that forbids a name stops only the lazy version.

Both rules were driven from planted violations in **both** packages, because a rule that covers one
tool and not its twin looks identical to a rule that covers both. The controls plant real code, not
prose: both CLIs name `DefaultDatabase` inside javadoc to say they do not use it, javadoc leaves no
bytecode edge, and a control that planted only a comment would have passed while testing nothing.

## Alternatives considered

- **Refuse when not attached to a terminal.** The most attractive of the five in principle, because
  it separates *owner at a keyboard* from *agent in a harness*, which is the real distinction the
  flag only approximates. **Rejected on a measurement, not a hunch.** `build.gradle.kts` is the
  authority: of the eight `JavaExec` dev tasks it registers, exactly one wires `standardInput` — the
  interactive `rate`. `retractEntity` and `ownClaim` do not, so `System.console()` is null when the
  owner runs them at their own keyboard. The rule would have refused everybody, always, making it a
  worse-explained version of "require `--db`" that would also have been read as a bug.

- **Require `--db` for every writing tool, `rate` included.** Consistent, easier to fence — the rule
  would name a category rather than two tools — and it needs no judgement about which writes are
  serious. Rejected because it taxes the daily driver to protect the one write that is recoverable:
  a rating is changed by re-rating, and `rate` is entered many times a session.

- **Keep the default and add a confirmation flag, such as `--my-real-log`.** Equivalent protection,
  and the error message could name the flag exactly. Rejected as the same typing cost carrying an
  extra concept: two flags that both mean "which database" instead of one that already does.

- **Require `--db`, but let `SEGUE_DB` satisfy it.** Kinder to the owner, who sets it once and stops
  typing paths. Rejected for the reason above, which is the reason the hole existed: an agent's shell
  is initialised from the owner's profile and inherits the variable, so the check would pass for
  precisely the caller it exists to stop.

- **Rename the tasks so no short prefix resolves.** Attacks the half of the fault that actually
  surprised the agent, and needs no behaviour change at all. Rejected because it treats the symptom
  while making the tasks harder to type, and because the abbreviation is only half the fault — a
  fully typed `./gradlew ownClaim --args="mint …"` with no `--db` was equally dangerous before this
  decision, and is refused after it.

## Consequences

- **Gradle's abbreviation stays, and this ADR does not settle it.** `./gradlew own` still resolves
  to `:ownClaim` and still runs. It now refuses to do anything, which is the outcome that matters.
  The developer guide and the `ownClaim` task description both say so in as many words, because the
  next person to expect `Task 'own' not found` deserves to be told it will not happen.

- **Nothing here protects the tools that keep a default, and they do not need it.** Which tools
  those are is not a list to keep in prose: they are exactly the callers of
  `support.DefaultDatabase.resolve`, which one grep answers and which
  `theClaimToolsHaveNoDefaultDatabase` keeps true by making the two claim tools' absence from that
  set enforceable. Every one of them either reads (a wrong database costs a wrong answer, discarded
  by running again) or writes a rating, recoverable by re-rating, on the argument above. A hand-copied
  membership list is what an earlier draft of this line got wrong: it counted `hoverableSvg` in, and
  `hoverableSvg` has no `--db` and opens no store at all. The fences are scoped to `retract` and
  `own` by package name, so a future dev tool inherits nothing — a deliberate cost, and the reason a
  third claim tool would have to be added to both rules by hand.

- **The `String`/`Path` line is where the fence can be drawn, and it is not the whole capability.** A
  `support` helper returning the default as a `String` for the caller to parse would pass both rules.
  That hole is left open knowingly: the refusal sentence itself carries that path as text, and no
  predicate can tell a sentence from a path spelled out. What the rules buy is that reintroducing the
  default now requires writing the parse in the claim tool, in the open.

- **Six copies of the resolution became one.** `support.DefaultDatabase` holds it for the four tools
  that still default; the two that do not have nothing left to hold. The consolidation was pinned
  first, so that replacing six copies could not quietly change any of them.

- **Every invocation of these two tools written down before today is now wrong**, in scripts, notes
  and shell history. The task descriptions and the developer guide were corrected with it, and the
  examples name `$HOME` rather than `~`: a tilde does not expand inside double quotes in either zsh
  or bash, so an example with one produces `no segue database at ~/.segue/segue.db` and reads like a
  different bug.

- **The stray `Q001` row stays.** The owner decided that, it is recorded in issue #179, and it is not
  this decision's business. `Q001` is consumed; the next mint takes `Q002`.
