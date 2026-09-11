package com.robsartin.segue.expand;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.expansion.ExpansionOutcome;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A tally in, one aligned block of text out. Every count here is invented (ADR 33, issue #37); the
 * two source ids, "wikidata" and "musicbrainz", are vocabulary rather than entities, exactly as
 * {@code CensusReport}'s tests treat them.
 */
class ExpansionReportTest {

  /** Anything qid-shaped at all, wherever it appears. */
  private static final Pattern A_QID = Pattern.compile("\\bQ\\d+\\b");

  /**
   * Today's block, character for character. The header is a string literal here, not {@link
   * ExpansionReport#HEADER} — the point of the pin is to catch the header's own text moving, and
   * reading it off the constant it is meant to pin would let a reworded header carry the pin along
   * with it and prove nothing.
   */
  private static final List<String> GOLDEN_BLOCK =
      List.of(
          "# segue promotion expansion — aggregates only: no labels, no notes, no entity ids"
              + " (ADR 51, ADR 63).",
          "",
          "promotions",
          "  considered                12",
          "  expanded                   9",
          "  added nothing              2",
          "  refused                    2",
          "  failed                     1",
          "",
          "graph",
          "  nodes added               34",
          "  edge assertions recorded  77",
          "",
          "edge assertions by source",
          "  wikidata                  60",
          "  musicbrainz               17",
          "",
          "shortfalls",
          "  neighbours skipped         5",
          "  endpoints refused          3",
          "  bound cut the result       1",
          "  unavailable",
          "    musicbrainz              1",
          "  truncated",
          "    wikidata                 2",
          "",
          "refused, by reason",
          "  unknown entity             1",
          "  local entity               1");

  /** The example instant, invented; nothing here was read from a database. */
  private static final Instant SINCE = Instant.parse("2026-09-08T00:00:00Z");

  /**
   * The clause both blocks print when an instant was given, character for character. A literal, not
   * ExpansionReport's constant — see GOLDEN_BLOCK.
   */
  private static final String SINCE_LINE =
      "# only promotions rated on or after 2026-09-08T00:00:00Z: 7 excluded (rated before it) —"
          + " a rating's timestamp is its last write, so a re-rated old promotion counts as new.";

  /** The golden block with the clause inserted under its header, and nothing else moved. */
  private static List<String> withSinceLine(List<String> block) {
    List<String> withIt = new ArrayList<>(block);
    withIt.add(1, SINCE_LINE);
    return List.copyOf(withIt);
  }

  private static ExpansionTally goldenTally() {
    // LinkedHashMap, not Map.of: the golden block pins insertion order, and Map.of's iteration
    // order is unspecified.
    Map<String, Integer> edgesBySource = new LinkedHashMap<>();
    edgesBySource.put("wikidata", 60);
    edgesBySource.put("musicbrainz", 17);
    Map<ExpansionOutcome.Reason, Integer> refusalsByReason = new LinkedHashMap<>();
    refusalsByReason.put(ExpansionOutcome.Reason.UNKNOWN_ENTITY, 1);
    refusalsByReason.put(ExpansionOutcome.Reason.LOCAL_ENTITY, 1);
    return new ExpansionTally(
        12,
        9,
        2,
        1,
        34,
        77,
        5,
        3,
        1,
        edgesBySource,
        Map.of("musicbrainz", 1),
        Map.of("wikidata", 2),
        refusalsByReason);
  }

  @Test
  @DisplayName("the whole block renders exactly, section by section, in the pinned order")
  void shouldRenderTheWholeBlockWhenATallyIsGiven() {
    assertThat(ExpansionReport.lines(goldenTally())).containsExactlyElementsOf(GOLDEN_BLOCK);
  }

  @Test
  @DisplayName("the block names the instant and what it excluded when a filter was applied")
  void shouldNameTheInstantWhenTheBlockCoversOnlyWhatWasRatedSince() {
    assertThat(ExpansionReport.lines(goldenTally(), Optional.of(new RatedSince(SINCE, 7))))
        .containsExactlyElementsOf(withSinceLine(GOLDEN_BLOCK));
  }

  @Test
  @DisplayName("the dry run block states what would be visited, headed differently")
  void shouldRenderTheDryRunBlockWhenNothingIsAppended() {
    List<String> lines = ExpansionReport.dryRunLines(new Preflight(4, 2, 1));

    assertThat(lines)
        .containsExactly(
            "# segue promotion expansion — dry run: appends nothing. Aggregates only"
                + " (ADR 51, ADR 63).",
            "",
            "promotions",
            "  considered    4",
            "  in the graph  2",
            "  minted        1");
  }

  @Test
  @DisplayName("the dry run block names the instant and what it excluded when a filter was applied")
  void shouldNameTheInstantWhenTheDryRunBlockCoversOnlyWhatWasRatedSince() {
    List<String> lines =
        ExpansionReport.dryRunLines(new Preflight(4, 2, 1), Optional.of(new RatedSince(SINCE, 7)));

    assertThat(lines)
        .containsExactly(
            "# segue promotion expansion — dry run: appends nothing. Aggregates only"
                + " (ADR 51, ADR 63).",
            SINCE_LINE,
            "",
            "promotions",
            "  considered    4",
            "  in the graph  2",
            "  minted        1");
  }

  @Test
  @DisplayName("every column lines up, because the padding comes from the block's own widths")
  void shouldAlignEveryColumnWhenTheCountsDifferInWidth() {
    ExpansionTally tally =
        new ExpansionTally(100_000, 1, 0, 0, 0, 0, 0, 0, 0, Map.of(), Map.of(), Map.of(), Map.of());

    List<String> lines = ExpansionReport.lines(tally);

    String considered =
        lines.stream()
            .filter(line -> line.trim().startsWith("considered"))
            .findFirst()
            .orElseThrow();
    String failed =
        lines.stream().filter(line -> line.trim().startsWith("failed")).findFirst().orElseThrow();

    assertThat(considered).isEqualTo("  considered                100000");
    assertThat(failed).isEqualTo("  failed                         0");
    assertThat(considered.length())
        .as("the widest label and the widest count set one column each, for every row")
        .isEqualTo(failed.length());
  }

  @Test
  @DisplayName("no line carries anything Q-shaped, however the source-keyed maps are populated")
  void shouldCarryNoIdentifierWhenTheBlockIsRendered() {
    List<String> lines = ExpansionReport.lines(goldenTally());

    assertThat(lines).noneMatch(line -> A_QID.matcher(line).find());
  }

  @Test
  @DisplayName("an empty section still prints its heading, with no rows under it")
  void shouldStillPrintTheHeadingWhenASectionHasNoRows() {
    ExpansionTally tally =
        new ExpansionTally(
            1, 1, 0, 0, 0, 0, 0, 0, 0, Map.of(), Map.of("musicbrainz", 1), Map.of(), Map.of());

    List<String> lines = ExpansionReport.lines(tally);

    int index = lines.indexOf("edge assertions by source");
    assertThat(index).as("the heading is printed even though the map is empty").isPositive();
    assertThat(lines.get(index + 1))
        .as("no row follows an empty section — the next thing is the blank line before the next")
        .isEmpty();
  }
}
