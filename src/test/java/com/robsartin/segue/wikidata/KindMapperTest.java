package com.robsartin.segue.wikidata;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.NodeKind;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Wikidata has tens of thousands of classes and segue has six kinds (ADR 21). This is the
 * deliberately small bridge, plus an honest fallback for everything else.
 */
class KindMapperTest {

  @Test
  @DisplayName("a human is a PERSON")
  void mapsHuman() {
    assertThat(KindMapper.fromInstanceOf(List.of("Q5"))).isEqualTo(NodeKind.PERSON);
  }

  @Test
  @DisplayName("bands and organisations are GROUPs")
  void mapsGroups() {
    assertThat(KindMapper.fromInstanceOf(List.of("Q215380"))).isEqualTo(NodeKind.GROUP);
    assertThat(KindMapper.fromInstanceOf(List.of("Q43229"))).isEqualTo(NodeKind.GROUP);
  }

  @Test
  @DisplayName("films, albums and books are WORKs")
  void mapsWorks() {
    assertThat(KindMapper.fromInstanceOf(List.of("Q11424"))).isEqualTo(NodeKind.WORK);
    assertThat(KindMapper.fromInstanceOf(List.of("Q482994"))).isEqualTo(NodeKind.WORK);
    assertThat(KindMapper.fromInstanceOf(List.of("Q7725634"))).isEqualTo(NodeKind.WORK);
  }

  @Test
  @DisplayName("cities and countries are PLACEs")
  void mapsPlaces() {
    assertThat(KindMapper.fromInstanceOf(List.of("Q515"))).isEqualTo(NodeKind.PLACE);
    assertThat(KindMapper.fromInstanceOf(List.of("Q6256"))).isEqualTo(NodeKind.PLACE);
  }

  @Test
  @DisplayName("an unmapped class falls back to CONCEPT rather than guessing")
  void unmappedFallsBackToConcept() {
    // ADR 22: record what we could not map rather than inventing a kind for it.
    assertThat(KindMapper.fromInstanceOf(List.of("Q99999999"))).isEqualTo(NodeKind.CONCEPT);
    assertThat(KindMapper.isMapped("Q99999999")).isFalse();
    assertThat(KindMapper.isMapped("Q5")).isTrue();
  }

  @Test
  @DisplayName("no instance-of claims at all is CONCEPT, not a crash")
  void emptyIsConcept() {
    assertThat(KindMapper.fromInstanceOf(List.of())).isEqualTo(NodeKind.CONCEPT);
  }

  @Test
  @DisplayName("the first mapped class wins, even when an unmapped one comes first")
  void firstMappedWins() {
    // Real entities carry several P31 values. Picking the first RECOGNISED one is what
    // stops an obscure class shadowing "human".
    assertThat(KindMapper.fromInstanceOf(List.of("Q99999999", "Q5"))).isEqualTo(NodeKind.PERSON);
  }

  @Test
  @DisplayName("the specific ways Wikidata says 'band' all map to GROUP")
  void theWaysWikidataSaysBand() {
    // Q215380 "musical group" is the one everybody assumes. It is not the only one in use, and
    // an act typed with any of the others fell through to CONCEPT — which then failed the bulk
    // seeding tool's kind check and sent a perfectly good band to review (issue #49). These
    // classes were MEASURED against a real list of nine hundred acts, not guessed, which is the
    // growth path this class's own note asks for; every QID was looked up and confirmed by
    // label AND description before it was written down.
    assertThat(KindMapper.fromInstanceOf(List.of("Q5741069"))) // rock band
        .isEqualTo(NodeKind.GROUP);
    assertThat(KindMapper.fromInstanceOf(List.of("Q9212979"))) // musical duo
        .isEqualTo(NodeKind.GROUP);
    assertThat(KindMapper.fromInstanceOf(List.of("Q19351429"))) // a cappella group
        .isEqualTo(NodeKind.GROUP);
    assertThat(KindMapper.fromInstanceOf(List.of("Q42998"))) // orchestra
        .isEqualTo(NodeKind.GROUP);
    assertThat(KindMapper.fromInstanceOf(List.of("Q131186"))) // choir
        .isEqualTo(NodeKind.GROUP);
    assertThat(KindMapper.fromInstanceOf(List.of("Q1538570"))) // gospel choir
        .isEqualTo(NodeKind.GROUP);
    assertThat(KindMapper.fromInstanceOf(List.of("Q207338"))) // string quartet
        .isEqualTo(NodeKind.GROUP);
    assertThat(KindMapper.fromInstanceOf(List.of("Q163740"))) // nonprofit organization
        .isEqualTo(NodeKind.GROUP);
    // A loose collective of session players is typed neither as a band nor as an organisation.
    // It is still, unambiguously, a group of people — which is all NodeKind.GROUP claims.
    assertThat(KindMapper.fromInstanceOf(List.of("Q16334295"))) // group of humans
        .isEqualTo(NodeKind.GROUP);
    assertThat(KindMapper.fromInstanceOf(List.of("Q13473501"))) // collective
        .isEqualTo(NodeKind.GROUP);
  }
}
