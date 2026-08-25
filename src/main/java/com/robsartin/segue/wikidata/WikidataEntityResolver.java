package com.robsartin.segue.wikidata;

import com.fasterxml.jackson.databind.JsonNode;
import com.robsartin.segue.domain.Candidate;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.port.EntityResolver;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolution against the Wikidata Action API: {@code wbsearchentities} to find, {@code
 * wbgetentities} to fetch.
 *
 * <p>Search is read-only by construction — it returns {@link Candidate}s and writes nothing. That
 * matters for the MCP surface (ADR 26), where the model is expected to search, show the user the
 * options, and only then add one.
 */
public final class WikidataEntityResolver implements EntityResolver {

  private static final String SOURCE_ID = "wikidata";

  private final WikidataClient client;
  private final Clock clock;

  public WikidataEntityResolver(WikidataClient client) {
    this(client, Clock.systemUTC());
  }

  public WikidataEntityResolver(WikidataClient client, Clock clock) {
    this.client = Objects.requireNonNull(client, "client");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public String id() {
    return SOURCE_ID;
  }

  @Override
  public List<Candidate> search(String query, NodeKind kind, int limit) {
    Objects.requireNonNull(query, "query");
    JsonNode response =
        client.get(
            Map.of(
                "action", "wbsearchentities",
                "search", query,
                "language", "en",
                "uselang", "en",
                "limit", Integer.toString(Math.clamp(limit, 1, 50)),
                "format", "json"));

    List<Candidate> out = new ArrayList<>();
    for (JsonNode hit : response.path("search")) {
      String qid = hit.path("id").asText(null);
      String label = hit.path("label").asText(null);
      if (qid == null || label == null) {
        continue;
      }
      String description = hit.path("description").asText(null);
      // wbsearchentities does not return P31, so the kind is not knowable here without a
      // second round trip per hit. Report CONCEPT and let fetch() settle it — better than
      // paying N requests to decorate a list the caller may discard.
      Candidate candidate = new Candidate(qid, label, description, NodeKind.CONCEPT);
      if (kind == null || kind == candidate.kind()) {
        out.add(candidate);
      }
    }
    return List.copyOf(out);
  }

  @Override
  public Optional<NodeAssertion> fetch(String qid) {
    Objects.requireNonNull(qid, "qid");
    JsonNode entity = entity(qid);
    if (entity == null) {
      return Optional.empty();
    }
    String label = ClaimMapper.label(entity);
    if (label == null || label.isBlank()) {
      return Optional.empty();
    }
    NodeKind kind = KindMapper.fromInstanceOf(ClaimMapper.instanceOf(entity));
    return Optional.of(
        new NodeAssertion(qid, kind, label, new Provenance(SOURCE_ID, qid, clock.instant(), 1.00)));
  }

  /** The raw entity node, or null when Wikidata does not have it. Shared with the adapter. */
  JsonNode entity(String qid) {
    JsonNode response =
        client.get(
            Map.of(
                "action", "wbgetentities",
                "ids", qid,
                "languages", "en",
                "format", "json"));
    JsonNode entity = response.at("/entities/" + qid);
    if (entity.isMissingNode() || entity.has("missing")) {
      return null;
    }
    return entity;
  }
}
