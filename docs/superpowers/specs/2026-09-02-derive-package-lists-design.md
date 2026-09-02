# The dev-tool and adapter package lists are derived from the tree

Issue #165. Written 2026-09-02.

## The residual

`ArchitectureTest.DEV_TOOL_PACKAGES` and `ADAPTER_PACKAGES` are hand-maintained lists, and every
sibling fence derives from them. A package the constant does not name is fenced by nothing. #165
measured it: an eighth dev tool planted under `src/main`, reaching `export`, `recommend` and
`IngestService`, left every one of `ArchitectureTest`'s rules green. The build still failed — three
`DeveloperGuideEnumerationsTest` checks are filesystem-derived — but all three can be satisfied by
editing the guide, so an unfenced tool can ship with the constant untouched. That is how `own` arrived
in #92: fenced only once someone remembered to add it.

The #145 shape does not close this. Comparing a guide sentence to a constant is a document against a
document; if neither moves, nothing fails. The set has to come from the tree.

## What the tree says, measured on 2026-09-02

Three independent signals name the dev tools, and today they agree exactly:

| signal | packages |
|---|---|
| `build.gradle.kts` `JavaExec` tasks, by `mainClass` package | `export own rate ratings recommend retract seed` |
| packages holding a class named `*Cli` with `public static void main` | the same seven |
| `DEV_TOOL_PACKAGES` | the same seven |

`app` holds a `main` (`SegueApplication`) but no `JavaExec` task and no `*Cli`; `export` holds a
second `main` (`HoverableSvg`) inside a package already counted. Neither disturbs the derivation.

One signal names the adapters: the packages containing a class that implements an interface in
`port` — `jena` and `tinker` (`GraphStore`), `sqlite` (`AffinityStore`, `AssertionLog`), `wikidata`
(`EntityResolver`, `SourceAdapter`), `musicbrainz` (`SourceAdapter`) — exactly `ADAPTER_PACKAGES`.
`IdentityMerge` has no `implements` in `src/main` (it is a bean); `port` itself is excluded.

## The decision

**Derive both sets from the tree, and assert the constants equal them.** The constants stay — they are
the readable list every fence and javadoc cites, and a deliberate act to edit — but they are no longer
the source of truth. A new dev-tool package, or a new adapter, reds the build until the constant names
it; a constant naming a package the tree no longer has reds too.

- **Dev tools:** a test derives the set two ways — the `mainClass` packages of every `JavaExec`
  registration in `build.gradle.kts`, and the packages of every class named `*Cli` declaring
  `public static void main` — and asserts the two derivations and the constant are the same set,
  naming the odd one out. Two signals because a tool that has a task but no `*Cli`, or a `*Cli` but no
  task, is itself a finding.
- **Adapters:** a test derives the set through ArchUnit's imported classes — every class assignable
  to an interface in `..port..`, grouped by package, minus `port` — and asserts it equals
  `ADAPTER_PACKAGES`.
- **The guide, #145's shape kept but pointed at the derivation:** the guide's dev-tool sentence
  (`docs/developer-guide.md`, "…are the seven dev-side tools") is compared as a *set* to the derived
  set, not to the constant, so a stale sentence cannot agree with a stale constant.

**Positive controls, definition of done.** Plant a new tool (`promote/PromoteCli` with `main`
and a `JavaExec` registration): the dev-tool derivation reds naming `promote`, the constant unchanged,
`ArchitectureTest`'s fences still green — that green is the point. Plant a class implementing
`GraphStore` in a new package: the adapter derivation reds naming it. Add a bogus entry to each
constant: red the other way. Revert each; quote each.

## Rejected

- **Derive the set and drop the constants.** Fences would follow the tree automatically, but the
  readable list — cited by twenty javadocs and the guide — would become a computed value nobody can
  read without running a test, and a predicate that stops matching would silently un-fence
  everything rather than red.
- **One dev-tool signal only.** Either alone is sufficient today; disagreement between them is
  information the single-signal version discards.
- **Grep `src/main` for `implements GraphStore` etc.** A text predicate over source is the parser
  hole this repo keeps finding; ArchUnit already has the class graph, typed.

## Recorded

ADR 32 (layering and ArchUnit) says the test is the list; this change makes the tree the list and the
test the check. A dated amendment says so, names the two tests, and states what is deliberately not
derived (the exception `rate → recommend` in `otherDevToolsAnd`, which is a decision, not a fact of the
tree).
