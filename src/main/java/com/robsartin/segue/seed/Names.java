package com.robsartin.segue.seed;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The two string operations the seeding tool needs: folding two spellings of one act onto one key,
 * and offering the spellings worth asking Wikidata about.
 *
 * <p>Both are pure functions on purpose. They are where the judgement lives, and judgement that
 * lives in a pure function can be asserted without a network.
 */
public final class Names {

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

  /** Discogs disambiguates same-named acts with a trailing bracketed number. */
  private static final Pattern DISAMBIGUATOR_SUFFIX = Pattern.compile("\\s*\\(\\d+\\)$");

  /**
   * Titles that are sometimes a title and sometimes the act's actual name. There is no rule that
   * separates the peer from the rapper, so this list only decides which second spelling is worth a
   * question — never which spelling is right.
   */
  private static final Set<String> HONORIFICS =
      Set.of("sir", "lord", "lady", "dame", "dr", "rev", "reverend", "prof", "professor");

  private Names() {}

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

  /**
   * The spellings to try, literal first.
   *
   * <p>The input is never rewritten. Each fallback is an extra question, and the caller keeps
   * whichever one answers confidently — which is the only workable rule when a leading "Sir" is a
   * title on one line and half a stage name on the next.
   */
  public static List<String> spellings(String name) {
    Objects.requireNonNull(name, "name");
    List<String> out = new ArrayList<>();
    String literal = name.trim();
    out.add(literal);
    String withoutSuffix = DISAMBIGUATOR_SUFFIX.matcher(literal).replaceAll("").trim();
    if (!withoutSuffix.isEmpty() && !out.contains(withoutSuffix)) {
      out.add(withoutSuffix);
    }
    String withoutHonorific = stripHonorific(out.get(out.size() - 1));
    if (withoutHonorific != null && !out.contains(withoutHonorific)) {
      out.add(withoutHonorific);
    }
    return List.copyOf(out);
  }

  /** The name without its leading honorific, or null when it does not start with one. */
  private static String stripHonorific(String name) {
    int space = name.indexOf(' ');
    if (space < 0) {
      return null;
    }
    String first = name.substring(0, space).toLowerCase(Locale.ROOT).replace(".", "");
    String rest = name.substring(space + 1).trim();
    return HONORIFICS.contains(first) && !rest.isEmpty() ? rest : null;
  }
}
