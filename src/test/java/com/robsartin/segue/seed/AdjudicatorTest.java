package com.robsartin.segue.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.wikidata.KindMapper;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Every name here is invented; see {@link NamesTest}. */
class AdjudicatorTest {

  private static final String MUSICIAN = "Q639669"; // musician
  private static final String FOOTBALLER = "Q937857"; // association football player

  // Invented classes (ADR 58), because what is under test here is the check and not the
  // vocabulary. Which real classes a book row takes is Expectations' decision, pinned in
  // ExpectationsTest.
  private static final String WRITTEN_CLASS = "Q0901601";
  private static final String OTHER_WRITTEN_CLASS = "Q0901602";
  private static final String FILM_CLASS = "Q0901603";
  private static final String EDITION_CLASS = "Q0901604";

  /** A kind that takes a work of either written class — the shape Expectations gives "book". */
  private static final Expectation BOOK =
      new Expectation(
          EnumSet.of(NodeKind.WORK), Set.of(), Set.of(WRITTEN_CLASS, OTHER_WRITTEN_CLASS));

  private static CandidateFacts work(String qid, String label, int sitelinks, String... classes) {
    return new CandidateFacts(
        qid, label, "a work", List.of(), NodeKind.WORK, List.of(classes), List.of(), sitelinks);
  }

  private static CandidateFacts person(
      String qid, String label, int sitelinks, String... occupations) {
    return new CandidateFacts(
        qid,
        label,
        "a description",
        List.of(),
        NodeKind.PERSON,
        List.of(),
        List.of(occupations),
        sitelinks);
  }

  private static CandidateFacts group(String qid, String label, int sitelinks) {
    return new CandidateFacts(
        qid, label, "a band", List.of(), NodeKind.GROUP, List.of(), List.of(), sitelinks);
  }

  private static Decision decide(String query, String kind, List<CandidateFacts> candidates) {
    return Adjudicator.decide(query, Expectations.forKind(kind), candidates);
  }

  @Test
  @DisplayName("nothing found is unresolved, not a guess")
  void nothingFoundIsUnresolved() {
    Decision decision = decide("Velvet Ossuary", "musician", List.of());

    assertThat(decision.outcome()).isEqualTo(Outcome.UNRESOLVED);
    assertThat(decision.qid()).isNull();
  }

  @Test
  @DisplayName("an exact name, a fitting kind and a clear margin is accepted")
  void independentSignalsAgreeing() {
    Decision decision =
        decide(
            "Velvet Ossuary",
            "musician",
            List.of(group("Q090000001", "Velvet Ossuary", 30), group("Q090000002", "Bramble", 90)));

    assertThat(decision.outcome()).isEqualTo(Outcome.ACCEPTED);
    assertThat(decision.qid()).isEqualTo("Q090000001");
    assertThat(decision.label()).isEqualTo("Velvet Ossuary");
  }

  @Test
  @DisplayName("case and punctuation do not stop an exact name match")
  void nameMatchIsFolded() {
    Decision decision =
        decide("The Go‑Ahead’s", "musician", List.of(group("Q090000003", "The Go-Ahead's", 12)));

    assertThat(decision.outcome()).isEqualTo(Outcome.ACCEPTED);
  }

  @Test
  @DisplayName("a name Wikidata records as an alias counts as an exact name")
  void anAliasIsAName() {
    // The duo billed under an early name: Wikidata's label is the later one, and the name
    // being resolved is recorded as an alias. That is Wikidata's own claim about identity.
    CandidateFacts duo =
        new CandidateFacts(
            "Q090000004",
            "Ashgrove & Vale",
            "folk duo",
            List.of("The Tin Lanterns"),
            NodeKind.GROUP,
            List.of(),
            List.of(),
            40);

    Decision decision = decide("The Tin Lanterns", "musician", List.of(duo));

    assertThat(decision.outcome()).isEqualTo(Outcome.ACCEPTED);
    assertThat(decision.qid()).isEqualTo("Q090000004");
  }

  @Test
  @DisplayName("an entity actually called this beats a far more famous one that used to be")
  void aLabelMatchBeatsAnAliasMatch() {
    // A stage name is regularly some more famous person's discarded birth name, and the famous
    // one wins every popularity contest. Wikidata's label is its primary claim about what a
    // thing is called and an alias is a secondary one, so a label match is not merely a better
    // score — it is a different, stronger kind of evidence, and the sitelink margin is not
    // allowed to overrule it.
    CandidateFacts theFamousOne =
        new CandidateFacts(
            "Q090000019",
            "Ashgrove Vale",
            "very famous singer",
            List.of("Marguerite Vale"),
            NodeKind.PERSON,
            List.of(),
            List.of(MUSICIAN),
            300);

    Decision decision =
        decide(
            "Marguerite Vale",
            "musician",
            List.of(theFamousOne, person("Q090000020", "Marguerite Vale", 6, MUSICIAN)));

    assertThat(decision.qid()).isEqualTo("Q090000020");
    assertThat(decision.outcome()).isEqualTo(Outcome.ACCEPTED);
  }

  @Test
  @DisplayName("a one-letter name is not evidence that somebody else's alias is it")
  void anAliasMatchNeedsASubstantialName() {
    // Real case: a band whose name is a single letter. Wikidata's search does not surface it at
    // all, and a far more famous artist carries that letter as an alias — so the alias match is
    // the only match, and it is confidently wrong. A very short string is not distinctive
    // enough for "this is also called that" to mean anything, so it goes to review.
    CandidateFacts theFamousOne =
        new CandidateFacts(
            "Q090000021",
            "Ashgrove Vale",
            "very famous singer",
            List.of("V"),
            NodeKind.PERSON,
            List.of(),
            List.of(MUSICIAN),
            300);

    assertThat(decide("V", "musician", List.of(theFamousOne)).outcome()).isEqualTo(Outcome.REVIEW);
  }

  @Test
  @DisplayName("a confident wrong answer is what the occupation check exists to stop")
  void anExactNameOnTheWrongPersonIsReviewed() {
    // P31 is Q5 for every human, so kind alone cannot tell a musician from a footballer.
    // Without P106 this is an exact label match on a well-documented human: auto-accepted,
    // and wrong.
    Decision decision =
        decide(
            "Marguerite Vale",
            "musician",
            List.of(person("Q090000005", "Marguerite Vale", 25, FOOTBALLER)));

    assertThat(decision.outcome()).isEqualTo(Outcome.REVIEW);
    assertThat(decision.reason()).contains("occupation");
    // The candidate is still reported, so a human can accept or correct it in one look.
    assertThat(decision.qid()).isEqualTo("Q090000005");
  }

  @Test
  @DisplayName("a human with no stated occupation is a question, not an answer")
  void aHumanWithNoOccupationIsReviewed() {
    Decision decision =
        decide("Marguerite Vale", "musician", List.of(person("Q090000006", "Marguerite Vale", 4)));

    assertThat(decision.outcome()).isEqualTo(Outcome.REVIEW);
  }

  @Test
  @DisplayName("a group is never asked for an occupation")
  void aGroupIsNotAskedForAnOccupation() {
    Decision decision =
        decide("Velvet Ossuary", "musician", List.of(group("Q090000007", "Velvet Ossuary", 8)));

    assertThat(decision.outcome()).isEqualTo(Outcome.ACCEPTED);
  }

  @Test
  @DisplayName("the top hit being the wrong kind does not stop the right one being found")
  void theWrongKindIsSkipped() {
    CandidateFacts film =
        new CandidateFacts(
            "Q090000008",
            "Velvet Ossuary",
            "1974 film",
            List.of(),
            NodeKind.WORK,
            List.of(),
            List.of(),
            300);

    Decision decision =
        decide(
            "Velvet Ossuary", "musician", List.of(film, group("Q090000009", "Velvet Ossuary", 11)));

    assertThat(decision.outcome()).isEqualTo(Outcome.ACCEPTED);
    assertThat(decision.qid()).isEqualTo("Q090000009");
  }

  @Test
  @DisplayName("every name match being the wrong kind is a review, not an acceptance")
  void everyNameMatchTheWrongKind() {
    CandidateFacts film =
        new CandidateFacts(
            "Q090000010",
            "Velvet Ossuary",
            "1974 film",
            List.of(),
            NodeKind.WORK,
            List.of(),
            List.of(),
            300);

    Decision decision = decide("Velvet Ossuary", "musician", List.of(film));

    assertThat(decision.outcome()).isEqualTo(Outcome.REVIEW);
    assertThat(decision.reason()).contains("kind");
  }

  @Test
  @DisplayName("two plausible answers of the same kind and similar weight is a review")
  void aThinMarginIsAReview() {
    Decision decision =
        decide(
            "Ashgrove",
            "musician",
            List.of(group("Q090000011", "Ashgrove", 20), group("Q090000012", "Ashgrove", 17)));

    assertThat(decision.outcome()).isEqualTo(Outcome.REVIEW);
    assertThat(decision.reason()).contains("margin");
  }

  @Test
  @DisplayName("two plausible answers, one of them far better known, is accepted")
  void aWideMarginIsAccepted() {
    Decision decision =
        decide(
            "Ashgrove",
            "musician",
            List.of(group("Q090000013", "Ashgrove", 3), group("Q090000014", "Ashgrove", 60)));

    assertThat(decision.outcome()).isEqualTo(Outcome.ACCEPTED);
    assertThat(decision.qid()).isEqualTo("Q090000014");
  }

  @Test
  @DisplayName("two equally obscure answers are not separated by a margin of nothing")
  void twoUnknownsAreAReview() {
    Decision decision =
        decide(
            "Ashgrove",
            "musician",
            List.of(group("Q090000015", "Ashgrove", 0), group("Q090000016", "Ashgrove", 0)));

    assertThat(decision.outcome()).isEqualTo(Outcome.REVIEW);
  }

  @Test
  @DisplayName("no name matches at all, but the closest hit is still reported")
  void noNameMatchStillReportsTheClosest() {
    Decision decision =
        decide("Velvet Ossuary", "musician", List.of(group("Q090000017", "Bramble", 90)));

    assertThat(decision.outcome()).isEqualTo(Outcome.REVIEW);
    assertThat(decision.reason()).contains("name");
    assertThat(decision.qid()).isEqualTo("Q090000017");
  }

  @Test
  @DisplayName("an accepted decision says what convinced it")
  void anAcceptedDecisionShowsItsWorking() {
    Decision decision =
        decide(
            "Marguerite Vale",
            "musician",
            List.of(person("Q090000018", "Marguerite Vale", 22, MUSICIAN)));

    assertThat(decision.outcome()).isEqualTo(Outcome.ACCEPTED);
    assertThat(decision.reason()).isNotBlank();
  }

  @Test
  @DisplayName("a work whose stated class is one the kind names is accepted")
  void shouldAcceptTheWorkWhenItStatesAClassTheKindNames() {
    Decision decision =
        Adjudicator.decide(
            "The Salt Almanac",
            BOOK,
            List.of(work("Q0901605", "The Salt Almanac", 9, WRITTEN_CLASS)));

    assertThat(decision.outcome()).isEqualTo(Outcome.ACCEPTED);
    assertThat(decision.qid()).isEqualTo("Q0901605");
  }

  @Test
  @DisplayName("the film of the book is refused on its class, however well known it is")
  void shouldReviewTheFilmWhenOnlyItsClassSeparatesItFromTheBook() {
    Decision refused =
        Adjudicator.decide(
            "The Salt Almanac",
            BOOK,
            List.of(work("Q0901606", "The Salt Almanac", 300, FILM_CLASS)));

    assertThat(refused.outcome()).isEqualTo(Outcome.REVIEW);
    assertThat(refused.reason())
        .as("the line a person reads has to say which signal refused it, and what it saw")
        .contains("the kind, class or occupation")
        .contains(FILM_CLASS);
    assertThat(refused.qid()).as("the candidate is still reported").isEqualTo("Q0901606");

    // The control, one field wide: the same title, the same sitelink count, the same identifier,
    // one class changed. Without it, the refusal above could be about anything.
    Decision accepted =
        Adjudicator.decide(
            "The Salt Almanac",
            BOOK,
            List.of(work("Q0901606", "The Salt Almanac", 300, WRITTEN_CLASS)));

    assertThat(accepted.outcome()).isEqualTo(Outcome.ACCEPTED);
  }

  @Test
  @DisplayName("a book accepts a candidate stating KindMapper.BOOK through Expectations.forKinds")
  void shouldAcceptTheWorkWhenExpectationsForKindsSuppliesTheRealBookClass() {
    // Crosses the seam AdjudicatorTest's own BOOK constant never does: the real Expectations
    // table, not a hand-built stand-in shaped like it, is what production actually calls.
    Decision decision =
        Adjudicator.decide(
            "The Salt Almanac",
            Expectations.forKinds(List.of("book")),
            List.of(work("Q0901609", "The Salt Almanac", 9, KindMapper.BOOK)));

    assertThat(decision.outcome()).isEqualTo(Outcome.ACCEPTED);
  }

  @Test
  @DisplayName("a book refuses a film through Expectations.forKinds along the same path")
  void shouldReviewTheFilmWhenExpectationsForKindsSuppliesTheRealBookClass() {
    Decision decision =
        Adjudicator.decide(
            "The Salt Almanac",
            Expectations.forKinds(List.of("book")),
            List.of(work("Q0901610", "The Salt Almanac", 300, FILM_CLASS)));

    assertThat(decision.outcome()).isEqualTo(Outcome.REVIEW);
  }

  @Test
  @DisplayName("an edition does not outrank the work it is an edition of")
  void shouldPreferTheWorkWhenAnEditionSharesItsTitleAndIsBetterKnown() {
    // The edition would win the margin outright. It never reaches it: the class check sits
    // inside the same filter as the kind check, so the ranking only ever sees what fits.
    Decision decision =
        Adjudicator.decide(
            "The Salt Almanac",
            BOOK,
            List.of(
                work("Q0901607", "The Salt Almanac", 120, EDITION_CLASS),
                work("Q0901605", "The Salt Almanac", 9, WRITTEN_CLASS)));

    assertThat(decision.outcome()).isEqualTo(Outcome.ACCEPTED);
    assertThat(decision.qid()).isEqualTo("Q0901605");
  }

  @Test
  @DisplayName("two written works one title apart are a question for a person")
  void shouldReviewWhenTwoWrittenWorksShareATitleWithinTheMargin() {
    Decision decision =
        Adjudicator.decide(
            "The Salt Almanac",
            BOOK,
            List.of(
                work("Q0901605", "The Salt Almanac", 20, WRITTEN_CLASS),
                work("Q0901608", "The Salt Almanac", 17, OTHER_WRITTEN_CLASS)));

    assertThat(decision.outcome()).isEqualTo(Outcome.REVIEW);
    assertThat(decision.reason()).contains("margin");
  }

  @Test
  @DisplayName("a series-classed work is refused for film, and a film-classed one is accepted")
  void shouldReviewASeriesClassedWorkForFilmThroughExpectationsForKinds() {
    // The real Expectations table, not a hand-built stand-in — the seam #333's own fix wave
    // proved through, reused here for the other work kind.
    Decision refused =
        Adjudicator.decide(
            "The Salt Almanac",
            Expectations.forKinds(List.of("film")),
            List.of(work("Q0901701", "The Salt Almanac", 300, KindMapper.TELEVISION_SERIES)));

    assertThat(refused.outcome()).isEqualTo(Outcome.REVIEW);
    assertThat(refused.reason())
        .as("the line a person reads has to say which signal refused it, and what it saw")
        .contains("class")
        .contains(KindMapper.TELEVISION_SERIES);

    // The control, one field wide: same id, same title, same sitelink count, one class changed.
    Decision accepted =
        Adjudicator.decide(
            "The Salt Almanac",
            Expectations.forKinds(List.of("film")),
            List.of(work("Q0901701", "The Salt Almanac", 300, KindMapper.FILM)));

    assertThat(accepted.outcome()).isEqualTo(Outcome.ACCEPTED);
  }
}
