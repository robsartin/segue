# Chrome 153 asks for `update.googleapis.com` at startup — Design

**Issue:** #336. **Branch:** `336-ready`. **Status:** design, ready to plan.

`HeadlessChromeNetworkTest` — "the browser reaches no host but loopback, and asks only for the
phone-homes on record" — is red on the owner's machine since Google Chrome on it became
`153.0.8010.47` (macOS 26.6.2). The set it names is the record's hosts plus one,
`update.googleapis.com`. This document is the measurement that settles what that red is, and the
decision it supports: **a launch flag, not an allowlist entry.**

The measuring was done before this document was written, in a throwaway trial in the worktree that
was reverted; every number below is read off a real run on this machine and none of it is reasoned.

## 1. The red, verbatim

`SEGUE_REQUIRE_BROWSER=true ./gradlew test --tests '*HeadlessChromeNetworkTest'` on `336-ready` at
`origin/main`, 2026-09-15:

```
java.lang.AssertionError: [HeadlessChrome must make no DNS resolution and no URL request to any
host other than 127.0.0.1, beyond the attempts measured and named in KNOWN_ATTEMPTS. A host here
that is not in that list is a phone-home nobody has accounted for]
Expecting :
  ["2001:4860:4860::8888", "accounts.google.com", "update.googleapis.com", "www.google.com",
    "~notfound"]
to be subset of
  ["accounts.google.com", "www.google.com", "android.clients.google.com", "~notfound",
    "2001:4860:4860::8888", "redirector.gvt1.com"]
but found these extra elements:
  ["update.googleapis.com"]
	at HeadlessChromeNetworkTest.shouldContactOnlyLoopbackWhenTheDeckPageIsDriven(HeadlessChromeNetworkTest.java:206)
```

One host beyond the record, and it is the one the issue named. The other 59 tests in that run
passed, including the flush test and the two-constant subset test.

## 2. What the NetLog actually shows

Read off the guard's own kept log,
`build/reports/netlog/shouldContactOnlyLoopbackWhenTheDeckPageIsDriven.json`, from the red run of
2026-09-15. The issue's reading was checked against the file rather than taken:

| question | what the log says |
|---|---|
| which URL | `POST /service/update2/json?cup2key=16:…&cup2hreq=…` on `update.googleapis.com` |
| when | **168 ms** after the log's first event; last sighting 169 ms |
| in a log spanning | **227 ms**, first event to last |
| how far it got | **DNS, and no further** |
| what else is named | `accounts.google.com` (143 ms), `www.google.com` (154 ms), `~notfound` (156 ms), `2001:4860:4860::8888` (156 ms), `127.0.0.1` (169 ms) |
| what is absent | `android.clients.google.com` — this browser did not live near the 2.3 s at which it is first named |

The request's own headers name what is asking: `X-Goog-Update-Interactivity: fg`,
`X-Goog-Update-Updater: chrome-153.0.8010.47`, and one component id,
`X-Goog-Update-AppId: ceofaddefefcbblgcgnibnonglccbfja`. Which component that id is was not
established and is not claimed here; the id is recorded as the log wrote it.

It dies at DNS in six places, every one ending `ERR_NAME_NOT_RESOLVED`:
`HOST_RESOLVER_MANAGER_REQUEST`, `TCP_CONNECT_JOB_CONNECT`, `SSL_CONNECT_JOB_CONNECT`,
`SOCKET_POOL`, `URL_REQUEST_START_JOB`, `REQUEST_ALIVE`. **Nothing off loopback was reached** — no
`TCP_CONNECT`, no `SSL_` event, no QUIC session, no byte — which is why the assertion that matters
stayed green and only the inventory reddened. The log's own `clientInfo.command_line` records that
`--disable-component-update` was passed on that launch, so the issue's "despite
`--disable-component-update`" is confirmed from the file, not inferred.

**The issue's reading is correct in every particular**: asked at startup, about 170 ms in (168),
with the component-update flag on the command line, in a log far short of the 2.8 s case §5 of
`docs/loopback-only-evidence.md` records. So this is the *browser* changing, not the scenario.

**Two things in the raw log are not sightings, and a future reader should not be surprised by
them.** The log also names this machine's own IPv6 address in a `UDP_LOCAL_ADDRESS` event and
`google.com` in a `COOKIE_PERSISTENT_STORE_KEY_LOAD_COMPLETED` event. `NetLog.kindOf` drops the
first by name (`LOCAL_ADDRESS` is the near end of a socket) and the second by source type (a cookie
store is not a network event), so neither reaches the guard's host set. That is the parser working,
and it is the reason the red named exactly one extra host rather than three.

## 3. Reproducing it without Gradle

The ask is not caused by the deck page, the stub server or the DevTools session. Launched with the
harness's own flag list and `about:blank`, held five seconds, Chrome 153 names
`update.googleapis.com` at 168 ms — the same offset, with no page and no driver. That made each
flag trial a five-second launch rather than a Gradle run, and every trial below was confirmed
against the real test afterwards.

## 4. Flags tried, and what each did

Each was added alone to `HeadlessChrome.flags`, everything else unchanged.

| flag | result |
|---|---|
| `--disable-background-networking` | already on the command line; does not stop it |
| `--disable-component-update` | already on the command line; does not stop it |
| `--simulate-outdated-no-au="Tue, 31 Dec 2099 23:59:59 GMT"` | no change — still asked at 189 ms |
| `--check-for-update-interval=31536000` | no change — still asked at 215 ms |
| `--disable-component-extensions-with-background-pages` | no change — still asked at 187 ms |
| `--component-updater=fast-update` | no change — still asked at 186 ms |
| `--component-updater=test-request` | no change — still asked at 196 ms |
| `--disable-features=MaskedDomainList,EnableIpProtectionProxy,PrivacySandboxAttestations,TpcdMetadataGrants,OptimizationHints,ComponentUpdaterOnDemand,CrxDownload` | no change — still asked at 184 ms |
| `--component-updater=url-source=https://127.0.0.1:8899/update` | **retargets it** — no `update.googleapis.com`; the check goes to loopback instead |
| `--component-updater=url-source=http://127.0.0.1:8899/update` | **stops it** — no request to any host |
| `--component-updater=url-source=http://update.invalid.test/update` | **stops it** — no request to any host, not even the named one |
| `--component-updater=url-source=http://127.0.0.1:1/` | **stops it**, 3 launches of 3, and green in the guard's own scenario |

**The mechanism, stated honestly.** The component updater's update-check URL is a launch switch.
Chrome 153 dispatches the check to an `https://` source — measured, it went to `127.0.0.1` — and
dispatches **no check at all** when the source is not HTTPS, whatever host it names. It is the
scheme that decides, which is why `http://update.invalid.test/` produced no request either. This is
a flag doing something measurable, not a flag believed to help, which is the bar
`HeadlessChrome.flags` already sets for itself.

## 5. The decision

**Add `--component-updater=url-source=http://127.0.0.1:1/` to `HeadlessChrome.flags`. Do not admit
`update.googleapis.com` to `KNOWN_ATTEMPTS`.**

The issue's shape says a flag that stops the phone-home beats an allowlist entry, and the test's own
javadoc says why: the allowlist is checked with `isSubsetOf`, so it can only over-list, and an entry
is a permanent widening of the guard that nothing will ever announce as stale. A flag leaves the
guard as narrow as it was.

- **Loopback, not an invented hostname.** `http://update.invalid.test/` stops the request just as
  well today, but if a future Chrome ever accepts a non-HTTPS source it would start asking for a
  host that is in no list, and the guard would redden on the harness's own flag. Pointed at
  `127.0.0.1:1`, the worst a future Chrome can do is refuse a connection to this machine, which is
  the posture the harness already guarantees.
- **Port 1 rather than a port something may hold.** Nothing in this harness binds it, and the stub
  server binds an ephemeral port.
- **`--disable-component-update` stays.** It is measured not to stop *this* request on this build;
  it is not measured to be useless, and removing it is a separate question nobody has evidence for.

**What stays green, checked rather than assumed.** With the flag applied, the guard's host set is
`accounts.google.com`, `www.google.com`, `~notfound` and `2001:4860:4860::8888` — so
`PHONE_HOME_CONTROL` still has both its hosts, `requireTheParserStillSeesChromesAttempts` still
fires on real traffic, and `shouldKeepTheControlHostsInsideTheAllowlistWhenTheListsAreCompared` is
untouched because neither constant moves. `HeadlessChromeNetworkTest` and `DeckBehaviourTest` were
run together twice with the flag in place: both green, and the flush test's wait still decided and
still inside its bound.

**The positive control this buys.** An allowlist entry for a host only one platform asks for has no
local red — that is the standing cost of the `redirector.gvt1.com` entry, stated in its own comment.
A flag has one: remove it, and this test reddens naming `update.googleapis.com` on this machine, in
the same words as §1. The plan runs that control rather than asserting it.

## 6. What this means on Linux, and what is not touched

CI runs the same guard on `ubuntu-latest` against its own Google Chrome. Two honest statements:

- **Nothing here was measured on Linux.** The `redirector.gvt1.com` entry and the Linux host set in
  §5 of `docs/loopback-only-evidence.md` were measured on Chrome 152 on `ubuntu-latest` (CI run
  33655745937) and **are not changed by this work**. The rule the repository already follows — a
  list is not trimmed on evidence from a platform that did not produce it — forbids touching that
  entry on a macOS measurement, and this design does not.
- **The flag may well remove `redirector.gvt1.com` too**, since that host is the same component
  updater's download redirector and a check that is never dispatched downloads nothing. If it does,
  that entry becomes an over-listing: `isSubsetOf` stays green, no test reddens, and the entry is
  stale in exactly the way the constant's javadoc already warns about. That is a cost to notice, not
  a regression, and it is read off the next CI run's `reports` artifact rather than decided here.
- **A Linux red is still possible and still meaningful.** If Chrome on the runner asks for something
  new, the guard reddens there and the next platform's host set is read off the uploaded NetLog,
  exactly as the javadoc describes.

## 7. Documentation this changes

- **`HeadlessChromeNetworkTest`'s javadoc, the standing-example paragraph.** It currently says
  `update.googleapis.com` "is the standing example and stays out", on the ground that only another
  scenario asks for it. On Chrome 153 the guard's own scenario asked for it, so that ground is gone.
  The paragraph is corrected to record what Chrome 153 does, to say the §5 rule now has no standing
  example, and to say the host stays out because a flag removes the ask rather than because no
  scenario makes it.
- **The same javadoc's "a red naming a host from §5 is not a flake" paragraph.** It offers one
  explanation — the browser kept alive longer. Chrome 153 is the second, and the paragraph names it.
- **`docs/loopback-only-evidence.md` §5** gains a dated note: the Chrome 153 measurement, the
  offset, that it died at DNS and reached nothing, the flag, the flags that removed nothing, and the
  Linux paragraph above. The existing table and notes are left standing, as every note in that file
  leaves what came before it standing.
- **`HeadlessChrome.flags`'s comment** carries the new flag's own measurement, in the same form as
  the flags already there, including what was tried and removed nothing. The comment's opening "the
  two flags" becomes "the flags", because there are now three switches in that group.

## 8. No ADR amendment

ADR 52 is the decision that this browser's posture is loopback-only, that flags are kept only when a
NetLog justified them, and that `KNOWN_ATTEMPTS` is a per-scenario, per-platform inventory re-derived
when the browser changes. **All three decisions are being followed here, not changed** — a new Chrome
changed what the browser does, the harness re-derived against it, and the flag was kept because a
NetLog justified it. ADR 52 also says in terms that the flag list lives in the comment on
`HeadlessChrome.flags`, "which is the list rather than this page", so a new flag does not make the
ADR wrong. No file under `docs/adr` is touched.

## 9. Alternatives rejected

- **Admit `update.googleapis.com` to `KNOWN_ATTEMPTS` with the platform and version named**, in the
  form of the `redirector.gvt1.com` entry. This is the issue's fallback and would have been correct
  had no flag worked. Rejected because one does: an entry widens an `isSubsetOf` guard permanently,
  on a machine where a flag can close the hole instead.
- **`--component-updater=url-source=https://127.0.0.1:1/`.** Semantically the tidier story — the
  updater still runs and can only talk to loopback — but measured, it *dispatches* the check to
  `127.0.0.1:1`, which adds a refused loopback connection and a failed TLS attempt to every launch.
  This suite exists because of what Chrome does to its own socket pool at startup
  (`docs/retry-pool-flush-evidence.md` §4), and adding a failing loopback socket next to the
  cert-verifier flush is a risk taken for no gain.
- **`--component-updater=url-source=http://update.invalid.test/`.** Stops the request today; turns
  into a guard-reddening ask for an unlisted host if a future Chrome accepts non-HTTPS sources.
- **Removing `--disable-component-update`** because it did not stop this request. Out of scope and
  unsupported: it is measured not to stop *this* ask, which is not evidence that it stops nothing.
- **Waiting for a Linux measurement before adding the flag.** The rule the repository follows is
  that a list is not *extended* on reasoning. A flag measured on the platform that reddened is
  measured, and the Linux effect is an over-listing rather than a red.

## 10. Verification

- Per task: `SEGUE_REQUIRE_BROWSER=true ./gradlew test --tests '*HeadlessChromeNetworkTest'`,
  blocking, with the red observed before the fix and the control's red observed after it.
- The prose changes have no unit-testable behaviour. **Stated rather than implied:** they are
  verified by the `javadoc` task inside `check` (the javadoc must still build), by `spotlessCheck`
  (the comment must be formatted), by a planted-broken-link control against
  `DocumentationLinksTest` proving that guard reads the edited file, and by the full gate.
- Final: `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`,
  blocking, tests counted once from `build/test-results/test/*.xml`.
