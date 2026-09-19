package com.robsartin.segue.seed;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every name here is invented. ADR 33 makes the real list personal data and this repository is
 * public (issue #37), so no fixture, document or commit message in this project may quote it.
 */
class NamesTest {

  @Test
  @DisplayName("the literal spelling is always tried first")
  void literalSpellingComesFirst() {
    assertThat(Names.spellings("Lord Halcyon")).first().isEqualTo("Lord Halcyon");
  }

  @Test
  @DisplayName("a Discogs-style numeric suffix is offered as a second spelling")
  void offersTheSuffixStrippedSpelling() {
    assertThat(Names.spellings("The Tin Lantern (4)"))
        .containsExactly("The Tin Lantern (4)", "The Tin Lantern");
  }

  @Test
  @DisplayName("a parenthesis that is not a disambiguator is left alone")
  void leavesNonNumericParenthesesAlone() {
    assertThat(Names.spellings("Bramble (Live)")).containsExactly("Bramble (Live)");
  }

  @Test
  @DisplayName("a leading honorific is offered as a second spelling, never as a replacement")
  void offersTheHonorificStrippedSpelling() {
    // Honorifics cut both ways: some are titles ("Sir Edward ...") and some are stage names.
    // The tool tries both and prefers the confident hit rather than rewriting the input.
    assertThat(Names.spellings("Sir Halcyon Drift"))
        .containsExactly("Sir Halcyon Drift", "Halcyon Drift");
    assertThat(Names.spellings("Lord Ashgrove")).containsExactly("Lord Ashgrove", "Ashgrove");
  }

  @Test
  @DisplayName("an honorific that is the whole first word of a one-word remainder is not stripped")
  void doesNotStripAnHonorificThatWouldLeaveNothing() {
    assertThat(Names.spellings("Sir")).containsExactly("Sir");
  }

  @Test
  @DisplayName("both fallbacks can apply to one name")
  void appliesBothFallbacks() {
    assertThat(Names.spellings("Dame Marguerite Vale (2)"))
        .containsExactly("Dame Marguerite Vale (2)", "Dame Marguerite Vale", "Marguerite Vale");
  }
}
