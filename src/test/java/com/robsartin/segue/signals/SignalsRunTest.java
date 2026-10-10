package com.robsartin.segue.signals;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.Signal;
import com.robsartin.segue.sqlite.SqliteSignalStore;
import com.robsartin.segue.support.Outcome;
import com.robsartin.segue.support.ResolutionFiles;
import com.robsartin.segue.support.ResolutionRow;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The join between an export and the seed tool's answers. Every artist, qid and count is invented:
 * a real play count is the owner's listening history (ADR 33, issue #37).
 */
class SignalsRunTest {

  private static final Instant NOW = Instant.parse("2026-05-01T09:00:00Z");
  private static final String SOURCE = "invented-source";

  @TempDir private Path dir;

  private Path mapping() {
    return dir.resolve("names-qids.csv");
  }

  private Path review() {
    return dir.resolve("names-review.csv");
  }

  private static ResolutionRow answer(String name, String qid, Outcome outcome) {
    return new ResolutionRow(name, "musician", "LISTENED", qid, null, outcome, "invented");
  }

  @Test
  @DisplayName("an accepted name is stored under its qid with the raw counts")
  void shouldStoreAnAcceptedName() {
    ResolutionFiles.append(
        mapping(), List.of(answer("The Invented Quartet", "Q0900001", Outcome.ACCEPTED)));
    try (SqliteSignalStore store = SqliteSignalStore.inMemory()) {

      SignalsSummary summary =
          new SignalsRun(store)
              .run(
                  List.of(new SignalRow("The Invented Quartet", 40, true, false)),
                  mapping(),
                  review(),
                  SOURCE,
                  NOW);

      assertThat(store.readAll())
          .containsExactly(new Signal("Q0900001", SOURCE, 40, true, false, NOW));
      assertThat(summary).isEqualTo(new SignalsSummary(1, 1, 0, 0, 0, 1));
    }
  }

  @Test
  @DisplayName("a name left in review is counted and not stored")
  void shouldLeaveAReviewNameOutOfTheTable() {
    ResolutionFiles.append(
        review(), List.of(answer("A Band Nobody Spells Right", null, Outcome.REVIEW)));
    try (SqliteSignalStore store = SqliteSignalStore.inMemory()) {

      SignalsSummary summary =
          new SignalsRun(store)
              .run(
                  List.of(new SignalRow("A Band Nobody Spells Right", 7, false, false)),
                  mapping(),
                  review(),
                  SOURCE,
                  NOW);

      assertThat(store.readAll()).isEmpty();
      assertThat(summary.inReview()).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("a name neither file mentions is reported as unanswered, not as lost")
  void shouldReportAnUnansweredName() {
    try (SqliteSignalStore store = SqliteSignalStore.inMemory()) {

      SignalsSummary summary =
          new SignalsRun(store)
              .run(
                  List.of(new SignalRow("Never Resolved", 2, false, false)),
                  mapping(),
                  review(),
                  SOURCE,
                  NOW);

      assertThat(summary.unanswered()).isEqualTo(1);
      assertThat(store.readAll()).isEmpty();
    }
  }

  @Test
  @DisplayName("a minted entity is stored under its local qid and counted as minted")
  void shouldStoreAMintedEntityUnderItsLocalQid() {
    ResolutionFiles.append(review(), List.of(answer("A Garage Act", "Q00900042", Outcome.MINTED)));
    try (SqliteSignalStore store = SqliteSignalStore.inMemory()) {

      SignalsSummary summary =
          new SignalsRun(store)
              .run(
                  List.of(new SignalRow("A Garage Act", 3, false, true)),
                  mapping(),
                  review(),
                  SOURCE,
                  NOW);

      assertThat(store.readAll()).extracting(Signal::qid).containsExactly("Q00900042");
      assertThat(summary.minted()).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("two spellings that resolve to one entity combine into one signal")
  void shouldCombineSpellingsOfOneEntity() {
    ResolutionFiles.append(
        mapping(),
        List.of(
            answer("The Invented Quartet", "Q0900001", Outcome.ACCEPTED),
            answer("Invented Quartet, The", "Q0900001", Outcome.ACCEPTED)));
    try (SqliteSignalStore store = SqliteSignalStore.inMemory()) {

      SignalsSummary summary =
          new SignalsRun(store)
              .run(
                  List.of(
                      new SignalRow("The Invented Quartet", 10, false, false),
                      new SignalRow("Invented Quartet, The", 5, true, false)),
                  mapping(),
                  review(),
                  SOURCE,
                  NOW);

      assertThat(store.readAll())
          .containsExactly(new Signal("Q0900001", SOURCE, 15, true, false, NOW));
      assertThat(summary.entities()).isEqualTo(1);
      assertThat(summary.resolved()).isEqualTo(2);
    }
  }

  @Test
  @DisplayName("running the same export twice leaves one row, and a newer one replaces the counts")
  void shouldBeIdempotent() {
    ResolutionFiles.append(
        mapping(), List.of(answer("The Invented Quartet", "Q0900001", Outcome.ACCEPTED)));
    try (SqliteSignalStore store = SqliteSignalStore.inMemory()) {
      SignalsRun run = new SignalsRun(store);
      List<SignalRow> first = List.of(new SignalRow("The Invented Quartet", 40, false, false));
      run.run(first, mapping(), review(), SOURCE, NOW);
      run.run(first, mapping(), review(), SOURCE, NOW);
      assertThat(store.readAll()).hasSize(1);

      Instant later = NOW.plusSeconds(86_400);
      run.run(
          List.of(new SignalRow("The Invented Quartet", 55, false, true)),
          mapping(),
          review(),
          SOURCE,
          later);

      assertThat(store.readAll())
          .containsExactly(new Signal("Q0900001", SOURCE, 55, false, true, later));
    }
  }

  @Test
  @DisplayName("the summary says how many and never names an artist or a count of plays")
  void shouldSummariseWithoutNamingAnything() {
    String text = String.join("\n", new SignalsSummary(9, 5, 1, 2, 1, 6).lines("music.csv"));

    assertThat(text).contains("9 row(s) read from music.csv").doesNotContain("/");
  }
}
