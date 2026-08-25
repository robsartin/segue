package com.robsartin.segue.wikidata;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robsartin.segue.domain.AssertionRecord;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Wikidata claims to segue assertions: whitelist, direction, dates, confidence. */
class ClaimMapperTest {

  private static final Instant PULL = Instant.parse("2026-08-24T09:00:00Z");
  private static final String SUBJECT = "Q1194713";

  private JsonNode entity;

  @BeforeEach
  void loadFixture() throws IOException {
    try (InputStream in = getClass().getResourceAsStream("/wikidata/proposition-claims.json")) {
      entity = new ObjectMapper().readTree(in).at("/entities/" + SUBJECT);
    }
  }

  @Test
  @DisplayName("only whitelisted properties become assertions")
  void mapsOnlyWhitelistedProperties() {
    // P462 (colour) and P1476 (title) are real properties we deliberately do not model.
    // ADR 22: the vocabulary is borrowed and small, not everything Wikidata knows.
    List<AssertionRecord> out = ClaimMapper.map(SUBJECT, entity, PULL);

    assertThat(out)
        .extracting(AssertionRecord::typeCode)
        .containsExactlyInAnyOrder("DIRECTED", "COMPOSED_FOR", "WROTE_SCREENPLAY_FOR", "MEMBER_OF");
  }

  @Test
  @DisplayName("inverted properties are stored from the person's side")
  void invertsCreativeRelations() {
    // Wikidata says "film P57 person". segue stores "person DIRECTED film" (ADR 22).
    AssertionRecord directed =
        ClaimMapper.map(SUBJECT, entity, PULL).stream()
            .filter(a -> a.typeCode().equals("DIRECTED"))
            .findFirst()
            .orElseThrow();

    assertThat(directed.fromQid()).isEqualTo("Q1339275");
    assertThat(directed.toQid()).isEqualTo(SUBJECT);
  }

  @Test
  @DisplayName("direct properties keep the subject on the left")
  void keepsDirectRelations() {
    AssertionRecord memberOf =
        ClaimMapper.map(SUBJECT, entity, PULL).stream()
            .filter(a -> a.typeCode().equals("MEMBER_OF"))
            .findFirst()
            .orElseThrow();

    assertThat(memberOf.fromQid()).isEqualTo(SUBJECT);
    assertThat(memberOf.toQid()).isEqualTo("Q1299");
  }

  @Test
  @DisplayName("P580 and P582 qualifiers become the validity window")
  void mapsQualifiersToValidity() {
    AssertionRecord memberOf =
        ClaimMapper.map(SUBJECT, entity, PULL).stream()
            .filter(a -> a.typeCode().equals("MEMBER_OF"))
            .findFirst()
            .orElseThrow();

    assertThat(memberOf.validFrom()).isEqualTo(LocalDate.of(1983, 1, 1));
    assertThat(memberOf.validTo()).isEqualTo(LocalDate.of(2003, 7, 31));
  }

  @Test
  @DisplayName("a referenced statement is trusted more than an unreferenced one")
  void confidenceReflectsReferences() {
    // ADR 23's scale: 1.00 structured and referenced, 0.80 structured but unreferenced.
    List<AssertionRecord> out = ClaimMapper.map(SUBJECT, entity, PULL);

    AssertionRecord referenced =
        out.stream().filter(a -> a.typeCode().equals("DIRECTED")).findFirst().orElseThrow();
    AssertionRecord unreferenced =
        out.stream().filter(a -> a.typeCode().equals("COMPOSED_FOR")).findFirst().orElseThrow();

    assertThat(referenced.provenance().confidence()).isEqualTo(1.00);
    assertThat(unreferenced.provenance().confidence()).isEqualTo(0.80);
  }

  @Test
  @DisplayName("every assertion is attributed to wikidata and carries a citable statement ref")
  void attributesToWikidata() {
    assertThat(ClaimMapper.map(SUBJECT, entity, PULL))
        .allSatisfy(
            a -> {
              assertThat(a.provenance().sourceId()).isEqualTo("wikidata");
              assertThat(a.provenance().assertedAt()).isEqualTo(PULL);
              assertThat(a.provenance().sourceRef()).isNotNull();
              assertThat(a.provenance().isHypothesis()).isFalse();
            });
  }

  @Test
  @DisplayName("a snak with no value is skipped rather than crashing the whole entity")
  void skipsValuelessSnaks() {
    // P2047 in the fixture is snaktype "somevalue" — Wikidata's "we know there is one but
    // not what it is". One unusable claim must not lose the other forty.
    assertThat(ClaimMapper.map(SUBJECT, entity, PULL)).isNotEmpty();
  }

  @Test
  @DisplayName("instance-of claims are read for kind, not turned into edges")
  void doesNotEmitInstanceOfEdges() {
    assertThat(ClaimMapper.map(SUBJECT, entity, PULL)).noneMatch(a -> a.toQid().equals("Q11424"));
  }

  @Test
  @DisplayName("instanceOf exposes the P31 values for KindMapper")
  void exposesInstanceOf() {
    assertThat(ClaimMapper.instanceOf(entity)).containsExactly("Q11424");
  }

  @Test
  @DisplayName("the English label and description are readable")
  void readsLabelAndDescription() {
    assertThat(ClaimMapper.label(entity)).isEqualTo("The Proposition");
    assertThat(ClaimMapper.description(entity)).isEqualTo("2005 film by John Hillcoat");
  }
}
