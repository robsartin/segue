package com.robsartin.segue.evaluate;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.Equivalences;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.RatingAge;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
                  Equivalences.NONE,
                  Optional.empty())
              .run(knownList(), 25, lines::add);

      assertThat(readings).hasSameSizeAs(Setting.GRID);
      assertThat(lines).hasSize(3 + 1 + Setting.GRID.size());
      assertThat(lines.get(0)).isEqualTo(EvaluationReport.HEADER);
      assertThat(readings)
          .as("the one eligible entity was held out, so it is a candidate the sweep can return")
          .anyMatch(reading -> reading.hits() == 1);
      assertThat(readings)
          .as("the rated-down entity is in the pool in every fold, because suppression is withheld")
          .anyMatch(reading -> reading.negativesOffered() == HeldOut.EVERY);
    }
  }

  @Test
  @DisplayName("every fold is read, so an entity held out only in a later fold is a hit too")
  void shouldCountAHitFromEveryFoldWhenTwoEligibleEntitiesFallInDifferentFolds()
      throws IOException {
    try (TinkerGraphStore graph = InventedEvaluation.graph()) {
      List<String> lines = new ArrayList<>();

      List<Reading> readings =
          new EvaluateRun(
                  graph,
                  qid -> false,
                  Map.of(
                      InventedEvaluation.STRANGER, 5,
                      InventedEvaluation.HIDDEN, 5,
                      InventedEvaluation.REJECTED, 1),
                  Equivalences.NONE,
                  Optional.empty())
              .run(knownList(), 25, lines::add);

      assertThat(lines.get(1))
          .as("two eligible entities, one in fold zero and one in fold one, and both are read")
          .contains("2 eligible entity(ies)")
          .contains("in " + HeldOut.EVERY + " fold(s)")
          .contains("2 held out over all folds")
          .contains("at least 1 left on the known-list");
      assertThat(readings)
          .as("one hit in fold zero and one in fold one — a single-fold run reports one")
          .anyMatch(reading -> reading.hits() == 2);
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
              Equivalences.NONE,
              Optional.empty())
          .run(knownList(), 25, lines::add);

      assertThat(lines.get(1))
          .as("one eligible entity, not two — an institution is never a candidate")
          .contains("1 eligible entity(ies)");
    }
  }

  @Test
  @DisplayName("the header states both halves and every row splits when an instant is given")
  void shouldReportBothHalvesWhenTheRunIsSplitByRatingAge() throws IOException {
    try (TinkerGraphStore graph = InventedEvaluation.graph()) {
      List<String> lines = new ArrayList<>();
      RatingAge age =
          new RatingAge(Instant.parse("2026-09-06T15:00:00Z"), Set.of(InventedEvaluation.STRANGER));

      List<Reading> readings =
          new EvaluateRun(
                  graph,
                  qid -> false,
                  Map.of(
                      InventedEvaluation.STRANGER, 5,
                      InventedEvaluation.HIDDEN, 5,
                      InventedEvaluation.REJECTED, 1),
                  Equivalences.NONE,
                  Optional.of(age))
              .run(knownList(), 25, lines::add);

      assertThat(lines.get(1))
          .as(
              "the header's own identity: old plus new equals the total the line above already"
                  + " states, so a reader can cross-check the two lines against each other")
          .contains("2 held out over all folds");
      assertThat(lines.get(2))
          .as("two eligible entities, one each side of the instant")
          .contains("1 old (rated before it)")
          .contains("1 new (rated on or after it)");
      assertThat(readings)
          .as("every row carries the halves, and they partition the whole-population cells")
          .allMatch(reading -> reading.halves().split())
          .allMatch(
              reading -> reading.halves().oldHits() + reading.halves().newHits() == reading.hits());
    }
  }

  private Path knownList() throws IOException {
    Path known = dir.resolve("known.csv");
    Files.writeString(
        known, InventedEvaluation.KNOWN_ONE + "\n" + InventedEvaluation.KNOWN_TWO + "\n");
    return known;
  }
}
