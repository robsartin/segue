package com.robsartin.segue.expand;

import com.robsartin.segue.domain.Equivalences;
import com.robsartin.segue.domain.KnownList;
import com.robsartin.segue.expansion.EntityExpansion;
import com.robsartin.segue.expansion.ExpansionSources;
import com.robsartin.segue.ingest.GraphProjector;
import com.robsartin.segue.ingest.IngestService;
import com.robsartin.segue.ingest.Replay;
import com.robsartin.segue.port.ExpandContext;
import com.robsartin.segue.port.IdentityMerge;
import com.robsartin.segue.sqlite.SqliteAffinityStore;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.support.RequiredDatabase;
import com.robsartin.segue.tinker.TinkerGraphStore;
import com.robsartin.segue.wikidata.WikidataClient;
import com.robsartin.segue.wikidata.WikidataEntityResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The promotion expander's command line, without a {@code main} yet.
 *
 * <p><b>No {@code public static void main} in this class</b> (#284). {@code
 * PackageListsTest.shouldNameEveryDevToolPackageWhenAClassEndsInCliAndDeclaresAMain} keys on a
 * {@code *Cli} class declaring one, so adding it here would demand the Gradle task, {@code
 * ArchitectureTest.DEV_TOOL_PACKAGES} and every fence a dev tool carries in the same breath. Task
 * 10 is where those travel together.
 *
 * <p><b>{@code --db} is required, and {@code SEGUE_DB} does not satisfy it</b>, exactly as {@code
 * RetractCli}, {@code OwnCli}, {@code CensusCli} and {@code EvaluateCli} refuse it — this tool
 * writes, and an agent's shell inherits {@code SEGUE_DB} from the owner's profile (ADR 60). This
 * class never names {@code support.DefaultDatabase} and never takes a {@link Path} out of {@code
 * support}: the refusal quotes the path back through {@link RequiredDatabase#refusal}, which owns
 * that resolution.
 */
public final class ExpandCli {

  private static final Logger log = LoggerFactory.getLogger(ExpandCli.class);

  private static final String USAGE = "usage: --db <segue.db> [--max-new-edges <n>] [--dry-run]";

  private ExpandCli() {}

  /**
   * What to expand against, and how far.
   *
   * @param database no default, on purpose — see this class's javadoc
   * @param maxNewEdges the bound handed to every entity's expansion, defaulting to {@link
   *     ExpandContext#defaults()}
   * @param dryRun report what would be visited and touch no network and no log
   */
  record Options(Path database, int maxNewEdges, boolean dryRun) {}

  /** Parse and validate, refusing anything that could not work before a store is opened. */
  static Options parse(String[] args, String envDatabase, String userHome) {
    Map<String, String> values = new LinkedHashMap<>();
    boolean dryRun = false;

    for (int i = 0; i < args.length; i++) {
      String flag = args[i];
      if ("--dry-run".equals(flag)) {
        dryRun = true;
        continue;
      }
      String value = valueOf(args, i, flag);
      i++;
      // Refuses a repeated flag rather than letting the last one win — OwnCli's rule, worst on
      // the one flag whose value the operator reads back.
      if (values.put(flag, value) != null) {
        throw usage(flag + " was given twice");
      }
    }

    String given = values.remove("--db");
    if (given == null) {
      throw usage(RequiredDatabase.refusal(envDatabase, userHome));
    }
    Path database = Path.of(given);

    int maxNewEdges = ExpandContext.defaults().maxNewEdges();
    String maxNewEdgesValue = values.remove("--max-new-edges");
    if (maxNewEdgesValue != null) {
      maxNewEdges = number(maxNewEdgesValue);
      if (maxNewEdges <= 0) {
        throw usage("--max-new-edges must be positive");
      }
    }

    if (!values.isEmpty()) {
      throw usage("unknown option " + values.keySet().iterator().next());
    }

    return new Options(database, maxNewEdges, dryRun);
  }

  private static int number(String value) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw usage("--max-new-edges takes a whole number, got " + value);
    }
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
    run(args, System.getenv("SEGUE_DB"), System.getProperty("user.home"));
  }

  /**
   * {@code main}, with the two environment reads passed in.
   *
   * <p>A seam for {@code EvaluateCli.run}'s reason, and the order of the two refusals is the
   * behaviour: a missing {@code --db} has to be refused by {@link #parse} before {@code
   * Files.exists} is reached, or the operator is told "no segue database at …" — which reads as a
   * missing file rather than a missing flag, and names a path they never typed.
   *
   * <p><b>One {@link WikidataClient} for the Action API and one {@link Clock}, built here and
   * shared.</b> A second resolver would mean a second client, and one client per run is what keeps
   * the pacing honest across hundreds of expansions — {@link ExpansionSources#both} makes the same
   * argument one level down for the Query Service client and for {@code MusicBrainzClient}.
   *
   * <p><b>The expansion is built even for a dry run</b>, because {@link ExpandRun} holds one and a
   * dry run is still a run of this tool. Nothing in either client's constructor opens a socket, and
   * {@link ExpandRun#dryRun} never asks the expansion anything — the offline guarantee is the
   * method's, not the wiring's.
   */
  static void run(String[] args, String envDatabase, String userHome) {
    Options options = parse(args, envDatabase, userHome);

    // Refuse a database that is not there rather than creating an empty one and expanding nothing:
    // both sqlite constructors create the file and its schema if absent, which is right for a
    // server starting fresh and wrong for a tool whose promotions come out of a table.
    if (!Files.exists(options.database())) {
      throw new IllegalArgumentException(
          "no segue database at " + options.database() + " — nothing to expand");
    }

    try (SqliteAssertionLog assertions = new SqliteAssertionLog(options.database());
        SqliteAffinityStore affinity = new SqliteAffinityStore(options.database());
        TinkerGraphStore graph = new TinkerGraphStore()) {
      Replay replay = GraphProjector.replay(assertions, graph, IdentityMerge.NONE);
      log.info("replayed {} assertion(s)", replay.applied());

      // The fold the replay already derived (#246, ADR 64) — never read back and folded again.
      Equivalences merges = replay.fold().equivalences();
      // Resolved before the threshold is applied: a merge leaves two affinity rows naming one
      // thing, and promoting both would expand the id the owner retired as well as the one he
      // kept. A count, never a qid and never a score (ADR 33).
      Map<String, Integer> ratings = merges.resolve(affinity.readRatings());
      log.info("read {} rating(s)", ratings.size());

      // KnownList.promoted with no file IS "rated at or above PROMOTION_RATING, ascending by
      // qid" — the threshold and the order from the class that owns both, rather than a second
      // copy of the rule here (issues #106 and #109).
      List<String> promotions = KnownList.promoted(List.of(), ratings);
      log.info("{} promotion(s) to visit", promotions.size());

      Clock clock = Clock.systemUTC();
      WikidataEntityResolver resolver = new WikidataEntityResolver(new WikidataClient(), clock);
      // IdentityMerge.NONE and not carryingRatings: an expansion never records a SameAs, so there
      // is nothing for a merge hook to carry — and carryingRatings writes the taste layer, which
      // theExpanderWritesThroughIngestAlone forbids outright.
      IngestService ingest = new IngestService(assertions, graph, IdentityMerge.NONE);
      EntityExpansion expansion =
          new EntityExpansion(resolver, graph, ingest, ExpansionSources.both(resolver, clock));

      ExpandRun run = new ExpandRun(expansion, graph);
      if (options.dryRun()) {
        run.dryRun(promotions, log::info);
      } else {
        run.run(promotions, options.maxNewEdges(), log::info);
      }
    }
  }
}
