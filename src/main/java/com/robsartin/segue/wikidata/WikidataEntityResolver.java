package com.robsartin.segue.wikidata;

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
import tools.jackson.databind.JsonNode;

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
      // wbsearchentities does not return P31, so the real kind is not knowable here without
      // one extra round trip per hit — for a list the caller may well discard. Candidates are
      // therefore reported as CONCEPT and the `kind` argument is deliberately NOT applied:
      // a filter that cannot see the kind would return an empty list, which reads as "no such
      // entity" rather than "cannot filter". The description is what disambiguates a search
      // hit; kind is settled by fetch(). See ADR 26 — the MCP search_entities tool inherits
      // this, and should say so in its tool description rather than implying a working filter.
      out.add(new Candidate(qid, label, description, NodeKind.CONCEPT));
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
    Objects.requireNonNull(qid, "qid");
    if (!qid.matches("Q\\d+")) {
      // This id becomes a JSON Pointer segment below. Once add_entity(qid) takes
      // model-supplied strings (increment 4), an unvalidated qid is a pointer-injection
      // surface, not just a 404 waiting to happen.
      throw new IllegalArgumentException("not a QID: " + qid);
    }
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
