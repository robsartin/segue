package com.robsartin.segue.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link EdgeRecord#corroboration()} counts distinct sources - and an owner claim is deliberately
 * not a second one over a pair a real source already asserted (#92). ADR 55 declined {@code
 * subgroup} partly because a Wikidata coding manufacturing corroboration with itself was exactly
 * this hazard; an owner claim over the same pair does it with the owner's own hand instead.
 */
class EdgeRecordTest {

  private static final Instant WHEN = Instant.parse("2026-01-01T00:00:00Z");

  // ADR 58 stand-ins (ONE leading zero, Fixture's own Q0900001... shape): neither end names a
  // real Wikidata entity, so the fabricated INFLUENCED_BY claim below denotes nothing real either
  // (fix round 1: the brief's original "Q42" was a real allocated item — ADR 58's own named
  // failure pattern). Two leading zeros is LocalEntity's shape, not this one - see
  // LocalEntity.checkLocalShape and OwnerClaimProjectionTest:44 (fix round 2).
  private static final String FROM = "Q0900042";
  private static final String TO = "Q0900043";

  private static Provenance wikidataProvenance() {
    return new Provenance("wikidata", "S-invented-influence", WHEN, 1.0);
  }

  private static Provenance ownerProvenance() {
    return Provenance.owner(WHEN);
  }

  @Test
  @DisplayName("should not let an owner claim corroborate a source's claim")
  void shouldNotLetAnOwnerClaimCorroborateASourcesClaim() {
    EdgeRecord edge =
        new EdgeRecord(
            FROM,
            TO,
            "INFLUENCED_BY",
            null,
            null,
            List.of(wikidataProvenance(), ownerProvenance()));

    assertThat(edge.corroboration())
        .as("the owner is not a second witness to the world; two sources here is one")
        .isEqualTo(1);
  }
}
