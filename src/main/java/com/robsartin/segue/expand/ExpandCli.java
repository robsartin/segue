package com.robsartin.segue.expand;

import com.robsartin.segue.domain.Equivalences;
import com.robsartin.segue.domain.Expanded;
import com.robsartin.segue.domain.KnownList;
import com.robsartin.segue.domain.LocalEntity;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.RatingAge;
import com.robsartin.segue.domain.SecondHop;
import com.robsartin.segue.expansion.EntityAddition;
import com.robsartin.segue.expansion.EntityExpansion;
import com.robsartin.segue.expansion.ExpansionSources;
import com.robsartin.segue.ingest.GraphProjector;
import com.robsartin.segue.ingest.IngestService;
import com.robsartin.segue.ingest.LogProjection;
import com.robsartin.segue.ingest.Replay;
import com.robsartin.segue.port.ExpandContext;
import com.robsartin.segue.port.IdentityMerge;
import com.robsartin.segue.sqlite.SqliteAffinityStore;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.support.KnownListInput;
import com.robsartin.segue.support.RequiredDatabase;
import com.robsartin.segue.tinker.TinkerGraphStore;
import com.robsartin.segue.wikidata.WikidataClient;
import com.robsartin.segue.wikidata.WikidataEntityResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The promotion expander's command line, entered through {@code main} below and registered as the
 * tenth dev tool (#284), run as {@code ./gradlew expandPromotions}.
 *
 * <p><b>{@code --db} is required, and {@code SEGUE_DB} does not satisfy it</b>, exactly as {@code
 * RetractCli}, {@code OwnCli}, {@code CensusCli} and {@code EvaluateCli} refuse it — this tool
 * writes, and an agent's shell inherits {@code SEGUE_DB} from the owner's profile (ADR 60). This
 * class never names {@code support.DefaultDatabase} and never takes a {@link Path} out of {@code
 * support}: the refusal quotes the path back through {@link RequiredDatabase#refusal}, which owns
 * that resolution.
 *
 * <p><b>It reads when a rating last changed, and only when {@code --rated-since} asks.</b> The read
 * is {@code com.robsartin.segue.port.AffinityStore#readUpdatedAt}, under {@code
 * ArchitectureTest.onlyTheHarnessAndTheExpanderReadWhenARatingChanged} — the fence the evaluation
 * harness was the only tool inside until this one asked the same question (#276, #307). It carries
 * neither the note nor the score, and a run given no instant never makes the call at all. This is
 * the only class in the package that touches the store.
 *
 * <p><b>It reads a known-list file, and only when {@code --known} asks.</b> The file is the one
 * {@code recommend}, {@code rate}, {@code evaluate} and {@code graphCensus} take, read through
 * {@code support.KnownListInput}, and the population is its entities — folded onto their canonical
 * side — that no row in the log cites as an expansion's seed. That rule is {@code domain.Expanded},
 * which the census reads too, so the two tools cannot disagree about who has been expanded (#311,
 * #313). A run given a file composes no promotions and reads no rating at all. The two flags are
 * exclusive: they name different populations, and the block names one.
 *
 * <p><b>It reads a known-list file's second-hop neighbours, and only when {@code --second-hop}
 * asks.</b> This third population composes the file's ids with the owner's promotions, folds them
 * onto their canonical side, and visits the acts the graph holds a node for that no other member of
 * that composed population is within the recommender's hop limit of — {@code domain.SecondHop} is
 * the rule and {@code graphCensus --known --isolated} is its other reader. Unlike a {@code --known}
 * run, this one <b>does</b> read ratings: the population it composes with is the recommender's own
 * notion of known, promotions and all. {@code --second-hop} is exclusive with both {@code --known}
 * and {@code --rated-since} — all three name a different population, and the block names one.
 *
 * <p><b>It adds what the file names that the graph lacks, and only when {@code --add} asks.</b> The
 * population is composed exactly as a {@code --known} run composes it; what changes is that an id
 * in it the graph holds no node for is added through {@code expansion.EntityAddition} and then
 * expanded, rather than refused as an unknown entity. {@code --add} needs {@code --known}, refused
 * without it and refused with either of the other two populations too, because both are drawn from
 * the graph and nothing in them can be missing — only a file can name a missing entity. The
 * addition still reaches the graph through {@code IngestService.record} alone, this package's only
 * write (#328, ADR 19).
 */
public final class ExpandCli {

  private static final Logger log = LoggerFactory.getLogger(ExpandCli.class);

  private static final String USAGE =
      "usage: --db <segue.db> [--max-new-edges <n>] [--dry-run] [--rated-since <ISO-8601 instant,"
          + " e.g. 2026-09-06T15:00:00Z>] [--known <file of QIDs>] [--add] [--second-hop <file of"
          + " QIDs>]";

  private ExpandCli() {}

  /**
   * What to expand against, and how far.
   *
   * @param database no default, on purpose — see this class's javadoc
   * @param maxNewEdges the bound handed to every entity's expansion, defaulting to {@link
   *     ExpandContext#defaults()}
   * @param dryRun report what would be visited and touch no network and no log
   * @param add compose additions from the known-list file alongside the population, or false for
   *     none. Requires {@link #known}, because only a file can name an entity the graph lacks
   * @param ratedSince the instant to filter promotions by, or empty for no filter
   * @param known the known-list file whose never-expanded entities are the population, or empty for
   *     the promotions. Never read here: the guide's examples are parsed with an invented home
   * @param secondHop the known-list file whose second-hop neighbours are the population, or empty
   *     for the promotions. Never read here, exactly as {@link #known} is not
   */
  record Options(
      Path database,
      int maxNewEdges,
      boolean dryRun,
      boolean add,
      Optional<Instant> ratedSince,
      Optional<Path> known,
      Optional<Path> secondHop) {}

  /** Parse and validate, refusing anything that could not work before a store is opened. */
  static Options parse(String[] args, String envDatabase, String userHome) {
    Map<String, String> values = new LinkedHashMap<>();
    boolean dryRun = false;
    boolean add = false;

    for (int i = 0; i < args.length; i++) {
      String flag = args[i];
      if ("--dry-run".equals(flag)) {
        dryRun = true;
        continue;
      }
      if ("--add".equals(flag)) {
        add = true;
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

    Instant ratedSince = null;
    String ratedSinceValue = values.remove("--rated-since");
    if (ratedSinceValue != null) {
      ratedSince = instant(ratedSinceValue);
    }

    Path known = null;
    String knownValue = values.remove("--known");
    if (knownValue != null) {
      known = Path.of(knownValue);
    }

    Path secondHop = null;
    String secondHopValue = values.remove("--second-hop");
    if (secondHopValue != null) {
      secondHop = Path.of(secondHopValue);
    }

    if (known != null && ratedSince != null) {
      // Two populations, not two filters over one: --rated-since narrows the promotions and
      // --known replaces them. A run that took both would have to say which one it covered, and
      // the block says exactly one thing (#313).
      throw usage("--known and --rated-since name different populations — give one or neither");
    }
    if (secondHop != null && known != null) {
      throw usage("--second-hop and --known name different populations — give one or neither");
    }
    if (secondHop != null && ratedSince != null) {
      throw usage(
          "--second-hop and --rated-since name different populations — give one or neither");
    }
    if (add && ratedSince != null) {
      // Both of the graph-side populations are, by construction, entities the graph holds a node
      // for — one is KnownList.promoted and the other is a ring read out of the fold's own nodes
      // map — so nothing in either can be missing, and --add would have nothing to do. Only a
      // file can name an entity the graph lacks (#328).
      throw usage("--add and --rated-since name different populations — give one or neither");
    }
    if (add && secondHop != null) {
      throw usage("--add and --second-hop name different populations — give one or neither");
    }
    if (add && known == null) {
      throw usage("--add needs --known — only a file can name an entity the graph lacks");
    }

    if (!values.isEmpty()) {
      throw usage("unknown option " + values.keySet().iterator().next());
    }

    return new Options(
        database,
        maxNewEdges,
        dryRun,
        add,
        Optional.ofNullable(ratedSince),
        Optional.ofNullable(known),
        Optional.ofNullable(secondHop));
  }

  private static int number(String value) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw usage("--max-new-edges takes a whole number, got " + value);
    }
  }

  private static Instant instant(String value) {
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException e) {
      throw usage(
          "--rated-since takes an ISO-8601 instant like 2026-09-06T15:00:00Z, got " + value);
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
      List<String> population;
      Optional<Population> covered;
      if (options.known().isPresent()) {
        // The census's own two folds and its own rule (#311, #313), reused rather than copied:
        // the file's ids on their canonical side, and the log's expansion seeds on the same side,
        // so this tool and graphCensus cannot come to disagree about who has been expanded. A
        // --known run composes no promotions, so it reads no rating at all — ADR 16's
        // minimisation falling out of the shape, exactly as a run with no --rated-since reads no
        // timestamp.
        KnownListInput known = KnownListInput.read(options.known().get());
        List<String> named = merges.canonical(known.qids());
        Expanded expanded = Expanded.in(assertions.readAll()).onTheCanonicalSide(merges);
        // #344. The exclusion is of a local id the graph HOLDS a node for, never of the shape
        // alone: a hand-edited row, or one written against another database, can name a local id
        // the graph has never minted, and no source will ever answer for it either — but it is
        // not one of the owner's own entities until the graph says so. "Holds" is asked the same
        // way ExpandRun.dryRun already asks it for --add's own `to add` bucket (graph.node(qid)),
        // so the two cannot come to disagree about what the graph has.
        population =
            named.stream()
                .filter(qid -> !(LocalEntity.isLocal(qid) && graph.node(qid).isPresent()))
                .filter(qid -> !expanded.covers(qid))
                .toList();
        // #328. options.add() carried onto the population value: a --known run is the only
        // population --add can name, and the clause is composed here, before the run, exactly as
        // KnownNeverExpanded's own javadoc says it must be.
        covered =
            Optional.of(
                new KnownNeverExpanded(
                    known.name(), named.size() - population.size(), options.add()));
        log.info("{} known-list entity(s) to visit", population.size());
      } else if (options.secondHop().isPresent()) {
        // The population is composed ONCE, here, and nothing in the run re-reads it: a run that
        // expands its first entity must not shrink its own list mid-way. The NEXT run is smaller
        // by the rule alone — every entity this run expanded is covered by Expanded, and an
        // isolated act the new edges connected to something known is no longer isolated. No
        // state, no ledger, no --limit: the dry run's `considered` is the bound (#319).
        KnownListInput named = KnownListInput.read(options.secondHop().get());
        // Resolved before the threshold is applied, exactly as the promotions branch does it: a
        // merge leaves two affinity rows naming one thing. A count, never a qid and never a
        // score (ADR 33).
        Map<String, Integer> ratings = merges.resolve(affinity.readRatings());
        log.info("read {} rating(s)", ratings.size());
        // The population with promotions, because that is the recommender's own notion of known
        // (KnownList.promoted is what recommend seeds from) and an act one hop from a promotion
        // is not one the graph cannot place.
        List<String> promoted = KnownList.promoted(merges.canonical(named.qids()), ratings);
        // The log read once, and both answers taken from it — the fold is the boot's own
        // (theReplayingToolsTakeTheBootsFold), never rebuilt through Fold.of.
        List<LoggedAssertion> logged = assertions.readAll();
        Expanded expanded = Expanded.in(logged).onTheCanonicalSide(merges);
        LogProjection projection = LogProjection.of(logged, replay.fold());
        SecondHop rule = SecondHop.of(projection.nodes(), projection.edges(), promoted, expanded);
        population = rule.toExpand();
        covered = Optional.of(new SecondHopNeighbours(named.name(), rule.isolated().size()));
        log.info(
            "{} entity(s) to visit beside {} act(s) the graph cannot place",
            population.size(),
            rule.isolated().size());
      } else {
        // Resolved before the threshold is applied: a merge leaves two affinity rows naming one
        // thing, and promoting both would expand the id the owner retired as well as the one he
        // kept. A count, never a qid and never a score (ADR 33).
        Map<String, Integer> ratings = merges.resolve(affinity.readRatings());
        log.info("read {} rating(s)", ratings.size());
        // KnownList.promoted with no file IS "rated at or above PROMOTION_RATING, ascending by
        // qid" — the threshold and the order from the class that owns both, rather than a second
        // copy of the rule here (issues #106 and #109).
        List<String> promoted = KnownList.promoted(List.of(), ratings);
        // Read only when asked, and resolved through the same merges the ratings were, so the two
        // maps are keyed alike and a promotion's age cannot be read off another row (#276, #307).
        // The set handed over is the PROMOTIONS: a rated entity this tool was never going to
        // visit is not a reason to refuse the run.
        Optional<RatingAge> age =
            options
                .ratedSince()
                .map(
                    since ->
                        RatingAge.of(
                            since,
                            merges.resolveUpdatedAt(affinity.readUpdatedAt()),
                            Set.copyOf(promoted)));
        List<String> promotions =
            age.map(it -> promoted.stream().filter(it::isNew).toList()).orElse(promoted);
        covered = age.map(it -> new RatedSince(it.since(), promoted.size() - promotions.size()));
        population = promotions;
        log.info("{} promotion(s) to visit", promotions.size());
      }

      Clock clock = Clock.systemUTC();
      WikidataEntityResolver resolver = new WikidataEntityResolver(new WikidataClient(), clock);
      // IdentityMerge.NONE and not carryingRatings: an expansion never records a SameAs, so there
      // is nothing for a merge hook to carry — and carryingRatings writes the taste layer, which
      // theExpanderWritesThroughIngestAlone forbids outright.
      IngestService ingest = new IngestService(assertions, graph, IdentityMerge.NONE);
      EntityExpansion expansion =
          new EntityExpansion(resolver, graph, ingest, ExpansionSources.both(resolver, clock));

      ExpandRun run =
          options.add()
              ? new ExpandRun(expansion, graph, new EntityAddition(resolver, ingest))
              : new ExpandRun(expansion, graph);
      // The long arity when a filter was applied and the short one when none was, so both keep a
      // production caller rather than one of them being reachable from tests alone.
      if (options.dryRun()) {
        if (covered.isPresent()) {
          run.dryRun(population, covered, log::info);
        } else {
          run.dryRun(population, log::info);
        }
      } else if (covered.isPresent()) {
        run.run(population, covered, options.maxNewEdges(), log::info);
      } else {
        run.run(population, options.maxNewEdges(), log::info);
      }
    }
  }
}
