package com.robsartin.segue.seed;

import com.robsartin.segue.wikidata.WikidataClient;
import com.robsartin.segue.wikidata.WikidataEntityResolver;
import java.nio.file.Path;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The entry point, run from Gradle: {@code ./gradlew resolveNames --args="--list <path>"}.
 *
 * <p><b>Not an MCP tool, deliberately.</b> ADR 26 pins the surface at six tools and argues the size
 * of that surface is the point; bulk import is an authoring concern that happens once, for a file
 * that lives outside the repository, and it would be the seventh tool with nothing to say for
 * itself. It is also not something a model should be driving: nine hundred names is a batch job
 * with a resume file, not a conversation.
 *
 * <p><b>No {@code System.out}.</b> ADR 30 makes SLF4J the only logging API and an ArchUnit rule
 * enforces it across the whole project, including here — this tool has no protocol on stdout to
 * protect, but a second class reaching for {@code System.out} is exactly what that rule exists to
 * catch, and a dev tool is not a reason to make an exception.
 */
public final class SeedCli {

  private static final Logger log = LoggerFactory.getLogger(SeedCli.class);

  private static final String USAGE =
      "usage: --list <names.csv> [--mapping <out.csv>] [--review <out.csv>]"
          + " [--chunk <acts per write>] [--candidates <per spelling>]";

  private SeedCli() {}

  /**
   * Where the tool reads and writes.
   *
   * <p>Every path defaults to a sibling of the input list, which lives outside this repository and
   * must stay there: the list and the mapping are personal data under ADR 33, and this repository
   * is public (issue #37).
   */
  public record Options(Path list, Path mapping, Path review, int chunkSize, int candidates) {

    public Options {
      Objects.requireNonNull(list, "list");
      Objects.requireNonNull(mapping, "mapping");
      Objects.requireNonNull(review, "review");
    }
  }

  static Options parse(String[] args) {
    Path list = null;
    Path mapping = null;
    Path review = null;
    int chunkSize = 25;
    int candidates = 7;
    for (int i = 0; i < args.length; i += 2) {
      String flag = args[i];
      if (i + 1 >= args.length) {
        throw new IllegalArgumentException(flag + " needs a value. " + USAGE);
      }
      String value = args[i + 1];
      switch (flag) {
        case "--list" -> list = Path.of(value);
        case "--mapping" -> mapping = Path.of(value);
        case "--review" -> review = Path.of(value);
        case "--chunk" -> chunkSize = Integer.parseInt(value);
        case "--candidates" -> candidates = Integer.parseInt(value);
        default -> throw new IllegalArgumentException("unknown option " + flag + ". " + USAGE);
      }
    }
    if (list == null) {
      throw new IllegalArgumentException("--list is required. " + USAGE);
    }
    return new Options(
        list,
        mapping == null ? sibling(list, "-qids.csv") : mapping,
        review == null ? sibling(list, "-review.csv") : review,
        chunkSize,
        candidates);
  }

  private static Path sibling(Path list, String suffix) {
    String name = list.getFileName().toString();
    int dot = name.lastIndexOf('.');
    return list.resolveSibling((dot < 0 ? name : name.substring(0, dot)) + suffix);
  }

  public static void main(String[] args) {
    Options options = parse(args);
    WikidataClient client = new WikidataClient();
    SeedRun run =
        new SeedRun(
            new SeedResolver(
                new WikidataEntityResolver(client),
                new WikidataFacts(client),
                options.candidates()),
            options.mapping(),
            options.review(),
            options.chunkSize());
    SeedSummary summary = run.run(SeedFiles.readList(options.list()));
    summary.lines().forEach(log::info);
    log.info("mapping: {}", options.mapping());
    log.info("review:  {}", options.review());
  }
}
