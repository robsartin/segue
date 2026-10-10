package com.robsartin.segue.signals;

import com.robsartin.segue.domain.Signal;
import com.robsartin.segue.port.SignalStore;
import com.robsartin.segue.support.NameFold;
import com.robsartin.segue.support.Outcome;
import com.robsartin.segue.support.ResolutionFiles;
import com.robsartin.segue.support.ResolutionRow;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Join an export to the answers {@code resolveNames} already wrote, and store what resolved (#350).
 *
 * <p><b>It resolves nothing itself.</b> The mapping and review files are the seed tool's output
 * (ADR 40), read through {@code ResolutionFiles}; a name that did not resolve stays in the review
 * file, with the confidence label the seed tool gave it, and can be minted as a local entity (ADR
 * 59) like any other row — a minted row is read here as resolved, under its own qid. This tool has
 * no network, no resolver, and no dependency on the seed package, so it cannot grow a second answer
 * to "who is this artist".
 *
 * <p><b>It touches the signal table and nothing else.</b> It holds no {@code GraphStore}, {@code
 * AssertionLog} or {@code AffinityStore}: a signal is not an assertion and is not a rating.
 *
 * <p><b>Idempotent</b>: the table's key is entity and source, so running the same export twice
 * leaves one row each, and running a newer one replaces the older counts.
 *
 * <p><b>Nothing here logs.</b> The summary is counts; no artist, no entity and no count of plays
 * leaves through it (ADR 16, ADR 33).
 */
final class SignalsRun {

  private final SignalStore store;

  SignalsRun(SignalStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  SignalsSummary run(
      List<SignalRow> rows, Path mapping, Path review, String source, Instant observedAt) {
    Objects.requireNonNull(rows, "rows");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(observedAt, "observedAt");

    Map<String, List<ResolutionRow>> answers = new LinkedHashMap<>();
    List<ResolutionRow> answered = new ArrayList<>(ResolutionFiles.readRows(mapping));
    answered.addAll(ResolutionFiles.readRows(review));
    for (ResolutionRow row : answered) {
      answers.computeIfAbsent(NameFold.fold(row.name()), key -> new ArrayList<>()).add(row);
    }

    Map<String, Signal> byQid = new LinkedHashMap<>();
    int resolved = 0;
    int minted = 0;
    int inReview = 0;
    int unanswered = 0;
    for (SignalRow row : rows) {
      List<ResolutionRow> candidates = answers.get(NameFold.fold(row.artist()));
      if (candidates == null) {
        unanswered++;
        continue;
      }
      ResolutionRow hit =
          candidates.stream()
              .filter(c -> c.qid() != null)
              .filter(c -> c.confidence() == Outcome.ACCEPTED || c.confidence() == Outcome.MINTED)
              .findFirst()
              .orElse(null);
      if (hit == null) {
        inReview++;
        continue;
      }
      if (hit.confidence() == Outcome.MINTED) {
        minted++;
      } else {
        resolved++;
      }
      Signal signal =
          new Signal(hit.qid(), source, row.playCount(), row.loved(), row.favorited(), observedAt);
      byQid.merge(hit.qid(), signal, Signal::plus);
    }
    byQid.values().forEach(store::put);
    return new SignalsSummary(rows.size(), resolved, minted, inReview, unanswered, byQid.size());
  }
}
