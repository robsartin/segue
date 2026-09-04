package com.robsartin.segue.census;

import com.robsartin.segue.domain.NodeKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A census in, one aligned block of text out. A pure function, and the only class here that decides
 * what a person sees.
 *
 * <p><b>It orders as well as renders</b>, on {@code RatingsTable}'s reason: a writer that announced
 * an ordering somebody else applied could be made to lie by one refactor. Kinds come out in
 * declaration order, scores 1 to 5 always, types and source ids sorted, corroboration ascending —
 * so two runs over one unchanged log produce byte-identical text, which is ADR 43's contract.
 *
 * <p><b>Every label is a literal in this file.</b> Nothing here interpolates a value read from the
 * data except an integer, which is the property that makes the whole output safe to paste and the
 * property {@code CensusIsSafeToPasteTest} asserts. The two exceptions are the edge type codes and
 * source ids, which are vocabulary rather than entities and are covered by that test's "no Q-shaped
 * token anywhere" clause.
 */
public final class CensusReport {

  /** Said on the first line, every time — what this is, and what it is not. */
  public static final String HEADER =
      "# segue graph census — aggregates only: no labels, no ids, no notes (ADR 51, ADR 63).";

  private static final String GAP = "  ";

  private record Line(String label, Integer count) {}

  private CensusReport() {}

  public static List<String> lines(Census census) {
    Objects.requireNonNull(census, "census");
    List<Line> body = body(census);
    int width =
        body.stream()
            .filter(line -> line.count() != null)
            .mapToInt(line -> line.label().length())
            .max()
            .orElse(0);
    List<String> rendered = new ArrayList<>();
    rendered.add(HEADER);
    for (Line line : body) {
      if (line.count() == null) {
        rendered.add("");
        rendered.add(line.label());
      } else {
        rendered.add(line.label() + " ".repeat(width - line.label().length()) + GAP + line.count());
      }
    }
    return List.copyOf(rendered);
  }

  private static List<Line> body(Census census) {
    List<Line> body = new ArrayList<>();

    body.add(section("nodes"));
    body.add(count("total", census.nodes().total()));
    for (Map.Entry<NodeKind, Integer> kind : census.nodes().byKind().entrySet()) {
      body.add(count(kind.getKey().name(), kind.getValue()));
    }

    body.add(section("edges"));
    body.add(count("total", census.edges().total()));
    body.add(count("dangling", census.edges().dangling()));
    census.edges().bySource().forEach((source, n) -> body.add(count("backed by " + source, n)));
    census.edges().byType().forEach((type, n) -> body.add(count("of type " + type, n)));
    census
        .edges()
        .byCorroboration()
        .forEach((sources, n) -> body.add(count("corroborated by " + sources, n)));

    ClaimCensus claims = census.claims();
    body.add(section("claims"));
    body.add(count("log rows", claims.rows()));
    body.add(count("retractions", claims.retractions()));
    body.add(count("rows they removed", claims.rowsRetracted()));
    body.add(count("entities they name", claims.entitiesRetracted()));
    body.add(count("local entities minted", claims.localEntitiesMinted()));
    body.add(count("merges standing", claims.mergesStanding()));
    body.add(count("merges superseded", claims.mergesSuperseded()));
    body.add(
        count("merges superseded but edge-referenced", claims.mergesSupersededButEdgeReferenced()));
    body.add(count("stand-ins", claims.standIns()));
    body.add(count("stand-ins with no edge", claims.standInsWithNoEdge()));

    TasteCensus taste = census.taste();
    body.add(section("taste"));
    body.add(count("ratings", taste.total()));
    taste.byScore().forEach((score, n) -> body.add(count("rated " + score, n)));
    body.add(count("on a local id", taste.onALocalId()));
    body.add(count("on a stand-in", taste.onAStandIn()));
    body.add(count("on a retracted id", taste.onARetractedId()));

    DegreeCensus degree = census.degree();
    body.add(section("degree"));
    body.add(count("floor", degree.floor()));
    body.add(count("p50", degree.p50()));
    body.add(count("p90", degree.p90()));
    body.add(count("p99", degree.p99()));
    body.add(count("max", degree.max()));
    body.add(count("at or below the floor", degree.atOrBelowTheFloor()));

    body.add(section("bridge"));
    body.add(count("entities MusicBrainz reached", census.bridge().entitiesReached()));
    body.add(count("of those, carrying classes", census.bridge().entitiesReachedWithClasses()));

    return body;
  }

  private static Line section(String name) {
    return new Line(name, null);
  }

  private static Line count(String label, int value) {
    return new Line("  " + label, value);
  }
}
