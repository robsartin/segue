package com.robsartin.segue.own;

import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.Qid;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.support.RequiredDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The entry point, run from Gradle: {@code ./gradlew ownClaim --args="mint --db
 * $HOME/.segue/segue.db --kind WORK --label 'A Self-Pressed Record'"}.
 *
 * <p><b>{@code $HOME} and not {@code ~}.</b> A tilde does not expand inside double quotes in either
 * zsh or bash, so {@code --args="mint --db ~/.segue/…"} arrives as a literal {@code ~} and the tool
 * dies with {@code no segue database at ~/.segue/segue.db}. This class already argues that a broken
 * example is worse than a broken task name; a pastable one has to survive the quotes it is pasted
 * inside.
 *
 * <p><b>{@code --db} is required</b> (#179). This tool has no default database at all, where every
 * other dev tool falls back to {@code SEGUE_DB} or {@code ${user.home}/.segue/segue.db}. The
 * default is what turned the mistake below into a permanent row. {@code SEGUE_DB} does not satisfy
 * the requirement either: an agent's shell is initialised from the owner's profile and inherits it,
 * so the variable cannot tell the owner apart from an agent running as the owner, and a flag typed
 * per invocation can.
 *
 * <p><b>The task is {@code ownClaim}. This line said {@code own} - the package name - and that is
 * worse than a broken example.</b> Gradle matches abbreviated task names by camel-case hump, so
 * {@code ./gradlew own} does not fail: it resolves to {@code :ownClaim} and runs, against the
 * DEFAULT database, because {@code --db} was not part of the copied line either. Verified against
 * this build: {@code ./gradlew own --args="mint …" --dry-run} prints {@code :ownClaim SKIPPED}. A
 * wrong invocation that errors costs a retype; this one appends a row to a log ADR 19 forbids
 * editing. Every example here names {@code ownClaim} in full, and passes {@code --db} - which this
 * tool now requires outright: {@code ./gradlew own} still resolves to {@code :ownClaim}, and now
 * refuses to do anything.
 *
 * <p><b>Dev-side, and deliberately never a seventh MCP tool.</b> ADR 26 held {@code assert_edge}
 * back until corroboration was visibly working and ADR 56 made it work - but the reason for holding
 * it back now cuts the other way. The caller of an MCP tool is a language model, and an owner claim
 * is exempt from the corroboration count by design (#92, and see {@code EdgeRecord.corroboration}).
 * An MCP {@code assert_edge} would therefore let a model launder model-generated structure into the
 * one tier that skips quarantine, which is precisely what ADR 23 exists to prevent. Dev-side keeps
 * it the owner's, in the shape of {@code rate}, {@code recommend}, {@code listRatings} and {@code
 * retractEntity}.
 *
 * <p>Three operations, because the owner's layer has three claims: {@code mint} a local entity,
 * {@code assert} an edge between two ids, {@code merge} a local id into the Wikidata id it turned
 * out to be. One operation per run, exactly as {@code retractEntity} does one retraction per run -
 * so minting something and then asserting an edge to it is two invocations, and the second sees the
 * first because it replays the log.
 *
 * <p>The second dev-side tool that <b>writes</b> the world-fact layer, after {@code retractEntity}.
 * Its fence says what that means: it may append an owner claim through {@link
 * com.robsartin.segue.ingest.IngestService#claim}, and it may not touch a {@code GraphStore}, an
 * {@code AffinityStore}, an engine, an export or a network.
 */
public final class OwnCli {

  private static final Logger log = LoggerFactory.getLogger(OwnCli.class);

  private static final String USAGE =
      "usage: mint --kind <"
          + kinds()
          + "> --label \"<name>\""
          + " | assert --from <Q…> --to <Q…> --type <CODE>"
          + " | merge --local <Q00…> --canonical <Q…>"
          + " --db <segue.db> [--dry-run]";

  private OwnCli() {}

  /**
   * Which operation, its arguments, and whether to stop short of appending.
   *
   * <p><b>Three records rather than one with six unused components.</b> The operations share only
   * the database and the dry run; a single {@code Options} carrying {@code kind}, {@code label},
   * {@code fromQid}, {@code toQid}, {@code typeCode}, {@code localQid} and {@code canonicalQid}
   * would leave five of seven null on every run and put the question "which of these is set?" in
   * {@code OwnRun} rather than at the command line where it was answered. Sealed, so {@code
   * OwnRun}'s switch is exhaustive and a fourth operation cannot be added without deciding what it
   * does.
   *
   * @param database the log to append to. Required, and named by {@code --db} on every invocation:
   *     this tool has no default, because the default is what turned {@code ./gradlew own} into a
   *     row in the owner's real log (#179). {@code SEGUE_DB} does not satisfy it - an agent's shell
   *     is initialised from the owner's profile and inherits it. The other four dev tools still
   *     default, through {@code support.DefaultDatabase}, which this package deliberately does not
   *     use. It uses {@code support.RequiredDatabase} instead, which owns the refusal sentence and
   *     calls {@code resolve} itself: the rule stays in one place, and this package stays clear of
   *     the class the intended ArchUnit fence names
   * @param dryRun report what would be claimed and append nothing. Not decoration: every operation
   *     here appends a row to a log that is never edited, and two of the three name qids by hand
   */
  public sealed interface Options {

    Path database();

    boolean dryRun();
  }

  /** "This exists, and Wikidata does not model it." The id is allocated by {@link OwnRun}. */
  public record Mint(Path database, NodeKind kind, String label, boolean dryRun)
      implements Options {

    public Mint {
      Objects.requireNonNull(database, "database");
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(label, "label");
    }
  }

  /**
   * "I know this relationship holds."
   *
   * <p>Named {@code Assert} rather than the subcommand string, which is {@code assert} - a Java
   * keyword, and not renameable to dodge that: the word is what the operator types.
   */
  public record Assert(Path database, String fromQid, String toQid, String typeCode, boolean dryRun)
      implements Options {

    public Assert {
      Objects.requireNonNull(database, "database");
      Objects.requireNonNull(fromQid, "fromQid");
      Objects.requireNonNull(toQid, "toQid");
      Objects.requireNonNull(typeCode, "typeCode");
    }
  }

  /** "This local entity turned out to be that Wikidata item." */
  public record Merge(Path database, String localQid, String canonicalQid, boolean dryRun)
      implements Options {

    public Merge {
      Objects.requireNonNull(database, "database");
      Objects.requireNonNull(localQid, "localQid");
      Objects.requireNonNull(canonicalQid, "canonicalQid");
    }
  }

  /**
   * Parse and validate, refusing anything that could not work before a store is opened.
   *
   * <p><b>What is deliberately NOT checked here.</b> The local-entity shape, the canonical side's
   * allocatability and the relation-type vocabulary are all enforced once, by {@code
   * LocalEntity.minted}, {@code SameAs.declared} and {@code OwnerEdge.claimed}. Re-stating any of
   * them here would put a second copy of a rule at the write boundary, which is the copy a future
   * writer misses when the first one moves - the reason Task 2 put those checks in the factories
   * rather than in the constructors in the first place. What this method checks is the shape of the
   * command line itself, plus {@link Qid#looksLikeAQid} so a typo names the flag it was typed
   * against rather than surfacing as a domain message with no flag in it.
   */
  static Options parse(String[] args, String envDatabase, String userHome) {
    if (args.length == 0) {
      throw usage("an operation is required — mint, assert or merge");
    }
    String operation = args[0];
    Map<String, String> values = new LinkedHashMap<>();
    boolean dryRun = false;

    for (int i = 1; i < args.length; i++) {
      String flag = args[i];
      if ("--dry-run".equals(flag)) {
        dryRun = true;
        continue;
      }
      String value = valueOf(args, i, flag);
      i++;
      // --db goes in the same map as every other flag, and is taken out again below. It used to
      // be a special case above this check, which meant `--db a --db b` silently took the last -
      // on the one argument where last-wins is worst, because the operator reads back the first
      // --db they typed and the claim lands in the second database. One check, no siblings to
      // keep in step.
      if (values.put(flag, value) != null) {
        throw usage(flag + " was given twice");
      }
    }

    String given = values.remove("--db");
    if (given == null) {
      throw usage(RequiredDatabase.refusal(envDatabase, userHome));
    }
    Path resolved = Path.of(given);
    return switch (operation) {
      case "mint" -> mint(resolved, values, dryRun);
      case "assert" -> assertion(resolved, values, dryRun);
      case "merge" -> merge(resolved, values, dryRun);
      default -> throw usage("unknown operation " + operation);
    };
  }

  private static Mint mint(Path database, Map<String, String> values, boolean dryRun) {
    NodeKind kind = kind(required(values, "--kind"));
    String label = required(values, "--label");
    if (label.isBlank()) {
      throw usage("--label must say what the entity is called");
    }
    refuseTheRest(values);
    return new Mint(database, kind, label, dryRun);
  }

  private static Assert assertion(Path database, Map<String, String> values, boolean dryRun) {
    String from = qid(values, "--from");
    String to = qid(values, "--to");
    String type = required(values, "--type");
    refuseTheRest(values);
    return new Assert(database, from, to, type, dryRun);
  }

  private static Merge merge(Path database, Map<String, String> values, boolean dryRun) {
    String local = qid(values, "--local");
    String canonical = qid(values, "--canonical");
    refuseTheRest(values);
    return new Merge(database, local, canonical, dryRun);
  }

  /**
   * Refuse anything the operation did not consume.
   *
   * <p>An option belonging to a different operation is the failure this catches: {@code mint --kind
   * WORK --label x --local Q00900042} reads as a merge to whoever typed it, and ignoring the flag
   * would mint an entity instead - silently, into a log nobody may edit.
   */
  private static void refuseTheRest(Map<String, String> values) {
    if (!values.isEmpty()) {
      throw usage("unknown option " + values.keySet().iterator().next() + " for this operation");
    }
  }

  private static String required(Map<String, String> values, String flag) {
    String value = values.remove(flag);
    if (value == null) {
      throw usage(flag + " is required");
    }
    return value;
  }

  private static String qid(Map<String, String> values, String flag) {
    String value = required(values, flag);
    if (!Qid.looksLikeAQid(value)) {
      throw usage(flag + " must look like Q12345, got: " + value);
    }
    return value;
  }

  private static NodeKind kind(String value) {
    try {
      return NodeKind.valueOf(value);
    } catch (IllegalArgumentException notAKind) {
      // Named rather than swallowed: NodeKind is six ontological kinds and deliberately not a
      // domain vocabulary (no MUSICIAN, no FILM), so the first mistake anybody makes here is
      // typing a role. A refusal that does not list the six leaves them guessing.
      throw usage("--kind must be one of " + kinds() + ", got: " + value);
    }
  }

  private static String kinds() {
    return Stream.of(NodeKind.values()).map(Enum::name).collect(Collectors.joining("|"));
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
   * <p>A seam, and not a decorative one: the order of the two refusals below is the behaviour. The
   * missing {@code --db} has to be refused by {@link #parse} before {@code Files.exists} is
   * reached, or the operator is told "no segue database at …" - which reads as a missing file
   * rather than a missing flag, and names a path they never typed. A test can only hold that order
   * if it can supply a home directory of its own; through {@code main} it would have to reach the
   * real one.
   */
  static void run(String[] args, String envDatabase, String userHome) {
    Options options = parse(args, envDatabase, userHome);

    // Refuse a database that is not there rather than creating an empty one and claiming into it:
    // SqliteAssertionLog's constructor creates the file and its schema if absent, which is right
    // for a server starting fresh and wrong for a tool that exists to add to one. It matters more
    // here than in RetractCli - a mistyped --db there retracts nothing and fails; here it would
    // mint the owner's first local entity into a database nobody asked for. ExportCli, RatingsCli,
    // RecommendCli and RetractCli all check the same thing.
    if (!Files.exists(options.database())) {
      throw new IllegalArgumentException(
          "no segue database at " + options.database() + " — nothing to claim against");
    }

    try (AssertionLog assertions = new SqliteAssertionLog(options.database())) {
      new OwnRun(assertions, Clock.systemUTC()).run(options, log::info);
    }
  }
}
