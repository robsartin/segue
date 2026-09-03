package com.robsartin.segue.recommend;

import static com.robsartin.segue.recommend.InventedWorld.sourced;
import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.AffinityRecord;
import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.EdgeTypes;
import com.robsartin.segue.domain.LocalEntity;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.OwnerEdge;
import com.robsartin.segue.domain.SameAs;
import com.robsartin.segue.domain.Scorer;
import com.robsartin.segue.port.IdentityMerge;
import com.robsartin.segue.sqlite.SqliteAffinityStore;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #92: a merge leaves two ids naming one thing, and the recommender must still count it once.
 *
 * <p><b>The two tests here are the two faces of one defect, and both were measured before either
 * was written.</b> Against Task 4's head, over the invented graph this class builds — one minted
 * entity rated 5, reaching one candidate through one artist:
 *
 * <pre>
 *   BEFORE merge    candidate 1.6667, 1 shared intermediate
 *   AFTER  merge    candidate 3.3333, 2 shared intermediates
 *   CANONICAL only  the MINTED ID ranked first, above the candidate the run existed to find
 * </pre>
 *
 * <p>Exactly doubled. {@code KnownList.promoted} promotes both affinity rows a merge leaves behind,
 * the graph held both nodes carrying the same edges until #178 folded them onto one, and the
 * owner's one opinion seeds the sweep twice. The third line is the same defect from the other side:
 * with only the canonical id rated, segue offers the owner the entity he minted and then told it
 * about.
 *
 * <p><b>The first test scores by {@code raw}, and the reason was a residual #92's fix could not
 * reach.</b> A merge used to <em>add</em> an edge to the graph — {@code IngestService.carry} copied
 * the owner's edge onto the canonical id and left the local one where it was — so the shared
 * artist's degree grew by one, and {@code lift} discounts each intermediate by the log of that
 * degree. Under the shipped {@code lift} the same fixture read 0.2236 before the merge, 0.4332
 * after it unfixed (1.94x), and 0.2166 after it fixed: a 3% residual that was the graph having one
 * more edge in it, not the rating being counted twice. {@code raw} is the one scorer whose discount
 * is constant, so it isolates the question this test is asking and lets it be asserted without a
 * tolerance. That residual was the concern recorded here, it became issue #178, and it is gone —
 * both folds resolve endpoints now, so the merged entity's edges exist once. This test keeps {@code
 * raw} because the question it asks is still about ratings and not about degree.
 *
 * <p><b>It drives {@link RecommendCli#main} rather than {@link RecommendRun}, for the reason {@code
 * AffinityWeightedRecommendationTest} does</b> — what is under test is a wiring, not an arithmetic.
 * The equivalence is folded out of the ratings where the ratings are read, and a test that
 * assembled that fold itself would prove nothing about where the fold comes from.
 *
 * <p>Every qid and label below is invented; the canonical side of a merge has to be an id Wikidata
 * could allocate ({@code SameAs} refuses anything else), so {@code Q900} stands in for one exactly
 * as {@code MergeCarriesEverythingTest} uses it.
 */
class MergedIdIsOfferedOnceTest {

  /** Two leading zeros: a local entity, not one of ADR 58's single-zero stand-ins. */
  private static final String MINTED = "Q00900042";

  /** The id Wikidata turned out to have. Allocatable, which the local side never is. */
  private static final String CANONICAL = "Q900";

  private static final String VIA = "Q900211";
  private static final String CANDIDATE = "Q900311";

  /** On the {@code --known} file and reaching nothing: the file may not be empty. */
  private static final String ON_THE_FILE = "Q900199";

  /** Low enough that nothing needs padding past three edges, and above the sweep's own minimum. */
  private static final int FLOOR = 3;

  private static final Instant WHEN = Instant.parse("2026-08-31T09:00:00Z");

  @TempDir private Path dir;

  @Test
  @DisplayName("a merged entity's rating counts once, not twice, so the score does not move")
  void shouldCountAMergedEntitysRatingOnceWhenBothIdsAreInTheGraph() throws IOException {
    Path db = graphOnDisk();
    rate(db, MINTED);

    double before = scoreOf(recommendTo(db, "before.txt", Scorer.RAW), CANDIDATE);

    merge(db);

    double after = scoreOf(recommendTo(db, "after.txt", Scorer.RAW), CANDIDATE);
    assertThat(after)
        .as("a merge says two ids are one thing; counting both is counting the owner's word twice")
        .isEqualTo(before);
  }

  @Test
  @DisplayName(
      "a merged local id is not offered back as a candidate when the canonical id is rated")
  void shouldNotOfferBackAMergedLocalIdWhenTheCanonicalIdIsRated() throws IOException {
    Path db = graphOnDisk();
    appendMerge(db);
    rate(db, CANONICAL);

    String report = recommendTo(db, "canonical-only.txt", Scorer.LIFT);

    assertThat(report)
        .as("the owner minted this and then said what it really is; offering it back is a loop")
        .doesNotContain("(" + MINTED + ")");
  }

  /**
   * The merge as production makes it: appended to the log, and its rating carried by the same port
   * {@code IngestService.apply} calls. Both halves, because both halves are what leaves two live
   * affinity rows behind.
   */
  private void merge(Path db) {
    appendMerge(db);
    try (SqliteAffinityStore affinity = new SqliteAffinityStore(db)) {
      IdentityMerge.carryingRatings(affinity).follow(MINTED, CANONICAL);
    }
  }

  private void appendMerge(Path db) {
    try (SqliteAssertionLog log = new SqliteAssertionLog(db)) {
      log.append(SameAs.declared(MINTED, CANONICAL, WHEN));
    }
  }

  private void rate(Path db, String qid) {
    try (SqliteAffinityStore affinity = new SqliteAffinityStore(db)) {
      affinity.put(new AffinityRecord(qid, 5, null, WHEN));
    }
  }

  /** One thing the owner minted, citing an artist, who cites the candidate. */
  private Path graphOnDisk() {
    Path db = dir.resolve("scratch.db");
    try (SqliteAssertionLog log = new SqliteAssertionLog(db)) {
      log.append(LocalEntity.minted(MINTED, NodeKind.GROUP, "a band no source knows", WHEN));
      log.append(new NodeAssertion(ON_THE_FILE, NodeKind.GROUP, "one on your list", sourced()));
      log.append(new NodeAssertion(VIA, NodeKind.PERSON, "an artist they cite", sourced()));
      log.append(new NodeAssertion(CANDIDATE, NodeKind.GROUP, "who that artist cites", sourced()));
      log.append(OwnerEdge.claimed(MINTED, VIA, EdgeTypes.INFLUENCED_BY.code(), WHEN));
      log.append(
          new AssertionRecord(
              VIA, CANDIDATE, EdgeTypes.INFLUENCED_BY.code(), null, null, sourced()));

      // Both the minted entity and the candidate have to clear the floor, or the first is never
      // offered back (which is the second test's whole subject) and the second is never ranked.
      padTo(log, MINTED, FLOOR - 1, 10);
      padTo(log, CANDIDATE, FLOOR - 1, 20);

      // The artist is padded off the floor of a small graph, so that neither test is reading a
      // number produced by a two-edge node. A PERSON is never a hub whatever its degree
      // (PathRanking.isHub asks CONCEPT only), so this changes nothing else about the walk.
      padTo(log, VIA, 10, 30);
    }
    return db;
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

  private String recommendTo(Path db, String fileName, Scorer scorer) throws IOException {
    Path out = dir.resolve(fileName);
    RecommendCli.main(
        new String[] {
          "--db", db.toString(),
          "--known", knownList().toString(),
          "--out", out.toString(),
          "--min-degree", String.valueOf(FLOOR),
          "--scorer", scorer.spelling()
        });
    return Files.readString(out);
  }

  private Path knownList() throws IOException {
    Path list = dir.resolve("known.csv");
    Files.writeString(list, ON_THE_FILE + "\n");
    return list;
  }

  /** The score the report prints beside a candidate, which is the number a reader would notice. */
  private static double scoreOf(String report, String qid) {
    Matcher line =
        Pattern.compile("(?m)^\\s*\\d+\\.\\s+(\\d+\\.\\d+)\\s+.*\\(" + Pattern.quote(qid) + "\\)")
            .matcher(report);
    assertThat(line.find()).as("%s must appear in the report at all", qid).isTrue();
    return Double.parseDouble(line.group(1));
  }
}
