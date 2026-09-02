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

---

## Superseded 2026-09-02: the stated defect is false, measured

Everything above is kept as the record of what was decided and why. It is wrong, and no part of it
was implemented: `deck.html` is unchanged, and the two `await response.text()` lines were not added.

The design named its own stop condition — the test must be seen red on the unchanged page first.
Written as specified (`shouldLeaveNoExchangeInFlightWhenTheServerRefuses`: the stub refuses the card
fetch with 503 and a small JSON body, then the rating POST with the existing 403, polling the
in-flight counter to zero on a bounded deadline after each), it was **green on arrival**, in a real
headless Chrome, in 1.3 s. Two reasons, and the second is the one that decides it.

**1. The counter counts exchanges, not sockets.** `inFlight` is incremented and decremented by a
`com.sun.net.httpserver` filter wrapped around the exchange, so it returns to zero when the stub
finishes writing, whatever the client does next. `untilQuiet()`'s own javadoc says exactly this, and
says it about exactly this case: a response the page never drains leaves the socket held long after
the exchange has ended and the count has gone back to zero. The definition of done above asked that
counter to observe the one thing it documents itself unable to observe. No arrangement of that test
can make it red, which is why it was not kept.

**2. At these body sizes nothing is stranded.** A throwaway diagnostic made the page issue seven
consecutive refused card fetches and recorded the client port of each exchange the stub served. On
the unchanged page all seven arrived on **one** pooled socket, reused every time; with the two drain
lines added, all seven again arrived on one. The instrument is not dead: with the refusal body grown
to 4 MB the same seven exchanges arrived on **two** ports, so it can report stranding when stranding
happens. A small response is already buffered when `fetch` resolves and the page drops the
`Response` immediately, so Chrome releases the socket without the body ever being read. The
stranding this design imagined needs a body still streaming, which no refusal in this codebase
produces. The measurement is written up in `docs/retry-precondition-evidence.md` §10.

### The decision

**The page is unchanged; the harness javadoc remains the guard.** This is the alternative the
original text rejected as "principled, but it leaves the harness's precondition resting on an
ordering nobody asserts" — and that objection still stands as a description of the code. What the
measurement changes is the premise underneath it: there is no defect for the ordering to be hiding,
so a two-line change to the page cannot be sold as a fix, and a test that can only ever be green
cannot be the thing that guards it. The rejected `response.body?.cancel()` option is moot.

If the precondition is ever to hold by construction rather than by measurement, the two lines remain
harmless and cheap — but they would land as a belt-and-braces change verified by some other explicit
method, not as a bug fix, and not behind a test with no red.
