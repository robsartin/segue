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
  @DisplayName("a leading article does not make a different act")
  void foldsAwayALeadingThe() {
    assertThat(Names.fold("The Bramble Sons")).isEqualTo(Names.fold("Bramble Sons"));
  }

  @Test
  @DisplayName("dash variants fold together, including the non-breaking hyphen")
  void foldsDashVariants() {
    // U+2011 NON-BREAKING HYPHEN and U+2013 EN DASH against plain hyphen-minus.
    assertThat(Names.fold("Ash‑Grove Rounders")).isEqualTo(Names.fold("Ash-Grove Rounders"));
    assertThat(Names.fold("Ash–Grove Rounders")).isEqualTo(Names.fold("Ash-Grove Rounders"));
  }

  @Test
  @DisplayName("a curly apostrophe folds onto a straight one")
  void foldsApostropheVariants() {
    assertThat(Names.fold("The Halcyon’s")).isEqualTo(Names.fold("The Halcyon's"));
  }

  @Test
  @DisplayName("accents fold away")
  void foldsAccents() {
    assertThat(Names.fold("Jorge Ballastrón")).isEqualTo(Names.fold("Jorge Ballastron"));
  }

  @Test
  @DisplayName("a stroke letter folds to its base rather than vanishing")
  void foldsStrokeLetters() {
    // The trap: U+0142 has no NFKD decomposition, so NFKD-then-drop-non-ASCII DELETES it and
    // "Wozniak" would fold to "wozniak" while "Woźniak"-with-a-stroke folded to "woniak".
    assertThat(Names.fold("Stanisław Wodnik")).isEqualTo(Names.fold("Stanislaw Wodnik"));
    assertThat(Names.fold("Bjørn Halstad")).isEqualTo(Names.fold("Bjorn Halstad"));
  }

  @Test
  @DisplayName("spacing and case fold away")
  void foldsSpacingAndCase() {
    assertThat(Names.fold("Neil De Vries Tallow")).isEqualTo(Names.fold("Neil deVries Tallow"));
  }

  @Test
  @DisplayName("names one edit apart stay apart — folding is not fuzzy matching")
  void doesNotFoldNearMisses() {
    // Two different people whose names differ by one letter are on the real list. Folding must
    // never merge them; an edit-distance pass would.
    assertThat(Names.fold("Bryan Ashgrove")).isNotEqualTo(Names.fold("Ryan Ashgrove"));
  }

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
