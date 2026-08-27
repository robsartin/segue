package com.robsartin.segue.export;

import com.robsartin.segue.ingest.GraphProjector;
import com.robsartin.segue.port.AffinityStore;
import com.robsartin.segue.sqlite.SqliteAffinityStore;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The entry point, run from Gradle: {@code ./gradlew exportGraph --args="--view … --out …"}.
 *
 * <p><b>Not an MCP tool, and not in {@code seed}.</b> ADR 26 pins the tool surface at six, and
 * drawing a picture is an operator's job rather than a model's. It is a sibling of {@code seed}
 * rather than part of it because {@code seed} is fenced by {@code
 * ArchitectureTest.seedNeverOpensAStore} — it resolves names and must never open a store — and this
 * tool's entire job is reading one. Two dev-side tools, two opposite relationships with the
 * database, two packages. ADR 41.
 *
 * <p><b>It reads and never writes.</b> {@code ArchitectureTest.theExporterOnlyReads} forbids this
 * package from calling {@code GraphStore.record}, {@code GraphStore.upsertNode} or {@code
 * AssertionLog.append}, and from depending on {@code IngestService} at all. The graph it queries is
 * rebuilt in memory by {@link GraphProjector} — the same replay the application performs at boot,
 * so an exported route is the route {@code find_paths} would return — and it is thrown away when
 * the JVM exits. Nothing on disk changes.
 *
 * <p><b>No {@code System.out}.</b> ADR 30 makes SLF4J the only logging API and an ArchUnit rule
 * enforces it project-wide. This tool has no protocol on stdout to protect; a dev tool is still not
 * a reason to make an exception.
 */
public final class ExportCli {

  private static final Logger log = LoggerFactory.getLogger(ExportCli.class);

  private static final String USAGE =
      "usage: --view <"
          + ViewKind.names()
          + "> --out <file> [--format <"
          + OutputFormat.names()
          + ">, inferred from the --out extension when absent] [--db <segue.db>]"
          + " [route: --from <QID> --to <QID> [--max-hops <n>]]"
          + " [neighbourhood: --qid <QID> [--depth <n>]]"
          + " [subgraph: --qids <file>]"
          + " [full: --all]"
          + " [--include-affinity]";

  private ExportCli() {}

  /**
   * What to export, from where, to where.
   *
   * <p>{@code out} has no default on purpose. The output of this tool belongs outside the working
   * tree, and a tool that picks a path for you is a tool that quietly writes one into the
   * repository; {@code *.dot} and {@code *.graphml} are gitignored as the second lock, not the
   * first.
   *
   * @param format resolved rather than taken: {@code --format} if given, otherwise whatever the
   *     {@code out} extension names, otherwise {@link OutputFormat#DEFAULT}. A {@code --format}
   *     that disagrees with the extension never reaches here — it is refused. See {@code
   *     formatFor}.
   * @param database the assertion log to read. Defaults exactly as the server's does — {@code
   *     SEGUE_DB} if set, otherwise {@code ${user.home}/.segue/segue.db}. That default is stated
   *     twice, here and in {@code application.yaml}, because this tool is plain Java and ADR 32
   *     keeps Spring out of every package but {@code app} and {@code mcp}; the yaml is the
   *     authority for the server and this is the authority for the tool.
   */
  public record Options(
      ViewKind view,
      OutputFormat format,
      Path database,
      Path out,
      String fromQid,
      String toQid,
      int maxHops,
      String qid,
      int depth,
      Path qidList,
      boolean includeAffinity) {

    public Options {
      Objects.requireNonNull(view, "view");
      Objects.requireNonNull(format, "format");
      Objects.requireNonNull(database, "database");
      Objects.requireNonNull(out, "out");
    }
  }

  /**
   * Parse and validate, refusing anything that could not possibly work before a store is opened.
   *
   * <p>{@code --all} is checked here rather than at the point of writing, which is the difference
   * between refusing a whole-graph export in a millisecond and refusing it after a multi-second
   * replay. It leaves no trace in {@link Options}: past this method the full view is simply
   * permitted.
   */
  static Options parse(String[] args, String envDatabase, String userHome) {
    ViewKind view = null;
    OutputFormat requestedFormat = null;
    Path database = null;
    Path out = null;
    String fromQid = null;
    String toQid = null;
    int maxHops = 4;
    String qid = null;
    int depth = 1;
    Path qidList = null;
    boolean all = false;
    boolean includeAffinity = false;

    for (int i = 0; i < args.length; i++) {
      String flag = args[i];
      switch (flag) {
        case "--all" -> all = true;
        case "--include-affinity" -> includeAffinity = true;
        default -> {
          String value = valueOf(args, i, flag);
          i++;
          switch (flag) {
            case "--view" -> view = ViewKind.parse(value);
            case "--format" -> requestedFormat = OutputFormat.parse(value);
            case "--db" -> database = Path.of(value);
            case "--out" -> out = Path.of(value);
            case "--from" -> fromQid = value;
            case "--to" -> toQid = value;
            case "--max-hops" -> maxHops = Integer.parseInt(value);
            case "--qid" -> qid = value;
            case "--depth" -> depth = Integer.parseInt(value);
            case "--qids" -> qidList = Path.of(value);
            default -> throw usage("unknown option " + flag);
          }
        }
      }
    }

    if (view == null) {
      throw usage("--view is required");
    }
    if (out == null) {
      throw usage("--out is required");
    }
    switch (view) {
      case ROUTE -> {
        if (fromQid == null) {
          throw usage("the route view needs --from");
        }
        if (toQid == null) {
          throw usage("the route view needs --to");
        }
        if (maxHops <= 0) {
          throw usage("--max-hops must be positive");
        }
      }
      case NEIGHBOURHOOD -> {
        if (qid == null) {
          throw usage("the neighbourhood view needs --qid");
        }
        if (depth < 1) {
          throw usage("--depth must be at least 1");
        }
      }
      case SUBGRAPH -> {
        if (qidList == null) {
          throw usage("the subgraph view needs --qids, a file of the entities to keep");
        }
      }
      case FULL -> {
        if (!all) {
          throw usage(
              "the full view exports the whole graph — tens of thousands of nodes on a real one,"
                  + " which no layout engine will draw. Pass --all if that is what you want.");
        }
      }
    }

    return new Options(
        view,
        formatFor(requestedFormat, out),
        database != null ? database : defaultDatabase(envDatabase, userHome),
        out,
        fromQid,
        toQid,
        maxHops,
        qid,
        depth,
        qidList,
        includeAffinity);
  }

  /**
   * Where {@link ExportRun}'s notes go.
   *
   * <p>{@code ExportRun} reports through a {@code Consumer} rather than a logger of its own so that
   * a test can observe the ordering — the counts must reach the operator before the file exists.
   * The level is this class's decision, and the one note that is not routine gets the level that
   * says so: an export carrying affinity is personal data (ADR 33, issue #37), and it is worth
   * standing out in a terminal from the two lines of counts around it.
   */
  private static void note(String line) {
    if (AffinityOverlay.PERSONAL_DATA_WARNING.equals(line)) {
      log.warn(line);
    } else {
      log.info(line);
    }
  }

  /**
   * Which format to write, from the two places the caller can say so.
   *
   * <p><b>The extension is an argument, not decoration.</b> An {@code --out} ending in {@code .dot}
   * states the intent as plainly as {@code --format dot} does, and the old behaviour — default to
   * GraphML and ignore the name — wrote XML into a file called {@code route.dot}, reported success,
   * and failed minutes later inside Graphviz with a syntax error on the XML declaration (issue
   * #57). A tool that is handed the answer and discards it has produced a file that lies about
   * itself to every program downstream.
   *
   * <p><b>A contradiction is refused rather than resolved.</b> When {@code --format} and the
   * extension disagree, one of the two is a mistake and nothing here can know which; obeying either
   * one silently reintroduces the same misnamed file. There is deliberately no override flag: the
   * refusal costs one re-run, and the alternative costs a confusing failure in another tool. Fix
   * the flag or rename the file — both are one edit.
   */
  private static OutputFormat formatFor(OutputFormat requested, Path out) {
    OutputFormat named = OutputFormat.forPath(out).orElse(null);
    if (requested == null) {
      return named != null ? named : OutputFormat.DEFAULT;
    }
    if (named != null && named != requested) {
      throw usage(
          "--format "
              + requested.spelling()
              + " contradicts the "
              + OutputFormat.extensionOf(out)
              + " extension of --out "
              + out
              + ", which names "
              + named.spelling()
              + ". Change one — a file whose name says otherwise fails later, in another tool");
    }
    return requested;
  }

  private static Path defaultDatabase(String envDatabase, String userHome) {
    return envDatabase != null && !envDatabase.isBlank()
        ? Path.of(envDatabase)
        : Path.of(userHome, ".segue", "segue.db");
  }

  private static String valueOf(String[] args, int i, String flag) {
    if (i + 1 >= args.length) {
      throw usage(flag + " needs a value");
    }
    return args[i + 1];
  }

  private static IllegalArgumentException usage(String problem) {
    String sentence = problem.endsWith(".") ? problem : problem + ".";
    return new IllegalArgumentException(sentence + " " + USAGE);
  }

  public static void main(String[] args) {
    Options options = parse(args, System.getenv("SEGUE_DB"), System.getProperty("user.home"));

    // Refuse a database that is not there rather than creating an empty one and exporting
    // nothing: SqliteAssertionLog's constructor creates the file and its schema if absent, which
    // is right for a server starting fresh and wrong for a tool whose whole job is to read.
    if (!Files.exists(options.database())) {
      throw new IllegalArgumentException(
          "no graph at " + options.database() + " — nothing to export");
    }

    try (SqliteAssertionLog assertions = new SqliteAssertionLog(options.database());
        TinkerGraphStore graph = new TinkerGraphStore()) {

      // The bounded views traverse, so they need the projection. The full and subgraph views read
      // the log directly (ADR 19) and would pay a multi-second replay for nothing.
      if (!options.view().readsTheWholeLog()) {
        long applied = GraphProjector.project(assertions, graph);
        log.info("replayed {} assertion(s) from {}", applied, options.database());
      }

      AffinityStore ratings =
          options.includeAffinity() ? new SqliteAffinityStore(options.database()) : null;
      try {
        AffinityOverlay overlay = ratings == null ? null : new AffinityOverlay(ratings);
        new ExportRun(new ViewSelector(graph, assertions), overlay, options.format().writer())
            .run(options, ExportCli::note);
      } finally {
        if (ratings != null) {
          ratings.close();
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("could not write " + options.out(), e);
    }
  }
}
