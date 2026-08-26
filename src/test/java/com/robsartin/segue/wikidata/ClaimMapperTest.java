package com.robsartin.segue.wikidata;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.AssertionRecord;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Wikidata claims to segue assertions: whitelist, direction, dates, confidence. */
class ClaimMapperTest {

  private static final Instant PULL = Instant.parse("2026-08-24T09:00:00Z");
  private static final String SUBJECT = "Q180337";

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
        .containsExactlyInAnyOrder(
            "DIRECTED",
            "COMPOSED_FOR",
            "WROTE_SCREENPLAY_FOR",
            "MEMBER_OF",
            "BASED_ON",
            "PERFORMED",
            "RECEIVED_AWARD");
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

    assertThat(directed.fromQid()).isEqualTo("Q552814");
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
  @DisplayName("an award received is stored pointing from the recipient at the award")
  void awardsPointFromTheRecipient() {
    // Issue #32. Wikidata states P166 on the RECIPIENT — "The Proposition P166 AACTA Award for
    // Best Cinematography" — so RECEIVED_AWARD is a DIRECT type and the subject stays on the
    // left. This is the assertion that would have caught registering it as inverted, which reads
    // as the award having received the film.
    AssertionRecord award =
        ClaimMapper.map(SUBJECT, entity, PULL).stream()
            .filter(a -> a.typeCode().equals("RECEIVED_AWARD"))
            .findFirst()
            .orElseThrow();

    assertThat(award.fromQid()).isEqualTo(SUBJECT);
    assertThat(award.toQid()).isEqualTo("Q4649799");
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
  @DisplayName("a references block of only P143/P4656 counts as unreferenced")
  void selfReferentialImportReferencesDoNotCount() {
    // P144 in the fixture carries one reference, and that reference is only P143
    // ("imported from Wikimedia project") — a bot import citing itself, not authority.
    // ADR 23's 1.00 tier is for a real reference; this must land at 0.80 like no reference
    // at all.
    AssertionRecord basedOn =
        ClaimMapper.map(SUBJECT, entity, PULL).stream()
            .filter(a -> a.typeCode().equals("BASED_ON"))
            .findFirst()
            .orElseThrow();

    assertThat(basedOn.provenance().confidence()).isEqualTo(0.80);
  }

  @Test
  @DisplayName("a non-QID object id is skipped rather than reaching the graph store broken")
  void skipsNonQidObjectIds() {
    // P361 in the fixture points at "P123" — the shape wikibase-property/lexeme/form/sense
    // datavalues take. AssertionRecord does not validate, so an unvalidated id would reach
    // TinkerGraphStore.requireVertex and blow up mid-batch, after the log entry is already
    // written.
    assertThat(ClaimMapper.map(SUBJECT, entity, PULL))
        .noneMatch(a -> a.typeCode().equals("PART_OF"));
  }

  @Test
  @DisplayName("a below-day-precision date is treated as absent, not a fabricated day")
  void ignoresLowPrecisionDates() {
    // P175 in the fixture has a P580 qualifier at precision 9 (year). Reading its raw
    // "+1990-01-01..." text as a LocalDate would feed false day-level precision into
    // validAt() time-travel queries.
    AssertionRecord performed =
        ClaimMapper.map(SUBJECT, entity, PULL).stream()
            .filter(a -> a.typeCode().equals("PERFORMED"))
            .findFirst()
            .orElseThrow();

    assertThat(performed.validFrom()).isNull();
  }

  @Test
  @DisplayName("a deprecated statement is dropped, even though it carries a reference")
  void dropsDeprecatedStatements() {
    // P737 in the fixture is rank "deprecated" with a non-empty references array — Wikidata
    // marks these wrong-but-recorded. Without the guard this would land at confidence 1.00,
    // the top of ADR 23's scale, and PathRanking would surface a known-false claim first.
    assertThat(ClaimMapper.map(SUBJECT, entity, PULL))
        .noneMatch(a -> a.typeCode().equals("INFLUENCED_BY"));
  }

  @Test
  @DisplayName(
      "a snak with no value, and a snak whose value is not an entity id, are both skipped rather"
          + " than crashing the whole entity")
  void skipsValuelessAndMistypedSnaks() {
    // P57 in the fixture now carries three statements: one good DIRECTED claim, one
    // snaktype "somevalue" (Wikidata's "we know there is one but not what it is"), and one
    // whose datavalue is a plain string rather than an entity id. Only the first should
    // survive — the other two must not crash the whole entity, and must not silently count.
    List<AssertionRecord> out = ClaimMapper.map(SUBJECT, entity, PULL);

    assertThat(out).filteredOn(a -> a.typeCode().equals("DIRECTED")).hasSize(1);
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
