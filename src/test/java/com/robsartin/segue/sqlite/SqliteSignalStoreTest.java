package com.robsartin.segue.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.Signal;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Every qid, source and count here is invented (ADR 33, issue #37). */
class SqliteSignalStoreTest {

  private static final Instant T1 = Instant.parse("2026-03-01T10:00:00Z");
  private static final Instant T2 = Instant.parse("2026-04-01T10:00:00.250Z");

  @TempDir private Path dir;

  @Test
  @DisplayName("a stored signal reads back as it was written")
  void shouldReadBackWhatWasStored() {
    try (SqliteSignalStore store = SqliteSignalStore.inMemory()) {
      Signal signal = new Signal("Q0900001", "invented-source", 12, true, false, T1);

      store.put(signal);

      assertThat(store.readAll()).containsExactly(signal);
    }
  }

  @Test
  @DisplayName("the same entity and source is one row, and the later write replaces the earlier")
  void shouldOverwriteTheRowForTheSameEntityAndSource() {
    try (SqliteSignalStore store = SqliteSignalStore.inMemory()) {
      store.put(new Signal("Q0900001", "invented-source", 12, true, false, T1));
      Signal later = new Signal("Q0900001", "invented-source", 3, false, true, T2);

      store.put(later);

      assertThat(store.readAll()).containsExactly(later);
    }
  }

  @Test
  @DisplayName("a second source adds a row beside the first rather than replacing it")
  void shouldKeepSourcesApart() {
    try (SqliteSignalStore store = SqliteSignalStore.inMemory()) {
      store.put(new Signal("Q0900001", "first-invented", 5, false, false, T1));
      store.put(new Signal("Q0900001", "second-invented", 9, false, false, T1));

      assertThat(store.readAll())
          .extracting(Signal::source)
          .containsExactly("first-invented", "second-invented");
    }
  }

  @Test
  @DisplayName("the rows are still there when the file is opened again")
  void shouldPersistInTheFile() {
    Path file = dir.resolve("segue.db");
    Signal signal = new Signal("Q0900002", "invented-source", 1, false, false, T1);
    try (SqliteSignalStore store = new SqliteSignalStore(file)) {
      store.put(signal);
    }

    try (SqliteSignalStore reopened = new SqliteSignalStore(file)) {
      assertThat(reopened.readAll()).containsExactly(signal);
    }
  }
}
