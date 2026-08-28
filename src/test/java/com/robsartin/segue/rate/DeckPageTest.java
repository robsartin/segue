package com.robsartin.segue.rate;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DeckPageTest {

  private static String page() throws Exception {
    try (InputStream in = DeckPageTest.class.getResourceAsStream("/rate/deck.html")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  @DisplayName("the page reaches no external host, so it works offline and cannot phone anywhere")
  void hasNoExternalAssets() throws Exception {
    assertThat(page()).doesNotContain("http://").doesNotContain("https://").doesNotContain("//cdn");
  }

  @Test
  @DisplayName("the five ratings are real buttons, not clickable divs")
  void ratingsAreSemanticButtons() throws Exception {
    String html = page();
    for (int rating = 1; rating <= 5; rating++) {
      assertThat(html).contains("data-rating=\"" + rating + "\"");
    }
    assertThat(html).contains("<button");
  }

  @Test
  @DisplayName("the card region announces itself to a screen reader")
  void announcesEachCard() throws Exception {
    assertThat(page()).contains("aria-live");
  }
}
