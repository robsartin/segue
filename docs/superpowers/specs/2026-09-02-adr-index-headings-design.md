# The ADR index's section headings are validated against the toolkit's names

Issue #190. Written 2026-09-02.

## The defect

`AdrIndexTest` (#170) asserts rows ascend within each `## ` section and does not validate the section
names, so a typo'd heading opens a second section whose rows ascend on their own: a silent split. It was
left out on a reviewer's reasoning that the names belong to the adr-toolkit's `build_index`
(`_AXIS_DISPLAY_NAMES`), not to this repository. Filed so the omission is a decision on record.

## The decision

**Assert every `## ` heading in `docs/adr/README.md` is one of the toolkit's display names or
`Uncategorized`, and that no name appears twice, citing the toolkit as the authority in the assertion
message.** The allowed set is the headings the index carries today, derived by reading the file before
writing the test, and stated in the test as a list with a comment naming `adr_toolkit/index.py
_AXIS_DISPLAY_NAMES` as where the names come from; a toolkit rename requires a test change here, which is
the accepted cost and is said in the javadoc. Duplicate detection is what closes the split: a typo makes
an unknown name (red), a copy makes a duplicate (red).

**Positive controls, definition of done:** typo one heading (`## Uncategorised`) → red naming it and citing
the toolkit; duplicate one heading with rows under it → red naming the duplicate; a heading that is not
`## ` (a `### `) is not a section and is ignored — confirm by reading the existing parser. Reverted, quoted.

## Rejected

- **Vendor `build_index` and regenerate.** The #170 spec measured that the generator's axis rule is `tags[0]`
  and 42 of 43 post-baseline ADRs carry `project` first, so regeneration would collapse the sections; a
  different decision, not this issue's.
- **Leave it.** The split degrades ordering only, but a decision on record is what was asked for, and the
  test is a dozen lines.

## Recorded

No ADR; ADR 1's index rules are unchanged. The test's javadoc records the authority.
