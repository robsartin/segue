# Increment 4a: MCP Server over stdio — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A working MCP server over stdio exposing five tools over the existing graph, so the project's open risk — is MCP a pleasant *authoring* interface, or do you want a UI within ten minutes? — can finally be answered by using it.

**Architecture:** Spring Boot 4.1.1 with Spring AI 2.0.1's MCP server starter. Tools are `@McpTool`-annotated methods on thin classes in `mcp`, delegating to a `SegueService` facade that owns the ports. Spring appears only in `app` and `mcp`; every adapter stays plain Java. At startup the graph is rebuilt by replaying the SQLite assertion log.

**Tech Stack:** Java 21 (toolchain 25), Gradle, Spring Boot 4.1.1, Spring AI 2.0.1 (MCP protocol revision 2025-11-25), JUnit 6.1.3, AssertJ, ArchUnit 1.5.0.

## Global Constraints

- **Base package** `com.robsartin.segue`. Build `group` is `com.robsartin`.
- **Java toolchain 25, `options.release = 21`.**
- **All versions in `gradle/libs.versions.toml`.** Spring Boot `4.1.1`, Spring AI `2.0.1`.
- **Coverage gates: line > 0.80, branch > 0.65, instruction > 0.80.** Never lower them.
- **stdout belongs to the MCP protocol.** No `System.out` anywhere in `src/main` — ArchUnit already forbids it, and `System.err`/`printStackTrace` too. All logging goes through SLF4J to **stderr**. The Spring banner is off.
- **Spring appears only in `app` and `mcp`.** `domain`, `port`, `tinker`, `jena`, `sqlite`, `wikidata`, `ingest` stay framework-free. A new ArchUnit rule enforces this in Task 1.
- **Only `ingest` writes.** Tools call `SegueService`, which calls `IngestService`. The existing `onlyIngestAppliesClaimsToTheGraph` rule stands.
- **Outbound requests identify segue by repository URL, never an email address.**
- **Affinity notes are never logged.** Not applicable in 4a (no taste layer yet) but the rule stands.
- `./gradlew spotlessApply` then `./gradlew clean check` green at **every** commit.
- Conventional Commits.
- **Work in `~/code/segue-wt/4-mcp-server` on branch `4-mcp-server`.** Do not touch `~/code/segue`.

## Verified API facts (do not re-derive, do not guess)

Confirmed by inspecting the published 2.0.1 artifacts:

- Tool annotation: **`org.springframework.ai.mcp.annotation.McpTool`** with attributes `name()`, `description()`, `title()`, `generateOutputSchema()`, `annotations()`, `metaProvider()`.
- Parameter annotation: **`org.springframework.ai.mcp.annotation.McpToolParam`** with `required()` and `description()`.
- Config properties: `spring.ai.mcp.server.stdio` (boolean, default `false`), `spring.ai.mcp.server.protocol` (default `streamable`), `.name`, `.version`, `.type` (`sync`/`async`), `.instructions`.
- `spring-ai-starter-mcp-server:2.0.1` depends directly on `spring-boot-starter:4.1.1`, so the pairing is supported.
- Spring AI 2.0.x ships MCP SDK 2.0.x, which implements protocol revision **2025-11-25** — not the current 2026-07-28. That is pinned deliberately (ADR 27).

---

## File Structure

**Created:**

| Path | Responsibility |
|---|---|
| `support/UuidV7.java` | RFC 9562 version-7 identifiers — the JDK has none |
| `app/SegueApplication.java` | Spring Boot entry point |
| `app/SegueConfiguration.java` | Beans: log, graph, adapters, ingest; boot replay |
| `app/SegueProperties.java` | Database path and expansion defaults |
| `mcp/SegueService.java` | The facade every tool calls; owns the ports |
| `mcp/ToolResult.java` | Structured result shape shared by the tools |
| `mcp/EntityTools.java` | `search_entities`, `add_entity`, `get_entity` |
| `mcp/GraphTools.java` | `expand_entity`, `find_paths` |
| `mcp/CorrelationId.java` | UUIDv7 per request, in MDC |
| `src/main/resources/logback-spring.xml` | ECS-ish JSON to **stderr** |
| `src/main/resources/application.yaml` | Server identity, stdio profile |

**Modified:** `build.gradle.kts`, `gradle/libs.versions.toml`, `arch/ArchitectureTest.java`, `CLAUDE.md`

---

### Task 1: Spring Boot wiring, and fencing Spring out of the core

**Files:**
- Modify: `gradle/libs.versions.toml`, `build.gradle.kts`
- Create: `src/main/java/com/robsartin/segue/app/SegueApplication.java`
- Create: `src/main/resources/application.yaml`
- Modify: `src/test/java/com/robsartin/segue/arch/ArchitectureTest.java`
- Create: `src/test/java/com/robsartin/segue/app/SegueApplicationTest.java`

**Interfaces:**
- Consumes: nothing
- Produces: a bootable Spring context; the ArchUnit rule that keeps Spring confined.

- [ ] **Step 1: Add the dependencies**

`gradle/libs.versions.toml`, under `[versions]`:

```toml
springBoot = "4.1.1"
springAi = "2.0.1"
```

under `[libraries]`:

```toml
spring-boot-bom = { module = "org.springframework.boot:spring-boot-dependencies", version.ref = "springBoot" }
spring-ai-bom = { module = "org.springframework.ai:spring-ai-bom", version.ref = "springAi" }
spring-boot-starter = { module = "org.springframework.boot:spring-boot-starter" }
spring-ai-starter-mcp-server = { module = "org.springframework.ai:spring-ai-starter-mcp-server" }
spring-boot-starter-test = { module = "org.springframework.boot:spring-boot-starter-test" }
```

under `[plugins]`:

```toml
spring-boot = { id = "org.springframework.boot", version.ref = "springBoot" }
```

`build.gradle.kts` — add `alias(libs.plugins.spring.boot)` to the `plugins` block, and to `dependencies`:

```kotlin
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.spring.ai.bom))
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.ai.starter.mcp.server)

    testImplementation(libs.spring.boot.starter.test)
```

**The Spring Boot plugin brings a `bootJar` task and disables the plain `jar`.** That is fine for an application. It also applies dependency management; confirm it does not silently override a version the catalog pins — if it does, say which.

- [ ] **Step 2: Write the entry point**

`src/main/java/com/robsartin/segue/app/SegueApplication.java`:

```java
package com.robsartin.segue.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point.
 *
 * <p>The banner is off and every appender targets stderr, because on the stdio transport
 * stdout carries the MCP protocol and a single stray line corrupts it. See
 * docs/adr/0028-mcp-transports.md — this is enforced by an ArchUnit rule and a stdout-purity
 * integration test, not by remembering.
 */
@SpringBootApplication(scanBasePackages = "com.robsartin.segue")
public class SegueApplication {

  public static void main(String[] args) {
    SpringApplication application = new SpringApplication(SegueApplication.class);
    application.setBannerMode(org.springframework.boot.Banner.Mode.OFF);
    application.run(args);
  }
}
```

- [ ] **Step 3: Minimal configuration**

`src/main/resources/application.yaml`:

```yaml
spring:
  main:
    banner-mode: "off"
  application:
    name: segue
  ai:
    mcp:
      server:
        name: segue
        version: 0.1.0
        type: sync
        instructions: >
          A personal interest graph. Search for entities, add them, expand them from
          Wikidata, and find citable routes between two things. Every relationship
          carries the provenance of who claimed it.

segue:
  database: ${SEGUE_DB:${user.home}/.segue/segue.db}
  expand:
    max-new-edges: 200
```

- [ ] **Step 4: Fence Spring out of the core**

Add to `ArchitectureTest`:

```java
  /** ADR 32: the framework lives at the edges. Everything else stays plain Java. */
  @ArchTest
  static final ArchRule springOnlyInAppAndMcp =
      noClasses()
          .that()
          .resideOutsideOfPackages("..app..", "..mcp..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("org.springframework..")
          .because(
              "ADR 25 and ADR 32: adapters must be testable without an application context,"
                  + " and adding a source must not require a framework");
```

Note this **supersedes** the narrower `wikidataDoesNotDependOnSpring` rule, which was vacuous
until now. Delete that rule and say so in your report — one rule that covers every package
beats two where one is a subset.

- [ ] **Step 5: Prove the fence bites**

It is no longer vacuous — Spring is on the classpath now. Temporarily add
`import org.springframework.stereotype.Component;` and a `@Component` annotation to a class in
`wikidata`, run the arch tests, confirm `springOnlyInAppAndMcp` fails naming it, remove it,
confirm green and a clean `git status`. Quote the failure.

- [ ] **Step 6: Assert the context loads**

`src/test/java/com/robsartin/segue/app/SegueApplicationTest.java`:

```java
package com.robsartin.segue.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** The context loads. Trivial, and the first thing to break when wiring goes wrong. */
@SpringBootTest
class SegueApplicationTest {

  @Test
  void contextLoads() {
    // Deliberately empty: the assertion is that @SpringBootTest got this far.
  }
}
```

This will likely FAIL until Task 5 supplies the beans the MCP starter needs. If it does, note
the exact failure and move on — Task 5 is where it turns green. **Do not stub beans here to
force it.**

- [ ] **Step 7: Gate and commit**

```bash
./gradlew spotlessApply && ./gradlew clean check
git add gradle/libs.versions.toml build.gradle.kts src/main/java/com/robsartin/segue/app src/main/resources/application.yaml src/test/java/com/robsartin/segue/arch/ArchitectureTest.java src/test/java/com/robsartin/segue/app
git commit -m "build: add Spring Boot and the MCP server starter, fenced to app and mcp"
```

If `clean check` fails only because `contextLoads` cannot find beans, that is the expected
intermediate state — **but the build must still be green to commit.** Annotate the test
`@org.junit.jupiter.api.Disabled("beans arrive in Task 5")` with that exact reason, and
re-enable it in Task 5. Removing a disable is a step in Task 5; do not let it linger.

---

### Task 2: UuidV7 — RFC 9562 identifiers

**Files:**
- Create: `src/main/java/com/robsartin/segue/support/UuidV7.java`
- Create: `src/test/java/com/robsartin/segue/support/UuidV7Test.java`

**Interfaces:**
- Consumes: nothing
- Produces: `UuidV7.generate()` returning `java.util.UUID`.

- [ ] **Step 1: Write the failing test**

```java
package com.robsartin.segue.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RFC 9562 version 7. The JDK has no generator — {@code UUID.randomUUID()} is version 4 —
 * and version 4 is unordered, so logs cannot be sorted by identifier.
 */
class UuidV7Test {

  @Test
  @DisplayName("it is version 7 and RFC 4122 variant")
  void hasCorrectVersionAndVariant() {
    UUID id = UuidV7.generate();

    assertThat(id.version()).isEqualTo(7);
    assertThat(id.variant()).isEqualTo(2);
  }

  @Test
  @DisplayName("the leading 48 bits are the current Unix time in milliseconds")
  void encodesTimestamp() {
    long before = System.currentTimeMillis();
    UUID id = UuidV7.generate();
    long after = System.currentTimeMillis();

    long timestamp = id.getMostSignificantBits() >>> 16;

    assertThat(timestamp).isBetween(before - 1000, after + 1000);
    assertThat(Instant.ofEpochMilli(timestamp)).isAfter(Instant.parse("2020-01-01T00:00:00Z"));
  }

  @Test
  @DisplayName("identifiers minted in sequence sort in the order they were minted")
  void sortsChronologically() {
    // This is the whole reason for v7 over v4: a log tail reads chronologically without
    // parsing timestamps.
    List<UUID> minted =
        IntStream.range(0, 200)
            .mapToObj(
                i -> {
                  if (i % 50 == 0) {
                    try {
                      Thread.sleep(2);
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    }
                  }
                  return UuidV7.generate();
                })
            .toList();

    List<UUID> sorted =
        minted.stream()
            .sorted(java.util.Comparator.comparingLong(u -> u.getMostSignificantBits() >>> 16))
            .toList();

    assertThat(sorted).isEqualTo(minted);
  }

  @Test
  @DisplayName("two identifiers minted in the same millisecond still differ")
  void isUnique() {
    assertThat(IntStream.range(0, 10_000).mapToObj(i -> UuidV7.generate()).distinct().count())
        .isEqualTo(10_000);
  }
}
```

- [ ] **Step 2: Run it, confirm it fails to compile**

```bash
./gradlew compileTestJava
```

- [ ] **Step 3: Write it**

```java
package com.robsartin.segue.support;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Version 7 UUIDs, per RFC 9562.
 *
 * <p>The JDK has no v7 generator — {@code UUID.randomUUID()} is version 4, which is unordered.
 * Version 7 puts a millisecond timestamp in the leading 48 bits, so identifiers sort by the
 * time they were minted and a log tail reads chronologically without anyone parsing dates.
 *
 * <p>Hand-written rather than pulling a dependency: the layout is fully specified and about
 * fifteen lines, and it is asserted against the RFC in {@code UuidV7Test}. A library becomes
 * the right answer only if guaranteed monotonicity *within* a millisecond is ever needed,
 * which a correlation identifier does not require.
 *
 * <pre>
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                     unix_ts_ms (48 bits)                      |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |  ver (0111)   |       rand_a (12 bits)        | var(10)|      |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                     rand_b (62 bits)                          |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * </pre>
 */
public final class UuidV7 {

  private static final SecureRandom RANDOM = new SecureRandom();

  private UuidV7() {}

  /** A fresh version-7 identifier. */
  public static UUID generate() {
    byte[] random = new byte[10];
    RANDOM.nextBytes(random);

    long millis = System.currentTimeMillis();

    long most = millis << 16;
    most |= (long) (random[0] & 0x0F) << 8;
    most |= random[1] & 0xFF;
    most &= ~(0xFL << 12);
    most |= 0x7L << 12; // version 7

    long least = 0;
    for (int i = 2; i < 10; i++) {
      least = (least << 8) | (random[i] & 0xFF);
    }
    least &= ~(0x3L << 62);
    least |= 0x2L << 62; // RFC 4122 variant

    return new UUID(most, least);
  }
}
```

- [ ] **Step 4: Verify, gate, commit**

```bash
./gradlew test --tests '*UuidV7Test'
./gradlew spotlessApply && ./gradlew clean check
git add src/main/java/com/robsartin/segue/support src/test/java/com/robsartin/segue/support
git commit -m "feat: generate RFC 9562 version-7 identifiers for request correlation"
```

If `sortsChronologically` proves flaky (many identifiers can land in one millisecond, making
ordering within that millisecond arbitrary), **do not weaken it into meaninglessness** — the
`Thread.sleep(2)` every 50 iterations exists to force millisecond boundaries. If it still
flakes, report it and propose a fix rather than deleting the assertion.

---

### Task 3: Structured logging to stderr

**Files:**
- Create: `src/main/resources/logback-spring.xml`
- Create: `src/test/java/com/robsartin/segue/app/LoggingTargetsStderrTest.java`

**Interfaces:**
- Consumes: nothing
- Produces: every log line on stderr, as one JSON object per line.

- [ ] **Step 1: Write the appender configuration**

`src/main/resources/logback-spring.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!--
  Every appender targets System.err.

  On the stdio transport stdout carries the MCP protocol: one newline-delimited JSON-RPC
  message per line, and nothing else. A single log line on stdout corrupts the stream and
  the client sees a parse error rather than a diagnostic. The MCP specification designates
  stderr for logging and tells clients not to read it as an error signal.

  See docs/adr/0028-mcp-transports.md and docs/adr/0030-structured-logging.md.
-->
<configuration>
  <appender name="STDERR" class="ch.qos.logback.core.ConsoleAppender">
    <target>System.err</target>
    <encoder class="ch.qos.logback.classic.encoder.JsonEncoder"/>
  </appender>

  <root level="INFO">
    <appender-ref ref="STDERR"/>
  </root>

  <logger name="com.robsartin.segue" level="INFO"/>
</configuration>
```

**Verify `ch.qos.logback.classic.encoder.JsonEncoder` exists in the Logback version Boot 4.1.1
brings.** If it does not, use Boot's own structured-logging support instead
(`logging.structured.format.console: ecs` in `application.yaml`, with the console appender
targeting stderr) and report which you used and why. Do not invent an encoder class name.

- [ ] **Step 2: Write the test**

```java
package com.robsartin.segue.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logging must not touch stdout. On the stdio transport stdout is the protocol channel, and
 * a stray line there is not a cosmetic problem — it corrupts the JSON-RPC stream.
 */
class LoggingTargetsStderrTest {

  @Test
  @DisplayName("a log line goes to stderr and stdout stays untouched")
  void logsGoToStderrOnly() {
    PrintStream originalOut = System.out;
    PrintStream originalErr = System.err;
    ByteArrayOutputStream capturedOut = new ByteArrayOutputStream();
    ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();

    try {
      System.setOut(new PrintStream(capturedOut, true, StandardCharsets.UTF_8));
      System.setErr(new PrintStream(capturedErr, true, StandardCharsets.UTF_8));

      Logger log = LoggerFactory.getLogger(LoggingTargetsStderrTest.class);
      log.info("a marker line that must not reach stdout");

      assertThat(capturedOut.toString(StandardCharsets.UTF_8)).isEmpty();
      assertThat(capturedErr.toString(StandardCharsets.UTF_8)).contains("must not reach stdout");
    } finally {
      System.setOut(originalOut);
      System.setErr(originalErr);
    }
  }
}
```

**Note:** Logback resolves `System.err` once at configuration time, so swapping the stream
after the appender is initialised may not be observed. If the test fails for that reason
rather than a real one, say so and switch to asserting the resolved appender's target via
Logback's context API instead. **Report which approach you used** — an assertion that passes
because it is measuring the wrong thing is worse than none.

- [ ] **Step 3: Verify, gate, commit**

```bash
./gradlew test --tests '*LoggingTargetsStderrTest'
./gradlew spotlessApply && ./gradlew clean check
git add src/main/resources/logback-spring.xml src/test/java/com/robsartin/segue/app/LoggingTargetsStderrTest.java
git commit -m "feat: send structured logs to stderr so stdout stays the protocol channel"
```

---

### Task 4: Request correlation

**Files:**
- Create: `src/main/java/com/robsartin/segue/mcp/CorrelationId.java`
- Create: `src/test/java/com/robsartin/segue/mcp/CorrelationIdTest.java`

**Interfaces:**
- Consumes: `UuidV7` (Task 2)
- Produces: `CorrelationId.begin()` returning `String`; `CorrelationId.current()` returning `String`; `CorrelationId.clear()`.

- [ ] **Step 1: Write the failing test**

```java
package com.robsartin.segue.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class CorrelationIdTest {

  @AfterEach
  void clear() {
    CorrelationId.clear();
  }

  @Test
  @DisplayName("begin mints an id and puts it in MDC")
  void beginPutsIdInMdc() {
    String id = CorrelationId.begin();

    assertThat(id).isNotBlank();
    assertThat(MDC.get(CorrelationId.KEY)).isEqualTo(id);
    assertThat(CorrelationId.current()).isEqualTo(id);
  }

  @Test
  @DisplayName("two requests get different ids")
  void idsAreDistinct() {
    String first = CorrelationId.begin();
    CorrelationId.clear();
    String second = CorrelationId.begin();

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  @DisplayName("ids sort in the order they were minted")
  void idsSortChronologically() {
    // Time-ordered so a log tail reads chronologically. This is why UUIDv7 and not v4.
    String first = CorrelationId.begin();
    CorrelationId.clear();
    try {
      Thread.sleep(2);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    String second = CorrelationId.begin();

    assertThat(first).isLessThan(second);
  }

  @Test
  @DisplayName("clear removes it, and current is empty outside a request")
  void clearRemovesIt() {
    CorrelationId.begin();
    CorrelationId.clear();

    assertThat(MDC.get(CorrelationId.KEY)).isNull();
    assertThat(CorrelationId.current()).isEmpty();
  }
}
```

- [ ] **Step 2: Run it, confirm it fails**

- [ ] **Step 3: Write it**

```java
package com.robsartin.segue.mcp;

import com.robsartin.segue.support.UuidV7;
import org.slf4j.MDC;

/**
 * A time-ordered identifier for one tool call, carried in MDC so every log line for that call
 * can be found from one string.
 *
 * <p>The identifier is also included in the text of failed tool results, so an error a user
 * sees in a conversation can be pasted straight into a log search. That is the difference
 * between debuggable and not (ADR 29).
 *
 * <p>Note the stdio transport has no header layer at all — per-request metadata travels in the
 * JSON-RPC body — so there is nothing to propagate a trace context from. This identifier is the
 * only correlation available there.
 */
public final class CorrelationId {

  /** MDC key, and the field name in the structured log. */
  public static final String KEY = "segue.request.id";

  private CorrelationId() {}

  /** Mint an identifier for this request and publish it to MDC. */
  public static String begin() {
    String id = UuidV7.generate().toString();
    MDC.put(KEY, id);
    return id;
  }

  /** The current request's identifier, or empty outside a request. */
  public static String current() {
    String id = MDC.get(KEY);
    return id == null ? "" : id;
  }

  /** Remove it. Always call this when the request ends, or it leaks into the next one. */
  public static void clear() {
    MDC.remove(KEY);
  }
}
```

- [ ] **Step 4: Verify, gate, commit**

```bash
./gradlew test --tests '*CorrelationIdTest'
./gradlew spotlessApply && ./gradlew clean check
git add src/main/java/com/robsartin/segue/mcp/CorrelationId.java src/test/java/com/robsartin/segue/mcp/CorrelationIdTest.java
git commit -m "feat: correlate each tool call with a time-ordered identifier"
```

---

### Task 5: Beans, and rebuilding the graph at boot

**Files:**
- Create: `src/main/java/com/robsartin/segue/app/SegueProperties.java`
- Create: `src/main/java/com/robsartin/segue/app/SegueConfiguration.java`
- Create: `src/test/java/com/robsartin/segue/app/SegueConfigurationTest.java`
- Modify: `src/test/java/com/robsartin/segue/app/SegueApplicationTest.java` (remove the `@Disabled` from Task 1)

**Interfaces:**
- Consumes: `SqliteAssertionLog`, `TinkerGraphStore`, `WikidataClient`, `WikidataEntityResolver`, `WikidataSourceAdapter`, `IngestService`, `GraphProjector`
- Produces: beans for `AssertionLog`, `GraphStore`, `EntityResolver`, `List<SourceAdapter>`, `IngestService`.

- [ ] **Step 1: Properties**

```java
package com.robsartin.segue.app;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param database where the assertion log lives — a single file, per ADR 24
 * @param maxNewEdges default bound on one expansion
 */
@ConfigurationProperties(prefix = "segue")
public record SegueProperties(Path database, int maxNewEdges) {

  public SegueProperties {
    if (maxNewEdges <= 0) {
      maxNewEdges = 200;
    }
  }
}
```

Bind `segue.expand.max-new-edges` to `maxNewEdges` — **check whether relaxed binding maps the
nested key to a flat record component.** If it does not, flatten the property to
`segue.max-new-edges` in `application.yaml` and say so.

- [ ] **Step 2: Configuration**

```java
package com.robsartin.segue.app;

import com.robsartin.segue.ingest.GraphProjector;
import com.robsartin.segue.ingest.IngestService;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.port.EntityResolver;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.port.SourceAdapter;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.tinker.TinkerGraphStore;
import com.robsartin.segue.wikidata.WikidataClient;
import com.robsartin.segue.wikidata.WikidataEntityResolver;
import com.robsartin.segue.wikidata.WikidataSourceAdapter;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring.
 *
 * <p>This is the only place that knows about every layer, and that is deliberate: the adapters
 * are plain Java constructed here, so they stay testable without an application context and a
 * new source needs no framework knowledge (ADR 25).
 */
@Configuration
@EnableConfigurationProperties(SegueProperties.class)
public class SegueConfiguration {

  private static final Logger log = LoggerFactory.getLogger(SegueConfiguration.class);

  @Bean(destroyMethod = "close")
  AssertionLog assertionLog(SegueProperties properties) {
    return new SqliteAssertionLog(properties.database());
  }

  @Bean(destroyMethod = "close")
  GraphStore graphStore(AssertionLog assertionLog) {
    // The graph is a projection of the log (ADR 19). Rebuilding it at boot is what makes
    // that true rather than aspirational — and what makes the engine choice reversible.
    GraphStore store = new TinkerGraphStore();
    long replayed = GraphProjector.project(assertionLog, store);
    log.info("replayed {} assertions into {}", replayed, store.id());
    return store;
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  WikidataEntityResolver wikidataEntityResolver(Clock clock) {
    return new WikidataEntityResolver(new WikidataClient(), clock);
  }

  @Bean
  EntityResolver entityResolver(WikidataEntityResolver wikidata) {
    return wikidata;
  }

  @Bean
  List<SourceAdapter> sourceAdapters(WikidataEntityResolver resolver, Clock clock) {
    return List.of(new WikidataSourceAdapter(resolver, clock));
  }

  @Bean
  IngestService ingestService(AssertionLog assertionLog, GraphStore graphStore) {
    return new IngestService(assertionLog, graphStore);
  }
}
```

**A `List<SourceAdapter>` bean can collide with Spring's collection injection.** If it does,
wrap it in a small `SourceAdapters` record holding the list and inject that instead. Report
which you did.

- [ ] **Step 3: Test the wiring, including boot replay**

```java
package com.robsartin.segue.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The context wires the real stack, and the graph is rebuilt from the log at startup. */
@SpringBootTest
class SegueConfigurationTest {

  @TempDir static Path tempDir;

  @DynamicPropertySource
  static void seedDatabase(DynamicPropertyRegistry registry) {
    Path db = tempDir.resolve("boot.db");
    try (SqliteAssertionLog log = new SqliteAssertionLog(db)) {
      log.append(
          new NodeAssertion(
              "Q192668",
              NodeKind.PERSON,
              "Nick Cave",
              new Provenance("wikidata", "Q192668", Instant.parse("2026-08-24T09:00:00Z"), 1.0)));
    }
    registry.add("segue.database", db::toString);
  }

  @Autowired GraphStore graphStore;

  @Test
  @DisplayName("a claim written before startup is in the graph after it")
  void replaysTheLogAtBoot() {
    assertThat(graphStore.node("Q192668")).isPresent();
    assertThat(graphStore.node("Q192668").orElseThrow().label()).isEqualTo("Nick Cave");
  }
}
```

- [ ] **Step 4: Re-enable the context test**

Remove the `@Disabled` Task 1 added to `SegueApplicationTest`, and confirm it passes.

- [ ] **Step 5: Verify, gate, commit**

```bash
./gradlew test --tests '*SegueConfigurationTest' --tests '*SegueApplicationTest'
./gradlew spotlessApply && ./gradlew clean check
git add src/main/java/com/robsartin/segue/app src/test/java/com/robsartin/segue/app
git commit -m "feat: wire the stack and rebuild the graph from the log at boot"
```

---

### Task 6: SegueService, the facade

**Files:**
- Create: `src/main/java/com/robsartin/segue/mcp/ToolResult.java`
- Create: `src/main/java/com/robsartin/segue/mcp/SegueService.java`
- Create: `src/test/java/com/robsartin/segue/mcp/SegueServiceTest.java`

**Interfaces:**
- Consumes: `EntityResolver`, `GraphStore`, `IngestService`, `SourceAdapter`, `PathRanking`
- Produces: `SegueService` with `search`, `addEntity`, `expandEntity`, `getEntity`, `findPaths`. Task 7's tools call only this.

- [ ] **Step 1: Write the result shape**

```java
package com.robsartin.segue.mcp;

import java.util.List;

/**
 * What a tool returns.
 *
 * <p>Carries a machine-readable payload AND a note about what did not happen, because three
 * different outcomes otherwise look identical: the source was unreachable, the entity genuinely
 * had nothing, or the result was cut short by the caller's own bound. The MCP specification
 * expects execution errors to come back as readable text the model can act on rather than as
 * protocol errors, so the shortfall belongs in the result (ADR 27).
 *
 * @param outcome one of "ok", "partial", "error" — the model reads this first
 * @param detail human-readable, and the only place a correlation id appears on failure
 */
public record ToolResult<T>(String outcome, String detail, T payload) {

  public static <T> ToolResult<T> ok(String detail, T payload) {
    return new ToolResult<>("ok", detail, payload);
  }

  public static <T> ToolResult<T> partial(String detail, T payload) {
    return new ToolResult<>("partial", detail, payload);
  }

  public static <T> ToolResult<List<T>> error(String detail) {
    return new ToolResult<>("error", detail, List.of());
  }
}
```

- [ ] **Step 2: Write the failing test**

Cover, at minimum: `search` returns candidates and writes nothing; `addEntity` records a node
and is idempotent on a second call; `expandEntity` reports `partial` when the source was
unavailable and when the result was truncated, and `ok` otherwise; `getEntity` groups
neighbours by edge type; `findPaths` returns routes ordered most-trustworthy-first.

Use the fixture-backed adapter and an in-memory SQLite log — **no network in these tests.**

Write the test first and watch it fail before implementing.

- [ ] **Step 3: Write the facade**

The facade owns the ports and does exactly five things. Key requirements:

- `search(query, kind, limit)` — delegates to `EntityResolver.search`, writes nothing.
- `addEntity(qid)` — `EntityResolver.fetch`, then `IngestService.record`. Empty fetch is an
  `error` result naming the qid, not an exception.
- `expandEntity(qid, maxNewEdges)` — resolves the seed from the graph (error if unknown), runs
  every `SourceAdapter` that `supports` its kind, and **records neighbour nodes before the
  edges that reference them** or `GraphStore.record` will throw. Map `ExpandResult`'s
  `sourceUnavailable` and `truncated` onto `partial` with a detail line saying which.
- `getEntity(qid)` — node plus neighbours grouped by `typeCode`.
- `findPaths(from, to, maxHops)` — `GraphStore.paths` then `PathRanking.rank`. **Ranked, not
  raw**: ADR 31 exists because shortest is not most trustworthy.

Every failure path returns a `ToolResult` carrying `CorrelationId.current()` in its detail.
**Nothing throws out of this class** except programmer error (null arguments).

- [ ] **Step 4: Verify, gate, commit**

---

### Task 7: The five tools

**Files:**
- Create: `src/main/java/com/robsartin/segue/mcp/EntityTools.java`
- Create: `src/main/java/com/robsartin/segue/mcp/GraphTools.java`
- Create: `src/test/java/com/robsartin/segue/mcp/ToolSurfaceTest.java`

**Interfaces:**
- Consumes: `SegueService` (Task 6)
- Produces: five `@McpTool` methods.

- [ ] **Step 1: Confirm how the starter discovers tools**

Before writing anything, determine whether `@McpTool` methods are found by component scanning
alone or need a registration bean. Read the autoconfiguration in
`spring-ai-autoconfigure-mcp-server-common:2.0.1`. **Record what you find in your report** —
do not guess, and do not copy a pattern from another project.

- [ ] **Step 2: Write the tools**

Five methods, split across two classes. Names exactly: `search_entities`, `add_entity`,
`expand_entity`, `get_entity`, `find_paths`. Each:

- annotated `@McpTool(name = ..., description = ..., generateOutputSchema = true)` — the
  output schema is required by ADR 26, and `generateOutputSchema` is what produces it;
- parameters annotated `@McpToolParam(required = ..., description = ...)`;
- delegates to `SegueService` and returns its `ToolResult`;
- begins with `CorrelationId.begin()` and ends with `CorrelationId.clear()` in a `finally`.

**The `search_entities` description must state that `kind` does not filter.** Wikidata's
`wbsearchentities` does not return `P31`, so the kind is unknowable at search time. A tool
description implying a working filter would mislead the model into believing an empty result
means "no such entity". Say plainly: results are not filtered by kind; use the description to
disambiguate.

**`assert_edge` is deliberately absent** (ADR 26) until corroboration is visibly working. Do
not add it.

- [ ] **Step 3: Test the surface**

Assert, without starting a transport: all five tools are discovered; their names match MCP's
charset rules; each declares a non-blank description; `search_entities`'s description mentions
that kind does not filter. Then extend the existing ArchUnit tool-name rule if it does not
already cover these.

- [ ] **Step 4: Verify, gate, commit**

---

### Task 8: The stdio profile, and proving stdout stays clean

**Files:**
- Modify: `src/main/resources/application.yaml`
- Create: `src/test/java/com/robsartin/segue/app/StdioPurityTest.java`
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: everything
- Produces: a runnable stdio MCP server.

- [ ] **Step 1: Add the stdio profile**

```yaml
---
spring:
  config:
    activate:
      on-profile: stdio
  main:
    web-application-type: none
  ai:
    mcp:
      server:
        stdio: true
```

- [ ] **Step 2: The test that actually protects the protocol**

Launch the built application as a **subprocess** with the `stdio` profile, send it a
`tools/list` JSON-RPC request on stdin, and assert that **every line it writes to stdout parses
as JSON**. Capture stderr separately and assert the logs went there.

This is the test that matters. The ArchUnit rule cannot see into a dependency that misbehaves,
or into the framework's own startup output — only running the process can. If launching a
subprocess proves impractical in the test harness, **say so and propose an alternative rather
than substituting a weaker in-process assertion that would pass regardless.**

- [ ] **Step 3: Run it by hand, once**

Build and launch the server, send a `tools/list` request, and confirm five tools come back.
Paste the actual response into your report. This is the first time the thing exists as a
working server; look at it.

- [ ] **Step 4: Update CLAUDE.md**

Add `app/`, `mcp/` and `support/` to the architecture map. Record in the gotchas:

- stdout is the protocol channel on stdio; all logging goes to stderr; enforced by an
  ArchUnit rule AND a subprocess purity test, because the rule alone cannot see dependencies.
- the MCP protocol revision is pinned to 2025-11-25 because that is what the Java SDK speaks,
  not because it is current.
- `search_entities`'s `kind` argument does not filter, and its tool description says so.

**Do not name dependency versions.**

- [ ] **Step 5: Gate, commit, push, open the PR**

```bash
./gradlew spotlessApply && ./gradlew clean check
git add -A && git commit -m "feat: run segue as an MCP server over stdio"
git push -u origin 4-mcp-server
gh pr create --fill-first
```

Then edit the body: test count and coverage, that it closes part of #4, the pinned protocol
revision, the `kind`-filter caveat, and that the HTTP transport follows in 4b.

- [ ] **Step 6: Stop for review.** Do not merge.

---

## Notes for the implementer

**stdout is the protocol.** If you are ever unsure whether something prints, assume it does and
check. This is the one failure in this increment that is silent and total.

**Three API facts are verified and listed at the top of this plan.** Everything else about
Spring AI — how tools are discovered, whether a bean is needed, what the starter autoconfigures
— you must **read from the artifacts** rather than assume. This project has already shipped
three invented Wikidata QIDs that turned out to be real unrelated entities; the lesson
generalises to API names.

**Never lower a coverage threshold.** If the gate fails, report the numbers.

**The build must be green at every commit.** Task 1's `@Disabled` is the single sanctioned
exception, and Task 5 removes it.
