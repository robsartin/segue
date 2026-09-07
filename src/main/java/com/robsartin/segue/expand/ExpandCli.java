package com.robsartin.segue.expand;

import com.robsartin.segue.port.ExpandContext;
import com.robsartin.segue.support.RequiredDatabase;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

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
final class ExpandCli {

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
}
