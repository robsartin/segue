package com.robsartin.segue.seed;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The spellings worth asking Wikidata about.
 *
 * <p>A pure function on purpose. It is where the judgement lives, and judgement that lives in a
 * pure function can be asserted without a network.
 *
 * <p>{@link com.robsartin.segue.support.NameFold#fold} moved to {@code support} in #342, so the
 * owner-claim tool can fold a review row's name too. Folding two spellings onto one key is a
 * question both tools ask; which spellings are worth asking Wikidata about is resolver knowledge,
 * and nothing else asks.
 */
public final class Names {

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
