package com.robsartin.segue.support;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The key two spellings of one act share.
 *
 * <p>A pure function on purpose. It is where the judgement lives, and judgement that lives in a
 * pure function can be asserted without a network.
 *
 * <p><b>In {@code support} because two tools fold the same names</b> (#342). The seed tool folds so
 * a re-run does not resolve a name twice; the owner-claim tool folds a review row's name against
 * the mapping so a second batch mint does not mint it twice. Two spellings that fold to one key in
 * one tool and to two in the other would mint a duplicate of something the mapping already carries.
 *
 * <p>{@code seed.Names.spellings} stays where it is: which spellings are worth asking Wikidata
 * about is resolver knowledge, and nothing else asks.
 */
public final class NameFold {

  /**
   * Letters whose stroke is part of the glyph rather than a combining mark.
   *
   * <p>{@code ł} has NO NFKD decomposition, so the usual normalise-then-drop-combining-marks pass
   * DELETES it: a name spelled with one folds a letter shorter than the same name spelled without,
   * and the two never meet. Both spellings occur in real input, so this map runs first.
   */
  private static final Map<Character, Character> STROKE_LETTERS = new LinkedHashMap<>();

  static {
    STROKE_LETTERS.put('ł', 'l');
    STROKE_LETTERS.put('Ł', 'L');
    STROKE_LETTERS.put('ø', 'o');
    STROKE_LETTERS.put('Ø', 'O');
    STROKE_LETTERS.put('đ', 'd');
    STROKE_LETTERS.put('Đ', 'D');
    STROKE_LETTERS.put('ħ', 'h');
    STROKE_LETTERS.put('Ħ', 'H');
  }

  private NameFold() {}

  /**
   * The key two spellings of one act share.
   *
   * <p>Dash and apostrophe variants — U+2011 against hyphen-minus, U+2019 against U+0027 — need no
   * step of their own: the final pass keeps letters and digits and drops everything else, which
   * unifies them along with spaces and brackets. Accents are dropped, case is dropped, and a
   * leading definite article is dropped, because a band listed once with one and once without is
   * one band.
   *
   * <p><b>Nothing here is fuzzy.</b> Two names one edit apart are two different people often enough
   * that an edit-distance pass would merge real distinctions; if one is ever added it feeds review,
   * never acceptance.
   */
  public static String fold(String name) {
    Objects.requireNonNull(name, "name");
    StringBuilder mapped = new StringBuilder(name.length());
    name.trim()
        .chars()
        .forEach(c -> mapped.append(STROKE_LETTERS.getOrDefault((char) c, (char) c)));
    String decomposed = Normalizer.normalize(mapped, Normalizer.Form.NFKD);
    StringBuilder unaccented = new StringBuilder(decomposed.length());
    for (int i = 0; i < decomposed.length(); i++) {
      char c = decomposed.charAt(i);
      if (Character.getType(c) != Character.NON_SPACING_MARK) {
        unaccented.append(c);
      }
    }
    String lower = unaccented.toString().toLowerCase(Locale.ROOT);
    if (lower.startsWith("the ")) {
      lower = lower.substring(4);
    }
    StringBuilder key = new StringBuilder(lower.length());
    lower.chars().filter(Character::isLetterOrDigit).forEach(c -> key.append(Character.toChars(c)));
    return key.toString();
  }
}
