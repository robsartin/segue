package com.robsartin.segue.musicbrainz;

import com.robsartin.segue.app.WikidataMusicBrainzIdentity;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.ingest.GraphProjector;
import com.robsartin.segue.musicbrainz.MusicBrainzProbe.ProbeInputs;
import com.robsartin.segue.musicbrainz.MusicBrainzProbe.ProbeReport;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.port.IdentityMerge;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.support.DefaultDatabase;
import com.robsartin.segue.tinker.TinkerGraphStore;
import com.robsartin.segue.wikidata.WikidataClient;
import com.robsartin.segue.wikidata.WikidataEntityResolver;
import com.robsartin.segue.wikidata.WikidataSourceAdapter;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The instrument behind ADR 55's magnitudes, run against the real endpoints. It prints {@link
 * MusicBrainzProbe}'s five blocks and asserts their structure; the offline {@code
 * MusicBrainzProbeTest} runs the same engine over a fixture and asserts the values, which is where
 * exact numbers are legitimate because they are true by construction.
 *
 * <p><b>Run it deliberately: {@code ./gradlew mbProbe -Dsegue.probe.db=/tmp/segue-probe.db}</b>,
 * against a copy the owner made. Tagged {@code live}, so {@code ./gradlew check} never reaches it,
 * and tagged {@code probe} as well, so {@code ./gradlew liveTest} does not either. That second tag
 * is not decoration: {@code liveTest} forwards no {@code -Dsegue.probe.db}, so a probe it reached
 * would hit the refusal below and make a task that has to stay runnable on any machine
 * unconditionally red. A live smoke test needs the network and nothing else; a probe needs a copy
 * of the log, which is a different thing to ask of whoever types the command.
 *
 * <p><b>It fails rather than skips when no copy is named, and a skip was rejected on purpose.</b>
 * {@code Assumptions.abort} would report success for a run that never happened, which is the same
 * defect as a table of zeros and the one this repository has now filed three times. {@link
 * ProbeDatabase#require} is the first statement below, so a missing {@code -Dsegue.probe.db} is a
 * failure carrying the copy step, before a client or a database handle exists — and {@code
 * MusicBrainzProbeTest} watches that from inside {@code check}, by calling this very method with
 * the property cleared.
 *
 * <p><b>No figure from ADR 55 appears here as an expectation.</b> The graph grows, so every number
 * in that ADR is a dated measurement of 2026-08; asserting one would be red the first time anyone
 * seeds anything, and the cheapest route back to green is to edit the expectation. {@link
 * MusicBrainzProbe#assertInvariants} is the whole of what this run checks.
 *
 * <p><b>Seed order is log order</b> — the order the log states its node claims in, first claim per
 * entity. ADR 55 did not record which seeds its scratch probe drew, so nothing here reproduces its
 * <i>sample</i>: the figures this prints will differ from the ones tabulated there, and a reader
 * setting the two side by side is comparing the <i>shape</i> of the tables and not their values.
 *
 * <p><b>The kind is the projection's, not the log's.</b> ADR 55 sampled on "the {@code node_kind}
 * on the latest node claim in that log"; this reads {@code NodeRecord.kind()} off the projection,
 * which ADR 42 re-derives from the classes the claims carry. The two can differ, and block 1 counts
 * the projection's kind — so drawing on the log's would let invariant 5, which holds {@code PERSON
 * + GROUP} to the seeds requested, go red on a correct run.
 *
 * <p><b>Aggregates only</b> (ADR 33, ADR 51): what reaches stdout is the rendered table and a
 * duration, and invariant 8 asserts of the table itself that it names no entity.
 */
@Tag("live")
@Tag("probe")
class MusicBrainzProbeLiveTest {

  /** How many seeds to draw, half {@code PERSON} and half {@code GROUP}, as ADR 55's run. */
  private static final int DEFAULT_SEEDS = 200;

  /** {@code -Dsegue.probe.seeds}, for a shorter run while the instrument itself is being read. */
  static final String SEEDS_PROPERTY = "segue.probe.seeds";

  @Test
  @DisplayName("should print the five blocks when run against a copy of the owner's log")
  void shouldPrintTheFiveBlocksWhenRunAgainstACopyOfTheOwnersLog() {
    Path database = database();
    int seeds = seedCount(System.getProperty(SEEDS_PROPERTY));
    long startedAt = System.nanoTime();
    try (AssertionLog log = new SqliteAssertionLog(database);
        GraphStore graph = new TinkerGraphStore()) {
      GraphProjector.project(log, graph, IdentityMerge.NONE);
      // One Query Service client for the bridge and the reverse pass, and a second aimed at the
      // Action API for the resolver, which is how SegueConfiguration.sourceAdapters builds them.
      WikidataClient queryService = WikidataClient.queryService();
      ProbeReport report =
          MusicBrainzProbe.run(
              new ProbeInputs(
                  sample(log, graph, seeds),
                  new MusicBrainzClient(),
                  new WikidataMusicBrainzIdentity(queryService),
                  new WikidataSourceAdapter(
                      new WikidataEntityResolver(new WikidataClient()),
                      queryService,
                      Clock.systemUTC()),
                  graph));

      System.out.println(report.render());
      // Per seed: one MusicBrainz /artist/<mbid>, and on the Wikidata side one mbidFor, one
      // batched identitiesFor over the relation targets, and one WikidataSourceAdapter.expand — and
      // MusicBrainz asks for about one request a second. So the elapsed time is the figure that
      // says whether a longer run is affordable.
      System.out.println(
          "\nelapsed: " + Duration.ofNanos(System.nanoTime() - startedAt).toSeconds() + "s");

      MusicBrainzProbe.assertInvariants(report);
    }
  }

  /**
   * The copy named by {@code -Dsegue.probe.db}, or {@link ProbeDatabase}'s refusal. Every input
   * that class needs is read here and passed in, because it holds no default and reads no
   * environment of its own.
   */
  private static Path database() {
    String home = System.getProperty("user.home");
    String segueDb = System.getenv("SEGUE_DB");
    return ProbeDatabase.require(
        System.getProperty(ProbeDatabase.PROPERTY),
        segueDb,
        DefaultDatabase.resolve(null, segueDb, home),
        Path.of(home));
  }

  /**
   * How many seeds to draw. A value that is not a number throws rather than falling back to the
   * default: a probe that quietly ran 200 seeds when it was told to run 20 is an instrument
   * reporting on something other than what was asked of it.
   */
  private static int seedCount(String propertyValue) {
    if (propertyValue == null || propertyValue.isBlank()) {
      return DEFAULT_SEEDS;
    }
    int seeds = Integer.parseInt(propertyValue.trim());
    if (seeds < 2) {
      throw new IllegalArgumentException(
          "-D" + SEEDS_PROPERTY + " must be at least 2 — half PERSON and half GROUP");
    }
    return seeds;
  }

  /**
   * Half {@code PERSON} and half {@code GROUP}, in the order the log states its node claims, taken
   * from the projection so the record the probe counts is the one the graph holds. An entity whose
   * claims were retracted is absent from the projection and is therefore not drawn, which is the
   * right answer rather than a filter: the probe measures what this graph would gain.
   */
  private static List<NodeRecord> sample(AssertionLog log, GraphStore graph, int seeds) {
    int perKind = seeds / 2;
    List<NodeRecord> drawn = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    int persons = 0;
    int groups = 0;
    for (LoggedAssertion assertion : log.readAll()) {
      if (!(assertion instanceof NodeAssertion node) || !seen.add(node.qid())) {
        continue;
      }
      Optional<NodeRecord> record = graph.node(node.qid());
      if (record.isEmpty()) {
        continue;
      }
      NodeKind kind = record.get().kind();
      if (kind == NodeKind.PERSON && persons < perKind) {
        drawn.add(record.get());
        persons++;
      } else if (kind == NodeKind.GROUP && groups < perKind) {
        drawn.add(record.get());
        groups++;
      }
      if (persons == perKind && groups == perKind) {
        break;
      }
    }
    return List.copyOf(drawn);
  }
}
