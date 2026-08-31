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

  private static Provenance wikidataProvenance() {
    return new Provenance("wikidata", "wikidata:Q42#P737", WHEN, 1.0);
  }

  private static Provenance ownerProvenance() {
    return Provenance.owner(WHEN);
  }

  @Test
  @DisplayName("should not let an owner claim corroborate a source's claim")
  void shouldNotLetAnOwnerClaimCorroborateASourcesClaim() {
    EdgeRecord edge =
        new EdgeRecord(
            "Q00900042",
            "Q42",
            "INFLUENCED_BY",
            null,
            null,
            List.of(wikidataProvenance(), ownerProvenance()));

    assertThat(edge.corroboration())
        .as("the owner is not a second witness to the world; two sources here is one")
        .isEqualTo(1);
  }
}
