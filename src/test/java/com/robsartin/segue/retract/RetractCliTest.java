package com.robsartin.segue.retract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.support.RequiredDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Everything that can be refused is refused before a database is opened. */
class RetractCliTest {

  @TempDir Path dir;

  private static final String DATABASE = "/graphs/some.db";

  /** Every valid invocation now names --db, so every test of anything else has to name it too. */
  private static RetractCli.Options parse(String... args) {
    String[] withDatabase = new String[args.length + 2];
    withDatabase[0] = "--db";
    withDatabase[1] = DATABASE;
    System.arraycopy(args, 0, withDatabase, 2, args.length);
    return RetractCli.parse(withDatabase, null, "/home/invented");
  }

  @Test
  @DisplayName("should read the qid, the reason and the database when --db names one")
  void shouldReadTheQidTheReasonAndTheDatabaseWhenDbNamesOne() {
    RetractCli.Options options = parse("--qid", "Q0900101", "--reason", "wrong entity");

    assertThat(options.qid()).isEqualTo("Q0900101");
    assertThat(options.reason()).isEqualTo("wrong entity");
    assertThat(options.dryRun()).isFalse();
    assertThat(options.database()).isEqualTo(Path.of(DATABASE));
  }

  @Test
  @DisplayName("--dry-run takes no value")
  void parsesDryRun() {
    assertThat(parse("--qid", "Q0900101", "--reason", "why", "--dry-run").dryRun()).isTrue();
  }

  @Test
  @DisplayName(
      "should refuse when --db is not given, naming the flag and the path it would have used")
  void shouldRefuseWhenTheDatabaseIsNotNamed() {
    // Issue #179: `./gradlew own --args="mint …"` appended to the owner's real log because the
    // database defaulted. A retraction is worse - the log is append-only, so a retraction of the
    // wrong entity cannot be taken back, only appended over. The message has to name the flag AND
    // the path it would have used, so the owner's next command is a copy-paste, not a lookup.
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                RetractCli.parse(
                    new String[] {"--qid", "Q0900101", "--reason", "wrong entity"},
                    null,
                    "/home/invented"))
        // The whole sentence, from the one class that owns it, so this tool cannot drift away
        // from its sibling's wording or from the env-or-home rule behind the path.
        .withMessageContaining(RequiredDatabase.refusal(null, "/home/invented"));
  }

  @Test
  @DisplayName("should refuse when only SEGUE_DB is set, and name it as the path to pass to --db")
  void shouldRefuseWhenOnlySegueDbIsSet() {
    // The case that would silently re-open the hole. An agent's shell is initialised from the
    // owner's profile, so it inherits SEGUE_DB; the variable cannot tell the owner apart from an
    // agent running as the owner, and a flag typed per invocation can. It is still the path the
    // refusal quotes back, because it is the database this would have used.
    assertThatThrownBy(
            () ->
                RetractCli.parse(
                    new String[] {"--qid", "Q0900101", "--reason", "why"},
                    "/elsewhere/segue.db",
                    "/home/invented"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(RequiredDatabase.refusal("/elsewhere/segue.db", "/home/invented"))
        .hasMessageContaining("/elsewhere/segue.db")
        .hasMessageNotContaining("/home/invented");
  }

  @Test
  @DisplayName("should refuse before opening any database when --dry-run is given without --db")
  void shouldRefuseBeforeOpeningAnyDatabaseWhenDryRunIsGivenWithoutTheDatabase() {
    // A dry run appends nothing, so the narrow argument says exempt it. Uniformity is worth more:
    // there is then no second path to reason about, and nobody has to remember which invocations
    // are exempt. Driven through run() rather than parse() because the property under test is an
    // ORDER - the refusal has to come before the Files.exists check, or the operator sees "no
    // segue database at …" and never learns that --db was the problem.
    Path home = dir.resolve("home");

    assertThatThrownBy(
            () ->
                RetractCli.run(
                    new String[] {"--qid", "Q0900101", "--reason", "why", "--dry-run"},
                    null,
                    home.toString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--db")
        .hasMessageNotContaining("no segue database");

    assertThat(Files.exists(home.resolve(".segue").resolve("segue.db")))
        .as("no database was opened, so none was created")
        .isFalse();
  }

  @Test
  @DisplayName("--qid is required")
  void requiresQid() {
    assertThatIllegalArgumentException().isThrownBy(() -> parse("--reason", "why"));
  }

  @Test
  @DisplayName("--reason is required: the log has to say why, and it is never edited afterwards")
  void requiresReason() {
    assertThatIllegalArgumentException().isThrownBy(() -> parse("--qid", "Q0900101"));
  }

  @Test
  @DisplayName("a qid that is not a qid is refused at the command line, naming the usage")
  void refusesSomethingThatIsNotAQid() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> parse("--qid", "the-highwaymen", "--reason", "why"))
        .withMessageContaining("--qid");
  }

  @Test
  @DisplayName("an unknown option is refused rather than ignored")
  void refusesUnknownOptions() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> parse("--qid", "Q0900101", "--reason", "why", "--force"));
  }

  @Test
  @DisplayName("a flag with no value is refused rather than read past the end of the arguments")
  void refusesAMissingValue() {
    assertThatIllegalArgumentException().isThrownBy(() -> parse("--qid"));
  }

  @Test
  @DisplayName("should refuse a database that is not there rather than creating one")
  void shouldRefuseADatabaseThatIsNotThereRatherThanCreatingOne() {
    // The refusal that was here before this branch, kept honest: the new --db check comes first
    // and must not have displaced it. The two are distinguishable, because they are different
    // mistakes - a flag nobody typed, and a path that does not name a database.
    Path absent = dir.resolve("nothing.db");

    assertThatThrownBy(
            () ->
                RetractCli.run(
                    new String[] {
                      "--db", absent.toString(), "--qid", "Q0900101", "--reason", "why"
                    },
                    null,
                    "/home/invented"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no segue database")
        .hasMessageNotContaining("--db is required");

    assertThat(Files.exists(absent)).as("the database was not created").isFalse();
  }

  @Test
  @DisplayName("should open the database and do its own work when --db names an existing one")
  void shouldOpenTheDatabaseWhenTheOneNamedByDbExists() {
    Path database = dir.resolve("segue.db");
    try (SqliteAssertionLog created = new SqliteAssertionLog(database)) {
      assertThat(created.readAll()).isEmpty();
    }

    // Past both refusals and into RetractRun, which reads the log and finds Q0900101 is not in the
    // projection. That message is only reachable from inside an opened database, so it is the
    // proof that a named --db still gets through.
    assertThatThrownBy(
            () ->
                RetractCli.run(
                    new String[] {
                      "--db",
                      database.toString(),
                      "--qid",
                      "Q0900101",
                      "--reason",
                      "why",
                      "--dry-run"
                    },
                    null,
                    "/home/invented"))
        .hasMessageContaining("nothing about Q0900101 is in the projection");
  }
}
