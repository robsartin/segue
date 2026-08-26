package com.robsartin.segue.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.NodeKind;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Every name here is invented; see {@link NamesTest}. */
class AdjudicatorTest {

  private static final String MUSICIAN = "Q639669"; // musician
  private static final String FOOTBALLER = "Q937857"; // association football player

  private static CandidateFacts person(
      String qid, String label, int sitelinks, String... occupations) {
    return new CandidateFacts(
        qid, label, "a description", List.of(), NodeKind.PERSON, List.of(occupations), sitelinks);
  }

  private static CandidateFacts group(String qid, String label, int sitelinks) {
    return new CandidateFacts(
        qid, label, "a band", List.of(), NodeKind.GROUP, List.of(), sitelinks);
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
            List.of(group("Q90000001", "Velvet Ossuary", 30), group("Q90000002", "Bramble", 90)));

    assertThat(decision.outcome()).isEqualTo(Outcome.ACCEPTED);
    assertThat(decision.qid()).isEqualTo("Q90000001");
    assertThat(decision.label()).isEqualTo("Velvet Ossuary");
  }

  @Test
  @DisplayName("case and punctuation do not stop an exact name match")
  void nameMatchIsFolded() {
    Decision decision =
        decide("The Go‑Ahead’s", "musician", List.of(group("Q90000003", "The Go-Ahead's", 12)));

    assertThat(decision.outcome()).isEqualTo(Outcome.ACCEPTED);
  }

  @Test
  @DisplayName("a name Wikidata records as an alias counts as an exact name")
  void anAliasIsAName() {
    // The duo billed under an early name: Wikidata's label is the later one, and the name
    // being resolved is recorded as an alias. That is Wikidata's own claim about identity.
    CandidateFacts duo =
        new CandidateFacts(
            "Q90000004",
            "Ashgrove & Vale",
            "folk duo",
            List.of("The Tin Lanterns"),
            NodeKind.GROUP,
            List.of(),
            40);

    Decision decision = decide("The Tin Lanterns", "musician", List.of(duo));

    assertThat(decision.outcome()).isEqualTo(Outcome.ACCEPTED);
    assertThat(decision.qid()).isEqualTo("Q90000004");
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
            "Q90000019",
            "Ashgrove Vale",
            "very famous singer",
            List.of("Marguerite Vale"),
            NodeKind.PERSON,
            List.of(MUSICIAN),
            300);

    Decision decision =
        decide(
            "Marguerite Vale",
            "musician",
            List.of(theFamousOne, person("Q90000020", "Marguerite Vale", 6, MUSICIAN)));

    assertThat(decision.qid()).isEqualTo("Q90000020");
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
            "Q90000021",
            "Ashgrove Vale",
            "very famous singer",
            List.of("V"),
            NodeKind.PERSON,
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
            List.of(person("Q90000005", "Marguerite Vale", 25, FOOTBALLER)));

    assertThat(decision.outcome()).isEqualTo(Outcome.REVIEW);
    assertThat(decision.reason()).contains("occupation");
    // The candidate is still reported, so a human can accept or correct it in one look.
    assertThat(decision.qid()).isEqualTo("Q90000005");
  }

  @Test
  @DisplayName("a human with no stated occupation is a question, not an answer")
  void aHumanWithNoOccupationIsReviewed() {
    Decision decision =
        decide("Marguerite Vale", "musician", List.of(person("Q90000006", "Marguerite Vale", 4)));

    assertThat(decision.outcome()).isEqualTo(Outcome.REVIEW);
  }

  @Test
  @DisplayName("a group is never asked for an occupation")
  void aGroupIsNotAskedForAnOccupation() {
    Decision decision =
        decide("Velvet Ossuary", "musician", List.of(group("Q90000007", "Velvet Ossuary", 8)));

    assertThat(decision.outcome()).isEqualTo(Outcome.ACCEPTED);
  }

  @Test
  @DisplayName("the top hit being the wrong kind does not stop the right one being found")
  void theWrongKindIsSkipped() {
    CandidateFacts film =
        new CandidateFacts(
            "Q90000008", "Velvet Ossuary", "1974 film", List.of(), NodeKind.WORK, List.of(), 300);

    Decision decision =
        decide(
            "Velvet Ossuary", "musician", List.of(film, group("Q90000009", "Velvet Ossuary", 11)));

    assertThat(decision.outcome()).isEqualTo(Outcome.ACCEPTED);
    assertThat(decision.qid()).isEqualTo("Q90000009");
  }

  @Test
  @DisplayName("every name match being the wrong kind is a review, not an acceptance")
  void everyNameMatchTheWrongKind() {
    CandidateFacts film =
        new CandidateFacts(
            "Q90000010", "Velvet Ossuary", "1974 film", List.of(), NodeKind.WORK, List.of(), 300);

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
            List.of(group("Q90000011", "Ashgrove", 20), group("Q90000012", "Ashgrove", 17)));

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
            List.of(group("Q90000013", "Ashgrove", 3), group("Q90000014", "Ashgrove", 60)));

    assertThat(decision.outcome()).isEqualTo(Outcome.ACCEPTED);
    assertThat(decision.qid()).isEqualTo("Q90000014");
  }

  @Test
  @DisplayName("two equally obscure answers are not separated by a margin of nothing")
  void twoUnknownsAreAReview() {
    Decision decision =
        decide(
            "Ashgrove",
            "musician",
            List.of(group("Q90000015", "Ashgrove", 0), group("Q90000016", "Ashgrove", 0)));

    assertThat(decision.outcome()).isEqualTo(Outcome.REVIEW);
  }

  @Test
  @DisplayName("no name matches at all, but the closest hit is still reported")
  void noNameMatchStillReportsTheClosest() {
    Decision decision =
        decide("Velvet Ossuary", "musician", List.of(group("Q90000017", "Bramble", 90)));

    assertThat(decision.outcome()).isEqualTo(Outcome.REVIEW);
    assertThat(decision.reason()).contains("name");
    assertThat(decision.qid()).isEqualTo("Q90000017");
  }

  @Test
  @DisplayName("an accepted decision says what convinced it")
  void anAcceptedDecisionShowsItsWorking() {
    Decision decision =
        decide(
            "Marguerite Vale",
            "musician",
            List.of(person("Q90000018", "Marguerite Vale", 22, MUSICIAN)));

    assertThat(decision.outcome()).isEqualTo(Outcome.ACCEPTED);
    assertThat(decision.reason()).isNotBlank();
  }
}
