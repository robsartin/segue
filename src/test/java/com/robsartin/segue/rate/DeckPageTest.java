package com.robsartin.segue.rate;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DeckPageTest {

  private static String page() throws Exception {
    try (InputStream in = DeckPageTest.class.getResourceAsStream("/rate/deck.html")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static boolean matches(String text, String regex) {
    return Pattern.compile(regex).matcher(text).find();
  }

  @Test
  @DisplayName("the page reaches no external host, so it works offline and cannot phone anywhere")
  void hasNoExternalAssets() throws Exception {
    String html = page();
    assertThat(html).doesNotContain("http://").doesNotContain("https://");
    // A protocol-relative URL (src="//cdn..." or a CSS url(//cdn...)) reaches out exactly like
    // https:// does. Match "//" only when it's immediately preceded by a quote or an open-paren,
    // which is how a URL attribute or a CSS url() writes it — never how this file's own "// "
    // line comments look, since every one of those is preceded by whitespace at the start of a
    // line, not a quote or a paren.
    assertThat(matches(html, "[\"'(]//[\\w.-]"))
        .as("no protocol-relative URL (e.g. src=\"//cdn...\" or url(//cdn...))")
        .isFalse();
    // A CSS url(...) reference is the other way this page could reach outside itself (a remote
    // font or image); this page embeds everything inline and needs none.
    assertThat(html.toLowerCase(Locale.ROOT)).doesNotContain("url(");
  }

  @Test
  @DisplayName(
      "the five ratings are attributes of real <button> elements, not just present somewhere")
  void ratingsAreSemanticButtons() throws Exception {
    String html = page();
    for (int rating = 1; rating <= 5; rating++) {
      // Requires data-rating="N" to appear inside a <button ...> tag specifically: the lazy,
      // no-'>' -crossing scan between "<button" and the attribute stays within one tag, so a
      // <div data-rating="1"> elsewhere in the page — even alongside an unrelated <button> that
      // satisfies a bare "contains '<button'" check — does not satisfy this.
      assertThat(matches(html, "<button\\b(?:(?!>)[\\s\\S])*?data-rating=\"" + rating + "\""))
          .as("data-rating=\"%d\" must be an attribute of a <button>", rating)
          .isTrue();
    }
  }

  @Test
  @DisplayName(
      "the card region announces itself to a screen reader, and is the element the script actually rewrites")
  void announcesEachCard() throws Exception {
    String html = page();

    Matcher liveTag = Pattern.compile("<[^>]*\\baria-live=\"[^\"]+\"[^>]*>").matcher(html);
    assertThat(liveTag.find()).as("expected an element carrying aria-live").isTrue();

    Matcher idMatcher = Pattern.compile("id=\"([^\"]+)\"").matcher(liveTag.group());
    assertThat(idMatcher.find())
        .as(
            "the aria-live element needs an id, so this test can confirm it is the thing that changes")
        .isTrue();
    String liveId = idMatcher.group(1);

    String script = html.substring(html.indexOf("<script>"));
    String idLiteral = "['\"]" + Pattern.quote(liveId) + "['\"]";
    String mutation = "(replaceChildren\\(|appendChild\\(|innerHTML\\s*=|textContent\\s*=)";

    // The lookup may be used directly (document.getElementById('card').replaceChildren(...)) or
    // stashed in a variable first (const card = document.getElementById('card'); ...
    // card.replaceChildren(...)) — accept either shape of the same fact: the element the script
    // looked up by this id is the element whose content actually gets rewritten.
    boolean rewrittenDirectly =
        matches(script, "document\\.getElementById\\(" + idLiteral + "\\)\\s*\\.\\s*" + mutation);

    Matcher assign =
        Pattern.compile(
                "(?:const|let|var)\\s+(\\w+)\\s*=\\s*document\\.getElementById\\("
                    + idLiteral
                    + "\\)")
            .matcher(script);
    boolean rewrittenViaVariable =
        assign.find() && matches(script, Pattern.quote(assign.group(1)) + "\\." + mutation);

    // An aria-live region nobody ever updates announces nothing new — the exact trap this test
    // exists to catch, and it would pass an "aria-live appears somewhere in the file" check
    // identically to a correct page.
    assertThat(rewrittenDirectly || rewrittenViaVariable)
        .as(
            "the aria-live element (#%s) must have its content rewritten somewhere in the script,"
                + " either via a direct chained call or through a variable it was assigned to",
            liveId)
        .isTrue();
  }
}
