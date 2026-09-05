package com.robsartin.segue.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.robsartin.segue.domain.Recommendations;
import com.robsartin.segue.domain.Scorer;
import com.robsartin.segue.recommend.RecommendCli.Options;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Everything that can be refused before a store is opened (ADR 45, ADR 41's shape). */
class RecommendCliTest {

  private static final String HOME = "/home/invented";

  private static Options parse(String... args) {
    return RecommendCli.parse(args, null, HOME);
  }

  @Test
  @DisplayName("the two paths are all it needs; everything else has a measured default")
  void theTwoPathsAreAllItNeeds() {
    Options options = parse("--known", "/tmp/known.csv", "--out", "/tmp/out.txt");

    assertThat(options.known()).isEqualTo(Path.of("/tmp/known.csv"));
    assertThat(options.out()).isEqualTo(Path.of("/tmp/out.txt"));
    assertThat(options.scorer()).isEqualTo(Scorer.LIFT);
    assertThat(options.minDegree()).isEqualTo(Recommendations.MIN_CANDIDATE_DEGREE);
    assertThat(options.top()).isEqualTo(RecommendCli.DEFAULT_TOP);
    assertThat(options.database()).isEqualTo(Path.of(HOME, ".segue", "segue.db"));
  }

  @Test
  @DisplayName("the database defaults exactly as the server's does")
  void theDatabaseFollowsTheEnvironment() {
    Options options =
        RecommendCli.parse(
            new String[] {"--known", "/tmp/known.csv", "--out", "/tmp/out.txt"},
            "/elsewhere/segue.db",
            HOME);

    assertThat(options.database()).isEqualTo(Path.of("/elsewhere/segue.db"));
  }

  @Test
  @DisplayName("there is no default output path, on purpose")
  void thereIsNoDefaultOutputPath() {
    assertThatThrownBy(() -> parse("--known", "/tmp/known.csv"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--out is required");
  }

  @Test
  @DisplayName("without a list of what you already know there is nothing to recommend against")
  void theKnownListIsRequired() {
    assertThatThrownBy(() -> parse("--out", "/tmp/out.txt"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--known is required");
  }

  @Test
  @DisplayName("the scorer is a dial the command line turns")
  void theScorerIsADialTheCommandLineTurns() {
    Options options =
        parse(
            "--known",
            "/tmp/known.csv",
            "--out",
            "/tmp/out.txt",
            "--scorer",
            "resource-alloc" + "ation");

    assertThat(options.scorer()).isEqualTo(Scorer.RESOURCE_ALLOCATION);
  }

  @Test
  @DisplayName("a scorer nobody implemented is refused by name")
  void anUnknownScorerIsRefused() {
    assertThatThrownBy(
            () ->
                parse("--known", "/tmp/known.csv", "--out", "/tmp/out.txt", "--scorer", "pagerank"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("pagerank");
  }

  @Test
  @DisplayName("the usage message spells the default scorer from the constant, not a second word")
  void shouldSpellTheDefaultScorerFromTheConstantWhenItRefusesAnything() {
    // The word in the usage string was a third copy of the default (issue #244): the enum could
    // move and the sentence offered to the operator would go on saying the old word.
    assertThatThrownBy(() -> parse("--out", "/tmp/out.txt"))
        .hasMessageContaining("default " + Recommendations.DEFAULT_SCORER.spelling());
  }

  @Test
  @DisplayName("the floor and the length of the list are both arguments")
  void theFloorAndTheLengthAreArguments() {
    Options options =
        parse(
            "--known",
            "/tmp/known.csv",
            "--out",
            "/tmp/out.txt",
            "--min-degree",
            "30",
            "--top",
            "5");

    assertThat(options.minDegree()).isEqualTo(30);
    assertThat(options.top()).isEqualTo(5);
  }

  @Test
  @DisplayName("a floor below two would let a node with one edge be normalised to the top")
  void theFloorHasAFloorOfItsOwn() {
    assertThatThrownBy(
            () -> parse("--known", "/tmp/known.csv", "--out", "/tmp/out.txt", "--min-degree", "1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--min-degree");
  }

  @Test
  @DisplayName("a list of no candidates is not a request anybody means")
  void anEmptyListIsRefused() {
    assertThatThrownBy(
            () -> parse("--known", "/tmp/known.csv", "--out", "/tmp/out.txt", "--top", "0"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--top");
  }

  @Test
  @DisplayName("an unknown option is refused rather than ignored")
  void anUnknownOptionIsRefused() {
    assertThatThrownBy(
            () -> parse("--known", "/tmp/known.csv", "--out", "/tmp/out.txt", "--include-affinity"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--include-affinity");
  }

  @Test
  @DisplayName("a flag with no value is refused, naming the flag")
  void aFlagWithNoValueIsRefused() {
    assertThatThrownBy(() -> parse("--known", "/tmp/known.csv", "--out"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--out needs a value");
  }

  @Test
  @DisplayName("a number that is not a number is refused, naming the flag")
  void aNumberThatIsNotANumberIsRefused() {
    assertThatThrownBy(
            () -> parse("--known", "/tmp/known.csv", "--out", "/tmp/out.txt", "--top", "lots"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--top");
  }
}
