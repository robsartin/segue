package com.robsartin.segue.retract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Everything that can be refused is refused before a database is opened. */
class RetractCliTest {

  private static RetractCli.Options parse(String... args) {
    return RetractCli.parse(args, null, "/home/invented");
  }

  @Test
  @DisplayName("qid and reason are both read, and the database defaults like the server's")
  void parsesTheRequiredArguments() {
    RetractCli.Options options = parse("--qid", "Q900101", "--reason", "wrong entity");

    assertThat(options.qid()).isEqualTo("Q900101");
    assertThat(options.reason()).isEqualTo("wrong entity");
    assertThat(options.dryRun()).isFalse();
    assertThat(options.database()).isEqualTo(Path.of("/home/invented", ".segue", "segue.db"));
  }

  @Test
  @DisplayName("SEGUE_DB wins over the home-directory default, exactly as the server reads it")
  void readsTheEnvironmentOverride() {
    RetractCli.Options options =
        RetractCli.parse(
            new String[] {"--qid", "Q900101", "--reason", "why"},
            "/elsewhere/segue.db",
            "/home/invented");

    assertThat(options.database()).isEqualTo(Path.of("/elsewhere/segue.db"));
  }

  @Test
  @DisplayName("--dry-run takes no value")
  void parsesDryRun() {
    assertThat(parse("--qid", "Q900101", "--reason", "why", "--dry-run").dryRun()).isTrue();
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
