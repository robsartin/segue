package com.robsartin.segue.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.robsartin.segue.domain.LocalEntity;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.OwnerEdge;
import com.robsartin.segue.domain.Retraction;
import com.robsartin.segue.domain.SameAs;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.port.IdentityMerge;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #228 whole-branch review, finding 1: {@code IngestService.claim}'s {@code OwnerEdge} arm
 * reaches {@code refuseEndpointsNothingHolds} only through {@code .ifPresent(...)} on {@code
 * Equivalences#foldEndpoints}, so an owner edge whose folded endpoints yield <b>nothing</b> is
 * ACCEPTED, appended, and applies at every boot forever — not refused, and not a bug: the comment
 * above {@code refuseEndpointsNothingHolds} says "a withdrawn or collapsed edge applies nothing and
 * the log boots, which is the only thing this guard is for." This test pins that as a bootability
 * gate rather than an effect gate: {@code claim} promises the log can always be replayed, not that
 * every accepted row does something.
 *
 * <p><b>Two shapes, matching {@code Equivalences#foldEndpoints}'s two reasons for yielding
 * nothing.</b> The first names an endpoint {@code Equivalences#retractedStandIns} has emptied — a
 * merge stood a canonical id up, and a retraction of the merge's local side took its only node away
 * before the owner edge naming it was claimed. The second names two endpoints that fold onto the
 * SAME canonical id — two locals merged onto one entity, and an edge between them collapses to a
 * self-loop, which {@code foldEndpoints} treats as manufactured evidence and drops.
 *
 * <p>Every entity here is invented (ADR 40, issue #37); {@code WOKEN} and {@code KEPSAKE} carry ADR
 * 59's two leading zeros (a minted local id) and {@code ANNEXED} carries ADR 62's eleven digits
 * with no leading zero (a merge's canonical side).
 */
class AnAcceptedOwnerEdgeThatAppliesNothingTest {

  private static final String WOKEN = "Q00900060";
  private static final String KEPSAKE = "Q00900061";
  private static final String ANNEXED = "Q10000900160";

  private static final Instant CLAIMED_AT = Instant.parse("2026-09-04T09:00:00Z");

  @Test
  @DisplayName(
      "should accept and apply nothing when an owner edge names a canonical id a retraction"
          + " emptied")
  void shouldAcceptAndApplyNothingWhenAnOwnerEdgeNamesACanonicalIdARetractionEmptied(
      @TempDir Path dir) {
    Path db = dir.resolve("segue.db");

    try (AssertionLog log = new SqliteAssertionLog(db)) {
      IngestService.claim(
          log, LocalEntity.minted(WOKEN, NodeKind.WORK, "a working title", CLAIMED_AT));
      IngestService.claim(
          log, LocalEntity.minted(KEPSAKE, NodeKind.WORK, "a keepsake", CLAIMED_AT));
      IngestService.claim(log, SameAs.declared(WOKEN, ANNEXED, CLAIMED_AT));
      IngestService.retract(log, new Retraction(WOKEN, "the wrong thing", CLAIMED_AT));

      assertThatCode(
              () ->
                  IngestService.claim(
                      log, OwnerEdge.claimed(KEPSAKE, ANNEXED, "INFLUENCED_BY", CLAIMED_AT)))
          .as(
              "withdrawn: the fold holds no node for ANNEXED once its only stand-in's local side"
                  + " is retracted, so foldEndpoints yields nothing and"
                  + " refuseEndpointsNothingHolds's .ifPresent never runs")
          .doesNotThrowAnyException();

      assertThat(log.readAll()).as("the claim was accepted and appended, not refused").hasSize(5);
    }

    try (AssertionLog reopened = new SqliteAssertionLog(db);
        GraphStore rebuilt = new TinkerGraphStore()) {
      assertThatCode(() -> GraphProjector.project(reopened, rebuilt, IdentityMerge.NONE))
          .as("the log is bootable forever, which is what the gate actually promises")
          .doesNotThrowAnyException();
      assertThat(rebuilt.edgeCount())
          .as("and the accepted edge applies nothing, on every boot, forever")
          .isZero();
    }
  }

  @Test
  @DisplayName(
      "should accept and apply nothing when an owner edge's two endpoints fold onto one canonical"
          + " id")
  void shouldAcceptAndApplyNothingWhenAnOwnerEdgesTwoEndpointsFoldOntoOneCanonicalId(
      @TempDir Path dir) {
    Path db = dir.resolve("segue.db");

    try (AssertionLog log = new SqliteAssertionLog(db)) {
      IngestService.claim(
          log, LocalEntity.minted(WOKEN, NodeKind.WORK, "a working title", CLAIMED_AT));
      IngestService.claim(
          log, LocalEntity.minted(KEPSAKE, NodeKind.WORK, "a keepsake", CLAIMED_AT));
      IngestService.claim(log, SameAs.declared(WOKEN, ANNEXED, CLAIMED_AT));
      IngestService.claim(log, SameAs.declared(KEPSAKE, ANNEXED, CLAIMED_AT));

      assertThatCode(
              () ->
                  IngestService.claim(
                      log, OwnerEdge.claimed(WOKEN, KEPSAKE, "INFLUENCED_BY", CLAIMED_AT)))
          .as(
              "collapsed: both endpoints fold onto ANNEXED, so foldEndpoints drops the self-loop"
                  + " and refuseEndpointsNothingHolds's .ifPresent never runs")
          .doesNotThrowAnyException();

      assertThat(log.readAll()).as("the claim was accepted and appended, not refused").hasSize(5);
    }

    try (AssertionLog reopened = new SqliteAssertionLog(db);
        GraphStore rebuilt = new TinkerGraphStore()) {
      assertThatCode(() -> GraphProjector.project(reopened, rebuilt, IdentityMerge.NONE))
          .as("the log is bootable forever, which is what the gate actually promises")
          .doesNotThrowAnyException();
      assertThat(rebuilt.edgeCount())
          .as("and the accepted edge applies nothing, on every boot, forever")
          .isZero();
    }
  }
}
