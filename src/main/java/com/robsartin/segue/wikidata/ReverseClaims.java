package com.robsartin.segue.wikidata;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.EdgeType;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.Provenance;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

/**
 * The other direction: which items point AT this one.
 *
 * <p>{@link ClaimMapper} can only see claims stated ON the entity being fetched, and Wikidata
 * states every creative relation on the work ({@code film P57 person}) and band membership on the
 * member ({@code person P463 band}). So expanding Nick Cave found four edges and expanding his band
 * found none — issue #20. One SPARQL query against the Query Service answers the reverse question
 * for the whole vocabulary at once. See ADR 36 for why SPARQL rather than {@code haswbstatement}.
 *
 * <p><b>The property set is not a second list.</b> It is {@link ClaimMapper#reverseProperties()},
 * for exactly the reason ClaimMapper derives its forward whitelist from {@link
 * com.robsartin.segue.domain.EdgeTypes}: a hand-kept subset here would silently stop covering a
 * relation type the day someone registers one, and that divergence is the bug this class exists to
 * fix. The one deliberate subtraction is the fallback-only properties (issue #33) — P527 states
 * from the band's side what P463 states from the member's, and asking the backwards question about
 * both ends of an inverse pair returns one relationship twice.
 *
 * <p><b>Direction is the same rule, with the subject swapped.</b> A reverse hit means Wikidata
 * holds {@code other P seed}, so the mapping is ClaimMapper's with {@code subject = other} and
 * {@code object = seed}. An inverted type (P57) therefore yields {@code seed DIRECTED other}, and a
 * direct one (P361) yields {@code other PART_OF seed}. Nothing about how an edge is stored depends
 * on which direction discovered it.
 *
 * <p><b>Truthy triples are lossy, and that is priced in.</b> {@code wdt:} exposes only the
 * best-ranked, non-deprecated value of a property — which usefully reproduces ClaimMapper's
 * deprecated-statement filter for free — but it discards the statement's reference block and its
 * qualifiers along with it. Two consequences, both deliberate:
 *
 * <ul>
 *   <li>Confidence is always 0.80, never ADR 23's referenced 1.00. We cannot see whether this
 *       statement is referenced, and claiming the higher grade on evidence we do not have would put
 *       unverified edges at the top of {@code PathRanking}.
 *   <li>{@code validFrom}/{@code validTo} are always null. "Blixa Bargeld was a Bad Seed from 1983
 *       to 2003" is ADR 20's own example and it arrives here as an undated membership. Expanding
 *       Bargeld himself recovers the window from the forward direction, and the log merges the two
 *       claims about one edge rather than choosing between them.
 * </ul>
 */
final class ReverseClaims {

  private static final String SOURCE_ID = "wikidata";
  private static final String ENTITY_PREFIX = "http://www.wikidata.org/entity/";
  private static final String DIRECT_PROPERTY_PREFIX = "http://www.wikidata.org/prop/direct/";
  private static final Pattern QID = Pattern.compile("Q\\d+");

  /**
   * One query, three jobs: find the backlinks, rank them by notability, and carry enough of each
   * neighbour's identity that nothing has to be fetched again.
   *
   * <p>The inner {@code SELECT} is where the caller's {@code maxNewEdges} bound is spent. Pushing
   * it server-side as {@code ORDER BY DESC(?sitelinks) LIMIT n+1} buys two things a client-side cut
   * cannot: the n we keep are the most-linked rather than an arbitrary slice — for the Bad Seeds
   * that is Nick Cave, Blixa Bargeld, Mick Harvey and Warren Ellis ahead of a 2024 album track —
   * and the one extra row makes truncation an observation rather than a guess.
   *
   * <p>{@code ?otherLabel} and {@code ?type} ride along so {@code SegueService.expandEntity} does
   * not need a {@code wbgetentities} round trip per neighbour: 73 discovered works would otherwise
   * mean 73 further calls before a single edge could be recorded. A description is NOT selected —
   * {@link NodeAssertion} has nowhere to put one, and fetching a field that is thrown away is just
   * someone else's bandwidth.
   *
   * <p>The {@code OPTIONAL} P31 and the label service both multiply rows, so a row is not an
   * assertion; the parser keys on (property, entity) instead.
   *
   * <p>{@code ?sitelinks} is required rather than {@code OPTIONAL}, which is also the filter that
   * keeps non-items out: every Wikibase item has the triple (zero when it has no sitelinks at all),
   * and a lexeme or a property does not.
   */
  private static final String QUERY_TEMPLATE =
      """
      SELECT DISTINCT ?p ?other ?otherLabel ?type ?sitelinks WHERE {
        {
          SELECT DISTINCT ?p ?other ?sitelinks WHERE {
            VALUES ?p { %s }
            ?other ?p wd:%s .
            ?other wikibase:sitelinks ?sitelinks .
          }
          ORDER BY DESC(?sitelinks) ?other
          LIMIT %d
        }
        OPTIONAL { ?other wdt:P31 ?type }
        SERVICE wikibase:label { bd:serviceParam wikibase:language "en". }
      }
      ORDER BY DESC(?sitelinks) ?other
      """;

  private final WikidataClient client;

  ReverseClaims(WikidataClient client) {
    this.client = Objects.requireNonNull(client, "client");
  }

  /**
   * Everything that points at {@code seedQid} through a mapped property.
   *
   * @param seedQid the entity being expanded
   * @param maxNewEdges the caller's bound, spent server-side rather than after the fact
   * @param assertedAt when we learned this — ADR 20's assertion time, not validity time
   * @throws WikidataUnavailableException if the Query Service could not be reached or refused
   * @throws IllegalArgumentException if {@code seedQid} is not a QID
   */
  Result lookup(String seedQid, int maxNewEdges, Instant assertedAt) {
    Objects.requireNonNull(seedQid, "seedQid");
    Objects.requireNonNull(assertedAt, "assertedAt");
    if (!QID.matcher(seedQid).matches()) {
      // The seed reaches here from add_entity, which takes a model-supplied string, and it is
      // concatenated straight into a SPARQL query below. This is the injection surface, closed
      // the same way WikidataEntityResolver.entity closes its own JSON-Pointer one.
      throw new IllegalArgumentException("not a QID: " + seedQid);
    }
    if (maxNewEdges <= 0) {
      throw new IllegalArgumentException("maxNewEdges must be positive, got: " + maxNewEdges);
    }

    JsonNode response = client.get(Map.of("query", query(seedQid, maxNewEdges), "format", "json"));

    // Insertion-ordered, because the query already ranked the rows by sitelinks and the bound
    // below keeps a prefix of that ranking. A HashMap here would silently discard the ordering
    // the query paid for.
    Map<String, AssertionRecord> byStatement = new LinkedHashMap<>();
    Map<String, String> labels = new LinkedHashMap<>();
    Map<String, Set<String>> classes = new LinkedHashMap<>();

    for (JsonNode row : response.path("results").path("bindings")) {
      String property = localName(row.at("/p/value").asText(null), DIRECT_PROPERTY_PREFIX);
      String other = localName(row.at("/other/value").asText(null), ENTITY_PREFIX);
      if (property == null || other == null || !QID.matcher(other).matches()) {
        continue;
      }
      if (other.equals(seedQid)) {
        // A self-loop adds no route and would confuse SegueService's neighbourOf, which
        // reports "no neighbour" when both ends are the seed.
        continue;
      }
      EdgeType type = ClaimMapper.typeFor(property);
      if (type == null || type.wikidataFallbackOnly()) {
        // The query never asks about a fallback-only property (issue #33), so this is defence
        // against the answer not being the one the query asked for: recording a P527 hit here
        // would restore the duplicate edge the whole change exists to remove.
        continue;
      }
      byStatement.computeIfAbsent(
          property + " " + other, key -> assertion(seedQid, other, property, type, assertedAt));
      rememberLabel(labels, other, row.at("/otherLabel/value").asText(null));
      String classQid = localName(row.at("/type/value").asText(null), ENTITY_PREFIX);
      if (classQid != null) {
        classes.computeIfAbsent(other, q -> new LinkedHashSet<>()).add(classQid);
      }
    }

    boolean truncated = byStatement.size() > maxNewEdges;
    List<AssertionRecord> assertions = byStatement.values().stream().limit(maxNewEdges).toList();

    return new Result(
        assertions, neighbours(seedQid, assertions, labels, classes, assertedAt), truncated);
  }

  private String query(String seedQid, int maxNewEdges) {
    String values =
        ClaimMapper.reverseProperties().stream()
            .map(property -> "wdt:" + property)
            .collect(Collectors.joining(" "));
    // One more than the bound, so "there were exactly n" and "there were thousands" are
    // distinguishable. A long, because maxNewEdges is caller-supplied and Integer.MAX_VALUE + 1
    // is a negative LIMIT rather than a query error.
    return QUERY_TEMPLATE.formatted(values, seedQid, (long) maxNewEdges + 1L);
  }

  private static AssertionRecord assertion(
      String seedQid, String other, String property, EdgeType type, Instant assertedAt) {
    // ClaimMapper's rule with the subject swapped: the underlying statement is `other P seed`.
    String from = type.wikidataInverted() ? seedQid : other;
    String to = type.wikidataInverted() ? other : seedQid;
    // The reference names that triple subject-first. ClaimMapper's fallback can leave the
    // subject implicit because it is always the entity being fetched; here it is the thing that
    // was discovered, so spelling it out is what stops two hits on one property sharing a
    // reference. The wdqs: prefix records that this came from the Query Service rather than
    // from a statement id, which a truthy triple does not carry.
    Provenance provenance =
        new Provenance(
            SOURCE_ID, "wdqs:" + other + ":" + property + ":" + seedQid, assertedAt, 0.80);
    return new AssertionRecord(from, to, type.code(), null, null, provenance);
  }

  private static void rememberLabel(Map<String, String> labels, String qid, String label) {
    // The rule this method used to spell out lives in WikibaseLabels now, because issue #163 gave
    // the same service a second consumer and the two copies were byte-identical.
    String believable = WikibaseLabels.believable(qid, label);
    if (believable != null) {
      labels.putIfAbsent(qid, believable);
    }
  }

  private static List<NodeAssertion> neighbours(
      String seedQid,
      List<AssertionRecord> assertions,
      Map<String, String> labels,
      Map<String, Set<String>> classes,
      Instant assertedAt) {

    // Only the entities the kept assertions actually mention: the bound may have cut some rows
    // away, and a node with no edge to it is not something an expansion should be creating.
    Set<String> referenced = new LinkedHashSet<>();
    for (AssertionRecord assertion : assertions) {
      referenced.add(assertion.fromQid());
      referenced.add(assertion.toQid());
    }
    referenced.remove(seedQid);

    List<NodeAssertion> out = new ArrayList<>();
    for (String qid : referenced) {
      String label = labels.get(qid);
      if (label == null) {
        continue;
      }
      // The P31 values this query already returned inline, kept on the claim beside the kind
      // they produced (issue #60, ADR 42) - the reverse lookup is the cheapest place in the
      // system to learn them, and throwing them away is what made a better KindMapper need a
      // re-seed to take effect.
      List<String> instanceOf = List.copyOf(classes.getOrDefault(qid, Set.of()));
      NodeKind kind = KindMapper.fromInstanceOf(instanceOf);
      // Confidence 1.00 and the qid as the reference, matching WikidataEntityResolver.fetch:
      // this is the same claim from the same source, and it would be odd for an entity's
      // identity to be graded differently depending on which call happened to learn it.
      out.add(
          new NodeAssertion(
              qid, kind, label, instanceOf, new Provenance(SOURCE_ID, qid, assertedAt, 1.00)));
    }
    return List.copyOf(out);
  }

  private static String localName(String iri, String prefix) {
    if (iri == null || !iri.startsWith(prefix)) {
      return null;
    }
    return iri.substring(prefix.length());
  }

  /**
   * What one reverse lookup found.
   *
   * @param assertions the discovered relationships, already cut to the caller's bound
   * @param neighbors identity for the entities on the far end, so the caller need not fetch them
   * @param truncated there was more than the bound allowed, and this is the notable prefix
   */
  record Result(
      List<AssertionRecord> assertions, List<NodeAssertion> neighbors, boolean truncated) {

    Result {
      assertions = List.copyOf(Objects.requireNonNull(assertions, "assertions"));
      neighbors = List.copyOf(Objects.requireNonNull(neighbors, "neighbors"));
    }
  }
}
