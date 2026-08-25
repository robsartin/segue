package com.robsartin.segue.app;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import java.util.Iterator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Logging must not touch stdout. On the stdio transport stdout is the protocol channel, and a stray
 * line there is not a cosmetic problem — it corrupts the JSON-RPC stream.
 *
 * <p>This does not swap {@code System.err} and capture bytes, for two independent reasons found by
 * running the naive version of this test (see task-3-report.md for both reproductions):
 *
 * <ul>
 *   <li>{@code logback-spring.xml} is a Spring Boot-only filename — vanilla Logback's own classpath
 *       auto-configuration never looks for it (only {@code logback.xml} / {@code
 *       logback-test.xml}). It is loaded by Spring Boot's {@code LoggingApplicationListener} during
 *       {@code SpringApplication.run}. A plain JUnit test with no Spring context never triggers
 *       that listener, so Logback falls back to its built-in default — a {@code ConsoleAppender}
 *       targeting {@code System.out}. Asserting on stream contents in a non-Spring test would
 *       therefore validate nothing about this project's actual configuration; it would validate
 *       Logback's factory default. This is why the test is {@code @SpringBootTest}: it has to go
 *       through the same boot path the real server uses.
 *   <li>Even once Spring is involved, {@link ConsoleAppender} resolves its target {@link
 *       java.io.OutputStream} once, when the appender starts, not on every write. Swapping {@code
 *       System.err} afterward is not guaranteed to be observed by an already-started appender, so a
 *       stream-capture assertion can pass or fail for reasons unrelated to the actual
 *       configuration.
 * </ul>
 *
 * <p>Instead this asserts against Logback's own configuration model: the root logger's appender is
 * a {@link ConsoleAppender} whose resolved {@code target} is {@code "System.err"}, encoding with
 * {@link StructuredLogEncoder}. That is deterministic regardless of test order or JVM reuse, and it
 * fails the instant {@code logback-spring.xml} points anywhere else.
 *
 * <p>FIX 10 of the increment-4a final review switched the encoder from Logback's own {@code
 * JsonEncoder} (valid JSON, but Logback's own field shape) to Spring Boot's {@code
 * StructuredLogEncoder}, which is what actually emits Elastic Common Schema — matching what ADR 30
 * decided rather than amending the ADR to match the code.
 */
@SpringBootTest
class LoggingTargetsStderrTest {

  @Test
  @DisplayName("the root logger's console appender is configured to target stderr as JSON")
  void consoleAppenderTargetsStderr() {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);

    Iterator<Appender<ILoggingEvent>> appenders = root.iteratorForAppenders();
    assertThat(appenders.hasNext()).as("root logger has a configured appender").isTrue();

    Appender<ILoggingEvent> appender = appenders.next();
    assertThat(appender).isInstanceOf(ConsoleAppender.class);

    ConsoleAppender<ILoggingEvent> consoleAppender = (ConsoleAppender<ILoggingEvent>) appender;
    assertThat(consoleAppender.getTarget())
        .as("appender target — must be stderr, never stdout, on the stdio transport")
        .isEqualTo("System.err");
    assertThat(consoleAppender.getEncoder())
        .as("encoder — Elastic Common Schema per ADR 30")
        .isInstanceOf(StructuredLogEncoder.class);

    assertThat(appenders.hasNext()).as("no second appender that could target stdout").isFalse();
  }
}
