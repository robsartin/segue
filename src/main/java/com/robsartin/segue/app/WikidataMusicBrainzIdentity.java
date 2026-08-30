package com.robsartin.segue.app;

import com.robsartin.segue.domain.Qid;
import com.robsartin.segue.musicbrainz.MusicBrainzIdentity;
import com.robsartin.segue.wikidata.WikidataClient;
import com.robsartin.segue.wikidata.WikidataUnavailableException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 * <p><b>The seed query is one round trip; the neighbourhood is as few as its size allows.</b>
 * {@link #qidsFor} puts a whole neighbourhood into {@code VALUES} clauses — the measured
 * neighbourhood was 387 across 40 seeds (#91's 2026-08-29 comment), and a call per neighbour
 * against a service that answers in tenths of a second is the shape the seam was made batched to
 * avoid. What it may not do is put all of them in <i>one</i> clause: see {@link
 * #MAX_MBIDS_PER_QUERY}.
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

  /**
   * The most MBIDs one {@code VALUES} clause may carry, because a {@code GET} spends its query on
   * the request line.
   *
   * <p><b>Measured, not estimated.</b> Driven through {@link WikidataClient}'s own encoding on
   * 2026-08-30 with {@link #BATCH_TEMPLATE} and MBIDs of the shape MusicBrainz sends, the request
   * URI comes to {@code 180 + 43n} bytes: 50 MBIDs is 2,330, 100 is 4,480, 200 is 8,780. The
   * classic ceiling on a request line is 8,192 bytes, which 186 MBIDs is the last batch to fit
   * under — and {@code application.yaml} ships {@code segue.expand.max-new-edges: 200}, a bound
   * {@code MusicBrainzSourceAdapter} spends on relations <i>before</i> it resolves any neighbour.
   * So the shipped configuration could hand this method 200 MBIDs and exceed the limit in one go.
   *
   * <p><b>What that would have cost is silence.</b> A 414 is not transient, so {@link
   * WikidataClient} does not retry it: it throws at once, {@link #ask} swallows it, the map comes
   * back empty, and every neighbour of that seed is dropped while {@code sourceUnavailable} stays
   * false — the "this artist has no members" reading the class note above exists to keep this
   * bridge from producing.
   *
   * <p><b>100 rather than 186.</b> 4,480 bytes is a batch whose safety does not depend on having
   * got the arithmetic exactly right, or on the template never gaining a line; the cost of the
   * headroom is one extra round trip per 100 neighbours against a service that answers in tenths of
   * a second. Each chunk is its own {@link WikidataClient#get}, so the retry policy, the honoured
   * {@code Retry-After} and its ceiling apply per request exactly as they did when there was one.
   */
  private static final int MAX_MBIDS_PER_QUERY = 100;

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
    List<String> asked =
        mbids.stream().filter(m -> m != null && MBID.matcher(m).matches()).distinct().toList();
    if (asked.isEmpty()) {
      // Nothing whitelisted, or nothing that could be an MBID. A VALUES clause with no members is
      // a round trip whose only possible answer is the empty map.
      return Map.of();
    }

    Map<String, String> resolved = new LinkedHashMap<>();
    for (int from = 0; from < asked.size(); from += MAX_MBIDS_PER_QUERY) {
      List<String> chunk = asked.subList(from, Math.min(from + MAX_MBIDS_PER_QUERY, asked.size()));
      String values = chunk.stream().map(m -> "\"" + m + "\"").collect(Collectors.joining(" "));
      JsonNode response = ask(BATCH_TEMPLATE.formatted(values, MBID_PROPERTY));
      if (response == null) {
        // One chunk's failure fails the whole call, which is what a single request already did.
        // Returning what the earlier chunks resolved would be worse than useless: a half-filled
        // map is indistinguishable from Wikidata knowing no QID for the rest, and that is the
        // normal drop path (ADR 22 clause 2) which reports nothing to anybody. Half an answer
        // would make a Query Service outage into silent, unflagged data loss for whichever
        // neighbours happened to land in the failing chunk.
        return Map.of();
      }
      collect(response, resolved);
    }
    return Map.copyOf(resolved);
  }

  /** Reads one response's bindings into {@code resolved}, dropping what cannot be a mapping. */
  private static void collect(JsonNode response, Map<String, String> resolved) {
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
