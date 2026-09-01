package com.robsartin.segue.retract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Everything that can be refused is refused before a database is opened. */
class RetractCliTest {

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
  @DisplayName("qid, reason and the database named by --db are all read")
  void parsesTheRequiredArguments() {
    RetractCli.Options options = parse("--qid", "Q900101", "--reason", "wrong entity");

    assertThat(options.qid()).isEqualTo("Q900101");
    assertThat(options.reason()).isEqualTo("wrong entity");
    assertThat(options.dryRun()).isFalse();
    assertThat(options.database()).isEqualTo(Path.of(DATABASE));
  }

  @Test
  @DisplayName("--dry-run takes no value")
  void parsesDryRun() {
    assertThat(parse("--qid", "Q900101", "--reason", "why", "--dry-run").dryRun()).isTrue();
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
                    new String[] {"--qid", "Q900101", "--reason", "wrong entity"},
                    null,
                    "/home/invented"))
        .withMessageContaining("--db")
        .withMessageContaining(Path.of("/home/invented", ".segue", "segue.db").toString());
  }

  @Test
  @DisplayName("--qid is required")
  void requiresQid() {
    assertThatIllegalArgumentException().isThrownBy(() -> parse("--reason", "why"));
  }

  @Test
  @DisplayName("--reason is required: the log has to say why, and it is never edited afterwards")
  void requiresReason() {
    assertThatIllegalArgumentException().isThrownBy(() -> parse("--qid", "Q900101"));
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
        .isThrownBy(() -> parse("--qid", "Q900101", "--reason", "why", "--force"));
  }

  @Test
  @DisplayName("a flag with no value is refused rather than read past the end of the arguments")
  void refusesAMissingValue() {
    assertThatIllegalArgumentException().isThrownBy(() -> parse("--qid"));
  }
}
