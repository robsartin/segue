package com.robsartin.segue.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SignalsCliTest {

  private static SignalsCli.Options parse(String... args) {
    return SignalsCli.parse(args, null, "/home/someone");
  }

  @Test
  @DisplayName("an import needs the mapping and the review file together")
  void shouldRequireBothAnswerFilesToImport() {
    assertThatThrownBy(() -> parse("--signals", "a.csv", "--mapping", "m.csv"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--mapping and --review");
  }

  @Test
  @DisplayName("--names-out is its own mode and is not combined with an import")
  void shouldRefuseNamesOutWithAnImport() {
    assertThatThrownBy(
            () -> parse("--signals", "a.csv", "--names-out", "n.csv", "--mapping", "m.csv"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--names-out");
  }

  @Test
  @DisplayName("--signals is required")
  void shouldRequireTheExport() {
    assertThatThrownBy(() -> parse("--names-out", "n.csv"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--signals is required");
  }

  @Test
  @DisplayName("an unknown option is refused with the usage")
  void shouldRefuseAnUnknownOption() {
    assertThatThrownBy(() -> parse("--bogus", "x"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown option --bogus")
        .hasMessageContaining("usage:");
  }

  @Test
  @DisplayName("the source defaults to apple-music and the database to the default one")
  void shouldDefaultTheSourceAndTheDatabase() {
    SignalsCli.Options options =
        parse("--signals", "a.csv", "--mapping", "m.csv", "--review", "r.csv");

    assertThat(options.source()).isEqualTo("apple-music");
    assertThat(options.db().toString()).endsWith("segue.db");
    assertThat(options.importing()).isTrue();
  }
}
