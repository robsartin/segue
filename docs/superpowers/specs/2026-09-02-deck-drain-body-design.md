# deck.html drains a refused response before returning

Issue #188. Written 2026-09-02.

## The defect

Two paths in `src/main/resources/rate/deck.html` — the card fetch and the rating POST's refusal branch —
`return` on `!response.ok` without reading the body. #169's harness counts in-flight exchanges so the
test can wait until no pooled socket is busy; an undrained body leaves an exchange open from the
stub's point of view. The precondition holds today only by test ordering, and the first draft of #169
claimed "nothing leaves a body undrained", which the reviewer showed false.

## The decision

**Drain on refusal: `await response.text()` before each `return`**, one line per path, so the harness's
precondition is true by construction. Not a product fix — the bodies are tiny and Chrome copes — and
the issue offered the principled alternative (leave the page, keep the javadoc as guard). Chosen because
a precondition that holds by ordering is the silent-no-op shape this repo keeps closing, the change is
two lines, and it does not shape the page around the test the way an inline favicon would have (#169's
line): draining a response you refused is what a page should do anyway.

**Positive control, definition of done:** a browser test (beside `DeckBehaviourTest`, same harness)
that has the stub refuse the card fetch and the rating POST, then asserts the stub's in-flight counter
returns to zero without the harness's ordering-based wait; seen red on the unchanged page (the counter
stays at one — quote), green after. Reverting one of the two lines reds it again (quote which path).

## Rejected

- **Leave the page; the javadoc is the guard.** Principled, but it leaves the harness's precondition
  resting on an ordering nobody asserts.
- **`response.body?.cancel()` instead of `text()`.** Cancel closes the socket rather than draining it,
  which is the opposite of what a pooled-socket precondition wants.

## Recorded

No ADR. `docs/retry-precondition-evidence.md` (from #169) gains one dated line saying the precondition is
now by construction.
