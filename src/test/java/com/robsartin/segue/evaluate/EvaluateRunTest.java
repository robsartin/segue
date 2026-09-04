package com.robsartin.segue.evaluate;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.Equivalences;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The run, over an invented graph and invented ratings. Nothing here comes from anybody's taste
 * layer (ADR 33, issue #37).
 */
class EvaluateRunTest {

  @TempDir private Path dir;

  @Test
  @DisplayName(
      "one row per setting reaches the report, and the held-out entity is hidden from the sweep")
  void shouldReportOneRowPerSettingWhenTheRunSweepsTheGrid() throws IOException {
    try (TinkerGraphStore graph = InventedEvaluation.graph()) {
      List<String> lines = new ArrayList<>();

      List<Reading> readings =
          new EvaluateRun(
                  graph,
                  qid -> false,
                  Map.of(InventedEvaluation.HIDDEN, 5, InventedEvaluation.REJECTED, 1),
                  Equivalences.NONE)
              .run(knownList(), 25, lines::add);

      assertThat(readings).hasSameSizeAs(Setting.GRID);
      assertThat(lines).hasSize(3 + 1 + Setting.GRID.size());
      assertThat(lines.get(0)).isEqualTo(EvaluationReport.HEADER);
      assertThat(readings)
          .as("the one eligible entity was held out, so it is a candidate the sweep can return")
          .anyMatch(reading -> reading.hits() == 1);
      assertThat(readings)
          .as("the rated-down entity is in the pool, because suppression is withheld")
          .anyMatch(reading -> reading.negativesOffered() == 1);
    }
  }

  @Test
  @DisplayName("a highly rated entity the sweep could never offer is not counted as eligible")
  void shouldNotCountAnEntityAsEligibleWhenTheSweepCouldNotOfferItBack() throws IOException {
    try (TinkerGraphStore graph = InventedEvaluation.graph()) {
      // An institution: a GROUP by kind, and refused as a candidate by the class it states.
      graph.upsertNode(
          new NodeRecord("Q0900441", NodeKind.GROUP, "an invented academy", List.of("Q0900801")));
      List<String> lines = new ArrayList<>();

      new EvaluateRun(
              graph,
              "Q0900801"::equals,
              Map.of(InventedEvaluation.HIDDEN, 5, "Q0900441", 5),
              Equivalences.NONE)
          .run(knownList(), 25, lines::add);

      assertThat(lines.get(1))
          .as("one eligible entity, not two — an institution is never a candidate")
          .contains("1 eligible entity(ies)");
    }
  }

  private Path knownList() throws IOException {
    Path known = dir.resolve("known.csv");
    Files.writeString(
        known, InventedEvaluation.KNOWN_ONE + "\n" + InventedEvaluation.KNOWN_TWO + "\n");
    return known;
  }
}
