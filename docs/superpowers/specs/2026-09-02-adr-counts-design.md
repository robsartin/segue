# ADRs stop stating counts that code can move

Issue #157. Written 2026-09-02.

## The defect

ADR 52 records the browser suite as "ten tests". It was eleven before #154 and twelve after; the
decision it records was right both times and the number was wrong both times. An immutable document
that states a number the next commit invalidates is the drift #145 was filed for, in ADR form.

This repository already has the better pattern: ADR 32 disclaims its own rule table — *"`ArchitectureTest`
is the list, not this table"* — and its 2026-09-02 amendment (#165) records the test that keeps the list
honest. ADR 53's implementor enumeration was falsified within a day (#144).

## The decision

**Sweep every ADR for a count that code can move, and amend each with a disclaimer that names where the
true value lives.** The sweep is measured, not assumed: a number-word or numeral adjacent to a countable
noun (tests, rules, tools, adapters, implementors, sources, packages, endpoints, columns, queries, ADRs)
in an ADR body outside its front matter and outside existing amendments — derived by script, then read by
hand to separate a *dated observation* ("the bake-off ran four queries", fixed forever) from a *claim
about the present* ("the suite has ten tests", moved twice). Only the second kind is amended. Each
amendment is dated, cites the issue, replaces nothing (ADR 1), says the number was a dated observation, and
names the file or test that is the authority now. ADR 52's amendment names the browser test class or
package the count describes.

**Positive control, definition of done:** the sweep's script output is in the report, with the hand
triage of every hit (amend / dated observation / not a count). `AdrIndexTest` and `DocumentationLinksTest`
green. A reviewer re-runs the script and gets the same hit list.

## Rejected

- **Correct ten to twelve.** One commit of accuracy, stale on the next browser test.
- **A test that greps ADRs for numbers.** A number is not a defect; a *claim about the present* is, and
  that is a reading, not a regex. The sweep is done once by hand with a script as its instrument.

## Recorded

The amendments themselves. No new ADR.
