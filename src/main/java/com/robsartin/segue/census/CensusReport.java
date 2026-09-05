package com.robsartin.segue.census;

import com.robsartin.segue.domain.NodeKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToIntFunction;

/**
 * A census in, one aligned block of text out. A pure function, and the only class here that decides
 * what a person sees.
 *
 * <p><b>It renders and does not order.</b> The order is the sections' own, and each section pins it
 * with {@code containsExactly} in its own test: kinds come out in {@code NodeKind} declaration
 * order because {@link NodeCensus} counts into an {@code EnumMap} ({@code NodeCensusTest}), and
 * edge types, source ids, corroboration counts and rating scores come out ascending because {@link
 * EdgeCensus} and {@link TasteCensus} count into {@code TreeMap}s ({@code EdgeCensusTest}, {@code
 * TasteCensusTest}). This method walks those maps in iteration order and adds nothing of its own,
 * so two runs over one unchanged log produce byte-identical text — ADR 43's contract, held where
 * the counting happens rather than announced a second time here.
 *
 * <p><b>The column is derived from the census, twice over.</b> Labels are padded to the widest
 * counted label, then a two-space gap, then the count right-aligned in the width of the widest
 * count — so a six-figure {@code log rows} moves the whole column rather than jutting out of it,
 * and no number is ever a copy of a constant somebody has to keep. {@code CensusReportTest} applies
 * this same rule by hand to the invented fixture, which is what makes its pinned block an
 * expectation rather than a transcript.
 *
 * <p><b>Every label is a literal in this file.</b> Nothing here interpolates a value read from the
 * data except an integer and one identifier. The exceptions are the edge type codes and source ids,
 * which are vocabulary rather than entities and are covered by {@code CensusIsSafeToPasteTest}'s
 * "no Q-shaped token anywhere" clause, and the class qids in the concept-classes rows, which ADR
 * 63's 2026-09-04 amendment rules the same way and for which that clause is narrowed to the {@code
 * class Q…} prefix this block owns.
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
    int labelWidth = widest(body, line -> line.label().length());
    int countWidth = widest(body, line -> String.valueOf(line.count()).length());
    List<String> rendered = new ArrayList<>();
    rendered.add(HEADER);
    for (Line line : body) {
      if (line.count() == null) {
        rendered.add("");
        rendered.add(line.label());
      } else {
        String value = String.valueOf(line.count());
        rendered.add(
            line.label()
                + " ".repeat(labelWidth - line.label().length())
                + GAP
                + " ".repeat(countWidth - value.length())
                + value);
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
    body.add(count("withdrawn", census.edges().withdrawn()));
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

    ConceptClassCensus conceptClasses = census.conceptClasses();
    body.add(section("concept classes"));
    body.add(count("stating no class", conceptClasses.statingNoClass()));
    body.add(count("distinct classes", conceptClasses.distinctClasses()));
    for (ConceptClassCensus.ConceptClass stated : conceptClasses.top()) {
      body.add(count("class " + stated.classQid(), stated.nodes()));
    }

    return body;
  }

  /** The widest of one measurement over the counted lines; section headings are not padded. */
  private static int widest(List<Line> body, ToIntFunction<Line> measure) {
    return body.stream().filter(line -> line.count() != null).mapToInt(measure).max().orElse(0);
  }

  private static Line section(String name) {
    return new Line(name, null);
  }

  private static Line count(String label, int value) {
    return new Line("  " + label, value);
  }
}
