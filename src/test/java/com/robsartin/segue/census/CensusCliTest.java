package com.robsartin.segue.census;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The refusal, and the two ways round it that must not work — the shape {@code RetractCliTest} and
 * {@code OwnCliTest} take for ADR 60's two claim tools, applied here for ADR 63's reason instead.
 *
 * <p>Each test also asserts that <b>no database was created under the test's own home</b>: a
 * refusal that opened one first would fail twice, which is what pins the refusal ahead of {@code
 * Files.exists}.
 */
class CensusCliTest {

  @TempDir private Path home;

  @Test
  @DisplayName("the census refuses to run when --db does not name a database")
  void shouldRefuseWhenTheDatabaseIsNotNamed() {
    assertThatThrownBy(() -> CensusCli.parse(new String[] {}, null, home.toString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--db is required")
        .hasMessageContaining(home.resolve(".segue").resolve("segue.db").toString());

    assertThat(home.resolve(".segue")).doesNotExist();
  }

  @Test
  @DisplayName("SEGUE_DB does not satisfy --db, and is quoted back in the refusal")
  void shouldRefuseWhenOnlySegueDbNamesADatabase() {
    Path inherited = home.resolve("inherited.db");

    assertThatThrownBy(
            () -> CensusCli.parse(new String[] {}, inherited.toString(), home.toString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--db is required")
        .hasMessageContaining(inherited.toString())
        .hasMessageContaining("SEGUE_DB is inherited");

    assertThat(inherited).doesNotExist();
  }

  @Test
  @DisplayName("the named database is what the options carry, whatever SEGUE_DB says")
  void shouldTakeTheFlagWhenBothTheFlagAndSegueDbNameADatabase() throws Exception {
    Path named = Files.createFile(home.resolve("named.db"));

    CensusCli.Options options =
        CensusCli.parse(
            new String[] {"--db", named.toString()},
            home.resolve("inherited.db").toString(),
            home.toString());

    assertThat(options.database()).isEqualTo(named);
  }
}
