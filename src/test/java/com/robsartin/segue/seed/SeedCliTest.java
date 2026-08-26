package com.robsartin.segue.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SeedCliTest {

  @Test
  @DisplayName("the output paths sit beside the list, which is outside this repository")
  void derivesOutputPathsFromTheList() {
    SeedCli.Options options = SeedCli.parse(new String[] {"--list", "/somewhere/else/list.csv"});

    assertThat(options.mapping()).isEqualTo(Path.of("/somewhere/else/list-qids.csv"));
    assertThat(options.review()).isEqualTo(Path.of("/somewhere/else/list-review.csv"));
  }

  @Test
  @DisplayName("every default can be overridden")
  void acceptsExplicitOptions() {
    SeedCli.Options options =
        SeedCli.parse(
            new String[] {
              "--list", "/a/list.csv",
              "--mapping", "/b/map.csv",
              "--review", "/b/rev.csv",
              "--chunk", "5",
              "--candidates", "3"
            });

    assertThat(options.mapping()).isEqualTo(Path.of("/b/map.csv"));
    assertThat(options.review()).isEqualTo(Path.of("/b/rev.csv"));
    assertThat(options.chunkSize()).isEqualTo(5);
    assertThat(options.candidates()).isEqualTo(3);
  }

  @Test
  @DisplayName("no list is a usage error, not a stack trace")
  void refusesWithoutAList() {
    assertThatThrownBy(() -> SeedCli.parse(new String[] {}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--list");
  }

  @Test
  @DisplayName("an unrecognised flag is refused rather than ignored")
  void refusesAnUnknownFlag() {
    assertThatThrownBy(() -> SeedCli.parse(new String[] {"--list", "/a.csv", "--write-graph"}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--write-graph");
  }

  @Test
  @DisplayName("a flag with no value is refused")
  void refusesADanglingFlag() {
    assertThatThrownBy(() -> SeedCli.parse(new String[] {"--list"}))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
