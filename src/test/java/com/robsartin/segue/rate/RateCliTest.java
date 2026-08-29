package com.robsartin.segue.rate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.robsartin.segue.domain.Recommendations;
import com.robsartin.segue.rate.RateCli.Options;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Everything that can be refused before a store is opened (ADR 45's shape, ADR 46). */
class RateCliTest {

  private static final String HOME = "/home/invented";

  @TempDir private Path dir;

  private static Options parse(String... args) {
    return RateCli.parse(args, null, HOME);
  }

  @Test
  @DisplayName("one path is all it needs; everything else has a measured default")
  void oneFlagIsAllItNeeds() {
    Options options = parse("--known", "/tmp/known.csv");

    assertThat(options.known()).isEqualTo(Path.of("/tmp/known.csv"));
    assertThat(options.port()).isEqualTo(RateCli.DEFAULT_PORT);
    assertThat(options.database()).isEqualTo(Path.of(HOME, ".segue", "segue.db"));
    // The default run must deal exactly what it always has: the same floor `recommend` defaults
    // to, by reference rather than by a second copy of the number (issue #119).
    assertThat(options.minDegree()).isEqualTo(Recommendations.MIN_CANDIDATE_DEGREE);
  }

  @Test
  @DisplayName("the database defaults exactly as the server's does")
  void theDatabaseFollowsTheEnvironment() {
    Options options =
        RateCli.parse(new String[] {"--known", "/tmp/known.csv"}, "/elsewhere/segue.db", HOME);

    assertThat(options.database()).isEqualTo(Path.of("/elsewhere/segue.db"));
  }

  @Test
  @DisplayName("without a list of what you already have there is nothing to deal")
  void theKnownListIsRequired() {
    assertThatThrownBy(RateCliTest::parse)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--known is required");
  }

  @Test
  @DisplayName("the port is a dial the command line turns")
  void thePortIsADialTheCommandLineTurns() {
    Options options = parse("--known", "/tmp/known.csv", "--port", "9999");

    assertThat(options.port()).isEqualTo(9999);
  }

  @Test
  @DisplayName("--db overrides the environment default")
  void theDatabaseFlagOverridesTheEnvironment() {
    Options options =
        RateCli.parse(
            new String[] {"--known", "/tmp/known.csv", "--db", "/scratch/segue.db"},
            "/elsewhere/segue.db",
            HOME);

    assertThat(options.database()).isEqualTo(Path.of("/scratch/segue.db"));
  }

  @Test
  @DisplayName("an unknown option is refused rather than ignored")
  void anUnknownOptionIsRefused() {
    assertThatThrownBy(() -> parse("--known", "/tmp/known.csv", "--include-affinity"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--include-affinity");
  }

  @Test
  @DisplayName("a flag with no value is refused, naming the flag")
  void aFlagWithNoValueIsRefused() {
    assertThatThrownBy(() -> parse("--known", "/tmp/known.csv", "--port"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--port needs a value");
  }

  @Test
  @DisplayName("a number that is not a number is refused, naming the flag")
  void aNumberThatIsNotANumberIsRefused() {
    assertThatThrownBy(() -> parse("--known", "/tmp/known.csv", "--port", "lots"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--port");
  }

  @Test
  @DisplayName("--revise is parsed and off by default")
  void parsesRevise() {
    assertThat(RateCli.parse(new String[] {"--known", "k.csv"}, null, "/home/x").revise())
        .isEmpty();
    assertThat(
            RateCli.parse(new String[] {"--known", "k.csv", "--revise", "3"}, null, "/home/x")
                .revise())
        .hasValue(3);
  }

  @Test
  @DisplayName("a --revise outside the 1-5 scale is refused, naming the scale")
  void refusesAReviseOffTheScale() {
    assertThatThrownBy(
            () ->
                RateCli.parse(new String[] {"--known", "k.csv", "--revise", "9"}, null, "/home/x"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("1 to 5");
  }

  @Test
  @DisplayName("the floor is a dial the command line turns, matching --recommend's flag")
  void theFloorIsADialTheCommandLineTurns() {
    Options options = parse("--known", "/tmp/known.csv", "--min-degree", "5");

    assertThat(options.minDegree()).isEqualTo(5);
  }

  @Test
  @DisplayName("a floor below two would let a node with one edge be normalised to the top")
  void theFloorHasAFloorOfItsOwn() {
    assertThatThrownBy(() -> parse("--known", "/tmp/known.csv", "--min-degree", "1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--min-degree");
  }

  @Test
  @DisplayName("--min-degree with --revise is refused: revise mode runs no sweep for it to floor")
  void reviseAndMinDegreeAreRefusedTogether() {
    assertThatThrownBy(
            () ->
                parse(
                    "--known", "/tmp/known.csv",
                    "--revise", "3",
                    "--min-degree", "5"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--min-degree")
        .hasMessageContaining("--revise");
  }

  @Test
  @DisplayName(
      "an entity rated 4 or 5 but absent from the file joins the deck's known-list (issue #106)")
  void knownJoinsWhatWasRatedHighly() throws IOException {
    Path list = dir.resolve("known.csv");
    Files.writeString(list, "Q900001\n", StandardCharsets.UTF_8);

    List<String> known = RateCli.known(list, Map.of("Q900002", 5, "Q900003", 2));

    assertThat(known).containsExactlyInAnyOrder("Q900001", "Q900002");
  }

  @Test
  @DisplayName("an entity already on the file is not duplicated by its own rating")
  void knownDoesNotDuplicate() throws IOException {
    Path list = dir.resolve("known.csv");
    Files.writeString(list, "Q900001\n", StandardCharsets.UTF_8);

    List<String> known = RateCli.known(list, Map.of("Q900001", 5));

    assertThat(known).containsExactly("Q900001");
  }
}
