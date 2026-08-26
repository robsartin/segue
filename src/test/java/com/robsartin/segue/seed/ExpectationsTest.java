package com.robsartin.segue.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.NodeKind;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExpectationsTest {

  // Wikidata occupation items, each looked up and confirmed by label AND description before
  // being written down here. Never guess one: a wrong QID is silently believed forever.
  private static final String WRITER = "Q36180"; // writer
  private static final String MUSICIAN = "Q639669"; // musician
  private static final String COMEDIAN = "Q245068"; // comedian
  private static final String FOOTBALLER = "Q937857"; // association football player
  private static final String FILM_ACTOR = "Q10800557"; // film actor

  @Test
  @DisplayName("a musician may be one person or a band")
  void musicianIsAPersonOrAGroup() {
    Expectation expectation = Expectations.forKind("musician");

    assertThat(expectation.acceptsKind(NodeKind.PERSON)).isTrue();
    assertThat(expectation.acceptsKind(NodeKind.GROUP)).isTrue();
    assertThat(expectation.acceptsKind(NodeKind.WORK)).isFalse();
  }

  @Test
  @DisplayName("an orchestra is a group, and a group has no occupation to check")
  void anEnsembleIsAGroupWithNoOccupation() {
    Expectation expectation = Expectations.forKind("orchestra");

    assertThat(expectation.acceptsKind(NodeKind.GROUP)).isTrue();
    assertThat(expectation.acceptsKind(NodeKind.PERSON)).isFalse();
    assertThat(expectation.checksOccupation()).isFalse();
  }

  @Test
  @DisplayName("the occupation sets separate the roles the input column names")
  void occupationSetsSeparateRoles() {
    assertThat(Expectations.forKind("author").acceptsOccupation(List.of(WRITER))).isTrue();
    assertThat(Expectations.forKind("author").acceptsOccupation(List.of(MUSICIAN))).isFalse();
    assertThat(Expectations.forKind("comedian").acceptsOccupation(List.of(COMEDIAN))).isTrue();
    assertThat(Expectations.forKind("musician").acceptsOccupation(List.of(MUSICIAN))).isTrue();
  }

  @Test
  @DisplayName("a comedian typed only as a screen actor still counts")
  void aComedianIsUsuallyTypedAsAnActor() {
    // Wikidata rarely types a working comedian as "comedian". It types them as film actor,
    // television actor, stage actor, voice actor — the sub-types of actor, none of which is
    // the actor item itself. Requiring the word "comedian" sent well-known comedians to
    // review while the answer was unambiguous, so the comedy vocabulary includes the acting
    // one. It is a widening of what counts as agreement, not a lowering of the bar: the name
    // and the margin still have to agree too.
    assertThat(Expectations.forKind("comedian").acceptsOccupation(List.of(FILM_ACTOR))).isTrue();
  }

  @Test
  @DisplayName("one matching occupation among several is enough")
  void oneMatchingOccupationIsEnough() {
    assertThat(Expectations.forKind("musician").acceptsOccupation(List.of(FOOTBALLER, MUSICIAN)))
        .isTrue();
  }

  @Test
  @DisplayName("no occupation at all is not a match — it is a question")
  void anEmptyOccupationListDoesNotMatch() {
    // P31 alone cannot tell a musician from a minister: both are Q5. An entity with no P106
    // therefore fails the check and goes to review rather than being accepted on the name.
    assertThat(Expectations.forKind("musician").acceptsOccupation(List.of())).isFalse();
  }

  @Test
  @DisplayName("an unrecognised kind column constrains nothing, and says so")
  void anUnknownKindConstrainsNothing() {
    Expectation expectation = Expectations.forKind("cartographer");

    assertThat(expectation.acceptsKind(NodeKind.PLACE)).isTrue();
    assertThat(expectation.acceptsKind(NodeKind.PERSON)).isTrue();
    assertThat(expectation.checksOccupation()).isFalse();
  }

  @Test
  @DisplayName("one name with two roles expects the union of both")
  void twoRolesUnion() {
    // The same person appears on the list twice under different roles. One person, two roles —
    // so the expectation is the union, not a conflict.
    Expectation expectation = Expectations.forKinds(List.of("composer", "author"));

    assertThat(expectation.acceptsOccupation(List.of(WRITER))).isTrue();
    assertThat(expectation.acceptsOccupation(List.of(MUSICIAN))).isTrue();
  }

  @Test
  @DisplayName("a union with an unconstrained kind is unconstrained")
  void unionWithAnUnknownKindIsUnconstrained() {
    Expectation expectation = Expectations.forKinds(List.of("author", "cartographer"));

    assertThat(expectation.acceptsKind(NodeKind.EVENT)).isTrue();
    assertThat(expectation.checksOccupation()).isFalse();
  }
}
