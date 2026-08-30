package com.robsartin.segue.rate;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the deck page <em>is</em>, read as a file — the half that running it cannot show.
 *
 * <p>This class used to carry the page's guards as well, and mutation-testing it (issue #103)
 * showed what a token-presence assertion is worth: it caught a deleted {@code if (!response.ok)}
 * and missed the same branch with its {@code return} taken out, which is precisely the
 * silent-data-loss defect issue #101 fixed. Those five assertions now live in {@code
 * DeckBehaviourTest}, which runs the page in a real browser and fails against the defective
 * version, not merely against the guard's absence.
 *
 * <p>What is left here is what a browser genuinely cannot answer: that the page reaches no external
 * host, that the ratings are real buttons, that the region a screen reader is told to watch is the
 * region the script rewrites, that the card is built as text rather than markup, and that the
 * revision banner has a background fill rather than merely a colour. Text and markup render
 * identically until a label contains a tag; a fill is a pixel question no assertion in a DOM can
 * settle. Both are read from the source on purpose.
 */
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

  private static String script() throws Exception {
    String html = page();
    return html.substring(html.indexOf("<script>"));
  }

  /**
   * The source of one brace-balanced block, given the text that opens it.
   *
   * <p>Crude on purpose: this file's script is a hundred lines of plain functions with no template
   * literals holding braces and no nested object literals that would defeat a brace count, so a
   * scanner is enough — and it is what lets an assertion say "inside {@code rate}" rather than
   * "somewhere in the page", which is the difference between a test that bites and one that a
   * comment mentioning the right word would satisfy.
   */
  private static String blockAfter(String script, String opener) {
    int at = script.indexOf(opener);
    assertThat(at).as("expected to find %s in the page script", opener).isNotNegative();
    int open = script.indexOf('{', at);
    int depth = 0;
    for (int i = open; i < script.length(); i++) {
      char c = script.charAt(i);
      if (c == '{') {
        depth++;
      } else if (c == '}') {
        depth--;
        if (depth == 0) {
          return script.substring(open, i + 1);
        }
      }
    }
    throw new AssertionError("unbalanced braces after " + opener);
  }

  @Test
  @DisplayName("every part of a card is built as text, so a vandalised label cannot become markup")
  void buildsTheCardAsTextNotMarkup() throws Exception {
    // label, kind, classes and every route line are Wikidata-derived text from an openly editable
    // source. #101 built them as DOM nodes for that reason, and #109 added the revision banner
    // under the same rule. A running page cannot see this one: text and markup render identically
    // until the day a label contains a tag, so what has to be pinned is HOW the card is built.
    String renderCard = blockAfter(script(), "function renderCard(");

    // The markup sinks must never be WRITTEN TO. Not "must not appear": the page's own comment
    // explains at length why it does not interpolate into innerHTML, and a test that banned the
    // word would fail on the sentence promising the thing it wants.
    assertThat(matches(renderCard, "\\b(inner|outer)HTML\\s*="))
        .as("nothing in a card may be assigned as markup")
        .isFalse();
    assertThat(renderCard)
        .as("nor built as markup another way")
        .doesNotContain("insertAdjacentHTML")
        .doesNotContain("document.write");
    assertThat(renderCard)
        .as("the card's text must go through textContent or createTextNode")
        .containsAnyOf("textContent", "createTextNode");
  }

  @Test
  @DisplayName(
      "the revision banner has a real background fill, not just colored text, so a revision card"
          + " cannot be mistaken for one of the page's plain muted captions")
  void revisionBannerIsVisuallyDistinct() throws Exception {
    String html = page();
    String renderCard = blockAfter(script(), "function renderCard(");
    String guarded = blockAfter(renderCard, "rating !== null");

    Matcher classAssign =
        Pattern.compile("(\\w+)\\.className\\s*=\\s*['\"](\\w[\\w-]*)['\"]").matcher(guarded);
    assertThat(classAssign.find())
        .as("expected the revision element to carry a class name naming its own CSS rule")
        .isTrue();
    String className = classAssign.group(2);

    String style = html.substring(html.indexOf("<style>"), html.indexOf("</style>"));
    Matcher rule =
        Pattern.compile("\\." + Pattern.quote(className) + "\\s*\\{([^}]*)\\}").matcher(style);
    assertThat(rule.find()).as("expected a CSS rule for ." + className).isTrue();
    String declarations = rule.group(1);

    // .kind, .why, .keys, .progress and .done all only set `color` against the page's own
    // background (var(--paper)). A revision card has to look different from a plain caption, not
    // merely be captioned — a genuine background fill, distinct from the page's own background,
    // is the bar this asserts.
    assertThat(matches(declarations, "background\\s*:\\s*(?!var\\(--paper\\)|none|transparent)\\S"))
        .as("the revision banner needs its own background fill to be unmistakable at a glance")
        .isTrue();
  }
}
