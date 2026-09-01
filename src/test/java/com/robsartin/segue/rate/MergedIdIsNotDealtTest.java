package com.robsartin.segue.rate;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.EdgeTypes;
import com.robsartin.segue.domain.LocalEntity;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.OwnerEdge;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.domain.SameAs;
import com.robsartin.segue.ingest.GraphProjector;
import com.robsartin.segue.port.IdentityMerge;
import com.robsartin.segue.rate.RateCli.Options;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #92 in the deck: the tool the owner actually sits in front of resolves merges too.
 *
 * <p><b>This class exists because a measurement said it had to.</b> The review of task 4b reverted
 * {@code RateCli}'s {@code Equivalences.resolve} call — and only that one — and the whole 950-test
 * suite stayed green. {@code RecommendCli.main} can be driven by a test; {@code RateCli.main}
 * starts a blocking HTTP server and cannot, and the {@code RateRun} tests enter below the point
 * where the fold happens. So a one-line deletion silently restored the defect in the interactive
 * tool, with nothing to catch it.
 *
 * <p>{@link RateCli#deck} is the seam that closes that, on {@code RateCli.known}'s own precedent,
 * and this drives it over a real log, a real replay and a real graph.
 *
 * <p><b>What the deck shows when the fold is missing.</b> The owner rated the id he minted and
 * later said what it really is. Unresolved, the rating stays on the local id, the canonical id is
 * on nobody's list and carries the merge's copied edges — so <b>the deck offers him the canonical
 * id as a discovery</b>, which is his own entity handed back under its new name. Resolved, the
 * rating is the canonical id's, the canonical id is known, and it is not dealt at all.
 *
 * <p>Every qid and label is invented. The canonical side of a merge must be an id Wikidata could
 * allocate, so {@code Q900} stands in for one as {@code MergeCarriesEverythingTest} uses it.
 */
class MergedIdIsNotDealtTest {

  /** Two leading zeros: a local entity, not one of ADR 58's single-zero stand-ins. */
  private static final String MINTED = "Q00900042";

  private static final String CANONICAL = "Q900";
  private static final String VIA = "Q900211";
  private static final String ON_THE_FILE = "Q900199";

  private static final int FLOOR = 3;
  private static final Instant WHEN = Instant.parse("2026-08-31T09:00:00Z");

  @TempDir private Path dir;

  @Test
  @DisplayName("the deck does not offer back the canonical id of something the owner merged")
  void theDeckDoesNotOfferBackWhatTheOwnerMerged() throws IOException {
    List<String> notes = new ArrayList<>();

    List<Card> deck = deckFor(Map.of(MINTED, 5), notes);

    assertThat(deck)
        .extracting(Card::qid)
        .as("the owner minted this and said what it is; dealing it back is his own entity renamed")
        .doesNotContain(CANONICAL);
    assertThat(notes).anyMatch(note -> note.contains("0 candidate(s) mixed in"));
  }

  @Test
  @DisplayName("a merged local id is not dealt either, at any rating the owner has given it")
  void theMergedLocalIdIsNotDealt() throws IOException {
    List<Card> deck = deckFor(Map.of(MINTED, 5), new ArrayList<>());

    assertThat(deck).extracting(Card::qid).doesNotContain(MINTED);
  }

  /** Replay the log exactly as {@code RateCli.main} does, then run the seam it runs. */
  private List<Card> deckFor(Map<String, Integer> asStored, List<String> notes) throws IOException {
    Path db = dir.resolve("scratch.db");
    try (SqliteAssertionLog assertions = logOnDisk(db);
        TinkerGraphStore graph = new TinkerGraphStore()) {
      GraphProjector.project(assertions, graph, IdentityMerge.NONE);
      return RateCli.deck(graph, assertions.readAll(), asStored, options(), notes::add);
    }
  }

  /**
   * One thing the owner minted, citing an artist, and then merged. The artist is padded so both the
   * minted id and the canonical copy of it clear the floor and are eligible to be dealt — a
   * candidate the floor holds out would make either assertion pass for the wrong reason.
   */
  private SqliteAssertionLog logOnDisk(Path db) {
    SqliteAssertionLog log = new SqliteAssertionLog(db);
    log.append(LocalEntity.minted(MINTED, NodeKind.GROUP, "a band no source knows", WHEN));
    log.append(new NodeAssertion(ON_THE_FILE, NodeKind.GROUP, "one on your list", sourced()));
    log.append(new NodeAssertion(VIA, NodeKind.PERSON, "an artist they cite", sourced()));
    log.append(OwnerEdge.claimed(ON_THE_FILE, VIA, EdgeTypes.INFLUENCED_BY.code(), WHEN));
    log.append(OwnerEdge.claimed(MINTED, VIA, EdgeTypes.INFLUENCED_BY.code(), WHEN));
    padTo(log, MINTED, FLOOR - 1, 10);
    padTo(log, VIA, 10, 30);
    log.append(SameAs.declared(MINTED, CANONICAL, WHEN));
    return log;
  }

  /** Records nobody's list touches: a WORK is never a candidate, whatever its degree. */
  private static void padTo(SqliteAssertionLog log, String qid, int records, int offset) {
    for (int i = 0; i < records; i++) {
      String record = "Q9009" + (offset + i);
      log.append(
          new NodeAssertion(record, NodeKind.WORK, "an invented record " + record, sourced()));
      log.append(
          new AssertionRecord(qid, record, EdgeTypes.PERFORMED.code(), null, null, sourced()));
    }
  }

  private Options options() throws IOException {
    Path list = dir.resolve("known.csv");
    Files.writeString(list, ON_THE_FILE + "\n", StandardCharsets.UTF_8);
    return new Options(
        dir.resolve("scratch.db"), list, RateCli.DEFAULT_PORT, FLOOR, OptionalInt.empty());
  }

  private static Provenance sourced() {
    return new Provenance("invented", "invented:1", WHEN, 1.0);
  }
}
