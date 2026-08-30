package com.robsartin.segue.recommend;

import static com.robsartin.segue.recommend.InventedWorld.sourced;
import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.AffinityRecord;
import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.EdgeTypes;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.Recommendations;
import com.robsartin.segue.sqlite.SqliteAffinityStore;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #85's own example, run through the real tool: <b>a candidate reached by three things rated
 * 5 outranks one reached by six things rated 2.</b>
 *
 * <p>ADR 45 built the recommender with the affinity half of ADR 33's payoff deliberately unwired —
 * {@code Recommendations.EQUAL_REGARD} — because ADR 33 treated a rating as personal data the
 * recommender could not see. Issue #85 moved that line to run between the score and the note, so
 * this is the test that says the seam is now a wire, end to end: the same graph, the same
 * known-list, the same scorer, and a different order once the {@code affinity} table has something
 * in it.
 *
 * <p><b>It drives {@link RecommendCli#main}, twice.</b> The point is the wiring rather than the
 * arithmetic — {@code RecommendationsTest} owns the weighting and {@code CandidateSweepTest} owns
 * the seam — and the wiring is a store being opened, read without its notes, and turned into a
 * function. A test calling {@link RecommendRun} directly would supply that function itself and
 * prove nothing about where it comes from.
 *
 * <p><b>The graph is built to make the two effects separable.</b> Both candidates end at exactly 12
 * edges, every intermediate is reached by exactly one known entity and carries exactly two edges,
 * and every scoring hop is an {@code INFLUENCED_BY} stated in the same direction. So the only thing
 * that differs between them is how many of the owner's entities reach them, and — after the ratings
 * are written — what those entities are worth.
 *
 * <p><b>Untested against real ratings, and it has to be said plainly.</b> The {@code affinity}
 * table on the machine this was written for holds zero rows, so nothing here has met a rating
 * somebody actually wrote. Every qid, label and rating below is invented (ADR 33, issue #37).
 */
class AffinityWeightedRecommendationTest {

  // Three things the owner loves, each reaching the first candidate through its own intermediate.
  private static final List<String> LOVED = List.of("Q900111", "Q900112", "Q900113");

  // Six things the owner is lukewarm about, reaching the second candidate the same way.
  private static final List<String> LUKEWARM =
      List.of("Q900121", "Q900122", "Q900123", "Q900124", "Q900125", "Q900126");

  private static final String BELOVED = "Q900301";
  private static final String CROWDED = "Q900302";

  /**
   * The shipped floor, by reference and never as a second copy: both candidates sit exactly on it.
   */
  private static final int DEGREE = Recommendations.MIN_CANDIDATE_DEGREE;

  private static final Instant RATED_AT = Instant.parse("2026-08-27T09:00:00Z");

  @TempDir private Path dir;

  @Test
  @DisplayName("six lukewarm things outrank three loved ones — until the ratings are written")
  void ratingsMoveTheRanking() throws IOException {
    Path db = graphOnDisk();
    Path known = knownList();

    String unweighted = recommendTo(db, known, "before.txt");
    // The starting position, and the reason the weighting is worth having: counting alone, the
    // candidate six of your things reach beats the one three of them reach, every time.
    assertThat(rankOf(unweighted, CROWDED)).isLessThan(rankOf(unweighted, BELOVED));

    rate(db);

    String weighted = recommendTo(db, known, "after.txt");
    assertThat(rankOf(weighted, BELOVED)).isLessThan(rankOf(weighted, CROWDED));
  }

  /** Invented ratings, written into the same file the graph lives in (ADR 33, ADR 39). */
  private void rate(Path db) {
    try (SqliteAffinityStore affinity = new SqliteAffinityStore(db)) {
      for (String loved : LOVED) {
        affinity.put(new AffinityRecord(loved, 5, "an invented note nobody will read", RATED_AT));
      }
      for (String lukewarm : LUKEWARM) {
        affinity.put(new AffinityRecord(lukewarm, 2, null, RATED_AT));
      }
    }
  }

  private String recommendTo(Path db, Path known, String fileName) throws IOException {
    Path out = dir.resolve(fileName);
    RecommendCli.main(
        new String[] {
          "--db", db.toString(),
          "--known", known.toString(),
          "--out", out.toString(),
          "--min-degree", String.valueOf(DEGREE)
        });
    return Files.readString(out);
  }

  /** Where a candidate appears in the report, which is the ranking made readable. */
  private static int rankOf(String report, String qid) {
    int at = report.indexOf("(" + qid + ")");
    assertThat(at).as("%s must appear in the report at all", qid).isNotNegative();
    return at;
  }

  private Path knownList() throws IOException {
    Path list = dir.resolve("known.csv");
    Files.writeString(list, String.join("\n", LOVED) + "\n" + String.join("\n", LUKEWARM) + "\n");
    return list;
  }

  /**
   * Two candidates, identical in every way the scorer can see except how many of yours reach them.
   */
  private Path graphOnDisk() {
    Path db = dir.resolve("scratch.db");
    try (SqliteAssertionLog log = new SqliteAssertionLog(db)) {
      log.append(new NodeAssertion(BELOVED, NodeKind.GROUP, "the loved ancestor", sourced()));
      log.append(new NodeAssertion(CROWDED, NodeKind.GROUP, "the crowded ancestor", sourced()));

      int intermediate = 0;
      for (String seed : LOVED) {
        reaches(log, seed, "Q9002" + (10 + intermediate++), BELOVED);
      }
      for (String seed : LUKEWARM) {
        reaches(log, seed, "Q9002" + (10 + intermediate++), CROWDED);
      }

      // Pad both to the same degree with records nothing on the list touches, so that lift — which
      // divides by the candidate's own degree — is comparing the two on equal terms.
      padTo(log, BELOVED, DEGREE - LOVED.size(), 0);
      padTo(log, CROWDED, DEGREE - LUKEWARM.size(), 50);
    }
    return db;
  }

  /** One of yours cites an artist; that artist cites the candidate. */
  private static void reaches(SqliteAssertionLog log, String seed, String via, String candidate) {
    log.append(new NodeAssertion(seed, NodeKind.GROUP, "one of yours " + seed, sourced()));
    log.append(new NodeAssertion(via, NodeKind.PERSON, "a cited artist " + via, sourced()));
    log.append(influence(seed, via));
    log.append(influence(via, candidate));
  }

  private static void padTo(SqliteAssertionLog log, String candidate, int records, int offset) {
    for (int i = 0; i < records; i++) {
      String record = "Q9009" + (offset + i);
      log.append(
          new NodeAssertion(record, NodeKind.WORK, "an invented record " + record, sourced()));
      log.append(
          new AssertionRecord(
              candidate, record, EdgeTypes.PERFORMED.code(), null, null, sourced()));
    }
  }

  private static AssertionRecord influence(String from, String to) {
    return new AssertionRecord(from, to, EdgeTypes.INFLUENCED_BY.code(), null, null, sourced());
  }
}
