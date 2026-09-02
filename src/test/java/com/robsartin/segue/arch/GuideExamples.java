package com.robsartin.segue.arch;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code ./gradlew <task> --args="…"} lines one runbook chapter shows, extracted from the
 * developer guide and split the way a shell would split them — issue #183, so each tool's runbook
 * can be run through that tool's own parser.
 *
 * <p><b>Shared rather than copied, and the reason is the one this package keeps meeting.</b> Two
 * runbooks are checked this way and their parsers are package-private in two different packages, so
 * the tests cannot share a package. Without this class the second test would carry a second copy of
 * the extraction, and the second copy of a rule is the one a future editor misses — {@link
 * RepositoryTree}'s own reason for existing.
 *
 * <p><b>A continued line is joined before it is read, and anything this class cannot read is a
 * failure rather than a skip.</b> That rule was reached three times, by measurement, and each time
 * the recogniser had been written as "the shapes I can parse". A {@code merge} example wrapped with
 * a trailing backslash slipped past; joining fixed that one. A single-quoted {@code --args='…'}
 * slipped past next. Then {@code --args ="…"}, with a space before the equals, slipped past the fix
 * for <em>that</em> - each time carrying a flag belonging to another operation, and each time green
 * in seconds.
 *
 * <p><b>So {@code mention} is deliberately wider than any argument syntax, and nothing about {@code
 * --args} belongs in it.</b> A line is a mention when it contains {@code ./gradlew} and the task
 * name as a whole word, colon-prefixed or not, whatever follows. <b>The only way a task invocation
 * escapes this check is by not naming the task.</b> {@code complete} stays strict - a double-quoted
 * {@code --args="…"}, closed on the line or continued with a backslash - and every mention that is
 * not complete lands in {@link #unreadableExamples()} naming the line and the shape required.
 *
 * <p><b>A mention with no {@code --args} anywhere on it is prose, and is allowed.</b> The guide
 * genuinely says things like "run as {@code ./gradlew ownClaim}" in its package table and "{@code
 * ./gradlew own} resolves to {@code :ownClaim}" in the layering section. Those sentences describe
 * the task rather than showing a command, there is nothing in them to run through a parser, and
 * failing them would be a check nobody could keep green. Anything carrying an {@code --args} token
 * in any spelling is a command, and is held to {@code complete}.
 *
 * <p><b>Single-quoted outer strings are refused rather than supported</b>, and the guide's own rule
 * is why: {@code $HOME} does not expand inside single quotes in either zsh or bash, so {@code
 * --args='--db $HOME/.segue/segue.db'} reaches the tool as a literal {@code $HOME} and dies. That
 * is the tilde defect one quote further out, so the refusal says which shape to use rather than
 * teaching this class to split a form the guide must not contain.
 */
public final class GuideExamples {

  /** The home directory the examples' {@code $HOME} stands for, invented so no real one is read. */
  public static final String INVENTED_HOME = "/home/invented";

  private static final String GUIDE = "docs/developer-guide.md";

  /**
   * One pasteable line: where it is, what it says, and the arguments a shell would hand the tool.
   *
   * @param line the 1-based line the example starts on, so a failure can be opened
   * @param text the example as the guide writes it, with any continuation joined
   * @param arguments the split argument string, {@code $HOME} already expanded
   */
  public record Example(int line, String text, List<String> arguments) {}

  private final List<Example> examples;
  private final List<String> unreadableExamples;

  private GuideExamples(List<Example> examples, List<String> unreadableExamples) {
    this.examples = List.copyOf(examples);
    this.unreadableExamples = List.copyOf(unreadableExamples);
  }

  /** Every example the guide shows for one Gradle task, in the order it shows them. */
  public static GuideExamples of(String taskName) {
    // The widest true statement, and no argument syntax in it at all: this names the task. Three
    // rounds of "the shapes I can parse" each left the next shape invisible.
    Pattern mention =
        Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(taskName) + "(?![A-Za-z0-9_])");
    // A colon-prefixed task is a legitimate invocation, so it is parsed rather than refused.
    Pattern complete =
        Pattern.compile("\\./gradlew :?" + Pattern.quote(taskName) + " --args=\"(.*)\"");
    String[] lines = RepositoryTree.read(RepositoryTree.root().resolve(GUIDE)).split("\n", -1);

    List<Example> examples = new ArrayList<>();
    List<String> unreadable = new ArrayList<>();
    for (int i = 0; i < lines.length; i++) {
      if (!lines[i].contains("./gradlew") || !mention.matcher(lines[i]).find()) {
        continue;
      }
      StringBuilder joined = new StringBuilder(lines[i]);
      int last = i;
      // A backslash at the end of a line removes the newline, so what the tool receives is this
      // line without the backslash followed by the next one exactly as written - indentation
      // included, which is what separates the two halves once the arguments are split.
      while (joined.charAt(joined.length() - 1) == '\\' && last + 1 < lines.length) {
        joined.setLength(joined.length() - 1);
        joined.append(lines[++last]);
      }
      String text = joined.toString();
      Matcher matcher = complete.matcher(text);
      if (matcher.find()) {
        examples.add(new Example(i + 1, matcher.group(), split(matcher.group(1))));
      } else if (!text.contains("--args")) {
        // Prose naming the task rather than showing a command - see the class javadoc.
        i = last;
        continue;
      } else {
        unreadable.add(
            "line "
                + (i + 1)
                + ": "
                + text
                + "\n    not read. A command must be spelled --args=\"…\" - double quotes, no"
                + " space around the equals - closed on the same line or continued with a trailing"
                + " backslash. Single quotes are refused: $HOME does not expand inside them in zsh"
                + " or bash, so the line would not be pasteable. A line naming the task with no"
                + " --args at all is prose and is allowed.");
      }
      i = last;
    }
    return new GuideExamples(examples, unreadable);
  }

  public List<Example> examples() {
    return examples;
  }

  /**
   * Every line mentioning {@code ./gradlew <task> --args=} that this class could not read as an
   * example - an unclosed double quote, a single-quoted outer string, no quote at all.
   *
   * <p>Not a skip, deliberately. An example this class cannot read is an example nothing checks,
   * which is the silent hole both the wrapped-line and the single-quoted plants found.
   */
  public List<String> unreadableExamples() {
    return unreadableExamples;
  }

  /**
   * Every example that writes a tilde.
   *
   * <p>The parsers cannot see this, because a tilde is a valid path character: it does not expand
   * inside the double quotes of {@code --args="…"}, so the example arrives at the tool as a literal
   * {@code ~} and dies with {@code no segue database at ~/.segue/segue.db}.
   */
  public List<String> withATilde() {
    return examples.stream()
        .filter(example -> example.text().contains("~"))
        .map(example -> "line " + example.line() + ": " + example.text())
        .toList();
  }

  /**
   * Split an {@code --args="…"} string the way the shell hands it to the tool.
   *
   * <p>The outer double quotes are already off, having been what the pattern matched between. What
   * is left is whitespace-separated words, except that a single-quoted run is one argument however
   * many spaces are inside it — which is how every {@code --label} and {@code --reason} in this
   * guide is written, and the only quoting the runbooks use.
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
