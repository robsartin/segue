package com.robsartin.segue.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.NodeKind;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The class half of an expectation, on its own and beside {@link ExpectationsTest}, which is about
 * the table rather than the rule.
 *
 * <p>Every identifier here is invented and denotes nothing (ADR 58). What is under test is the set
 * rule; which real classes a {@code book} row takes is {@code Expectations}' business, and naming
 * one here would tie this file to that vocabulary for no gain.
 */
class ExpectationTest {

  private static final String IN_THE_SET = "Q0901610";
  private static final String ALSO_IN_THE_SET = "Q0901612";
  private static final String OUTSIDE_THE_SET = "Q0901611";

  private static Expectation work(Set<String> classes) {
    return new Expectation(EnumSet.of(NodeKind.WORK), Set.of(), classes);
  }

  @Test
  @DisplayName("a work stating one of the kind's classes is the kind of thing the list meant")
  void shouldAcceptTheWorkWhenItStatesOneOfTheKindsClasses() {
    Expectation expectation = work(Set.of(IN_THE_SET, ALSO_IN_THE_SET));

    assertThat(expectation.acceptsClass(List.of(ALSO_IN_THE_SET))).isTrue();
    assertThat(expectation.acceptsClass(List.of(OUTSIDE_THE_SET, IN_THE_SET)))
        .as("one stated class in the set is enough, as one occupation already is")
        .isTrue();
  }

  @Test
  @DisplayName("a work stating only classes outside the set is refused")
  void shouldRefuseTheWorkWhenEveryStatedClassIsOutsideTheSet() {
    Expectation expectation = work(Set.of(IN_THE_SET));

    assertThat(expectation.acceptsClass(List.of(OUTSIDE_THE_SET))).isFalse();
    assertThat(expectation.acceptsClass(List.of()))
        .as("no class at all is a question, not an answer — the occupation rule, verbatim")
        .isFalse();
  }

  @Test
  @DisplayName("a kind that names no class takes any class, and no class at all")
  void shouldAcceptAnyClassWhenTheKindNamesNone() {
    Expectation expectation = work(Set.of());

    assertThat(expectation.acceptsClass(List.of(OUTSIDE_THE_SET))).isTrue();
    assertThat(expectation.acceptsClass(List.of())).isTrue();
  }

  @Test
  @DisplayName("the class is a real check only when the kind names one")
  void shouldCheckTheClassOnlyWhenTheKindNamesOne() {
    assertThat(work(Set.of(IN_THE_SET)).checksClass()).isTrue();
    assertThat(work(Set.of()).checksClass()).isFalse();
  }
}
