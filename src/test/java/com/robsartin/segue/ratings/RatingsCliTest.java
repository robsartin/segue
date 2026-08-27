package com.robsartin.segue.ratings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.robsartin.segue.ratings.RatingsCli.Options;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RatingsCliTest {

  private static final String HOME = "/invented/home";

  private static Options parse(String... args) {
    return RatingsCli.parse(args, null, HOME);
  }

  @Test
  @DisplayName("the default ordering is by rating, because the first question is what do I love")
  void defaultsToRating() {
    Options options = parse("--out", "/tmp/ratings.txt");

    assertThat(options.sort()).isEqualTo(SortOrder.RATING);
    assertThat(options.out()).isEqualTo(Path.of("/tmp/ratings.txt"));
  }

  @Test
  @DisplayName("--sort recent answers the other question: what did I change my mind about")
  void parsesTheRecencyOrdering() {
    assertThat(parse("--out", "/tmp/ratings.txt", "--sort", "recent").sort())
        .isEqualTo(SortOrder.RECENT);
  }

  @Test
  @DisplayName("an ordering nobody offers is refused by name, not silently defaulted")
  void refusesAnUnknownOrdering() {
    assertThatThrownBy(() -> parse("--out", "/tmp/ratings.txt", "--sort", "alphabetical"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("alphabetical")
        .hasMessageContaining(SortOrder.names());
  }

  @Test
  @DisplayName("--out is required, so a listing is never written to a path nobody chose")
  void refusesWithoutAnOutputPath() {
    assertThatThrownBy(() -> parse("--sort", "recent"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--out");
  }

  @Test
  @DisplayName("an unknown option is a usage error rather than an ignored argument")
  void refusesAnUnknownOption() {
    // With a value, so this reaches the unknown-option branch rather than the missing-value one -
    // the two produce different messages and only one of them is what this test is about.
    assertThatThrownBy(() -> parse("--out", "/tmp/ratings.txt", "--include-everything", "yes"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown option")
        .hasMessageContaining("--include-everything");
  }

  @Test
  @DisplayName("a flag with no value is refused rather than reaching past the end of the arguments")
  void refusesAFlagWithNoValue() {
    assertThatThrownBy(() -> parse("--out"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--out");
  }

  @Test
  @DisplayName("the database defaults the way the server's does: SEGUE_DB, then the home directory")
  void resolvesTheDatabaseLikeTheServer() {
    Options fromHome = parse("--out", "/tmp/ratings.txt");
    Options fromEnv =
        RatingsCli.parse(new String[] {"--out", "/tmp/ratings.txt"}, "/invented/scratch.db", HOME);
    Options explicit = parse("--out", "/tmp/ratings.txt", "--db", "/invented/other.db");

    assertThat(fromHome.database()).isEqualTo(Path.of(HOME, ".segue", "segue.db"));
    assertThat(fromEnv.database()).isEqualTo(Path.of("/invented/scratch.db"));
    assertThat(explicit.database()).isEqualTo(Path.of("/invented/other.db"));
  }
}
