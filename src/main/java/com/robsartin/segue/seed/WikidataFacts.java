package com.robsartin.segue.seed;

import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.wikidata.ClaimMapper;
import com.robsartin.segue.wikidata.KindMapper;
import com.robsartin.segue.wikidata.WikidataClient;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

/**
 * The one extra round trip that makes a decision possible, taken in batches.
 *
 * <p>{@code wbsearchentities} cannot report {@code P31}, so a candidate list says nothing about
 * what its entries ARE. The naive fix is a fetch per candidate, which for nine hundred names is
 * several thousand calls; {@code wbgetentities} takes fifty identifiers at a time, so this is a few
 * dozen.
 *
 * <p>Goes through {@link WikidataClient} rather than opening its own connection, so it inherits the
 * User-Agent Wikidata's policy asks for — which is a repository URL and never an email address (ADR
 * 16) — and the capped, {@code Retry-After}-honouring backoff that keeps a throttled run polite.
 */
public final class WikidataFacts {

  /** The Action API's documented ceiling for an anonymous caller. */
  static final int MAX_IDS_PER_CALL = 50;

  private static final String OCCUPATION = "P106";

  private final WikidataClient client;

  public WikidataFacts(WikidataClient client) {
    this.client = Objects.requireNonNull(client, "client");
  }

  /** Facts for every identifier Wikidata knows, keyed by QID. Unknown ones are simply absent. */
  public Map<String, CandidateFacts> factsFor(Collection<String> qids) {
    Objects.requireNonNull(qids, "qids");
    List<String> unique = new ArrayList<>(new LinkedHashSet<>(qids));
    Map<String, CandidateFacts> out = new LinkedHashMap<>();
    for (int from = 0; from < unique.size(); from += MAX_IDS_PER_CALL) {
      List<String> batch = unique.subList(from, Math.min(from + MAX_IDS_PER_CALL, unique.size()));
      readBatch(batch, out);
    }
    return Map.copyOf(out);
  }

  private void readBatch(List<String> batch, Map<String, CandidateFacts> out) {
    for (String qid : batch) {
      if (!qid.matches("Q\\d+")) {
        // The same rule WikidataEntityResolver.entity applies, and for the same reason: these
        // ids become JSON Pointer segments below.
        throw new IllegalArgumentException("not a QID: " + qid);
      }
    }
    JsonNode response =
        client.get(
            Map.of(
                "action", "wbgetentities",
                "ids", String.join("|", batch),
                // See ClaimMapper.label: languages=en alone loses the name of exactly the
                // entities most worth resolving.
                "languages", "en|mul",
                "props", "labels|descriptions|aliases|sitelinks|claims",
                "format", "json"));
    for (String qid : batch) {
      JsonNode entity = response.at("/entities/" + qid);
      if (entity.isMissingNode() || entity.has("missing")) {
        continue;
      }
      String label = ClaimMapper.label(entity);
      if (label == null || label.isBlank()) {
        // Without an English label there is no name to match against, so there is nothing this
        // tool can conclude about the entity.
        continue;
      }
      NodeKind kind = KindMapper.fromInstanceOf(ClaimMapper.instanceOf(entity));
      out.put(
          qid,
          new CandidateFacts(
              qid,
              label,
              ClaimMapper.description(entity),
              ClaimMapper.aliases(entity),
              kind,
              ClaimMapper.itemValues(entity, OCCUPATION),
              entity.path("sitelinks").size()));
    }
  }
}
