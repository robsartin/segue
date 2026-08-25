package com.robsartin.segue.wikidata;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.EdgeType;
import com.robsartin.segue.domain.EdgeTypes;
import com.robsartin.segue.domain.Provenance;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Turns one entity's Wikidata claims into segue assertions.
 *
 * <p>The whitelist is not a separate list: it IS {@link EdgeTypes}, keyed by {@link
 * EdgeType#wikidataProperty()}. ADR 22 says the vocabulary is borrowed rather than invented, and
 * deriving the filter from the vocabulary is what keeps that true — adding a relation type is one
 * registration, not a registration plus a filter entry that can fall out of step.
 *
 * <p><b>Direction.</b> Wikidata states most creative relations on the work ({@code film P57
 * person}); segue stores them from the person ({@code person DIRECTED film}). {@link
 * EdgeType#wikidataInverted()} records which, and this flips them mechanically.
 *
 * <p><b>This class sees half the graph, by construction.</b> Fetching an entity returns only claims
 * stated ON it, so expanding a film finds its director but expanding a person does not find their
 * films — Wikidata never stated that triple on the person. That is not a defect to fix here: it is
 * what {@link ReverseClaims} is for, and {@link WikidataSourceAdapter} runs both. Keeping the two
 * apart matters, because they have genuinely different evidence available — a claim read from the
 * entity carries its references and qualifiers, and a truthy triple carries neither (ADR 36).
 */
public final class ClaimMapper {

  private static final String SOURCE_ID = "wikidata";
  private static final String INSTANCE_OF = "P31";
  private static final String START_TIME = "P580";
  private static final String END_TIME = "P582";

  // "imported from Wikimedia project" / "Wikimedia import URL" — a bot citing its own import
  // pipeline, not an authority. A large share of real Wikidata references are only these two,
  // so treating their presence as authoritative would flatten ADR 23's distinction.
  private static final Set<String> SELF_REFERENTIAL_IMPORT_PROPERTIES = Set.of("P143", "P4656");

  // LinkedHashMap, not HashMap: ReverseClaims renders these property codes into a SPARQL VALUES
  // clause, and an unordered map would make the query text vary run to run for no reason.
  private static final Map<String, EdgeType> BY_PROPERTY = new LinkedHashMap<>();

  static {
    for (EdgeType type : EdgeTypes.all()) {
      if (type.wikidataProperty() != null) {
        EdgeType prior = BY_PROPERTY.put(type.wikidataProperty(), type);
        if (prior != null) {
          // put() silently keeps the last write. Two EdgeTypes claiming the same Wikidata
          // property is a vocabulary bug, not a valid configuration — one of them would
          // vanish from ingest with no error.
          throw new IllegalStateException(
              "two edge types claim "
                  + type.wikidataProperty()
                  + ": "
                  + prior.code()
                  + " and "
                  + type.code());
        }
      }
    }
  }

  private ClaimMapper() {}

  /**
   * The Wikidata properties this vocabulary maps, in registration order.
   *
   * <p>Shared with {@link ReverseClaims} on purpose. The forward whitelist IS {@link EdgeTypes},
   * and the reverse one has to be the same set or the two directions would drift apart the first
   * time someone registers a relation type — which is precisely the failure issue #20 describes,
   * one level up.
   */
  static List<String> mappedProperties() {
    return List.copyOf(BY_PROPERTY.keySet());
  }

  /** The edge type a Wikidata property maps to, or null when it is not in the vocabulary. */
  static EdgeType typeFor(String property) {
    return BY_PROPERTY.get(property);
  }

  /** Every whitelisted claim on {@code entity}, as assertions. */
  public static List<AssertionRecord> map(String subjectQid, JsonNode entity, Instant assertedAt) {
    List<AssertionRecord> out = new ArrayList<>();
    JsonNode claims = entity.path("claims");
    claims
        .properties()
        .forEach(
            property -> {
              EdgeType type = BY_PROPERTY.get(property.getKey());
              if (type == null) {
                return;
              }
              for (JsonNode statement : property.getValue()) {
                toAssertion(subjectQid, type, statement, assertedAt).ifPresent(out::add);
              }
            });
    return List.copyOf(out);
  }

  private static Optional<AssertionRecord> toAssertion(
      String subjectQid, EdgeType type, JsonNode statement, Instant assertedAt) {

    JsonNode snak = statement.path("mainsnak");
    if (!"value".equals(snak.path("snaktype").asText())) {
      // "somevalue"/"novalue": Wikidata knows there is one but not what it is. Nothing to store.
      return Optional.empty();
    }
    if ("deprecated".equals(statement.path("rank").asText("normal"))) {
      // Wikidata marks these wrong-but-recorded. Ingesting one as a fact — at 1.00, since
      // deprecated statements usually carry the reference that got them recorded — would
      // put a known-false claim at the top of PathRanking. See ADR 23.
      return Optional.empty();
    }
    String objectQid = snak.at("/datavalue/value/id").asText(null);
    if (objectQid == null || objectQid.isBlank()) {
      return Optional.empty();
    }
    if (!objectQid.matches("Q\\d+")) {
      // wikibase-property/lexeme/form/sense datatypes also carry a value.id ("P123",
      // "L123-F1"). AssertionRecord does not validate, so an unvalidated id would reach
      // TinkerGraphStore.requireVertex and throw mid-batch, after the log entry is already
      // written.
      return Optional.empty();
    }

    String from = type.wikidataInverted() ? objectQid : subjectQid;
    String to = type.wikidataInverted() ? subjectQid : objectQid;

    // ADR 23: a referenced statement is authoritative, an unreferenced one is merely structured.
    boolean referenced = hasRealReference(statement.path("references"));
    double confidence = referenced ? 1.00 : 0.80;

    LocalDate validFrom = qualifierDate(statement, START_TIME);
    LocalDate validTo = qualifierDate(statement, END_TIME);
    if (validFrom != null && validTo != null && validTo.isBefore(validFrom)) {
      // Wikidata occasionally holds an inverted window. Keep the claim, drop the nonsense,
      // rather than letting AssertionRecord's constructor reject the whole entity.
      validFrom = null;
      validTo = null;
    }

    String statementRef = statement.path("id").asText(type.wikidataProperty() + ":" + objectQid);

    return Optional.of(
        new AssertionRecord(
            from,
            to,
            type.code(),
            validFrom,
            validTo,
            new Provenance(SOURCE_ID, statementRef, assertedAt, confidence)));
  }

  /**
   * True when at least one reference carries a snak on a property other than the self-referential
   * import ones. A references block that is empty, or whose only snaks are P143/P4656, does not
   * count — see the class-level note on {@link #SELF_REFERENTIAL_IMPORT_PROPERTIES}.
   */
  private static boolean hasRealReference(JsonNode references) {
    if (!references.isArray()) {
      return false;
    }
    for (JsonNode reference : references) {
      for (String snakProperty : reference.path("snaks").propertyNames()) {
        if (!SELF_REFERENTIAL_IMPORT_PROPERTIES.contains(snakProperty)) {
          return true;
        }
      }
    }
    return false;
  }

  // Wikidata's time precision codes: 11 = day, 10 = month, 9 = year, and coarser below that.
  // Anything less precise than a day cannot honestly become a LocalDate.
  private static final int DAY_PRECISION = 11;

  private static LocalDate qualifierDate(JsonNode statement, String property) {
    JsonNode value = statement.at("/qualifiers/" + property + "/0/datavalue/value/time");
    if (value.isMissingNode() || value.asText().isBlank()) {
      return null;
    }
    int precision =
        statement.at("/qualifiers/" + property + "/0/datavalue/value/precision").asInt(-1);
    if (precision < DAY_PRECISION) {
      // A year- or month-precision date read as a LocalDate would feed false day-level
      // precision into validAt() time-travel queries.
      return null;
    }
    // Wikidata times look like "+1983-01-01T00:00:00Z" — a leading sign, and zeroes where the
    // precision does not reach. A zero month or day cannot be a LocalDate, so treat it as absent.
    String raw = value.asText();
    String iso = raw.startsWith("+") || raw.startsWith("-") ? raw.substring(1) : raw;
    String datePart = iso.length() >= 10 ? iso.substring(0, 10) : iso;
    if (datePart.contains("-00")) {
      return null;
    }
    try {
      return LocalDate.parse(datePart);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  /** The entity's {@code P31} values, for {@link KindMapper}. */
  public static List<String> instanceOf(JsonNode entity) {
    List<String> out = new ArrayList<>();
    for (JsonNode statement : entity.path("claims").path(INSTANCE_OF)) {
      String qid = statement.at("/mainsnak/datavalue/value/id").asText(null);
      if (qid != null && !qid.isBlank()) {
        out.add(qid);
      }
    }
    return List.copyOf(out);
  }

  /** The English label, or null. */
  public static String label(JsonNode entity) {
    return entity.at("/labels/en/value").asText(null);
  }

  /** The English description — what makes disambiguation possible — or null. */
  public static String description(JsonNode entity) {
    return entity.at("/descriptions/en/value").asText(null);
  }
}
