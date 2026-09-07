package com.robsartin.segue.expansion;

import com.robsartin.segue.musicbrainz.MusicBrainzClient;
import com.robsartin.segue.musicbrainz.MusicBrainzSourceAdapter;
import com.robsartin.segue.port.SourceAdapters;
import com.robsartin.segue.wikidata.WikidataClient;
import com.robsartin.segue.wikidata.WikidataEntityResolver;
import com.robsartin.segue.wikidata.WikidataSourceAdapter;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * Both sources, in the order they are asked — the one statement of that order, for both entry
 * points (#284).
 *
 * <p><b>The order is load-bearing and is not alphabetical.</b> {@link EntityExpansion} builds one
 * {@code ExpandContext} and bounds the concatenation of what the adapters return rather than
 * bounding each one, so a tight {@code maxNewEdges} is spent by whichever adapter comes first —
 * {@code CorroborationAcrossSourcesTest} pins that from both ends. Wikidata stays first because it
 * was first; changing which source wins a small budget is a decision with its own evidence to
 * gather, and it is not this one. A second entry point that built its own list would be a second
 * statement of that order, which is why this is a factory rather than a bean method.
 *
 * <p><b>{@link MusicBrainzSourceAdapter} is handed its identity bridge from here, and that is the
 * whole point of the seam.</b> The bridge crosses MBID to QID through Wikidata's P434, and it lives
 * beside this class because {@code musicbrainz} may not import {@code wikidata} and {@code
 * wikidata} may not import {@code musicbrainz} — both directions are ArchUnit rules. So the one
 * class that knows about both sits in the package the callers share; see {@link
 * WikidataMusicBrainzIdentity}'s javadoc, and
 * docs/adr/0066-expand-every-promotion-from-a-dev-tool.md.
 *
 * <p><b>One {@link MusicBrainzClient} per run, and that is what makes a batch throttled.</b> The
 * client reserves its own request slots, so the rate limit applies across every entity a caller
 * expands only while they all go through one instance. A caller that built a client per entity
 * would keep every fence in this file and silently unthrottle itself against a public API. The same
 * argument applies one line up to the Query Service client, which the reverse-lookup pass and the
 * bridge share: {@link WikidataClient} holds no per-caller state, and a second instance would open
 * a second connection pool to one host for nothing.
 */
public final class ExpansionSources {

  private ExpansionSources() {}

  /**
   * The two shipped adapters, wired plain — no framework knowledge anywhere below (ADR 25).
   *
   * @param resolver the Action API resolver the Wikidata adapter reads claims stated on an entity
   *     through; the Query Service client for the ones stated about it is built here (ADR 36)
   * @param clock the clock every adapter stamps its provenance from
   * @return the adapters, in the order they are asked
   */
  public static SourceAdapters both(WikidataEntityResolver resolver, Clock clock) {
    Objects.requireNonNull(resolver, "resolver");
    Objects.requireNonNull(clock, "clock");
    WikidataClient queryService = WikidataClient.queryService();
    return new SourceAdapters(
        List.of(
            new WikidataSourceAdapter(resolver, queryService, clock),
            new MusicBrainzSourceAdapter(
                new MusicBrainzClient(), new WikidataMusicBrainzIdentity(queryService), clock)));
  }
}
