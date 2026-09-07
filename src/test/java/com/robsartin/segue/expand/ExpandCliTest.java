package com.robsartin.segue.expand;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.robsartin.segue.port.ExpandContext;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The parser's refusals and defaults, driven without touching a database or a network. Every path
 * here is either invented or the test's own {@code @TempDir} (ADR 33, issue #37).
 */
class ExpandCliTest {

  @TempDir private Path home;

  @Test
  @DisplayName("--db is required, and the refusal quotes the default it would have resolved to")
  void shouldRefuseWhenNoDatabaseIsNamed() {
    assertThatThrownBy(() -> ExpandCli.parse(new String[] {}, null, home.toString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--db is required")
        .hasMessageContaining(home.resolve(".segue").resolve("segue.db").toString());
    assertThat(home.resolve(".segue")).doesNotExist();
  }

  @Test
  @DisplayName("SEGUE_DB does not satisfy --db, and the refusal quotes the path it would have used")
  void shouldRefuseWhenOnlySegueDbNamesTheDatabase() {
    String envDatabase = home.resolve("elsewhere.db").toString();

    assertThatThrownBy(() -> ExpandCli.parse(new String[] {}, envDatabase, home.toString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--db is required")
        .hasMessageContaining(envDatabase);
  }

  @Test
  @DisplayName("--db given twice is refused, because last-wins is worst on the flag read back")
  void shouldRefuseWhenTheDatabaseIsNamedTwice() {
    assertThatThrownBy(
            () -> ExpandCli.parse(new String[] {"--db", "a", "--db", "b"}, null, home.toString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("was given twice");
  }

  @Test
  @DisplayName("--max-new-edges defaults to the shared expansion bound when none is given")
  void shouldDefaultToTheSharedBoundWhenNoMaxNewEdgesIsGiven() {
    assertThat(
            ExpandCli.parse(new String[] {"--db", "db.sqlite"}, null, home.toString())
                .maxNewEdges())
        .isEqualTo(ExpandContext.defaults().maxNewEdges());
  }

  @Test
  @DisplayName("--max-new-edges is honoured when one is given")
  void shouldHonourTheBoundWhenOneIsGiven() {
    assertThat(
            ExpandCli.parse(
                    new String[] {"--db", "db.sqlite", "--max-new-edges", "25"},
                    null,
                    home.toString())
                .maxNewEdges())
        .isEqualTo(25);
  }

  @Test
  @DisplayName("--max-new-edges at or below zero is refused before any entity is expanded")
  void shouldRefuseTheBoundWhenItIsNotPositive() {
    assertThatThrownBy(
            () ->
                ExpandCli.parse(
                    new String[] {"--db", "db.sqlite", "--max-new-edges", "0"},
                    null,
                    home.toString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--max-new-edges must be positive");
  }

  @Test
  @DisplayName("--max-new-edges that is not a number is refused with a usage error")
  void shouldRefuseTheBoundWhenItIsNotANumber() {
    assertThatThrownBy(
            () ->
                ExpandCli.parse(
                    new String[] {"--db", "db.sqlite", "--max-new-edges", "lots"},
                    null,
                    home.toString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--max-new-edges");
  }

  @Test
  @DisplayName("a run is not a dry run unless the flag is given")
  void shouldNotBeADryRunWhenTheFlagIsAbsent() {
    assertThat(ExpandCli.parse(new String[] {"--db", "db.sqlite"}, null, home.toString()).dryRun())
        .isFalse();
  }

  @Test
  @DisplayName("a run is a dry run when the flag is given")
  void shouldBeADryRunWhenTheFlagIsGiven() {
    assertThat(
            ExpandCli.parse(new String[] {"--db", "db.sqlite", "--dry-run"}, null, home.toString())
                .dryRun())
        .isTrue();
  }

  @Test
  @DisplayName("an unknown option is refused with a usage error")
  void shouldRefuseAnUnknownOption() {
    assertThatThrownBy(
            () ->
                ExpandCli.parse(
                    new String[] {"--db", "db.sqlite", "--frobnicate", "x"}, null, home.toString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown option --frobnicate");
  }
}
