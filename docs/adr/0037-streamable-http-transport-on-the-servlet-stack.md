---
status: Accepted
date: "2026-08-25"
topic: streamable-http-transport-on-the-servlet-stack
tags: [project, mcp, interface, security]
supersedes: []
related: [mcp-transports, mcp-protocol-conformance, mcp-tool-surface, spring-boot-conventions, security-baseline]
---
# 37. Serve Streamable HTTP from the servlet stack, on a loopback allowlist

## Context

ADR 28 decided that segue ships both transports, that `Origin` is validated on every HTTP
request with 403 on mismatch, and that the server binds to `127.0.0.1`. Increment 4a
shipped only stdio, so none of that had to be made concrete. Building the HTTP half turns
out to need four choices ADR 28 does not make, each of which changes what the server does
rather than merely how it is written.

**Which HTTP stack.** Spring AI 2.0.1 offers `spring-ai-starter-mcp-server-webmvc` and
`spring-ai-starter-mcp-server-webflux`.

**Which protocol variant.** The starter offers three: `SSE` (the HTTP+SSE transport that
Streamable HTTP replaced, and which the starter marks deprecated for removal),
`STREAMABLE`, and `STATELESS` — a Spring AI variant of Streamable HTTP with no session and
no server-initiated stream. **The starter's effective default is `SSE`, not Streamable
HTTP**, despite the property metadata advertising `streamable` as the default: the SSE
auto-configuration's condition is `matchIfMissing = true` on `protocol=SSE`, so leaving the
property unset selects the deprecated transport.

**What counts as an acceptable `Origin`.** ADR 28 says the header is validated; it does not
say against what.

**How the two transports avoid each other.** Both auto-configurations contribute a bean of
type `McpServerTransportProviderBase`, and the SDK's server takes exactly one.

## Decision

- **The servlet stack (`webmvc`), not WebFlux.** Everything under the tool surface is
  blocking — SQLite over JDBC, an in-process Gremlin traversal — so a reactive stack would
  add a second concurrency model without removing a blocking call.
- **`spring.ai.mcp.server.protocol` is set explicitly to `streamable`.** Named rather than
  inherited, because the default is the deprecated SSE transport and because ADR 27 holds
  that a dependency bump should not silently change which protocol segue speaks.
- **`Origin` and `Host` are both validated against a loopback allowlist** — `localhost`,
  `127.0.0.1`, `[::1]`, any scheme, any port — using the SDK's own
  `DefaultServerTransportSecurityValidator` rather than a hand-written filter. A bad
  `Origin` is 403, a bad `Host` is 421. A request carrying no `Origin` at all is allowed:
  a browser cannot omit it, and a real MCP client never sends it.
- **The allowlist is a constant in `SegueConfiguration`, not a configuration property.**
  ADR 28 makes remote reachability "a deliberate configuration change with its own security
  review"; a property makes widening it a deploy-time accident instead.
- **The two transports are mutually exclusive on `spring.ai.mcp.server.stdio`**, the same
  property the starter's own `McpServerStdioDisabledCondition` reads. HTTP is what a plain
  `java -jar` gets; the `stdio` profile turns the web server off entirely.
- **No authentication.** ADR 28's binding decision is the access control: the server is
  reachable only from the machine it runs on, by anything already running as that user.
  Exposing it beyond loopback is out of scope and would need its own ADR.

## Alternatives considered

- **WebFlux.** Cheap to switch to, and it would let a slow expansion hold a request without
  holding a thread — which matters when ingest's virtual-thread fan-out lands. Virtual
  threads on the servlet stack answer the same problem without a second programming model,
  and nothing under the tool surface is non-blocking today.
- **`STATELESS` rather than `STREAMABLE`.** Genuinely attractive: it is closer to protocol
  revision 2026-07-28, which removes sessions outright, so migrating (ADR 27's tracked
  follow-up) would be a smaller step, and it holds no per-connection state to bound.
  Rejected because "Streamable HTTP" in ADR 28 means the specification's transport, and
  `STATELESS` is a Spring AI narrowing of it that cannot serve a server-initiated stream.
  Nothing in this design depends on a session, so the eventual move stays cheap either way.
- **A hand-written `OncePerRequestFilter` for the `Origin` check.** Fewer moving parts than
  overriding the transport bean, and it puts security-critical string comparison in this
  repository rather than in the SDK that already ships a tested implementation of it. The
  transport bean had to be overridden regardless (see Consequences), so the filter would
  have been extra code for no saving.
- **Allowing any `Origin`, relying on the loopback binding alone.** This is what the
  starter does by default, and it is exactly the DNS-rebinding hole ADR 28 names: a page in
  the user's own browser is on the loopback interface too.
- **An `http` profile symmetric with `stdio`.** Tidier to read, and it would mean neither
  transport is active by default, which makes `java -jar segue.jar` do nothing useful.

## Consequences

- `java -jar` with no profile serves MCP on `http://127.0.0.1:8080/mcp`; `SEGUE_HTTP_PORT`
  moves it. The `stdio` profile still starts no listener at all, which
  `TransportSelectionTest` pins.
- **The starter's Streamable HTTP auto-configuration cannot start on its own in Spring AI
  2.0.1.** Its `webMvcStreamableServerTransportProvider` bean method takes
  `McpServerStreamableHttpProperties`, and nothing registers that class as configuration
  properties — `McpServerAutoConfiguration` enables only `McpServerProperties` and
  `McpServerChangeNotificationProperties`. Setting `protocol: streamable` therefore fails
  the context with `NoSuchBeanDefinitionException` until the application enables the class
  itself, which `SegueConfiguration` now does. This is why the transport bean would have
  been overridden here even without the security validator, and it is worth re-checking on
  the next Spring AI bump.
- Two transports means two integration tests that boot the whole application, one as a
  subprocess and one on a real port. That is the cost ADR 28 accepted so neither rots.
- Adding the webmvc starter puts Tomcat and Spring MVC on the classpath of the stdio
  transport too, where they are never started. `spring.main.web-application-type: none` in
  the `stdio` profile is what keeps that true, and it is now load-bearing rather than
  belt-and-braces.
- Widening access — a second machine, a container, a shared instance — is a code change to
  the allowlist plus a decision about authentication, which is the friction ADR 28 wanted.
