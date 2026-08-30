package com.robsartin.segue.app;

import com.robsartin.segue.domain.Qid;
import com.robsartin.segue.musicbrainz.MusicBrainzIdentity;
import com.robsartin.segue.wikidata.WikidataClient;
import com.robsartin.segue.wikidata.WikidataUnavailableException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * The MBID-to-QID bridge {@code musicbrainz} declares and cannot implement, crossed through
 * Wikidata's {@code P434} — read back from the Action API on 2026-08-30 as "MusicBrainz artist ID",
 * datatype {@code external-id}, rather than recalled.
 *
 * <p><b>Why it lives in {@code app}.</b> It has to see both {@link MusicBrainzIdentity} and {@link
 * WikidataClient}, and ADR 32 says in one sentence which package may do that: "{@code app} is the
 * only package permitted to depend on everything, because wiring is its job." Neither adapter
 * package is a candidate — {@code ArchitectureTest.musicbrainzDoesNotDependOnOtherAdapters} and
 * {@code .wikidataDoesNotDependOnOtherAdapters} forbid both directions, and both were watched red
 * against a scratch class placed in each package in turn. So the seam is a real seam: {@code
 * musicbrainz} names what it needs, {@code app} supplies it, and a third source resolving
 * identities some other way is the same shape of work rather than a change to either.
 *
 * <p><b>It degrades, and never throws.</b> {@link MusicBrainzIdentity} declares no failure type,
 * and {@code SegueService.expandEntity} calls {@code adapter.expand} with no {@code try}, so a
 * {@link WikidataUnavailableException} escaping either method below would leave the SPI's "failures
 * degrade rather than propagate" contract through the back door — a tool call would return an error
 * where the tool layer expects a flagged result. Both methods therefore swallow it.
 *
 * <p><b>The cost of that is visible and is not hidden here.</b> An unreachable Wikidata makes
 * {@link #mbidFor} empty, and an empty MBID is how {@code MusicBrainzSourceAdapter} says
 * "MusicBrainz holds nothing bridged to this seed" — so a Query Service outage reads downstream as
 * "this artist has no members" rather than as "a source did not answer", which is the exact
 * confusion that adapter's own {@code sourceUnavailable} comment exists to prevent. Closing it
 * needs a failure channel on the seam, which is a change to an interface Task 3 settled; it is
 * written down instead.
 *
 * <p><b>Both queries are one round trip.</b> {@link #qidsFor} batches its whole neighbourhood into
 * a {@code VALUES} clause — the measured neighbourhood was 387 across 40 seeds (#91's 2026-08-29
 * comment), and a call per neighbour against a service that answers in tenths of a second is the
 * shape the seam was made batched to avoid.
 */
public final class WikidataMusicBrainzIdentity implements MusicBrainzIdentity {

  private static final Logger log = LoggerFactory.getLogger(WikidataMusicBrainzIdentity.class);

  /** Wikidata's "MusicBrainz artist ID". */
  private static final String MBID_PROPERTY = "P434";

  private static final String ENTITY_PREFIX = "http://www.wikidata.org/entity/";

  /**
   * MusicBrainz identifiers are UUIDs, and an MBID reaches this class from a MusicBrainz response —
   * contributor-entered data — before being concatenated into a SPARQL query. That is the injection
   * surface, closed the way {@code ReverseClaims.lookup} closes its own: refuse the value rather
   * than escape it.
   */
  private static final Pattern MBID =
      Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");

  /**
   * {@code ORDER BY} because the mapping is not one-to-one: a query for items stating more than one
   * P434 returns hits (measured 2026-08-30), so two rows can carry the same MBID and an unordered
   * answer would make which QID wins depend on the server's row order.
   */
  private static final String BATCH_TEMPLATE =
      """
      SELECT ?item ?mbid WHERE {
        VALUES ?mbid { %s }
        ?item wdt:%s ?mbid .
      }
      ORDER BY ?mbid ?item
      """;

  private static final String SEED_TEMPLATE =
      """
      SELECT ?mbid WHERE {
        wd:%s wdt:%s ?mbid .
      }
      ORDER BY ?mbid
      LIMIT 1
      """;

  private final WikidataClient queryService;

  public WikidataMusicBrainzIdentity(WikidataClient queryService) {
    this.queryService = Objects.requireNonNull(queryService, "queryService");
  }

  @Override
  public Optional<String> mbidFor(String qid) {
    if (!Qid.looksLikeAQid(qid)) {
      // The seed's QID reaches expand() from the graph, but add_entity puts model-supplied strings
      // in the graph, so this is the same concatenation surface as the MBIDs above.
      return Optional.empty();
    }
    JsonNode response = ask(SEED_TEMPLATE.formatted(qid, MBID_PROPERTY));
    if (response == null) {
      return Optional.empty();
    }
    for (JsonNode row : response.path("results").path("bindings")) {
      String mbid = row.at("/mbid/value").asText(null);
      if (mbid != null && MBID.matcher(mbid).matches()) {
        return Optional.of(mbid);
      }
    }
    return Optional.empty();
  }

  @Override
  public Map<String, String> qidsFor(Collection<String> mbids) {
    Objects.requireNonNull(mbids, "mbids");
    Set<String> asked =
        mbids.stream()
            .filter(m -> m != null && MBID.matcher(m).matches())
            .collect(Collectors.toCollection(LinkedHashSet::new));
    if (asked.isEmpty()) {
      // Nothing whitelisted, or nothing that could be an MBID. A VALUES clause with no members is
      // a round trip whose only possible answer is the empty map.
      return Map.of();
    }

    String values = asked.stream().map(m -> "\"" + m + "\"").collect(Collectors.joining(" "));
    JsonNode response = ask(BATCH_TEMPLATE.formatted(values, MBID_PROPERTY));
    if (response == null) {
      return Map.of();
    }

    Map<String, String> resolved = new LinkedHashMap<>();
    for (JsonNode row : response.path("results").path("bindings")) {
      String mbid = row.at("/mbid/value").asText(null);
      String item = row.at("/item/value").asText(null);
      if (mbid == null || item == null || !item.startsWith(ENTITY_PREFIX)) {
        continue;
      }
      String qid = item.substring(ENTITY_PREFIX.length());
      if (!Qid.looksLikeAQid(qid)) {
        // A lexeme or a property answers this shape too, and MusicBrainzSourceAdapter would drop
        // one anyway; dropping it here keeps the map's contract — every value is a QID.
        continue;
      }
      // An MBID absent from the result carries no QID and is simply not a key: the interface says
      // that dropping is ADR 22 clause 2 working as designed, and 49% of artist-relation
      // neighbours drop this way. Nothing is put here to record the absence.
      resolved.putIfAbsent(mbid, qid);
    }
    return Map.copyOf(resolved);
  }

  /** The response, or null when Wikidata did not answer. See the class note on degrading. */
  private JsonNode ask(String sparql) {
    try {
      return queryService.get(Map.of("query", sparql, "format", "json"));
    } catch (WikidataUnavailableException e) {
      log.warn("Wikidata did not answer the MBID bridge; resolving nothing this call", e);
      return null;
    }
  }
}
