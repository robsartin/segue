package com.robsartin.segue.seed;

import com.robsartin.segue.domain.NodeKind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The decision, as a pure function.
 *
 * <p>Everything that touches Wikidata is somewhere else. What is left here is the judgement, and
 * judgement in a pure function can be asserted offline, exhaustively, against invented names —
 * which is what lets {@code ./gradlew check} need no network.
 *
 * <p><b>Auto-accept only when independent signals agree.</b> Three of them, and each one covers a
 * failure the other two cannot see:
 *
 * <ol>
 *   <li><b>The name.</b> The queried spelling must equal the entity's own label or one of its
 *       recorded aliases, folded. Search relevance alone is not evidence: the top hit for a band's
 *       name is regularly a film, a crater or a surname.
 *   <li><b>The kind, and for a person the occupation.</b> {@code P31} separates a person from a
 *       band from a film. It does not separate a musician from a minister — every human is {@code
 *       Q5} — so for a {@code PERSON} the input list's {@code kind} column is checked against
 *       {@code P106}. This is the signal that stops a confident wrong answer, which is the only
 *       kind of wrong answer that matters here.
 *   <li><b>The margin.</b> Two entities can both match the name exactly and both fit the kind.
 *       Unless one is markedly better known than the other, there is nothing to choose between them
 *       and a person should look.
 * </ol>
 *
 * <p>Any conflict, and anything thin, goes to review. The cost of a review line is a minute; the
 * cost of a wrong QID is that every route through it is quietly false.
 */
public final class Adjudicator {

  /**
   * How much better known the winner has to be. A factor rather than a difference: the gap that
   * settles two obscure entities is not the gap that settles two famous ones.
   */
  static final int SITELINK_MARGIN_FACTOR = 2;

  /**
   * How long a folded name has to be before an ALIAS match counts as evidence.
   *
   * <p>A label match is always evidence, however short. An alias match on one or two characters is
   * not: a band called by a single letter does not surface in Wikidata's search at all, while some
   * far more famous artist carries that letter among their aliases — so the only match found is
   * confidently wrong. Three characters is not a tuned number; it is the point below which "this is
   * also called that" stops distinguishing anything.
   */
  static final int MINIMUM_ALIAS_LENGTH = 3;

  private Adjudicator() {}

  public static Decision decide(
      String query, Expectation expectation, List<CandidateFacts> candidates) {
    Objects.requireNonNull(query, "query");
    Objects.requireNonNull(expectation, "expectation");
    Objects.requireNonNull(candidates, "candidates");

    if (candidates.isEmpty()) {
      return new Decision(
          Outcome.UNRESOLVED, null, null, "no Wikidata candidate under any spelling");
    }

    List<CandidateFacts> named = byName(query, candidates);
    if (named.isEmpty()) {
      CandidateFacts closest = candidates.get(0);
      return new Decision(
          Outcome.REVIEW,
          closest.qid(),
          closest.label(),
          "no candidate whose name matches; closest was " + closest.describe());
    }

    List<CandidateFacts> fitting = named.stream().filter(c -> fits(expectation, c)).toList();
    // A label match and an alias match are not two scores on one scale. The label is Wikidata's
    // primary claim about what a thing is called; an alias is a secondary one, and is regularly
    // some far more famous person's discarded birth name. So if anything that fits is actually
    // CALLED this, the alias matches are set aside rather than outranked — otherwise the famous
    // one wins the sitelink margin every time.
    List<CandidateFacts> byLabel =
        fitting.stream().filter(c -> Names.fold(c.label()).equals(Names.fold(query))).toList();
    if (!byLabel.isEmpty()) {
      fitting = byLabel;
    }
    if (fitting.isEmpty()) {
      CandidateFacts closest = named.get(0);
      return new Decision(
          Outcome.REVIEW,
          closest.qid(),
          closest.label(),
          "name matches but the kind or occupation does not: " + describeMismatch(named));
    }

    List<CandidateFacts> ranked =
        fitting.stream()
            .sorted(Comparator.comparingInt(CandidateFacts::sitelinks).reversed())
            .toList();
    CandidateFacts best = ranked.get(0);
    if (ranked.size() > 1) {
      CandidateFacts runnerUp = ranked.get(1);
      if (best.sitelinks() <= runnerUp.sitelinks() * SITELINK_MARGIN_FACTOR) {
        return new Decision(
            Outcome.REVIEW,
            best.qid(),
            best.label(),
            "thin margin between "
                + best.describe()
                + " with "
                + best.sitelinks()
                + " sitelinks and "
                + runnerUp.describe()
                + " with "
                + runnerUp.sitelinks());
      }
    }
    return new Decision(
        Outcome.ACCEPTED,
        best.qid(),
        best.label(),
        "name, kind and occupation agree; " + best.sitelinks() + " sitelinks" + runnerUp(ranked));
  }

  /** Candidates Wikidata itself calls by this name, whether as its label or as an alias. */
  private static List<CandidateFacts> byName(String query, List<CandidateFacts> candidates) {
    String key = Names.fold(query);
    boolean aliasesCount = key.length() >= MINIMUM_ALIAS_LENGTH;
    List<CandidateFacts> named = new ArrayList<>();
    for (CandidateFacts candidate : candidates) {
      if (Names.fold(candidate.label()).equals(key)
          || (aliasesCount
              && candidate.aliases().stream().map(Names::fold).anyMatch(key::equals))) {
        named.add(candidate);
      }
    }
    return List.copyOf(named);
  }

  /**
   * Whether this candidate is the kind of thing the list said it was.
   *
   * <p>The occupation half applies to people only. A band has no {@code P106}, so requiring one
   * would reject every band, and a television series has none either.
   */
  private static boolean fits(Expectation expectation, CandidateFacts candidate) {
    if (!expectation.acceptsKind(candidate.kind())) {
      return false;
    }
    return candidate.kind() != NodeKind.PERSON
        || expectation.acceptsOccupation(candidate.occupations());
  }

  private static String describeMismatch(List<CandidateFacts> named) {
    StringBuilder out = new StringBuilder();
    for (CandidateFacts candidate : named) {
      if (!out.isEmpty()) {
        out.append("; ");
      }
      out.append(candidate.describe()).append(" is ").append(candidate.kind());
      if (candidate.kind() == NodeKind.PERSON) {
        out.append(" with occupations ").append(candidate.occupations());
      }
    }
    return out.toString();
  }

  private static String runnerUp(List<CandidateFacts> ranked) {
    return ranked.size() > 1 ? " against " + ranked.get(1).sitelinks() + " for the runner-up" : "";
  }
}
