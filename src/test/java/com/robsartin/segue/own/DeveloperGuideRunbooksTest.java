package com.robsartin.segue.own;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.arch.RepositoryTree;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The developer guide's {@code ownClaim} runbook shows commands the owner is meant to paste. This
 * runs every one of them through {@link OwnCli#parse}, which is the boundary that decides whether a
 * line is correct to type — issue #183, on issue #145's precedent that a committed document is
 * checked against the code rather than trusted.
 *
 * <p><b>Why {@code parse} and not the tool.</b> Running the examples end to end would mint into a
 * database on every {@code check}, and what a runbook has to get right is the command line: the
 * operation, its flags, and {@code --db}. {@code parse} enforces exactly that and opens nothing, so
 * an example that forgot {@code --db} is red here by construction. {@code --dry-run} examples parse
 * like any other; nothing is opened and nothing is run.
 *
 * <p><b>Two things {@code parse} cannot see, asserted separately.</b> A tilde is a valid path
 * character, so {@code --db ~/.segue/segue.db} parses cleanly and then dies in the shell — the
 * guide's own "Write {@code $HOME}, not {@code ~}" rule needs its own assertion. And a guide with
 * no examples at all would pass every parse, so the count of subcommands shown is the vacuity
 * guard.
 *
 * <p><b>This class lives in {@code own} rather than beside the other document tests in {@code
 * arch}</b>, because {@link OwnCli#parse} is package-private — the seam {@code OwnCliTest} drives,
 * and widening it to suit a test would undo the reason it is a seam.
 *
 * <p><b>{@code retractEntity}'s examples are deliberately NOT covered here.</b> {@code
 * RetractCli.parse(String[], String, String)} exists, with exactly the signature this class would
 * need, but it is package-private in {@code com.robsartin.segue.retract} — and this class has to be
 * in {@code own} for the reason above. One test class cannot reach both parsers without widening
 * one of them in production code, which #183 declined to do for a documentation check. If the
 * retraction runbook is ever to get the same treatment, the honest route is a second test class in
 * {@code retract}, not a wider {@code parse}.
 */
class DeveloperGuideRunbooksTest {

  private static final Path ROOT = RepositoryTree.root();
  private static final String GUIDE = RepositoryTree.read(ROOT.resolve("docs/developer-guide.md"));

  /** The home the examples' {@code $HOME} stands for, and the one {@code OwnCliTest} invents. */
  private static final String INVENTED_HOME = "/home/invented";

  /**
   * The shape the guide writes an example in. Load-bearing, as {@code
   * DeveloperGuideEnumerationsTest} says of every shape it parses: an example written some other
   * way is not checked, so the pattern is deliberately the one the runbook uses.
   */
  private static final Pattern EXAMPLE = Pattern.compile("\\./gradlew ownClaim --args=\"(.*)\"");

  private static final List<Example> EXAMPLES = examples();

  /**
   * One pasteable line: where it is, what it says, and the arguments a shell would hand the tool.
   *
   * @param line the 1-based line number in the guide, so a failure can be opened
   * @param text the example exactly as the guide writes it
   * @param arguments the split argument string, {@code $HOME} already expanded
   */
  private record Example(int line, String text, List<String> arguments) {}

  @Test
  @DisplayName("the guide shows at least one ownClaim example of each of the three subcommands")
  void shouldShowEverySubcommandWhenTheGuideRunsThroughOwnClaim() {
    Set<String> shown =
        EXAMPLES.stream()
            .filter(example -> !example.arguments().isEmpty())
            .map(example -> example.arguments().get(0))
            .collect(Collectors.toCollection(TreeSet::new));

    assertThat(shown)
        .as(
            "docs/developer-guide.md — the ownClaim runbook, one ./gradlew ownClaim --args=\"…\""
                + " line per operation. Without this the other two checks pass vacuously on a guide"
                + " that shows nothing")
        .contains("mint", "assert", "merge");
  }

  @Test
  @DisplayName("no ownClaim example writes a tilde where $HOME belongs")
  void shouldWriteHomeRatherThanATildeWhenAnExampleNamesADatabase() {
    List<String> tildes =
        EXAMPLES.stream()
            .filter(example -> example.text().contains("~"))
            .map(example -> "line " + example.line() + ": " + example.text())
            .toList();

    assertThat(tildes)
        .as(
            "docs/developer-guide.md — a tilde does not expand inside the double quotes of"
                + " --args=\"…\", so the example arrives at the tool as a literal ~ and dies with"
                + " \"no segue database at ~/.segue/segue.db\". OwnCli.parse cannot see this,"
                + " because a tilde is a valid path character")
        .isEmpty();
  }

  @Test
  @DisplayName("every ownClaim example parses through the tool's own parser")
  void shouldParseEveryExampleWhenTheGuideShowsAnOwnClaimCommand() {
    List<String> refused = new ArrayList<>();
    for (Example example : EXAMPLES) {
      try {
        OwnCli.parse(example.arguments().toArray(String[]::new), null, INVENTED_HOME);
      } catch (RuntimeException refusal) {
        refused.add(
            "line " + example.line() + ": " + example.text() + "\n    " + refusal.getMessage());
      }
    }

    assertThat(refused)
        .as(
            "docs/developer-guide.md — every ownClaim example is run through OwnCli.parse, the"
                + " boundary that decides whether a line is correct to type. --db is enforced"
                + " there, so an example that forgot it fails here")
        .isEmpty();
  }

  /** Every {@code ownClaim} example the guide shows, in the order it shows them. */
  private static List<Example> examples() {
    List<Example> found = new ArrayList<>();
    String[] lines = GUIDE.split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      Matcher matcher = EXAMPLE.matcher(lines[i]);
      if (matcher.find()) {
        found.add(new Example(i + 1, matcher.group(), split(matcher.group(1))));
      }
    }
    return List.copyOf(found);
  }

  /**
   * Split an {@code --args="…"} string the way the shell hands it to the tool.
   *
   * <p>The outer double quotes are already off, having been what the pattern matched between. What
   * is left is whitespace-separated words, except that a single-quoted run is one argument however
   * many spaces are inside it — which is how every {@code --label} and {@code --reason} in this
   * guide is written, and the only quoting the runbook uses.
   */
  private static List<String> split(String arguments) {
    List<String> words = new ArrayList<>();
    StringBuilder word = new StringBuilder();
    boolean quoted = false;
    boolean started = false;
    for (char c : arguments.replace("$HOME", INVENTED_HOME).toCharArray()) {
      if (c == '\'') {
        quoted = !quoted;
        started = true;
      } else if (!quoted && Character.isWhitespace(c)) {
        if (started) {
          words.add(word.toString());
          word.setLength(0);
          started = false;
        }
      } else {
        word.append(c);
        started = true;
      }
    }
    if (started) {
      words.add(word.toString());
    }
    return List.copyOf(words);
  }
}
