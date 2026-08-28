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
  @DisplayName("a rating that the server did not accept neither counts nor advances the card")
  void aFailedRatingDoesNotAdvance() throws Exception {
    String rate = blockAfter(script(), "async function rate(");

    // Before issue #101's final review this was `await fetch(...); rated++; index++; show();` —
    // a 400 or a 403 counted as a saved rating and moved the deck on, and the owner had no way
    // to know. A rating cannot be withdrawn (ADR 46), so a lost one is lost.
    assertThat(rate).as("the rate POST's outcome must be checked").contains("response.ok");
    assertThat(rate.indexOf("response.ok"))
        .as("the ok check must come before the session count and the index move")
        .isLessThan(rate.indexOf("rated++"))
        .isLessThan(rate.indexOf("index++"));

    // And the harder half: if the handler throws, com.sun.net.httpserver closes the connection
    // with no response at all, the promise REJECTS, and nothing after the await runs — which
    // used to leave `current` null and every subsequent rating key inert until the owner
    // pressed s or b. Reproduced against a store throwing what SqliteAffinityStore raises on
    // SQLITE_BUSY, which RateCli's own javadoc anticipates.
    assertThat(rate).as("a rejected fetch must be caught, not left to reject").contains("catch");
  }

  @Test
  @DisplayName("a held rating key writes one rating, not a run of them")
  void ignoresAutoRepeat() throws Exception {
    String keydown = blockAfter(script(), "addEventListener('keydown'");

    // Auto-repeat delivers roughly thirty events a second and a loopback round-trip takes a few
    // milliseconds, so a finger resting on '4' for a second wrote about fifteen ratings of 4 to
    // whatever cards went past. None of them can be withdrawn.
    assertThat(keydown).contains("event.repeat");
    assertThat(keydown.indexOf("event.repeat"))
        .as("the repeat guard must run before any key is acted on")
        .isLessThan(keydown.indexOf("rate("));
  }

  @Test
  @DisplayName("a key pressed with a modifier belongs to the browser, not to the deck")
  void ignoresModifiedKeys() throws Exception {
    String keydown = blockAfter(script(), "addEventListener('keydown'");

    // Cmd/Ctrl+S skipped a card and swallowed the browser's save dialog; Ctrl/Alt+1..5 recorded
    // a rating the owner was not asking for.
    assertThat(keydown).contains("ctrlKey").contains("metaKey").contains("altKey");
    assertThat(keydown.indexOf("Key"))
        .as("the modifier guard must run before any key is acted on")
        .isLessThan(keydown.indexOf("rate("));
  }

  @Test
  @DisplayName("skip and back cannot move the index while a request is in flight")
  void skipAndBackAreGuarded() throws Exception {
    String script = script();
    String keydown = blockAfter(script, "addEventListener('keydown'");

    // s/b used to mutate `index` inline, with no guard at all: pressing skip while a rate POST
    // was in flight advanced the index twice and one card was never dealt. No rating is
    // misapplied by that; a card is silently lost, which on a deck of eight hundred is invisible.
    assertThat(keydown)
        .as("the key handler must delegate rather than move the index itself")
        .doesNotContain("index++")
        .doesNotContain("index--");
    assertThat(blockAfter(script, "function skip(")).contains("busy");
    assertThat(blockAfter(script, "function back(")).contains("busy");
  }

  @Test
  @DisplayName(
      "a revision card's existing rating (issue #109) is rendered from the real value, inside its"
          + " own guard, and actually attached to the DOM")
  void rendersTheCurrentRatingOnARevisionCard() throws Exception {
    String renderCard = blockAfter(script(), "function renderCard(");

    // An unrated card (currentRating === null) must not get a revision banner at all — this is
    // what stops the deck's ordinary cards from claiming a rating they do not have.
    assertThat(matches(renderCard, "c\\.currentRating\\s*!==?\\s*null"))
        .as("expected renderCard to branch on whether c.currentRating is present")
        .isTrue();

    // Everything that builds and shows the banner must live INSIDE that guard's own block.
    // blockAfter locates the brace immediately following the guard text and balances it, so this
    // fails if the banner element were built unconditionally, with the guard merely present
    // somewhere else in the function.
    String guarded = blockAfter(renderCard, "c.currentRating !== null");

    // The element's text must interpolate the ACTUAL c.currentRating value (a template literal
    // referencing it), not a fixed label — a hard-coded "Currently rated" with no ${c...} would
    // read as compliant while showing the wrong number on every card.
    Matcher element =
        Pattern.compile(
                "const\\s+(\\w+)\\s*=\\s*document\\.createElement\\([^)]*\\)[\\s\\S]*?"
                    + "\\1\\.textContent\\s*=\\s*`[^`]*\\$\\{\\s*c\\.currentRating\\s*\\}[^`]*`")
            .matcher(guarded);
    assertThat(element.find())
        .as(
            "expected an element inside the guard whose textContent interpolates the actual"
                + " c.currentRating value")
        .isTrue();
    String varName = element.group(1);

    // Built is not the same as shown: the element has to actually reach the card region, not be
    // constructed and left unattached.
    assertThat(
            matches(
                guarded,
                "(nodes\\.push\\(\\s*"
                    + Pattern.quote(varName)
                    + "\\s*\\)|appendChild\\(\\s*"
                    + Pattern.quote(varName)
                    + "\\s*\\)|replaceChildren\\([^)]*\\b"
                    + Pattern.quote(varName)
                    + "\\b)"))
        .as("the revision element must actually be handed to the DOM, not built and discarded")
        .isTrue();

    // The XSS fix from #101 (see the class comment in deck.html) must not be undone for the one
    // piece of text on this card that this task adds.
    assertThat(guarded).doesNotContain("innerHTML");
  }

  @Test
  @DisplayName(
      "the revision banner has a real background fill, not just colored text, so a revision card"
          + " cannot be mistaken for one of the page's plain muted captions")
  void revisionBannerIsVisuallyDistinct() throws Exception {
    String html = page();
    String renderCard = blockAfter(script(), "function renderCard(");
    String guarded = blockAfter(renderCard, "c.currentRating !== null");

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
