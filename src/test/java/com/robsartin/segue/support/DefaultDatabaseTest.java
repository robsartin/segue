package com.robsartin.segue.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the one resolution that used to be six identical copies, one per dev tool's {@code parse}.
 * Read from the six before this test was written: {@code ExportCli}, {@code OwnCli}, {@code
 * RateCli}, {@code RatingsCli}, {@code RecommendCli} and {@code RetractCli} all resolved a default
 * database with the exact same rule, byte for byte — {@code SEGUE_DB} if set and not blank,
 * otherwise {@code ${user.home}/.segue/segue.db} — and none of the six treated the {@code --db}
 * flag's own value as blank-checked; whatever the flag carries wins outright. No disagreement was
 * found between them.
 */
class DefaultDatabaseTest {

  @Test
  @DisplayName("the --db flag wins when given, even with SEGUE_DB also set")
  void theFlagWinsWhenGiven() {
    Path resolved =
        DefaultDatabase.resolve("/from/flag/segue.db", "/from/env/segue.db", "/home/rob");

    assertThat(resolved).isEqualTo(Path.of("/from/flag/segue.db"));
  }

  @Test
  @DisplayName("SEGUE_DB wins when no flag is given")
  void theEnvironmentVariableWinsWhenNoFlagIsGiven() {
    Path resolved = DefaultDatabase.resolve(null, "/from/env/segue.db", "/home/rob");

    assertThat(resolved).isEqualTo(Path.of("/from/env/segue.db"));
  }

  @Test
  @DisplayName("user.home/.segue/segue.db is the fallback when neither flag nor SEGUE_DB is given")
  void fallsBackToTheUserHomeDatabaseWhenNeitherIsGiven() {
    Path resolved = DefaultDatabase.resolve(null, null, "/home/rob");

    assertThat(resolved).isEqualTo(Path.of("/home/rob", ".segue", "segue.db"));
  }

  @Test
  @DisplayName("a blank SEGUE_DB is treated as unset, exactly as every one of the six tools did")
  void treatsABlankEnvironmentVariableAsUnset() {
    Path resolved = DefaultDatabase.resolve(null, "   ", "/home/rob");

    assertThat(resolved).isEqualTo(Path.of("/home/rob", ".segue", "segue.db"));
  }
}
