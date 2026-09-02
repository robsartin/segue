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
 * failure rather than a skip.</b> That rule was reached twice, by measurement. A {@code merge}
 * example wrapped with a trailing backslash, carrying a flag belonging to another operation, was
 * extracted by nothing and passed silently - and the guide's example lines already run to 118
 * columns, so wrapping one is the likely next edit. Joining fixed that shape, and a single-quoted
 * {@code --args='…'} carrying the same wrong flag then passed just as silently. Narrowing the
 * recogniser is what keeps producing the hole, so the loud check is the widest one available:
 * <b>every line mentioning {@code ./gradlew <task> --args=} must be one this class fully
 * consumed</b>, and everything else lands in {@link #unreadableExamples()} naming the line.
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
    // The widest recogniser there is: any mention of the task and --args= at all. A narrower one
    // is what let a wrapped example and then a single-quoted one through, each time by failing to
    // match rather than by failing.
    String mention = "./gradlew " + taskName + " --args=";
    Pattern complete =
        Pattern.compile("\\./gradlew " + Pattern.quote(taskName) + " --args=\"(.*)\"");
    String[] lines = RepositoryTree.read(RepositoryTree.root().resolve(GUIDE)).split("\n", -1);

    List<Example> examples = new ArrayList<>();
    List<String> unreadable = new ArrayList<>();
    for (int i = 0; i < lines.length; i++) {
      if (!lines[i].contains(mention)) {
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
      } else {
        unreadable.add(
            "line "
                + (i + 1)
                + ": "
                + text
                + "\n    not read. An example must be a double-quoted --args=\"…\", closed on the"
                + " same line or continued with a trailing backslash. Single quotes are refused:"
                + " $HOME does not expand inside them in zsh or bash, so the line would not be"
                + " pasteable.");
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
