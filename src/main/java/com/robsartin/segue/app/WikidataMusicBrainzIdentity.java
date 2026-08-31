package com.robsartin.segue.app;

import com.robsartin.segue.domain.Qid;
import com.robsartin.segue.musicbrainz.MusicBrainzIdentity;
import com.robsartin.segue.musicbrainz.MusicBrainzIdentityUnavailableException;
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
 * package is a candidate — {@code ArchitectureTest.adaptersDoNotDependOnEachOther} forbids both
 * directions, and every one of the twenty ordered pairs five adapters make was watched red against
 * a scratch field placed in each package in turn (issue #140; before it, two of the pairs this
 * sentence relies on were the only ones covered, by two pairwise rules that no longer exist). So
 * the seam is a real seam: {@code musicbrainz} names what it needs, {@code app} supplies it, and a
 * third source resolving identities some other way is the same shape of work rather than a change
 * to either.
 *
 * <p><b>It reports its failures, and no longer swallows them</b> (<a
 * href="https://github.com/robsartin/segue/issues/148">issue #148</a>). It used to. {@link
 * MusicBrainzIdentity} declared no failure type and {@code SegueService.expandEntity} calls {@code
 * adapter.expand} with no {@code try}, so a {@link WikidataUnavailableException} escaping either
 * method below would have left the SPI's "failures degrade rather than propagate" contract through
 * the back door — and swallowing was the only other option available.
 *
 * <p><b>What that cost was recorded here before it was fixed, which is why it could be.</b> An
 * unreachable Wikidata made {@link #mbidFor} empty, and an empty MBID is how {@code
 * MusicBrainzSourceAdapter} says "MusicBrainz holds nothing bridged to this seed" — so a Query
 * Service outage read downstream as "this artist has no members" rather than as "a source did not
 * answer", which is the exact confusion that adapter's own {@code sourceUnavailable} comment exists
 * to prevent. ADR 54 records it as an established consequence.
 *
 * <p><b>The seam now declares {@link MusicBrainzIdentityUnavailableException}, so this class throws
 * it and the adapter catches it.</b> The SPI contract is untouched: nothing above {@code
 * MusicBrainzSourceAdapter.expand} sees the throw, and what reaches the tool layer is still a
 * flagged {@code ExpandResult} — but the flag is now set, where before it was false. The exception
 * is translated rather than passed through because {@code musicbrainz} may not import {@code
 * wikidata} (ADR 32); see {@link #ask}.
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
   * <p><b>What that would have cost is a whole seed's neighbourhood.</b> A 414 is not transient, so
   * {@link WikidataClient} does not retry it: it throws at once. Before issue #148 {@link #ask}
   * swallowed that, the map came back empty, and every neighbour of the seed was dropped while
   * {@code sourceUnavailable} stayed false — the "this artist has no members" reading the class
   * note above exists to keep this bridge from producing. It is now reported rather than silent,
   * which makes the batching a correctness measure rather than the only thing standing between an
   * outsized request and unflagged data loss; the batching stays because a reported failure is
   * still a failure.
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
      // One chunk's failure fails the whole call, by throwing out of ask(). Returning what the
      // earlier chunks resolved would be worse than useless: a half-filled map is
      // indistinguishable from Wikidata knowing no QID for the rest, and that is the normal drop
      // path (ADR 22 clause 2) which reports nothing to anybody. Returning an EMPTY map, which is
      // what this did before issue #148, had the same problem for every neighbour at once.
      collect(ask(BATCH_TEMPLATE.formatted(values, MBID_PROPERTY)), resolved);
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

  /**
   * The response, or {@link MusicBrainzIdentityUnavailableException} when Wikidata did not answer.
   *
   * <p>The translation is the point (issue #148): {@code musicbrainz} may not import {@code
   * wikidata} (ADR 32), so {@link WikidataUnavailableException} cannot cross the seam and the
   * failure has to arrive as the type the seam declares. Nothing is swallowed here any more — see
   * the class note.
   */
  private JsonNode ask(String sparql) {
    try {
      return queryService.get(Map.of("query", sparql, "format", "json"));
    } catch (WikidataUnavailableException e) {
      log.warn("Wikidata did not answer the MBID bridge", e);
      throw new MusicBrainzIdentityUnavailableException(
          "the Wikidata-backed MBID bridge could not be asked", e);
    }
  }
}
