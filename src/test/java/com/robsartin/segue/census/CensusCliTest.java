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
 * refusal that opened one first would fail twice.
 *
 * <p><b>The order of the two refusals is held through {@link CensusCli#run}, not through {@link
 * CensusCli#parse}.</b> {@code parse} has no {@code Files.exists} call to come before, so no
 * assertion about it can pin an order; the two tests at the foot of this class drive {@code run},
 * which has both checks in it, and each asserts the message it wants <b>and</b> the message it must
 * not see — {@code RetractCliTest}'s shape, for the reason {@code CensusCli.run}'s javadoc gives.
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

  @Test
  @DisplayName("the census refuses a database that is not there rather than counting an empty one")
  void shouldRefuseTheNamedDatabaseWhenItIsNotThere() {
    // Both sqlite constructors create the file and its schema if absent, so without this refusal a
    // mistyped path would produce a census of nothing rather than an error - and a census of
    // nothing is evidence that reads as a finding.
    Path absent = home.resolve("nothing.db");

    assertThatThrownBy(
            () -> CensusCli.run(new String[] {"--db", absent.toString()}, null, "/home/invented"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no segue database")
        .hasMessageContaining("nothing to count")
        .hasMessageNotContaining("--db is required");

    assertThat(absent).as("the database was not created").doesNotExist();
  }

  @Test
  @DisplayName("a missing --db is refused before a missing database, so the operator is told which")
  void shouldRefuseTheMissingFlagBeforeTheMissingFileWhenNeitherIsGiven() {
    // The property is an ORDER, and it is only visible through run(): parse() refuses first, or the
    // operator is told "no segue database at ..." about a path they never typed. Asserting the
    // message that must NOT appear is what makes this a test of the order rather than of the
    // refusal - a run with the checks the other way round says the other sentence.
    assertThatThrownBy(() -> CensusCli.run(new String[] {}, null, home.toString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--db is required")
        .hasMessageNotContaining("no segue database");

    assertThat(home.resolve(".segue"))
        .as("no database was opened, so none was created")
        .doesNotExist();
  }
}
