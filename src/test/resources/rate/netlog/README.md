# NetLog tails, trimmed from a real capture

Three fixtures for `HeadlessChromeLateMarkerTest`, which asks whether Chrome's startup
cert-verifier flush marker landed *after* the page's first socket (issue #193).

Each file is a **tail**: the slice of a NetLog that `HeadlessChrome.open` reads *after*
`Page.navigate`, in the shape Chrome writes while it is still running — the `constants`
block, then `"events": [`, then one event per line, with no closing bracket. That is the
shape `NetLog.Tail` is built for, and a fully closed log would not exercise it.

## How they were derived

1. `SEGUE_REQUIRE_BROWSER=true ./gradlew test --tests '*HeadlessChromeNetworkTest'` on
   macOS, Chrome 152, 2026-09-02. Its guard keeps the log it measured at
   `build/reports/netlog/shouldContactOnlyLoopbackWhenTheDeckPageIsDriven.json` on every
   run (see `NetLog.keep`) — 656 lines, 651 events, 142 KB.
2. Seven event lines were copied **verbatim** from it. Six are the page's own connection
   to the loopback stub (ordinals 61, 64, 66, 71, 73, 77 and 83 of that capture:
   `HTTP_STREAM_JOB_WAITING`, `TCP_CLIENT_SOCKET_POOL_REQUESTED_SOCKETS`, `CONNECT_JOB`,
   `SOCKET_ALIVE`, `TCP_CONNECT` begin and end, `CONNECT_JOB` end); the seventh is that
   capture's own flush marker, `QUIC_SESSION_POOL_MARK_ALL_ACTIVE_SESSIONS_GOING_AWAY`
   at ordinal 55.
3. The `constants.logEventTypes` block is trimmed to the four names the tail resolves,
   **with the ids that capture gave them** — 309, 483, 44 and 50. Chrome numbers event
   types by their position in an internal list, so these ids are true of that Chrome and
   of nothing else; that is exactly why `NetLog.Tail` reads them from the log rather than
   hardcoding them, and why a trimmed block is still a fair test of that resolution.
   The other ~1,200 names and the 47 KB they occupy carry nothing this parser reads.
4. Only the **order** of those lines differs between the three files. Nothing was edited
   inside a line.

Deliberately excluded: `UDP_LOCAL_ADDRESS`, which carries this machine's own LAN IPv6
address. Every address left in these files is loopback.

## What each one is

| file | the page's first socket | the flush marker | the observation |
| --- | --- | --- | --- |
| `marker-after-first-socket.json` | event 4 | event 7 | true — the late case #193 is about |
| `marker-before-first-socket.json` | event 5 | event 2 | false — the flush was over first |
| `no-marker.json` | event 4 | never | false — the existing "not seen" path |

Positions are ordinals **within the tail**, counted from the first event after
`Page.navigate`, which is where the harness starts a resumed tail. Event 1 of a tail is
not event 1 of the browser's log.
