package com.robsartin.segue.census;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.Expanded;
import com.robsartin.segue.domain.Fold;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.export.LogProjection;
import com.robsartin.segue.support.KnownListInput;
import com.robsartin.segue.wikidata.KindMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the known-list walk costs, measured once on a synthetic graph shaped like the real one
 * (issue #311, task 7).
 *
 * <p><b>Asserts correctness at scale and nothing about time.</b> The machine this runs on is
 * loaded, so a duration assertion here would be a flake generator rather than a guard. What this
 * class produces is an observation — read off the test report and the timed window printed below —
 * recorded in the task report and, as an order of magnitude in prose, in the runbook.
 *
 * <p><b>The timed window is what the flag adds, all of it.</b> The scan {@code Expanded.in} makes
 * over every row in the log is built inside {@code known.map(...)}, so it is paid only by a run
 * that passed {@code --known} — it is the first of the two costs the runbook names, and it is
 * inside the window here for that reason. The fold and the projection are paid either way, so they
 * are outside it — and timed separately, because the runbook's claim is a comparison between the
 * two and a comparison with one side unmeasured is an assertion rather than an observation.
 *
 * <p><b>The shape, not a copy of the real graph.</b> Nodes in the low six figures, edges a small
 * multiple of that, and degree deliberately skewed: a handful of hub nodes carry hundreds of edges
 * each, a modest pool of "touched" nodes carries a low double-digit degree from edges among
 * themselves, and the great majority of the {@link #NODE_COUNT} nodes carry no edge at all — which
 * is the shape a real Wikidata-sourced graph has, not an even distribution. Four extra nodes are
 * wired by hand rather than drawn from the random pool, so the walk's bound has a fact only the
 * generator knows to check itself against: {@link #ISOLATED} carries no edge and must be reported
 * as having no known neighbour, and {@link #TWO_HOP_A} and {@link #TWO_HOP_B} sit exactly two hops
 * apart through {@link #BRIDGE} and must not be.
 *
 * <p><b>Every hub is on the file, by construction rather than by the shuffle's luck.</b> The hubs
 * are the only entities any row in this log cites as an expansion's seed, so a population drawn
 * from the touched leaves alone would make {@code never expanded} exactly {@code in the graph} — an
 * assertion that cannot fail, and one a {@code covers()} answering false unconditionally would
 * pass. The strict inequality below is what says so out loud.
 *
 * <p><b>Both populations and the merge fold run here too.</b> The file names both sides of the
 * merge, {@link #MERGED_LOCAL} and {@link #MERGED_CANONICAL}, so the canonicalisation the unit
 * tests pin by hand runs over the whole population and {@code named} counts the pair once; and the
 * ratings map carries three promotions the file does not name, one rating on the merge's local side
 * that resolves onto an id the file already names, and one below the threshold — so the second
 * population is exercised at scale rather than being list-identical to the first.
 */
class KnownListCensusScaleTest {

  /** Wikibase's grammar refuses any qid with a leading zero (ADR 58) — every id here has one. */
  private static final Pattern LEADING_ZERO_QID = Pattern.compile("Q0\\d+");

  private static final int NODE_COUNT = 150_000;

  /** A few nodes, and each carries hundreds of edges. */
  private static final int HUB_COUNT = 30;

  private static final int HUB_MIN_DEGREE = 300;
  private static final int HUB_MAX_DEGREE = 700;

  /** The pool a hub's non-hub endpoint, and the extra leaf-to-leaf edges, are drawn from. */
  private static final int TOUCHED_LEAF_COUNT = 20_000;

  /**
   * Edges among the touched pool alone, on top of the hubs' own — what makes the total a small
   * multiple of {@link #NODE_COUNT} without touching the untouched majority.
   */
  private static final int EXTRA_EDGES = 200_000;

  /**
   * Drawn from the touched pool, distinct, before every hub, the four hand-wired plants, the
   * merge's two sides and {@link #ABSENT} are added.
   */
  private static final int KNOWN_LIST_SAMPLE = 844;

  private static final long SEED = 311L;

  private static String id(int i) {
    return "Q09" + String.format("%07d", i);
  }

  /**
   * In the graph, and no edge anywhere in the log names it — the isolated member the walk must
   * report.
   */
  private static final String ISOLATED = id(NODE_COUNT);

  /**
   * Two hops from {@link #TWO_HOP_B} through {@link #BRIDGE}, and nowhere else — the walk must not
   * report either as having no known neighbour.
   */
  private static final String TWO_HOP_A = id(NODE_COUNT + 1);

  private static final String TWO_HOP_B = id(NODE_COUNT + 2);

  /**
   * Links {@link #TWO_HOP_A} and {@link #TWO_HOP_B} and nothing else — not part of the random pool,
   * so nothing else in the log can shorten the two-hop distance between them.
   */
  private static final String BRIDGE = id(NODE_COUNT + 3);

  /** Named by the file and never claimed as a node — the first coverage gap there is, at scale. */
  private static final List<String> ABSENT =
      List.of(id(NODE_COUNT + 4), id(NODE_COUNT + 5), id(NODE_COUNT + 6));

  /**
   * The local side of a merge the file also names by its canonical side (ADR 59's two leading
   * zeros) — so the fold has something to fold at scale, and {@code named} counts it once.
   */
  private static final String MERGED_LOCAL = "Q0031101";

  /** The canonical side of that merge — ADR 62's eleven-digit reserved shape. */
  private static final String MERGED_CANONICAL = "Q10000900311";

  /**
   * Rated at or above {@code KnownList.PROMOTION_RATING} and not named by the file — what the
   * second population adds. Drawn from the untouched majority, so each is in the graph, carries no
   * edge and is cited by no row as a seed.
   */
  private static final List<String> PROMOTED =
      List.of(id(NODE_COUNT - 1), id(NODE_COUNT - 2), id(NODE_COUNT - 3));

  /** Rated below the threshold and not named by the file — promoted by nothing. */
  private static final String RATED_LOW = id(NODE_COUNT - 4);

  /**
   * <b>Before the sweep, not merely beside it.</b> A sibling {@code @Test} gives no ordering
   * guarantee — JUnit may run the sweep first — and the point of this check is that a generator
   * minting an allocatable id never gets as far as building a 150,000-node log out of them. A
   * {@code @BeforeAll} runs before every test in the class, whatever the order, so the sweep cannot
   * precede it; the named {@code @Test} below stays, so a failure is reported against the check
   * rather than against whatever ran next.
   */
  @BeforeAll
  static void everyGeneratedIdCarriesTheLeadingZero() {
    for (int i = 0; i < NODE_COUNT + 7; i++) {
      assertThat(id(i)).as("id(%d)", i).matches(LEADING_ZERO_QID);
    }
    assertThat(MERGED_LOCAL)
        .as("the merge's local side is ADR 59's two-leading-zero shape")
        .matches("Q00\\d+");
    assertThat(MERGED_CANONICAL)
        .as("the merge's canonical side is ADR 62's reserved eleven-digit shape")
        .matches("Q[1-9]\\d{10}");
  }

  @Test
  @DisplayName("every generated id carries ADR 58's leading zero, for every i the generator uses")
  void shouldCarryTheLeadingZeroWhenEveryGeneratedIdIsChecked() {
    everyGeneratedIdCarriesTheLeadingZero();
  }

  @Test
  @DisplayName(
      "the known-list walk reconciles at scale, on a synthetic graph shaped like the real one")
  void shouldReconcileAtScaleWhenTheWalkRunsOverASyntheticGraph() {
    Random rnd = new Random(SEED);

    long genStart = System.nanoTime();
    List<LoggedAssertion> log =
        new ArrayList<>(NODE_COUNT + EXTRA_EDGES + HUB_COUNT * HUB_MAX_DEGREE);

    for (int i = 0; i < NODE_COUNT; i++) {
      log.add(InventedCensus.node(id(i), NodeKind.values()[i % NodeKind.values().length], "n" + i));
    }
    log.add(InventedCensus.node(ISOLATED, NodeKind.PERSON, "isolated"));
    log.add(InventedCensus.node(TWO_HOP_A, NodeKind.PERSON, "two hop a"));
    log.add(InventedCensus.node(TWO_HOP_B, NodeKind.PERSON, "two hop b"));
    log.add(InventedCensus.node(BRIDGE, NodeKind.PERSON, "bridge"));

    // One merge, so the fold has something to fold at scale and the canonicalisation the unit
    // tests pin by hand runs over the whole population here too.
    log.add(InventedCensus.node(MERGED_CANONICAL, NodeKind.PERSON, "merged canonical"));
    log.add(InventedCensus.minted(MERGED_LOCAL, "merged local"));
    log.add(InventedCensus.merged(MERGED_LOCAL, MERGED_CANONICAL));

    // Every row so far is a node claim, a minted local entity or the merge — what the printed
    // edge count is measured against, rather than a second arithmetic copy of the same additions.
    int claimRows = log.size();

    // Hubs: 0..HUB_COUNT-1. Each gets a skewed, hundreds-wide degree of edges to a node drawn from
    // the touched leaf pool (HUB_COUNT..HUB_COUNT+TOUCHED_LEAF_COUNT-1) — parallel edges allowed,
    // as a real corroborated graph has them, and alternating expansion shapes so Expanded picks up
    // every hub as a seed by both the forward and the reverse arm.
    for (int hub = 0; hub < HUB_COUNT; hub++) {
      String hubId = id(hub);
      int degree = HUB_MIN_DEGREE + rnd.nextInt(HUB_MAX_DEGREE - HUB_MIN_DEGREE + 1);
      for (int e = 0; e < degree; e++) {
        int leaf = HUB_COUNT + rnd.nextInt(TOUCHED_LEAF_COUNT);
        String leafId = id(leaf);
        if (e % 2 == 0) {
          log.add(
              InventedCensus.edge(hubId, leafId, "MEMBER_OF", InventedCensus.expandedFrom(hubId)));
        } else {
          log.add(
              InventedCensus.edge(
                  leafId, hubId, "MEMBER_OF", InventedCensus.discoveredFrom(leafId, hubId)));
        }
      }
    }

    // Extra edges among the touched pool alone — what pushes the total edge count to a small
    // multiple of NODE_COUNT without touching the untouched majority. Plain provenance: these are
    // not expansion-shaped, so they do not change who Expanded counts as a seed.
    for (int e = 0; e < EXTRA_EDGES; e++) {
      int from = HUB_COUNT + rnd.nextInt(TOUCHED_LEAF_COUNT);
      int to = HUB_COUNT + rnd.nextInt(TOUCHED_LEAF_COUNT);
      if (from == to) {
        continue;
      }
      log.add(InventedCensus.edge(id(from), id(to), "INFLUENCED_BY", InventedCensus.sourced()));
    }

    log.add(InventedCensus.edge(TWO_HOP_A, BRIDGE, "MEMBER_OF", InventedCensus.sourced()));
    log.add(InventedCensus.edge(BRIDGE, TWO_HOP_B, "MEMBER_OF", InventedCensus.sourced()));

    long genEnd = System.nanoTime();

    // The known-list file: a shuffled sample of the touched pool, in the high hundreds, plus every
    // hub, the three hand-wired plants and three ids the log never claims as a node at all.
    List<Integer> touchedIndices = new ArrayList<>(TOUCHED_LEAF_COUNT);
    for (int i = 0; i < TOUCHED_LEAF_COUNT; i++) {
      touchedIndices.add(HUB_COUNT + i);
    }
    Collections.shuffle(touchedIndices, rnd);

    List<String> file = new ArrayList<>(KNOWN_LIST_SAMPLE + HUB_COUNT + 8);
    for (int i = 0; i < KNOWN_LIST_SAMPLE; i++) {
      file.add(id(touchedIndices.get(i)));
    }
    // Every hub, by construction rather than by the shuffle's luck: the hubs are the only entities
    // any row in this log cites as an expansion's seed, so a population drawn from the touched
    // leaves alone makes `never expanded` the whole of `in the graph` and a covers() that answered
    // false unconditionally would pass every assertion below.
    for (int hub = 0; hub < HUB_COUNT; hub++) {
      file.add(id(hub));
    }
    file.add(ISOLATED);
    file.add(TWO_HOP_A);
    file.add(TWO_HOP_B);
    file.add(MERGED_LOCAL);
    file.add(MERGED_CANONICAL);
    file.addAll(ABSENT);

    // The ratings the second population is composed from: three promotions the file does not name,
    // one rating on the merge's local side (which resolve() moves onto a canonical id the file
    // already names, so it promotes nothing), and one below the threshold (promoted by nothing).
    Map<String, Integer> ratings = new LinkedHashMap<>();
    for (String promotion : PROMOTED) {
      ratings.put(promotion, 5);
    }
    ratings.put(MERGED_LOCAL, 5);
    ratings.put(RATED_LOW, 2);

    // The fold and the projection are paid with or without the flag, so they sit outside the
    // window. Expanded.in does not: Census.of builds it inside known.map(...), so its scan over
    // every row in the log is the first of the two costs the runbook attributes to --known.
    long foldStart = System.nanoTime();
    Fold fold = Fold.of(log, KindMapper::rederive);
    LogProjection projection = LogProjection.of(log, fold);
    long foldEnd = System.nanoTime();

    long flagStart = System.nanoTime();
    Expanded expanded = Expanded.in(log);
    KnownListCensus census =
        KnownListCensus.of(
            new KnownListInput("known.csv", file), expanded, projection, fold, ratings);
    long flagEnd = System.nanoTime();

    System.out.println(
        "KnownListCensusScaleTest: rows="
            + log.size()
            + " edges="
            + (log.size() - claimRows)
            + " known="
            + file.size()
            + " generation_ms="
            + (genEnd - genStart) / 1_000_000
            + " fold_ms="
            + (foldEnd - foldStart) / 1_000_000
            + " flag_ms="
            + (flagEnd - flagStart) / 1_000_000);

    KnownListCensus.Population population = census.fromFile();

    assertThat(population.named())
        .as("the merge's two sides are both on the file and count once, on the canonical side")
        .isEqualTo(file.size() - 1);
    assertThat(population.inTheGraph()).as("at most named").isLessThanOrEqualTo(population.named());
    assertThat(population.neverExpanded())
        .as("at most in the graph")
        .isLessThanOrEqualTo(population.inTheGraph());
    assertThat(population.neverExpanded())
        .as("some member has been expanded, or this row is vacuously the whole population")
        .isLessThan(population.inTheGraph());
    assertThat(population.noKnownNeighbourWithinMaxHops())
        .as("at most in the graph")
        .isLessThanOrEqualTo(population.inTheGraph());
    assertThat(population.inTheGraphByKind().values().stream().mapToInt(Integer::intValue).sum())
        .as("the per-kind in-graph counts sum to in the graph")
        .isEqualTo(population.inTheGraph());
    assertThat(population.neverExpandedByKind().values().stream().mapToInt(Integer::intValue).sum())
        .as("the per-kind never-expanded counts sum to never expanded")
        .isEqualTo(population.neverExpanded());

    // The three ids ABSENT never claims as a node are the exact gap between named and in the graph.
    assertThat(population.named() - population.inTheGraph()).isEqualTo(ABSENT.size());

    // The second population, at scale: KnownList.promoted appends every rating at or above the
    // threshold that the file does not already name, and nothing else. The promotions sit outside
    // the touched pool, so each is in the graph, carries no edge and was never a seed.
    KnownListCensus.Population promoted = census.withPromotions();
    assertThat(promoted.named())
        .as("the promotions the file does not name, and neither the low rating nor the merged one")
        .isEqualTo(population.named() + PROMOTED.size());
    assertThat(promoted.inTheGraph()).isEqualTo(population.inTheGraph() + PROMOTED.size());
    assertThat(promoted.neverExpanded()).isEqualTo(population.neverExpanded() + PROMOTED.size());
    assertThat(promoted.noKnownNeighbourWithinMaxHops())
        .isEqualTo(population.noKnownNeighbourWithinMaxHops() + PROMOTED.size());
    assertThat(promoted.inTheGraphByKind().values().stream().mapToInt(Integer::intValue).sum())
        .as("the per-kind in-graph counts sum to in the graph, for this population too")
        .isEqualTo(promoted.inTheGraph());

    // The planted facts: an isolated member is reported, and one exactly two hops from another is
    // not — the same control KnownListCensusTest exercises by hand, reproduced at scale.
    Map<String, Set<String>> adjacency = Neighbours.in(projection);
    assertThat(Neighbours.reaches(adjacency, ISOLATED, Set.copyOf(file), 2))
        .as("ISOLATED carries no edge at all")
        .isFalse();
    assertThat(Neighbours.reaches(adjacency, TWO_HOP_A, Set.copyOf(file), 2))
        .as("TWO_HOP_A reaches TWO_HOP_B through BRIDGE, exactly two hops away")
        .isTrue();
    assertThat(Neighbours.reaches(adjacency, TWO_HOP_B, Set.copyOf(file), 2))
        .as("and the reverse direction reaches it too")
        .isTrue();
  }
}
