package com.robsartin.segue.signals;

import com.robsartin.segue.sqlite.SqliteSignalStore;
import com.robsartin.segue.support.DefaultDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The entry point, run from Gradle: {@code ./gradlew importSignals --args="…"} (#350).
 *
 * <p>Two steps, because resolving is the seed tool's job and this tool does not duplicate it:
 *
 * <ol>
 *   <li>{@code --signals <export.csv> --names-out <names.csv>} writes the {@code name,kind,status}
 *       list {@code resolveNames --list} reads. It opens no store.
 *   <li>{@code --signals <export.csv> --mapping <qids.csv> --review <review.csv>} reads the answers
 *       {@code resolveNames} wrote and stores what resolved, in the {@code signal} table beside the
 *       taste layer.
 * </ol>
 *
 * <p><b>Not an MCP tool.</b> ADR 26 pins six, and raw counts do not go through any MCP surface:
 * only a confidence and its evidence kind may (#351), for the reason ADR 33 keeps a note off it.
 * The output is personal data; the log lines are counts and basenames, never a path or an entity.
 */
public final class SignalsCli {

  private static final Logger log = LoggerFactory.getLogger(SignalsCli.class);

  /** The only source there is so far; a second one comes with its own export, one at a time. */
  static final String DEFAULT_SOURCE = "apple-music";

  private static final String USAGE =
      "usage: --signals <export.csv> (--names-out <names.csv> | --mapping <qids.csv> --review"
          + " <review.csv>) [--source <id>, default "
          + DEFAULT_SOURCE
          + "] [--db <segue.db>]";

  private SignalsCli() {}

  /**
   * What was asked for.
   *
   * @param signals the export step's file
   * @param namesOut where to write the list for {@code resolveNames}; null when importing
   * @param mapping the seed tool's accepted answers; null when writing the list
   * @param review the seed tool's other answers, minted rows included; null when writing the list
   */
  record Options(Path signals, Path namesOut, Path mapping, Path review, String source, Path db) {

    boolean importing() {
      return namesOut == null;
    }
  }

  static Options parse(String[] args, String envDatabase, String userHome) {
    String db = null;
    Path signals = null;
    Path namesOut = null;
    Path mapping = null;
    Path review = null;
    String source = DEFAULT_SOURCE;
    for (int i = 0; i < args.length; i += 2) {
      String flag = args[i];
      if (i + 1 >= args.length) {
        throw usage(flag + " needs a value");
      }
      String value = args[i + 1];
      switch (flag) {
        case "--signals" -> signals = Path.of(value);
        case "--names-out" -> namesOut = Path.of(value);
        case "--mapping" -> mapping = Path.of(value);
        case "--review" -> review = Path.of(value);
        case "--source" -> source = value;
        case "--db" -> db = value;
        default -> throw usage("unknown option " + flag);
      }
    }
    if (signals == null) {
      throw usage("--signals is required");
    }
    if (namesOut != null && (mapping != null || review != null)) {
      throw usage(
          "--names-out writes the list for resolveNames and is not combined with an import");
    }
    if (namesOut == null && (mapping == null || review == null)) {
      throw usage("an import needs both --mapping and --review");
    }
    if (source.isBlank()) {
      throw usage("--source cannot be blank");
    }
    return new Options(
        signals,
        namesOut,
        mapping,
        review,
        source,
        namesOut == null ? DefaultDatabase.resolve(db, envDatabase, userHome) : null);
  }

  private static IllegalArgumentException usage(String problem) {
    return new IllegalArgumentException(problem + ". " + USAGE);
  }

  public static void main(String[] args) {
    Options options = parse(args, System.getenv("SEGUE_DB"), System.getProperty("user.home"));
    var rows = SignalsFile.read(options.signals());
    if (!options.importing()) {
      SignalsFile.writeNames(rows, options.namesOut());
      log.info("{} name(s) written to {}", rows.size(), options.namesOut().getFileName());
      return;
    }
    // The sqlite constructor creates a missing file; for an import that would be a silent empty
    // database the owner then wonders about, so refuse as the ratings tool does.
    if (!Files.exists(options.db())) {
      throw new IllegalArgumentException("no segue database at " + options.db());
    }
    try (SqliteSignalStore store = new SqliteSignalStore(options.db())) {
      new SignalsRun(store)
          .run(rows, options.mapping(), options.review(), options.source(), Instant.now())
          .lines(options.signals().getFileName().toString())
          .forEach(log::info);
    }
  }
}
