package com.robsartin.segue.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the citation-rendering format {@code Hop.describe()} and {@code PathResult.render()}
 * actually produce. Both were only ever called by the deleted {@code BakeOff}, so nothing currently
 * specifies this format — and it is exactly what increment 4's {@code find_paths} MCP tool will
 * emit.
 */
class HopRenderingTest {

  private static final Instant WHEN = Instant.parse("2026-08-01T09:00:00Z");

  private static final NodeRecord CAVE = new NodeRecord("Q900001", NodeKind.PERSON, "Nick Cave");
  private static final NodeRecord MCCARTHY =
      new NodeRecord("Q900013", NodeKind.PERSON, "Cormac McCarthy");
  private static final NodeRecord HILLCOAT =
      new NodeRecord("Q900010", NodeKind.PERSON, "John Hillcoat");
  private static final NodeRecord PROPOSITION =
      new NodeRecord("Q900009", NodeKind.WORK, "The Proposition");

  @Test
  @DisplayName("a forward hop renders from -[TYPE]-> to, with a bracketed citation")
  void forwardHopWithOneCitation() {
    EdgeRecord edge =
        new EdgeRecord(
            CAVE.qid(),
            MCCARTHY.qid(),
            "INFLUENCED_BY",
            null,
            null,
            List.of(new Provenance("llm:claude", "chat-2026-08-22#a2", WHEN, 0.30)));
    Hop hop = new Hop(CAVE, edge, MCCARTHY, false);

    assertThat(hop.describe())
        .isEqualTo("Nick Cave -[INFLUENCED_BY]-> Cormac McCarthy [llm:claude chat-2026-08-22#a2]");
  }

  @Test
  @DisplayName("a backward hop renders from <-[TYPE]- to")
  void backwardHop() {
    EdgeRecord edge =
        new EdgeRecord(
            HILLCOAT.qid(),
            PROPOSITION.qid(),
            "DIRECTED",
            null,
            null,
            List.of(new Provenance("wikidata", "S-hillcoat-prop", WHEN, 1.00)));
    // The walk went from The Proposition to Hillcoat, against the edge's stored direction.
    Hop hop = new Hop(PROPOSITION, edge, HILLCOAT, true);

    assertThat(hop.describe())
        .isEqualTo("The Proposition <-[DIRECTED]- John Hillcoat [wikidata S-hillcoat-prop]");
  }

  @Test
  @DisplayName("multiple sources join as comma-separated citations, in source order")
  void multipleCitationsAreCommaSeparated() {
    EdgeRecord edge =
        new EdgeRecord(
            CAVE.qid(),
            PROPOSITION.qid(),
            "COMPOSED_FOR",
            null,
            null,
            List.of(
                new Provenance("wikidata", "S-cave-prop-score", WHEN, 1.00),
                new Provenance("musicbrainz", "mb-release-score-1", WHEN, 0.80)));
    Hop hop = new Hop(CAVE, edge, PROPOSITION, false);

    assertThat(hop.describe())
        .isEqualTo(
            "Nick Cave -[COMPOSED_FOR]-> The Proposition"
                + " [wikidata S-cave-prop-score, musicbrainz mb-release-score-1]");
  }

  @Test
  @DisplayName("a null sourceRef renders as the bare sourceId, with no trailing space")
  void nullSourceRefRendersBareSourceId() {
    EdgeRecord edge =
        new EdgeRecord(
            CAVE.qid(),
            MCCARTHY.qid(),
            "SIMILAR_TO",
            null,
            null,
            List.of(new Provenance("lastfm", null, WHEN, 0.50)));
    Hop hop = new Hop(CAVE, edge, MCCARTHY, false);

    assertThat(hop.describe()).isEqualTo("Nick Cave -[SIMILAR_TO]-> Cormac McCarthy [lastfm]");
  }

  @Test
  @DisplayName("PathResult.render() joins every hop's describe() on its own indented line")
  void multiHopPathRendersEachHopIndented() {
    EdgeRecord caveToProposition =
        new EdgeRecord(
            CAVE.qid(),
            PROPOSITION.qid(),
            "WROTE_SCREENPLAY_FOR",
            null,
            null,
            List.of(new Provenance("wikidata", "S-cave-prop-writer", WHEN, 1.00)));
    EdgeRecord hillcoatToProposition =
        new EdgeRecord(
            HILLCOAT.qid(),
            PROPOSITION.qid(),
            "DIRECTED",
            null,
            null,
            List.of(new Provenance("wikidata", "S-hillcoat-prop", WHEN, 1.00)));

    PathResult path =
        new PathResult(
            List.of(
                new Hop(CAVE, caveToProposition, PROPOSITION, false),
                new Hop(PROPOSITION, hillcoatToProposition, HILLCOAT, true)));

    String expected =
        "      Nick Cave -[WROTE_SCREENPLAY_FOR]-> The Proposition [wikidata"
            + " S-cave-prop-writer]\n"
            + "      The Proposition <-[DIRECTED]- John Hillcoat [wikidata S-hillcoat-prop]\n";
    assertThat(path.render()).isEqualTo(expected);
    assertThat(path.length()).isEqualTo(2);
  }
}
