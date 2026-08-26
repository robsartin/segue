package com.robsartin.segue.seed;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One run over a list: fold, skip what is already answered, resolve in chunks, write as it goes.
 *
 * <p><b>Resumable, and the results are the ledger.</b> Nine hundred names is thousands of HTTP
 * calls against someone else's free service, so a run that has to start over after a network blip
 * is not acceptable. Each chunk's answers are appended before the next chunk starts, and the next
 * run skips every folded name either output file already holds — so there is no progress file that
 * can disagree with the results.
 *
 * <p><b>Nothing is written to the graph.</b> This tool resolves names and reports what it found;
 * turning a QID into a node is {@code add_entity}'s job, and {@code IngestService} is the only
 * writer there is (ADR 19). An ArchUnit rule holds this package to it.
 */
public final class SeedRun {

  private static final Logger log = LoggerFactory.getLogger(SeedRun.class);

  private final SeedResolver resolver;
  private final Path mapping;
  private final Path review;
  private final int chunkSize;

  public SeedRun(SeedResolver resolver, Path mapping, Path review, int chunkSize) {
    this.resolver = Objects.requireNonNull(resolver, "resolver");
    this.mapping = Objects.requireNonNull(mapping, "mapping");
    this.review = Objects.requireNonNull(review, "review");
    if (chunkSize < 1) {
      throw new IllegalArgumentException("chunkSize must be at least 1");
    }
    this.chunkSize = chunkSize;
  }

  public SeedSummary run(List<SeedRow> rows) {
    Objects.requireNonNull(rows, "rows");
    List<NameGroup> groups = NameGroup.of(rows);
    Set<String> done = SeedFiles.alreadyResolved(List.of(mapping, review));
    List<NameGroup> outstanding =
        groups.stream().filter(group -> !done.contains(group.key())).toList();
    log.info(
        "{} rows, {} distinct acts, {} already answered", rows.size(), groups.size(), done.size());

    int accepted = 0;
    int needsReview = 0;
    int unresolved = 0;
    for (int from = 0; from < outstanding.size(); from += chunkSize) {
      List<NameGroup> chunk =
          outstanding.subList(from, Math.min(from + chunkSize, outstanding.size()));
      Map<String, Decision> decisions = resolver.resolve(chunk);
      List<ResolutionRow> acceptedRows = new ArrayList<>();
      List<ResolutionRow> reviewRows = new ArrayList<>();
      for (NameGroup group : chunk) {
        Decision decision = decisions.get(group.key());
        switch (decision.outcome()) {
          case ACCEPTED -> accepted++;
          case REVIEW -> needsReview++;
          case UNRESOLVED -> unresolved++;
        }
        // One output row per input line, so several spellings of one act each carry the answer.
        for (SeedRow row : group.rows()) {
          (decision.accepted() ? acceptedRows : reviewRows).add(ResolutionRow.of(row, decision));
        }
      }
      SeedFiles.append(mapping, acceptedRows);
      SeedFiles.append(review, reviewRows);
      log.info(
          "resolved {} of {} acts",
          Math.min(from + chunkSize, outstanding.size()),
          outstanding.size());
    }
    return new SeedSummary(
        rows.size(),
        groups.size(),
        groups.size() - outstanding.size(),
        accepted,
        needsReview,
        unresolved);
  }
}
