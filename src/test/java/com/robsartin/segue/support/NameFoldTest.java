package com.robsartin.segue.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every name here is invented. ADR 33 makes the real list personal data and this repository is
 * public (issue #37), so no fixture, document or commit message in this project may quote it.
 */
class NameFoldTest {

  @Test
  @DisplayName("a leading article does not make a different act")
  void foldsAwayALeadingThe() {
    assertThat(NameFold.fold("The Bramble Sons")).isEqualTo(NameFold.fold("Bramble Sons"));
  }

  @Test
  @DisplayName("dash variants fold together, including the non-breaking hyphen")
  void foldsDashVariants() {
    // U+2011 NON-BREAKING HYPHEN and U+2013 EN DASH against plain hyphen-minus.
    assertThat(NameFold.fold("Ash‑Grove Rounders")).isEqualTo(NameFold.fold("Ash-Grove Rounders"));
    assertThat(NameFold.fold("Ash–Grove Rounders")).isEqualTo(NameFold.fold("Ash-Grove Rounders"));
  }

  @Test
  @DisplayName("a curly apostrophe folds onto a straight one")
  void foldsApostropheVariants() {
    assertThat(NameFold.fold("The Halcyon’s")).isEqualTo(NameFold.fold("The Halcyon's"));
  }

  @Test
  @DisplayName("accents fold away")
  void foldsAccents() {
    assertThat(NameFold.fold("Jorge Ballastrón")).isEqualTo(NameFold.fold("Jorge Ballastron"));
  }

  @Test
  @DisplayName("a stroke letter folds to its base rather than vanishing")
  void foldsStrokeLetters() {
    // The trap: U+0142 has no NFKD decomposition, so NFKD-then-drop-non-ASCII DELETES it and
    // "Wozniak" would fold to "wozniak" while "Woźniak"-with-a-stroke folded to "woniak".
    assertThat(NameFold.fold("Stanisław Wodnik")).isEqualTo(NameFold.fold("Stanislaw Wodnik"));
    assertThat(NameFold.fold("Bjørn Halstad")).isEqualTo(NameFold.fold("Bjorn Halstad"));
  }

  @Test
  @DisplayName("spacing and case fold away")
  void foldsSpacingAndCase() {
    assertThat(NameFold.fold("Neil De Vries Tallow"))
        .isEqualTo(NameFold.fold("Neil deVries Tallow"));
  }

  @Test
  @DisplayName("names one edit apart stay apart — folding is not fuzzy matching")
  void doesNotFoldNearMisses() {
    // Two different people whose names differ by one letter are on the real list. Folding must
    // never merge them; an edit-distance pass would.
    assertThat(NameFold.fold("Bryan Ashgrove")).isNotEqualTo(NameFold.fold("Ryan Ashgrove"));
  }
}
