package com.robsartin.segue.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every relative link in {@code README.md} and {@code docs/**}{@code /*.md} resolves — the file it
 * names exists, and the {@code #anchor} it names is a heading in that file. Issue #168.
 *
 * <p>Nothing in the build read a documentation link until this class. {@link
 * DeveloperGuideEnumerationsTest} checks the guide's enumerations against the code and {@link
 * AdrIndexTest} checks the index's rows against the ADR files, but a link to a filename that does
 * not exist passed every gate: the sweep that opened #168 found two, both in the developer guide,
 * both naming {@code adr/0042-store-p31-and-rederive-kind.md} for a file whose name ends {@code
 * -at-projection.md}.
 *
 * <p><b>The slug rule is a parser feeding a guard</b>, so it is written out rather than
 * approximated. GitHub lowercases a heading, drops every character that is not a letter, digit,
 * space, hyphen or underscore, turns spaces into hyphens, and does <i>not</i> collapse the doubled
 * hyphen an em dash leaves behind; a repeated heading in one file gets {@code -1}, {@code -2}. A
 * slugger that gets this wrong either cries wolf on the guide's em-dash heading or, worse, passes a
 * genuinely wrong anchor.
 *
 * <p><b>A link inside code is not a link.</b> Fenced blocks and inline code spans are removed
 * before any matching: {@code docs/adr/0001-record-architecture-decisions.md} shows the template
 * {@code Superseded by [NNNN](...)} inside backticks, and a checker that reads it as a link reds on
 * a template — whereupon the fix somebody reaches for is to weaken the check.
 *
 * <p><b>Nothing link-shaped escapes quietly.</b> The strict pattern below reads one shape, {@code
 * [text](target)}, and a wider <i>mention</i> predicate reds on every {@code ](}, {@code ][} and
 * reference definition it did not consume. Three CommonMark shapes the strict pattern cannot read —
 * a link text containing {@code ]}, a titled {@code [t](path "Title")}, and the reference-style
 * {@code [t][ref]} — occur zero times today and would otherwise have vanished with no output. They
 * now fail loudly, which is the difference between "not present" and "not seen": a lenient matcher
 * feeding a guard turns "cannot read this" into "there is nothing here".
 *
 * <p>What is deliberately NOT checked, so nobody reads more assurance into this class than it
 * gives: {@code http}, {@code https} and {@code mailto} targets reach the network and are skipped,
 * and link <i>text</i> is prose. Only ATX ({@code # }) headings define anchors; the repository has
 * no setext headings, and the {@code ---} lines that look like one are front-matter closers or sit
 * inside a fence. That list is now complete by construction rather than by inspection — a shape
 * missing from it fails the second test instead of passing the first.
 */
class DocumentationLinksTest {

  private static final Path ROOT = RepositoryTree.root();

  /** {@code [text](target)} — the target runs to the first {@code )} and holds no whitespace. */
  private static final Pattern LINK = Pattern.compile("\\[[^\\]]*]\\(([^)\\s]+)\\)");

  /**
   * {@code ### Heading}, ATX only, with CommonMark's optional closing run of {@code #} dropped —
   * {@code ## H ##} is the heading {@code H}. The run must be preceded by whitespace, so a heading
   * that genuinely ends in a hash ({@code ### C#}) keeps it.
   */
  private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)(?:\\s+#+)?\\s*$");

  /** {@code ```java} or {@code ~~~}, indented or not. */
  private static final Pattern FENCE = Pattern.compile("^\\s*(`{3,}|~{3,})(.*)$");

  /** A run of backticks and everything up to the matching run, on one line. */
  private static final Pattern CODE_SPAN = Pattern.compile("(`+)(?:(?!\\1).)*\\1");

  /** {@code [ref]: target} at the head of a line — a reference definition, which is not read. */
  private static final Pattern REFERENCE_DEFINITION = Pattern.compile("^ {0,3}\\[[^\\]]*]:");

  /** What every supported shape is written as, quoted back when an unsupported one is found. */
  private static final String SUPPORTED =
      "unsupported link shape — write [text](target) whole and on one line, with no title, no"
          + " nested brackets, no reference labels";

  /** Targets that leave the repository. Checking them would put the network in the build. */
  private static final List<String> EXTERNAL = List.of("http://", "https://", "mailto:");

  /** One {@code [text](target)} found outside code, with where it was found. */
  private record Link(Path file, int line, String target) {
    String describe(String problem) {
      return "%s:%d  %s  — %s".formatted(ROOT.relativize(file), line, target, problem);
    }
  }

  /** {@code README.md} and every {@code .md} under {@code docs/}, walked once, sorted. */
  private static final List<Path> DOCUMENTS = documents();

  private static final Scan SCAN = scan(DOCUMENTS);
  private static final List<Link> LINKS = SCAN.links();

  @Test
  @DisplayName("every relative link in the documentation resolves to a file and a heading")
  void shouldResolveEveryTargetWhenTheDocumentationLinksAreFollowed() {
    List<String> broken = new ArrayList<>();
    for (Link link : LINKS) {
      String problem = resolve(link);
      if (problem != null) {
        broken.add(link.describe(problem));
      }
    }

    assertThat(broken)
        .as(
            "documentation links that do not resolve, out of %d relative links in %d documents."
                + " The document is the source and the link is what gets corrected. A path is"
                + " resolved against the linking file's own directory; an anchor is matched"
                + " against the target file's headings under GitHub's slug rule, repeats"
                + " suffixed -1, -2",
            LINKS.size(), DOCUMENTS.size())
        .isEmpty();
  }

  @Test
  @DisplayName("nothing link-shaped in the documentation is a shape this test cannot read")
  void shouldReadEveryLinkShapedThingWhenTheDocumentationIsSwept() {
    assertThat(SCAN.unsupported())
        .as(
            "link-shaped text the strict [text](target) pattern did not consume, in %d documents."
                + " A shape the matcher cannot read must fail, not vanish: a link the sweep never"
                + " saw is indistinguishable from a link that resolves, and that is the whole"
                + " defect this class exists to close. Rewrite the link, or widen LINK and add a"
                + " control for the new shape",
            DOCUMENTS.size())
        .isEmpty();
  }

  @Test
  @DisplayName("the sweep actually read the two documents most likely to break")
  void shouldHaveCheckedLinksInTheReadmeAndTheGuideWhenTheSweepRan() {
    assertThat(linksIn("README.md"))
        .as(
            "relative links found in README.md — zero means the walk or the matcher stopped working")
        .isNotEmpty();
    assertThat(linksIn("docs/developer-guide.md"))
        .as(
            "relative links found in docs/developer-guide.md — zero means the walk or the matcher"
                + " stopped working")
        .isNotEmpty();
  }

  private static List<String> linksIn(String relative) {
    Path file = ROOT.resolve(relative);
    return LINKS.stream().filter(l -> l.file().equals(file)).map(Link::target).toList();
  }

  /** The problem with this link, or {@code null} if it resolves. */
  private static String resolve(Link link) {
    String target = link.target();
    int hash = target.indexOf('#');
    String path = hash < 0 ? target : target.substring(0, hash);
    String anchor = hash < 0 ? "" : target.substring(hash + 1);

    Path targetFile = path.isEmpty() ? link.file() : link.file().getParent().resolve(path);
    if (!Files.isRegularFile(targetFile)) {
      return "missing file " + normalize(targetFile);
    }
    if (anchor.isEmpty()) {
      return null;
    }
    if (!anchors(targetFile).contains(anchor)) {
      return "no heading '%s' in %s".formatted(anchor, normalize(targetFile));
    }
    return null;
  }

  private static String normalize(Path path) {
    Path absolute = path.toAbsolutePath().normalize();
    return absolute.startsWith(ROOT) ? ROOT.relativize(absolute).toString() : absolute.toString();
  }

  /** Every anchor the target file defines, computed once per file. */
  private static final Map<Path, Set<String>> ANCHORS = new LinkedHashMap<>();

  private static Set<String> anchors(Path file) {
    return ANCHORS.computeIfAbsent(file.toAbsolutePath().normalize(), DocumentationLinksTest::read);
  }

  private static Set<String> read(Path file) {
    Set<String> anchors = new LinkedHashSet<>();
    Map<String, Integer> seen = new LinkedHashMap<>();
    for (String line : outsideFences(RepositoryTree.read(file))) {
      Matcher heading = HEADING.matcher(line);
      if (!heading.matches()) {
        continue;
      }
      String slug = slug(heading.group(2));
      int repeat = seen.merge(slug, 0, (a, b) -> a + 1);
      anchors.add(repeat == 0 ? slug : slug + "-" + repeat);
    }
    return anchors;
  }

  /**
   * GitHub's heading slug: lowercase, drop everything that is not a letter, digit, space, hyphen or
   * underscore, spaces become hyphens. Nothing is collapsed — the doubled hyphen an em dash leaves
   * between two spaces survives, which is why {@code #expanding-a-top-candidate-demotes-it--expand}
   * is the correct anchor and not a typo.
   */
  private static String slug(String heading) {
    StringBuilder slug = new StringBuilder();
    for (char c : heading.toLowerCase(Locale.ROOT).toCharArray()) {
      if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
        slug.append(c);
      } else if (c == ' ') {
        slug.append('-');
      }
    }
    return slug.toString();
  }

  /** One pass over the documents: the links it could read, and the shapes it could not. */
  private record Scan(List<Link> links, List<String> unsupported) {}

  private static Scan scan(List<Path> documents) {
    List<Link> links = new ArrayList<>();
    List<String> unsupported = new ArrayList<>();
    for (Path document : documents) {
      List<String> lines = outsideFences(RepositoryTree.read(document));
      for (int i = 0; i < lines.size(); i++) {
        String line = withoutCodeSpans(lines.get(i));
        Set<Integer> consumed = new LinkedHashSet<>();
        Matcher link = LINK.matcher(line);
        while (link.find()) {
          consumed.add(link.start(1) - 2); // the index of the `]` in this match's `](`
          String target = link.group(1);
          if (EXTERNAL.stream().noneMatch(target::startsWith)) {
            links.add(new Link(document, i + 1, target));
          }
        }
        for (String mention : mentions(line, consumed)) {
          unsupported.add("%s:%d  %s".formatted(ROOT.relativize(document), i + 1, mention));
        }
      }
    }
    return new Scan(List.copyOf(links), List.copyOf(unsupported));
  }

  /**
   * Everything on this line that looks like a link but is not one the strict pattern read: a {@code
   * ](} it did not consume, any {@code ][}, and a line-leading reference definition.
   */
  private static List<String> mentions(String line, Set<Integer> consumed) {
    List<String> found = new ArrayList<>();
    for (int at = line.indexOf("]("); at >= 0; at = line.indexOf("](", at + 1)) {
      if (!consumed.contains(at)) {
        found.add(SUPPORTED);
      }
    }
    for (int at = line.indexOf("]["); at >= 0; at = line.indexOf("][", at + 1)) {
      found.add(SUPPORTED);
    }
    if (REFERENCE_DEFINITION.matcher(line).find()) {
      found.add(SUPPORTED);
    }
    return found;
  }

  /**
   * The document's lines with every fenced block blanked out, so that line numbers still count and
   * neither a {@code # heading} nor a {@code [link](target)} inside a code block is read as one.
   */
  private static List<String> outsideFences(String text) {
    List<String> lines = new ArrayList<>();
    String open = null;
    for (String line : text.split("\n", -1)) {
      Matcher fence = FENCE.matcher(line);
      if (open == null && fence.matches()) {
        open = fence.group(1);
        lines.add("");
        continue;
      }
      if (open != null) {
        boolean closes =
            fence.matches()
                && fence.group(1).charAt(0) == open.charAt(0)
                && fence.group(1).length() >= open.length()
                && fence.group(2).isBlank();
        lines.add("");
        if (closes) {
          open = null;
        }
        continue;
      }
      lines.add(line);
    }
    return lines;
  }

  /** The line with every inline code span blanked out — see the class comment on ADR 1. */
  private static String withoutCodeSpans(String line) {
    return CODE_SPAN.matcher(line).replaceAll("");
  }

  /** {@code README.md} and every {@code .md} under {@code docs/}, sorted for a stable order. */
  private static List<Path> documents() {
    try (Stream<Path> tree = Files.walk(ROOT.resolve("docs"))) {
      return Stream.concat(
              Stream.of(ROOT.resolve("README.md")),
              tree.filter(Files::isRegularFile)
                  .filter(p -> p.getFileName().toString().endsWith(".md")))
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
