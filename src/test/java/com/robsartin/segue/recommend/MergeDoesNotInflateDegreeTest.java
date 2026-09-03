package com.robsartin.segue.recommend;

import static com.robsartin.segue.recommend.InventedWorld.sourced;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.robsartin.segue.domain.AffinityRecord;
import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.EdgeTypes;
import com.robsartin.segue.domain.LocalEntity;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.OwnerEdge;
import com.robsartin.segue.domain.SameAs;
import com.robsartin.segue.sqlite.SqliteAffinityStore;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Issue #178: a merge is an equivalence, not new evidence, so it must not move the page.
 *
 * <p><b>What the defect was.</b> {@code IngestService.carry} copied a merged local entity's edges
 * onto its canonical QID and left them on the local id, and {@code LogProjection.carry} did the
 * same to the same log. Two nodes then carried one entity's edges, so every neighbour of the merged
 * entity had one more incident edge than the world justified. {@code Scorer.LIFT} divides by the
 * candidate's own degree and discounts each intermediate by the log of its degree, so the inflation
 * reached the score twice over. Both folds now read every endpoint through {@code
 * Equivalences.foldEndpoints} instead, so a merged entity's edges exist once.
 *
 * <p><b>This is the graph half of the defect alone.</b> The owner's entity below is deliberately
 * <em>unrated</em>: issue #92 already folded the ratings so a merged entity counts once, and rating
 * this one would mix the two halves back together. It carries the shape ADR 59 exists for — a thing
 * no source knows, minted and connected by the owner — and nothing else.
 *
 * <p><b>Measured before it was written</b>, and the measurement is in {@code
 * docs/superpowers/specs/2026-09-02-merge-degree-design.md}. Against the copy, at twenty owner
 * edges the rank-1 candidate loses 12.50 % of its score and drops to rank 2 behind an entity that
 * did not move at all, one entry leaves the page and one enters; at two owner edges a degree-10
 * candidate loses 9.15 % and moves three places. Restoring the copy in the boot fold alone reds
 * this test at all three degrees with exactly those figures, which is the control that says it can
 * fail. The same fixture rebuilt with the owner's edges counted <em>once</em> returns the pre-merge
 * top 25 in the pre-merge order with a largest score difference of 0.0000000000 — so the target
 * state is exact and needs no tolerance.
 *
 * <p><b>The instrument is validated before it is believed.</b> An empty difference and a dead
 * instrument look identical, so the first test replays one unchanged log twice and holds the two
 * files to being byte-identical. It is green on today's code. Nothing the second test reports means
 * anything without it. What makes that possible is that the report carries no clock and no random
 * order: {@code RecommendationReport} writes counts, a floor reading and a ranking, and {@code
 * KnownList.promoted} and {@code Equivalences.in} both keep log order (ADR 45).
 *
 * <p><b>The comparison is at the resolution the file prints.</b> {@code RecommendationReport} emits
 * four decimal places, so the 1e-9 tolerance below is exact equality of what a reader sees and
 * cannot notice a score change smaller than 5e-5. That is deliberate: the guard is written against
 * the artefact the operator compares, the same way ADR 45's amendment method compares two runs.
 *
 * <p><b>The guard below was committed {@code @Disabled} and the fold is what took the annotation
 * off.</b> It was never a pending test nor a known-failure to live with: it is the definition of
 * done for the fold, parked red for exactly as long as the defect stood. It was one of <b>two</b>
 * such guards — the other is {@code MergedIdIsDrawnAsAnOrphanTest}, which holds controller ruling 3
 * — and the gate is what says both came off: it reported <b>2 skipped</b> while they were parked
 * and reports <b>0</b> now. A skip count above zero here would mean the fold had shipped with its
 * own guards switched off, which is the one way these tests could be worthless.
 *
 * <p>Every QID and label here is invented and no known-list is behind them (ADR 40, ADR 51). The
 * canonical side of a merge has to be an id Wikidata could allocate ({@code SameAs} refuses
 * anything else) and the local side has to be one it never could ({@code LocalEntity}'s {@code
 * Q00…} shape).
 */
class MergeDoesNotInflateDegreeTest {

  /** Two leading zeros: the shape {@code LocalEntity} reserves for something the owner minted. */
  private static final String LOCAL = "Q00930001";

  /** The id the owner later found it already had. Allocatable, which the local side never is. */
  private static final String CANONICAL = "Q930900";

  /** On the {@code --known} file. Four of them, two rated, exactly as the spec measured. */
  private static final int SEEDS = 4;

  /** {@code PERSON} intermediates, padded to degrees 12–22. */
  private static final int VIAS = 6;

  /** {@code GROUP} candidates, padded to degrees 6–33. */
  private static final int CANDIDATES = 30;

  /** The shipped default, and the floor the measurement was taken at. */
  private static final int FLOOR = 5;

  private static final int TOP = 25;

  /** Exact, at the four decimal places the report prints. */
  private static final double SCORE_TOLERANCE = 1e-9;

  private static final Instant WHEN = Instant.parse("2026-09-02T09:00:00Z");

  /** Rank, id and score, read back off the file rather than off an object nobody sees. */
  private record Entry(int rank, String qid, double score) {}

  @TempDir private Path dir;

  @DisplayName("replaying one unchanged log twice writes the same bytes, so a difference is real")
  @ParameterizedTest(name = "the minted entity carries {0} edge(s)")
  @ValueSource(ints = {2, 5, 20})
  void shouldWriteTheSameReportWhenTheSameUnchangedLogIsReplayedTwice(int ownerEdges)
      throws IOException {
    Path db = graphOnDisk(ownerEdges);

    String once = recommendTo(db, "once.txt");
    String again = recommendTo(db, "again.txt");

    assertThat(again)
        .as(
            "an empty difference and a dead instrument look the same; this is what tells them apart")
        .isEqualTo(once);
  }

  @DisplayName("a merge does not move the top 25, because an equivalence is not new evidence")
  @ParameterizedTest(name = "the merged entity carries {0} edge(s)")
  @ValueSource(ints = {2, 5, 20})
  void shouldLeaveTheTopTwentyFiveUnmovedWhenAMergeIsAppended(int ownerEdges) throws IOException {
    Path db = graphOnDisk(ownerEdges);
    Map<String, Entry> before = resolved(ranking(recommendTo(db, "before.txt")));

    appendMerge(db);

    Map<String, Entry> after = ranking(recommendTo(db, "after.txt"));
    assertThat(order(after))
        .as(
            "a merge says two ids are one thing; it adds no evidence, so the page it produces must"
                + " be the page it produced before. What moved:%s",
            movement(before, after))
        .containsExactlyElementsOf(order(before));
    for (Entry was : before.values()) {
      assertThat(after.get(was.qid()).score())
          .as("%s kept its rank and lost score, which only an inflated degree can do", was.qid())
          .isCloseTo(was.score(), within(SCORE_TOLERANCE));
    }
  }

  /** What the merge did to the page, in the words a reader would use to check it. */
  private static String movement(Map<String, Entry> before, Map<String, Entry> after) {
    StringBuilder said = new StringBuilder();
    for (Entry was : before.values()) {
      Entry now = after.get(was.qid());
      if (now == null) {
        said.append(
            String.format(
                Locale.ROOT, "%n  %s left the page, from rank %d", was.qid(), was.rank()));
      } else if (now.rank() != was.rank() || now.score() != was.score()) {
        said.append(
            String.format(
                Locale.ROOT,
                "%n  %s rank %d -> %d, score %.4f -> %.4f (%+.2f%%)",
                was.qid(),
                was.rank(),
                now.rank(),
                was.score(),
                now.score(),
                (now.score() - was.score()) / was.score() * 100.0));
      }
    }
    for (Entry now : after.values()) {
      if (!before.containsKey(now.qid())) {
        said.append(
            String.format(
                Locale.ROOT, "%n  %s entered the page, at rank %d", now.qid(), now.rank()));
      }
    }
    return said.toString();
  }

  /**
   * The merged entity is one thing under two names, so the page before the merge is compared under
   * the name it has after it. It sits outside the top 25 in this fixture at every degree measured,
   * so this is a no-op today — and it is here because the guard would be wrong without it, not
   * because the fixture needs it.
   */
  private static Map<String, Entry> resolved(Map<String, Entry> before) {
    Map<String, Entry> under = new LinkedHashMap<>();
    for (Entry entry : before.values()) {
      String qid = entry.qid().equals(LOCAL) ? CANONICAL : entry.qid();
      under.put(qid, new Entry(entry.rank(), qid, entry.score()));
    }
    return under;
  }

  private static List<String> order(Map<String, Entry> page) {
    return new ArrayList<>(page.keySet());
  }

  /** The ranking as the file states it: the heading line of every entry, in the printed order. */
  private static Map<String, Entry> ranking(String report) {
    Matcher heading =
        Pattern.compile("(?m)^\\s*(\\d+)\\.\\s+(\\d+\\.\\d+)\\s+.*?\\((Q\\d+)\\) — ")
            .matcher(report);
    Map<String, Entry> page = new LinkedHashMap<>();
    while (heading.find()) {
      page.put(
          heading.group(3),
          new Entry(
              Integer.parseInt(heading.group(1)),
              heading.group(3),
              Double.parseDouble(heading.group(2))));
    }
    assertThat(page)
        .as("a report with no ranking in it would make every comparison vacuous")
        .hasSize(TOP);
    return page;
  }

  private String recommendTo(Path db, String fileName) throws IOException {
    Path out = dir.resolve(fileName);
    RecommendCli.main(
        new String[] {
          "--db", db.toString(),
          "--known", knownList().toString(),
          "--out", out.toString(),
          "--min-degree", String.valueOf(FLOOR),
          "--top", String.valueOf(TOP),
          "--scorer", "lift"
        });
    return Files.readString(out);
  }

  private Path knownList() throws IOException {
    StringBuilder list = new StringBuilder();
    for (int i = 0; i < SEEDS; i++) {
      list.append(seed(i)).append('\n');
    }
    Path file = dir.resolve("known.csv");
    Files.writeString(file, list.toString());
    return file;
  }

  /** The merge exactly as production makes it: appended to the same log, and replayed from it. */
  private void appendMerge(Path db) {
    try (SqliteAssertionLog log = new SqliteAssertionLog(db)) {
      log.append(SameAs.declared(LOCAL, CANONICAL, WHEN));
    }
  }

  /**
   * Four known entities, six intermediates, thirty candidates and one entity the owner minted.
   *
   * <p>The degrees are spread across the range a real graph shows at this floor, because the whole
   * effect being measured is one extra edge on a node of a given size: at degree 10 that is 9.15 %
   * of a candidate's score and at degree 31 it is 3.12 %, and a fixture with one degree in it would
   * report a single point on that curve as if it were the curve.
   */
  private Path graphOnDisk(int ownerEdges) {
    Path db = dir.resolve("scratch.db");
    Map<String, Integer> degree = new LinkedHashMap<>();
    int[] filler = {0};
    try (SqliteAssertionLog log = new SqliteAssertionLog(db)) {
      for (int i = 0; i < SEEDS; i++) {
        log.append(new NodeAssertion(seed(i), NodeKind.GROUP, "one on your list " + i, sourced()));
      }
      for (int j = 0; j < VIAS; j++) {
        log.append(
            new NodeAssertion(via(j), NodeKind.PERSON, "an artist they cite " + j, sourced()));
      }
      for (int k = 0; k < CANDIDATES; k++) {
        log.append(
            new NodeAssertion(
                candidate(k), NodeKind.GROUP, "who that artist cites " + k, sourced()));
      }

      // Each known entity cites three intermediates, and the four of them cover all six.
      for (int i = 0; i < SEEDS; i++) {
        for (int step = 0; step < 3; step++) {
          edge(log, degree, seed(i), via((i + step) % VIAS));
        }
      }
      // Each intermediate cites candidates in two arithmetic families, so the evidence overlaps
      // and a candidate can be reached by more than one of the owner's entities.
      for (int j = 0; j < VIAS; j++) {
        for (int k = 0; k < CANDIDATES; k++) {
          if (k % VIAS == j || k % 7 == j) {
            edge(log, degree, via(j), candidate(k));
          }
        }
      }

      for (int j = 0; j < VIAS; j++) {
        padTo(log, degree, filler, via(j), 12 + j * 2);
      }
      for (int k = 0; k < CANDIDATES; k++) {
        padTo(log, degree, filler, candidate(k), 6 + (k % 10) * 3);
      }

      // The owner's own entity: one intermediate first, then candidates, so that both mechanisms
      // the spec measured are in the fixture — a candidate's own degree raised by one, and an
      // intermediate's degree raised by one, which reaches every candidate behind it.
      log.append(LocalEntity.minted(LOCAL, NodeKind.GROUP, "a band no source knows", WHEN));
      for (String target : reachedBy(ownerEdges)) {
        log.append(OwnerEdge.claimed(LOCAL, target, EdgeTypes.INFLUENCED_BY.code(), WHEN));
      }
    }
    try (SqliteAffinityStore affinity = new SqliteAffinityStore(db)) {
      affinity.put(new AffinityRecord(seed(0), 5, null, WHEN));
      affinity.put(new AffinityRecord(seed(1), 4, null, WHEN));
    }
    return db;
  }

  private static List<String> reachedBy(int ownerEdges) {
    List<String> targets = new ArrayList<>();
    targets.add(via(0));
    for (int k = 1; k < CANDIDATES; k++) {
      targets.add(candidate(k));
    }
    return targets.subList(0, ownerEdges);
  }

  private static void edge(
      SqliteAssertionLog log, Map<String, Integer> degree, String from, String to) {
    log.append(
        new AssertionRecord(from, to, EdgeTypes.INFLUENCED_BY.code(), null, null, sourced()));
    degree.merge(from, 1, Integer::sum);
    degree.merge(to, 1, Integer::sum);
  }

  /** Filler records, which no kind rule ever admits as a candidate, whatever their degree. */
  private static void padTo(
      SqliteAssertionLog log, Map<String, Integer> degree, int[] filler, String qid, int target) {
    while (degree.getOrDefault(qid, 0) < target) {
      String record = String.format(Locale.ROOT, "Q94%05d", filler[0]++);
      log.append(
          new NodeAssertion(record, NodeKind.WORK, "an invented record " + record, sourced()));
      log.append(
          new AssertionRecord(qid, record, EdgeTypes.PERFORMED.code(), null, null, sourced()));
      degree.merge(qid, 1, Integer::sum);
      degree.merge(record, 1, Integer::sum);
    }
  }

  private static String seed(int i) {
    return "Q93000" + (i + 1);
  }

  private static String via(int j) {
    return String.format(Locale.ROOT, "Q9301%02d", j);
  }

  private static String candidate(int k) {
    return String.format(Locale.ROOT, "Q9302%02d", k);
  }
}
