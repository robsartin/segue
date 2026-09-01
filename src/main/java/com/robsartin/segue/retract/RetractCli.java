package com.robsartin.segue.retract;

import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.support.RequiredDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The entry point, run from Gradle: {@code ./gradlew retractEntity --args="--db
 * $HOME/.segue/segue.db --qid Q… --reason …"}.
 *
 * <p><b>{@code $HOME} and not {@code ~} in that example, and it is not a style choice.</b> A tilde
 * does not expand inside double quotes in either zsh or bash, so {@code --args="--db ~/.segue/…"}
 * arrives as the literal four characters {@code ~/.s…} and the tool dies with {@code no segue
 * database at ~/.segue/segue.db}. An example that cannot be pasted is the failure this class's next
 * paragraph is about.
 *
 * <p><b>{@code --db} is required</b> (#179). Every other dev tool defaults to {@code SEGUE_DB} or
 * {@code ${user.home}/.segue/segue.db}; this one refuses to run without being told which database
 * to append to, because a retraction lands in a log ADR 19 forbids editing - it cannot be taken
 * back, only appended over. {@code SEGUE_DB} does not satisfy the requirement: an agent's shell is
 * initialised from the owner's profile and inherits it, so it cannot tell the owner apart from an
 * agent running as the owner.
 *
 * <p><b>Deliberately not a seventh MCP tool.</b> ADR 26 pins the surface at six, and #5, ADR 40,
 * ADR 41 and ADR 43 each declined a seventh for lighter reasons than this one. Retraction is the
 * heaviest of them: the caller of an MCP tool is a language model, and a model that can propose
 * retractions of its own is a different and much larger question than "can a wrong entity be taken
 * back out" - ADR 44 deliberately does not open it. This is the owner's judgement about their own
 * graph, made on their own machine.
 *
 * <p>Fourth of four dev-side tools, after {@code seed} (ADR 40), {@code export} (ADR 41) and {@code
 * ratings} (ADR 43) - and the first one that writes. Its fence says exactly what that means: it may
 * append a retraction through {@link com.robsartin.segue.ingest.IngestService}, and it may not
 * touch a {@code GraphStore}, an {@code AffinityStore}, an engine, an export or a network. See
 * {@code ArchitectureTest.theRetractionToolWritesOnlyRetractions}.
 */
public final class RetractCli {

  private static final Logger log = LoggerFactory.getLogger(RetractCli.class);

  private static final Pattern QID = Pattern.compile("Q\\d+");

  private static final String USAGE =
      "usage: --db <segue.db> --qid <Q12345> --reason \"<why>\" [--dry-run]";

  private RetractCli() {}

  /**
   * Which entity, why, and whether to stop short of appending.
   *
   * @param database the log to append to. Required, and named by {@code --db} on every invocation:
   *     this tool has no default, because the default was the hole in #179. An agent's shell is
   *     initialised from the owner's profile, so {@code SEGUE_DB} is inherited and cannot stand in
   *     for a flag that is typed each time. Every dev tool that does still default goes through
   *     {@code support.DefaultDatabase.resolve}, so grepping for that call is what says which ones
   *     they are; this package deliberately does not use it. It uses {@code
   *     support.RequiredDatabase} instead, which owns the refusal sentence and calls {@code
   *     resolve} itself: the rule stays in one place, and this package stays clear of the class
   *     {@code ArchitectureTest.theClaimToolsHaveNoDefaultDatabase} names
   * @param reason required. The value of keeping a retraction in an append-only log is that it
   *     records what we concluded and why; there is no editing one afterwards to add the why
   * @param dryRun report what the retraction would reach and append nothing. Not decoration: this
   *     tool's whole subject is a QID that turned out to be the wrong entity, and it leaves no
   *     output file to check afterwards
   */
  public record Options(Path database, String qid, String reason, boolean dryRun) {

    public Options {
      Objects.requireNonNull(database, "database");
      Objects.requireNonNull(qid, "qid");
      Objects.requireNonNull(reason, "reason");
    }
  }

  /** Parse and validate, refusing anything that could not work before a store is opened. */
  static Options parse(String[] args, String envDatabase, String userHome) {
    Path database = null;
    String qid = null;
    String reason = null;
    boolean dryRun = false;

    for (int i = 0; i < args.length; i++) {
      String flag = args[i];
      if ("--dry-run".equals(flag)) {
        dryRun = true;
        continue;
      }
      String value = valueOf(args, i, flag);
      i++;
      switch (flag) {
        case "--db" -> database = Path.of(value);
        case "--qid" -> qid = value;
        case "--reason" -> reason = value;
        default -> throw usage("unknown option " + flag);
      }
    }

    if (database == null) {
      throw usage(RequiredDatabase.refusal(envDatabase, userHome));
    }
    if (qid == null) {
      throw usage("--qid is required");
    }
    if (!QID.matcher(qid).matches()) {
      throw usage("--qid must look like Q12345, got: " + qid);
    }
    if (reason == null || reason.isBlank()) {
      throw usage("--reason is required — the log records why, and is never edited afterwards");
    }
    return new Options(database, qid, reason, dryRun);
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

    // Refuse a database that is not there rather than creating an empty one and retracting
    // nothing: SqliteAssertionLog's constructor creates the file and its schema if absent, which
    // is right for a server starting fresh and wrong for a tool that exists to change one.
    // ExportCli and RatingsCli check the same thing for the same reason.
    if (!Files.exists(options.database())) {
      throw new IllegalArgumentException(
          "no segue database at " + options.database() + " — nothing to retract");
    }

    try (AssertionLog assertions = new SqliteAssertionLog(options.database())) {
      new RetractRun(assertions, Clock.systemUTC()).run(options, log::info);
    }
  }
}
