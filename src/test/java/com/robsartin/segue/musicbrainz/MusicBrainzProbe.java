package com.robsartin.segue.musicbrainz;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.ExpansionBounds;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.Qid;
import com.robsartin.segue.port.ExpandContext;
import com.robsartin.segue.port.ExpandResult;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.port.SourceAdapter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The instrument behind ADR 55's magnitudes, committed so they can be re-derived rather than
 * re-attested. It prints five blocks — sample, census, the {@code isNew} breakdown, the saving per
 * expansion, and what filling {@code neighbors()} would cost — in the shape ADR 55 tabulates them,
 * so a reader can lay one beside the other.
 *
 * <p><b>It asserts structure and never a value.</b> The graph grows, so every figure in ADR 55 is a
 * dated measurement; a test asserting 959 would be red the first time anyone seeds anything, and
 * the cheapest route back to green is to edit the expectation, which is how a guard dies. {@link
 * #assertInvariants} is what both the offline run and the live run check, and it is held to being
 * non-vacuous by a planted violation per clause in {@code MusicBrainzProbeEngineTest}.
 *
 * <p><b>Every input is a parameter and nothing here has a default.</b> The seeds arrive already
 * read and already bounded, from a database path the caller supplies — the owner's real database is
 * refused rather than defaulted to, which is {@code ProbeDatabase}'s job and not this class's.
 *
 * <p><b>Seed order is the order the log states the claims in.</b> ADR 55 did not record which seeds
 * it drew, so a later run reproduces the <i>shape</i> of these tables and not the sample: the
 * figures will differ, and nothing here should be read as re-deriving ADR 55's own numbers.
 *
 * <p><b>Aggregates only</b> (ADR 33, ADR 51). Nothing this class emits names an entity — no QID, no
 * MBID, no label — and that is asserted of the rendered text rather than reviewed, which is
 * narrower than ADR 51's rule and does not weaken it.
 */
final class MusicBrainzProbe {

  /**
   * The shared {@code maxNewEdges} bound, which is the shipped one ADR 55's run was taken at. Named
   * rather than inlined: block 5's last row counts the seeds this cut, and a reader has to be able
   * to see which bound did the cutting.
   */
  static final int SHARED_BOUND = ExpandContext.defaults().maxNewEdges();

  // The whole vocabulary the table may print, held once so the renderer and the privacy check
  // cannot drift: a cell that is not a number, not a percentage and not one of these is a name,
  // and a name is what ADR 33 and ADR 51 forbid this instrument to emit.
  private static final String SAMPLE_HEADING = "### 1. Sample";
  private static final String CENSUS_HEADING = "### 2. Census";
  private static final String NEIGHBOURS_HEADING = "### 3. Neighbours";
  private static final String SAVING_HEADING = "### 4. The saving per expansion";
  private static final String COST_HEADING = "### 5. What filling `neighbors()` would cost";

  private static final String WHAT = "what";
  private static final String COUNT = "count";
  private static final String SHARE = "share";
  private static final String RELATION_TYPE = "relation type";
  private static final String WHAT_THE_NEIGHBOUR_WAS = "what the neighbour was";
  private static final String FETCH_SPENT = "fetch spent today?";
  private static final String MEDIAN = "median";
  private static final String P90 = "p90";
  private static final String MAX = "max";
  private static final String TOTAL = "TOTAL";

  private static final String SEEDS_REQUESTED = "seeds requested";
  private static final String SEEDS_PERSON = "seeds PERSON";
  private static final String SEEDS_GROUP = "seeds GROUP";
  private static final String BRIDGED = "bridged via P434";
  private static final String ARTIST_RELATIONS = "artist relations returned";
  private static final String SEEDS_WITH_A_NEIGHBOUR = "seeds with a resolved neighbour";
  private static final String RESOLVED_NEIGHBOURS = "resolved neighbours";

  private static final String ALREADY_IN_THE_GRAPH = "already in the graph";
  private static final String DESCRIBED_IN_THE_SAME_CALL = "new, but described in the same call";
  private static final String NEW_AND_UNDESCRIBED = "new and undescribed";
  private static final String IS_NEW_FALSE = "no — `isNew` is false";
  private static final String DESCRIBED_WINS = "no — `described` wins";
  private static final String THE_SAVING = "yes — this is the whole saving";

  private static final String CLASS_LESS_CREATIONS = "new neighbours created class-less";
  private static final String ERASURE_OCCURRENCES = "erasure occurrences";
  private static final String DISTINCT_ERASED = "distinct nodes erased";
  private static final String CARRYING_INSTANCE_OF =
      "of those carrying a non-empty `instanceOf` today";
  private static final String SEEDS_THE_BOUND_CUT = "seeds the shared bound cut";

  private static final Set<String> HEADINGS =
      Set.of(SAMPLE_HEADING, CENSUS_HEADING, NEIGHBOURS_HEADING, SAVING_HEADING, COST_HEADING);

  private static final Set<String> VOCABULARY =
      Set.of(
          "",
          "---",
          WHAT,
          COUNT,
          SHARE,
          RELATION_TYPE,
          WHAT_THE_NEIGHBOUR_WAS,
          FETCH_SPENT,
          MEDIAN,
          P90,
          MAX,
          TOTAL,
          SEEDS_REQUESTED,
          SEEDS_PERSON,
          SEEDS_GROUP,
          BRIDGED,
          ARTIST_RELATIONS,
          SEEDS_WITH_A_NEIGHBOUR,
          RESOLVED_NEIGHBOURS,
          ALREADY_IN_THE_GRAPH,
          DESCRIBED_IN_THE_SAME_CALL,
          NEW_AND_UNDESCRIBED,
          IS_NEW_FALSE,
          DESCRIBED_WINS,
          THE_SAVING,
          CLASS_LESS_CREATIONS,
          ERASURE_OCCURRENCES,
          DISTINCT_ERASED,
          CARRYING_INSTANCE_OF,
          SEEDS_THE_BOUND_CUT);

  private static final Pattern A_QID = Pattern.compile("Q\\d+");
  private static final Pattern AN_MBID =
      Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
  private static final Pattern AN_INTEGER = Pattern.compile("\\d+");
  private static final Pattern A_PERCENTAGE = Pattern.compile("\\d+%");

  /**
   * What a MusicBrainz relation type may look like. Wider than the sixteen lower-case types ADR
   * 55's census happened to hold: MusicBrainz states types carrying capitals, hyphens, commas and
   * parentheses ({@code DJ-mix}, {@code (has) collaborated on}), and rejecting one of those as if
   * it were a name would red the live run on a correct table.
   *
   * <p><b>This is a shape and a length bound, and it is not a name detector — say so rather than
   * let its name imply otherwise.</b> A census key of {@code "Radiohead"} matches it, and would
   * match any pattern loose enough to admit the real vocabulary: an artist's name and a relation
   * type are the same shape of string. <b>What actually keeps a name out of the census is
   * structural</b>: {@link #census(List)} counts {@code ArtistRelation::type} and nothing else, so
   * a key here is a type string MusicBrainz stated, and the report carries no label to print in the
   * first place. This check is the belt over that — it catches a key that is an identifier or is
   * far too long to be a type, and the QID and MBID patterns beside it are what reject an
   * identifier by shape.
   */
  private static final Pattern A_RELATION_TYPE =
      Pattern.compile("[A-Za-z(][A-Za-z0-9 ()',./&+-]{0,39}");

  private MusicBrainzProbe() {}

  /**
   * What one seed's expansion was seen to produce. Hand-buildable, so the arithmetic below can be
   * driven without a network, a database or a server.
   */
  record SeedObservation(
      NodeKind kind,
      boolean bridged,
      List<ArtistRelation> relations,
      List<String> resolvedNeighbours,
      Set<String> describedInTheSameCall,
      int collectedAssertions) {}

  /** Block 1. */
  record Sample(
      int seedsRequested,
      int seedsPerson,
      int seedsGroup,
      int bridged,
      int artistRelations,
      int seedsWithAResolvedNeighbour,
      int resolvedNeighbours) {}

  /**
   * Block 3, mirroring the three branches of {@code SegueService.expandEntity}. The shares are
   * carried rather than computed at render time so that the invariant over them has something to
   * fail on — an invariant only the renderer could break is one no report can be built to violate.
   */
  record Buckets(
      int alreadyInTheGraph,
      int describedInTheSameCall,
      int newAndUndescribed,
      int alreadyShare,
      int describedShare,
      int undescribedShare) {
    int total() {
      return alreadyInTheGraph + describedInTheSameCall + newAndUndescribed;
    }

    int sharesTotal() {
      return alreadyShare + describedShare + undescribedShare;
    }
  }

  /** Block 4. */
  record Percentiles(int median, int p90, int max, List<Integer> perSeed) {}

  /** Block 5. */
  record Cost(
      int classLessCreations,
      int erasureOccurrences,
      int distinctErased,
      int erasedCarryingInstanceOf,
      int seedsTheBoundCut) {}

  /**
   * Everything the engine reads. The seeds are already drawn and already bounded; the graph is a
   * projection of the same log they were drawn from.
   */
  record ProbeInputs(
      List<NodeRecord> seeds,
      MusicBrainzClient client,
      MusicBrainzIdentity identity,
      SourceAdapter wikidataSide,
      GraphStore graph) {}

  /**
   * The five blocks.
   *
   * <p><b>Block 4's p90 is nearest rank</b>, {@code sorted.get(ceil(0.90n) - 1)}. ADR 55 records "a
   * p90 of 4" and not which definition produced it, so the choice here is this probe's own and the
   * two cannot be set side by side without assuming the scratch probe agreed — a linear
   * interpolation over the same counts gives a different number. Task 4's amendment to ADR 55 says
   * so.
   */
  record ProbeReport(
      Sample sample, Map<String, Integer> census, Buckets buckets, Percentiles saving, Cost cost) {

    /** The five blocks, in the spec's order, laid out so a reader can set them beside ADR 55's. */
    String render() {
      StringBuilder out = new StringBuilder();
      out.append(SAMPLE_HEADING).append("\n\n").append(header(WHAT, COUNT));
      row(out, SEEDS_REQUESTED, sample.seedsRequested());
      row(out, SEEDS_PERSON, sample.seedsPerson());
      row(out, SEEDS_GROUP, sample.seedsGroup());
      row(out, BRIDGED, sample.bridged());
      row(out, ARTIST_RELATIONS, sample.artistRelations());
      row(out, SEEDS_WITH_A_NEIGHBOUR, sample.seedsWithAResolvedNeighbour());
      row(out, RESOLVED_NEIGHBOURS, sample.resolvedNeighbours());

      out.append("\n").append(CENSUS_HEADING).append("\n\n").append(header(RELATION_TYPE, COUNT));
      census.forEach((type, count) -> row(out, type, count));
      row(out, TOTAL, census.values().stream().mapToInt(Integer::intValue).sum());

      out.append("\n")
          .append(NEIGHBOURS_HEADING)
          .append("\n\n")
          .append(header(WHAT_THE_NEIGHBOUR_WAS, COUNT, SHARE, FETCH_SPENT));
      bucketRow(
          out,
          ALREADY_IN_THE_GRAPH,
          buckets.alreadyInTheGraph(),
          buckets.alreadyShare(),
          IS_NEW_FALSE);
      bucketRow(
          out,
          DESCRIBED_IN_THE_SAME_CALL,
          buckets.describedInTheSameCall(),
          buckets.describedShare(),
          DESCRIBED_WINS);
      bucketRow(
          out,
          NEW_AND_UNDESCRIBED,
          buckets.newAndUndescribed(),
          buckets.undescribedShare(),
          THE_SAVING);
      bucketRow(out, TOTAL, buckets.total(), buckets.sharesTotal(), "");

      out.append("\n").append(SAVING_HEADING).append("\n\n").append(header(MEDIAN, P90, MAX));
      out.append("| ")
          .append(saving.median())
          .append(" | ")
          .append(saving.p90())
          .append(" | ")
          .append(saving.max())
          .append(" |\n");

      out.append("\n").append(COST_HEADING).append("\n\n").append(header(WHAT, COUNT));
      row(out, CLASS_LESS_CREATIONS, cost.classLessCreations());
      row(out, ERASURE_OCCURRENCES, cost.erasureOccurrences());
      row(out, DISTINCT_ERASED, cost.distinctErased());
      row(out, CARRYING_INSTANCE_OF, cost.erasedCarryingInstanceOf());
      row(out, SEEDS_THE_BOUND_CUT, cost.seedsTheBoundCut());
      return out.toString();
    }

    private static String header(String... cells) {
      StringBuilder out = new StringBuilder();
      for (String cell : cells) {
        out.append("| ").append(cell).append(' ');
      }
      out.append("|\n");
      out.append("| --- ".repeat(cells.length)).append("|\n");
      return out.toString();
    }

    private static void row(StringBuilder out, String label, int count) {
      out.append("| ").append(label).append(" | ").append(count).append(" |\n");
    }

    private static void bucketRow(
        StringBuilder out, String label, int count, int share, String fetch) {
      out.append("| ")
          .append(label)
          .append(" | ")
          .append(count)
          .append(" | ")
          .append(share)
          .append("% | ")
          .append(fetch)
          .append(" |\n");
    }
  }

  /**
   * A frequency count of relation types across every seed's relations, descending by count and then
   * by type, so two runs over one sample print the rows in one order.
   */
  static Map<String, Integer> census(List<List<ArtistRelation>> relationsBySeed) {
    Map<String, Integer> counts = new HashMap<>();
    for (List<ArtistRelation> relations : relationsBySeed) {
      for (ArtistRelation relation : relations) {
        counts.merge(relation.type(), 1, Integer::sum);
      }
    }
    Map<String, Integer> ordered = new LinkedHashMap<>();
    counts.entrySet().stream()
        .sorted(
            Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()))
        .forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
    return ordered;
  }

  /**
   * The shape checker both runs call. Every assertion here is over the report's structure, never
   * over a value: the graph grows, so ADR 55's figures are dated measurements and an assertion on
   * one of them would be red the first time anyone seeds anything.
   */
  static void assertInvariants(ProbeReport report) {
    Sample sample = report.sample();
    Buckets buckets = report.buckets();
    Percentiles saving = report.saving();
    Cost cost = report.cost();

    assertThat(sample.seedsRequested())
        .as(
            "invariant 0 (the instrument ran): the probe was handed at least one seed. A table of"
                + " zeros and a dead instrument look identical, which is the failure this"
                + " repository has filed three times, so an empty sample fails here rather than"
                + " printing five well-formed blocks of nothing")
        .isPositive();
    assertThat(sample.bridged())
        .as(
            "invariant 0 (the instrument ran): at least one seed bridged to MusicBrainz. Every"
                + " block below is empty when none did, and a run that measured nothing must fail"
                + " rather than report success")
        .isPositive();

    assertThat(report.census().values().stream().mapToInt(Integer::intValue).sum())
        .as("invariant 1: block 2's counts sum to block 1's relation total")
        .isEqualTo(sample.artistRelations());

    assertThat(report.census().values().stream().filter(count -> count < 1).count())
        .as(
            "invariant 2: every census count is at least one. That no relation type appears twice"
                + " is deliberately not asserted — the census is a Map, so no report can be built"
                + " that violates it, and an assertion nothing can fail teaches nothing")
        .isZero();

    assertThat(buckets.total())
        .as("invariant 3: block 3's three buckets partition block 1's resolved-neighbour total")
        .isEqualTo(sample.resolvedNeighbours());

    if (buckets.total() == 0) {
      assertThat(buckets.sharesTotal())
          .as("invariant 4: with no resolved neighbour to share out, every share is zero")
          .isZero();
    } else {
      assertThat(buckets.sharesTotal())
          .as(
              "invariant 4: block 3's shares sum to 100%, within the one point three roundings of"
                  + " a whole percentage can cost")
          .isBetween(99, 101);
    }

    assertThat(sample.seedsPerson() + sample.seedsGroup())
        .as("invariant 5: block 1's PERSON and GROUP seeds sum to the seeds requested")
        .isEqualTo(sample.seedsRequested());
    assertThat(sample.bridged())
        .as("invariant 5: the bridged seeds are a subset of the seeds requested")
        .isLessThanOrEqualTo(sample.seedsRequested());
    assertThat(sample.seedsWithAResolvedNeighbour())
        .as("invariant 5: the seeds with a resolved neighbour are a subset of the bridged seeds")
        .isLessThanOrEqualTo(sample.bridged());

    assertThat(saving.median())
        .as("invariant 6: block 4's median never exceeds its p90")
        .isLessThanOrEqualTo(saving.p90());
    assertThat(saving.p90())
        .as("invariant 6: block 4's p90 never exceeds its max")
        .isLessThanOrEqualTo(saving.max());
    assertThat(saving.max())
        .as(
            "invariant 6: block 4's max is the largest per-seed count of block 3's third bucket."
                + " The two are the same quantity read two ways, so a disagreement is an"
                + " arithmetic bug rather than a measurement")
        .isEqualTo(saving.perSeed().stream().mapToInt(Integer::intValue).max().orElse(0));

    assertThat(cost.distinctErased())
        .as("invariant 7: block 5's distinct nodes erased never exceed the erasure occurrences")
        .isLessThanOrEqualTo(cost.erasureOccurrences());
    assertThat(cost.erasedCarryingInstanceOf())
        .as(
            "invariant 7: those carrying a non-empty instanceOf are a subset of the distinct erased")
        .isLessThanOrEqualTo(cost.distinctErased());
    assertThat(cost.seedsTheBoundCut())
        .as("invariant 7: the seeds the shared bound cut are a subset of those with a neighbour")
        .isLessThanOrEqualTo(sample.seedsWithAResolvedNeighbour());
    assertThat(cost.classLessCreations())
        .as("invariant 7: block 5's class-less creations are block 3's third bucket, read again")
        .isEqualTo(buckets.newAndUndescribed());

    assertNamesNoEntity(report);
  }

  /**
   * Invariant 8. ADR 51 says no test can enforce its rule in general; that is true of an ADR's
   * prose and not of one program's own output, which is a finite vocabulary plus numbers.
   */
  private static void assertNamesNoEntity(ProbeReport report) {
    String rendered = report.render();
    assertThat(A_QID.matcher(rendered).find())
        .as("invariant 8 (privacy): the rendered table matches no QID — ADR 33, ADR 51")
        .isFalse();
    assertThat(AN_MBID.matcher(rendered).find())
        .as("invariant 8 (privacy): the rendered table matches no MBID")
        .isFalse();
    assertThat(report.census().keySet().stream().filter(key -> !isARelationType(key)).count())
        .as(
            "invariant 8 (privacy): every census row label is shaped like a MusicBrainz relation"
                + " type, which is a length and character bound and not a name check — what keeps"
                + " a name out of the census is that its keys are relation-type strings by"
                + " construction. The offending labels are deliberately not printed here — a"
                + " failure message is not a licence to name an entity")
        .isZero();
    List<String> unaccountedFor = new ArrayList<>();
    for (String line : rendered.lines().toList()) {
      if (line.isBlank()) {
        continue;
      }
      if (!line.startsWith("|")) {
        assertThat(HEADINGS)
            .as("invariant 8 (privacy): a non-table line is one of the five block headings")
            .contains(line);
        continue;
      }
      for (String cell : line.split("\\|", -1)) {
        String trimmed = cell.trim();
        if (!isAccountedFor(trimmed, report)) {
          unaccountedFor.add(redacted(trimmed));
        }
      }
    }
    assertThat(unaccountedFor)
        .as(
            "invariant 8 (privacy): every cell is an integer, a percentage, a heading of this"
                + " table's own vocabulary, or a MusicBrainz relation type this run censused."
                + " Shapes, not values, are shown — every character that could carry a name is"
                + " replaced by an x")
        .isEmpty();
  }

  private static boolean isAccountedFor(String cell, ProbeReport report) {
    return VOCABULARY.contains(cell)
        || AN_INTEGER.matcher(cell).matches()
        || A_PERCENTAGE.matcher(cell).matches()
        || report.census().containsKey(cell);
  }

  private static boolean isARelationType(String key) {
    return A_RELATION_TYPE.matcher(key).matches()
        && !A_QID.matcher(key).find()
        && !AN_MBID.matcher(key).find();
  }

  private static String redacted(String cell) {
    return cell.replaceAll("[^ ]", "x");
  }

  /** Block 3's counts, with each share rounded to a whole percentage point of their total. */
  private static Buckets buckets(int already, int described, int undescribed) {
    int total = already + described + undescribed;
    return new Buckets(
        already,
        described,
        undescribed,
        share(already, total),
        share(described, total),
        share(undescribed, total));
  }

  private static int share(int part, int total) {
    return total == 0 ? 0 : (int) Math.round(100.0 * part / total);
  }

  /**
   * Nearest-rank percentiles over the per-seed counts of block 3's third bucket - the saving a
   * filled {@code neighbors()} would collect, read per expansion rather than in total.
   */
  private static Percentiles percentiles(List<Integer> perSeed) {
    if (perSeed.isEmpty()) {
      return new Percentiles(0, 0, 0, List.of());
    }
    List<Integer> sorted = perSeed.stream().sorted().toList();
    return new Percentiles(
        nearestRank(sorted, 0.50),
        nearestRank(sorted, 0.90),
        sorted.getLast(),
        List.copyOf(perSeed));
  }

  private static int nearestRank(List<Integer> sorted, double fraction) {
    int rank = (int) Math.ceil(fraction * sorted.size());
    return sorted.get(Math.max(1, rank) - 1);
  }

  /**
   * Walks the seeds against the real client, the real bridge and the real Wikidata-side adapter,
   * then hands what it saw to {@link #report}. The gathering is the only part that speaks to
   * anything outside this class, and the fixture run is what exercises it end to end.
   */
  static ProbeReport run(ProbeInputs inputs) {
    ExpandContext ctx = new ExpandContext(SHARED_BOUND);
    List<SeedObservation> observations = new ArrayList<>();
    for (NodeRecord seed : inputs.seeds()) {
      Optional<String> mbid = inputs.identity().mbidFor(seed.qid());
      if (mbid.isEmpty()) {
        observations.add(
            new SeedObservation(seed.kind(), false, List.of(), List.of(), Set.of(), 0));
        continue;
      }
      // One fetch per seed, and one batch per seed, for everything below. Driving
      // MusicBrainzSourceAdapter here instead would re-issue both — MusicBrainz asks for one
      // request a second, and the offline run counts requests to prove none escaped.
      //
      // Relations MusicBrainz stated without a type are dropped rather than censused under an
      // invented label: a census row needs a type, and block 1 counts what block 2 counts.
      List<ArtistRelation> relations =
          inputs.client().artistRelations(mbid.get()).stream()
              .filter(relation -> relation.type() != null)
              .toList();
      List<String> targets =
          relations.stream()
              .map(ArtistRelation::targetMbid)
              .filter(Objects::nonNull)
              .distinct()
              .toList();
      // identitiesFor is one batched question per seed, as qidsFor was before it was retired
      // (issue #163): the probe reads only the QID off each answer, because a census counts
      // neighbours and not what the bridge could say about them.
      Map<String, String> qids = targets.isEmpty() ? Map.of() : qidsOf(inputs, targets);
      // Once per neighbour per expansion, which is the unit SegueService resolves them in and the
      // unit ADR 55 counted them in.
      List<String> neighbours =
          targets.stream()
              .map(qids::get)
              .filter(Objects::nonNull)
              .filter(qid -> !qid.equals(seed.qid()))
              .distinct()
              .toList();
      ExpandResult wikidata =
          inputs.wikidataSide().supports(seed.kind())
              ? inputs.wikidataSide().expand(seed, ctx)
              : ExpandResult.of(List.of());
      Set<String> described = new LinkedHashSet<>();
      for (NodeAssertion neighbour : wikidata.neighbors()) {
        described.add(neighbour.qid());
      }
      observations.add(
          new SeedObservation(
              seed.kind(),
              true,
              relations,
              neighbours,
              described,
              wikidata.assertions().size() + musicbrainzAssertions(relations, qids)));
    }
    return report(observations, inputs.graph());
  }

  /**
   * What {@code MusicBrainzSourceAdapter} would have added to the concatenation the shared bound
   * cuts, counted off the response already in hand. It reads the adapter's own {@code isMappable}
   * and applies the bound where the adapter applies it, so this is not a second copy of the
   * whitelist — block 5's last row would go stale the day that rule changed.
   */
  /** The QID of every target the bridge could name, in the order the batch was asked. */
  private static Map<String, String> qidsOf(ProbeInputs inputs, List<String> targets) {
    Map<String, BridgedIdentity> identities = inputs.identity().identitiesFor(targets);
    Map<String, String> qids = new LinkedHashMap<>();
    identities.forEach((mbid, identity) -> qids.put(mbid, identity.qid()));
    return Map.copyOf(qids);
  }

  private static int musicbrainzAssertions(
      List<ArtistRelation> relations, Map<String, String> qids) {
    return (int)
        relations.stream()
            .filter(MusicBrainzSourceAdapter::isMappable)
            .limit(SHARED_BOUND)
            .map(relation -> qids.get(relation.targetMbid()))
            .filter(Objects::nonNull)
            .filter(Qid::looksLikeAQid)
            .count();
  }

  /** The arithmetic over already-gathered observations. */
  static ProbeReport report(List<SeedObservation> observations, GraphStore graph) {
    int seedsPerson = 0;
    int seedsGroup = 0;
    int bridged = 0;
    int artistRelations = 0;
    int seedsWithAResolvedNeighbour = 0;
    int resolvedNeighbours = 0;
    int already = 0;
    int described = 0;
    int undescribed = 0;
    int erasureOccurrences = 0;
    int seedsTheBoundCut = 0;
    Set<String> erased = new LinkedHashSet<>();
    List<Integer> perSeed = new ArrayList<>();
    for (SeedObservation seed : observations) {
      if (seed.kind() == NodeKind.PERSON) {
        seedsPerson++;
      } else if (seed.kind() == NodeKind.GROUP) {
        seedsGroup++;
      }
      if (!seed.bridged()) {
        continue;
      }
      bridged++;
      artistRelations += seed.relations().size();
      Set<String> seen = new LinkedHashSet<>(seed.resolvedNeighbours());
      resolvedNeighbours += seen.size();
      if (!seen.isEmpty()) {
        seedsWithAResolvedNeighbour++;
      }
      int undescribedHere = 0;
      for (String neighbour : seen) {
        boolean isNew = graph.node(neighbour).isEmpty();
        boolean describedHere = seed.describedInTheSameCall().contains(neighbour);
        if (!isNew) {
          already++;
          if (!describedHere) {
            erasureOccurrences++;
            erased.add(neighbour);
          }
        } else if (describedHere) {
          described++;
        } else {
          undescribed++;
          undescribedHere++;
        }
      }
      if (!seen.isEmpty()) {
        perSeed.add(undescribedHere);
        if (seed.collectedAssertions() > ExpansionBounds.effective(seed.kind(), SHARED_BOUND)) {
          seedsTheBoundCut++;
        }
      }
    }
    int erasedCarryingInstanceOf =
        (int)
            erased.stream()
                .filter(qid -> graph.node(qid).filter(n -> !n.instanceOf().isEmpty()).isPresent())
                .count();
    Sample sample =
        new Sample(
            observations.size(),
            seedsPerson,
            seedsGroup,
            bridged,
            artistRelations,
            seedsWithAResolvedNeighbour,
            resolvedNeighbours);
    return new ProbeReport(
        sample,
        census(observations.stream().map(SeedObservation::relations).toList()),
        buckets(already, described, undescribed),
        percentiles(perSeed),
        new Cost(
            undescribed,
            erasureOccurrences,
            erased.size(),
            erasedCarryingInstanceOf,
            seedsTheBoundCut));
  }
}
