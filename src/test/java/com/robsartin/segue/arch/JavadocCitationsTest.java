package com.robsartin.segue.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every citation of a test from {@code src/main} javadoc resolves — the class it names is a file
 * under {@code src/test/java}, and the member it names is declared there. Issue #195.
 *
 * <p>Main-source javadoc names the test that enforces a rule, because {@code @link} cannot reach
 * the test source set: the test compilation depends on main, not the other way round, and putting
 * the test classes on the javadoc classpath to make the link work would invert that and publish
 * test types into the API documentation's link space. So the citation is prose in a code span, and
 * prose is what nothing checked until this class. A renamed {@link ArchitectureTest} rule used to
 * drift silently in every place it was named — five files name {@link
 * ArchitectureTest#theExporterOnlyReads} alone.
 *
 * <p><b>A citation wraps, and the wrap carries a leading asterisk.</b> Twenty of the citations in
 * the tree today sit on the second line of a javadoc span, so the raw text between the braces reads
 * {@code "\n * ArchitectureTest.seedNeverOpensAStore"}. A per-line matcher sees no citation at all
 * on either line and reports the file clean — which is how the design note's own sweep counted 31
 * sites where there are 51. The scan below therefore reads whole files, joins the wrap, and drops
 * the continuation asterisk before matching.
 *
 * <p><b>Nothing citation-shaped escapes quietly.</b> The strict pattern reads one shape — an
 * optional lowercase package prefix, a simple name containing {@code Test}, an optional member
 * joined by {@code .} or {@code #}, optional empty parentheses — and a wider <i>mention</i>
 * predicate reds on every remaining span whose text contains {@code Test} at all. Shapes that occur
 * zero times today and would otherwise vanish with no output: a nested class ({@code
 * OuterTest.InnerTest}, which parses as a member and then fails to resolve), a generic parameter, a
 * whitespace-separated {@code ArchitectureTest theRule}, an argument list inside the parentheses.
 * They fail loudly instead. A lenient matcher feeding a guard turns "cannot read this" into "there
 * is nothing here", which is the defect this class exists to close, not to reproduce.
 *
 * <p><b>One exclusion, named:</b> a span whose whole text is an annotation —
 * {@code @SpringBootTest} in {@link com.robsartin.segue.app.SegueApplication} is the single
 * occurrence — is not a citation of anything in {@code src/test}. It names a type on the compile
 * classpath, and the leading {@code @} is what says so.
 *
 * <p>What is deliberately NOT checked, so nobody reads more assurance into this class than it
 * gives. The ~110 {@code MainClass.member} citations of <i>main</i> classes are out of scope: now
 * that {@code javadoc} gates, those can become real {@code @link} tags and be checked by the
 * compiler, which is a better guarantee than this one and a separate piece of work. A package
 * prefix is not verified against the file's directory — the class is found by simple name, which is
 * unambiguous today because no two test files share one. A member is matched by declaration text, a
 * {@code void name(} method or a {@code static final … name =} field, not by loading the class:
 * matching the source keeps the rule readable in the failure message, and a citation of an
 * inherited or generated member would red rather than pass. And a {@code @code} span is read
 * wherever it occurs in a main source file, javadoc or string literal alike; that errs strict.
 */
class JavadocCitationsTest {

  private static final Path ROOT = RepositoryTree.root();

  /**
   * An inline code span and everything up to its closing brace. Reluctant, so nested spans on one
   * line stay separate; the brace is what ends it, as javadoc has no escape for one inside.
   */
  private static final Pattern CODE_SPAN = Pattern.compile("\\{@code(.*?)}", Pattern.DOTALL);

  /**
   * The one citation shape this class reads: an optional package prefix of lowercase segments, a
   * simple name containing {@code Test}, an optional member joined by {@code .} or {@code #}, and
   * optional empty parentheses. Anchored — a partial match is not a match.
   */
  private static final Pattern CITATION =
      Pattern.compile(
          "(?:[a-z][A-Za-z0-9_]*(?:\\.[a-z][A-Za-z0-9_]*)*\\.)?"
              + "([A-Z][A-Za-z0-9_]*Test[A-Za-z0-9_]*)"
              + "(?:[.#]([A-Za-z_][A-Za-z0-9_]*))?"
              + "(?:\\(\\))?");

  /** A span that is wholly an annotation name — see the class comment on the one exclusion. */
  private static final Pattern ANNOTATION = Pattern.compile("@[A-Z][A-Za-z0-9_]*");

  /** A javadoc line wrap inside a span: the break, its indent, and the continuation asterisk. */
  private static final Pattern WRAP = Pattern.compile("\\s*\\n\\s*\\*?");

  /** What every supported shape is written as, quoted back when an unsupported one is found. */
  private static final String SUPPORTED =
      "unsupported citation shape — write it as SimpleTestName, SimpleTestName.member or"
          + " SimpleTestName#member, optionally package-qualified and optionally followed by empty"
          + " parentheses, with nothing else in the code span";

  /** One test citation found in a main source file, with where it was found. */
  private record Citation(Path file, int line, String text, String className, String member) {

    /** Whether this citation names a member, rather than only a class. */
    boolean namesMember() {
      return member != null;
    }

    String describe(String problem) {
      return "%s:%d  %s  — %s".formatted(ROOT.relativize(file), line, text, problem);
    }
  }

  /** One pass over the main sources: the citations it could read, and the shapes it could not. */
  private record Scan(List<Citation> citations, List<String> unsupported) {}

  /** Every {@code .java} under {@code src/main/java}, walked once, sorted. */
  private static final List<Path> MAIN_SOURCES = sources("src/main/java");

  /** Simple class name to source file, for everything under {@code src/test/java}. */
  private static final Map<String, Path> TEST_CLASSES = testClasses();

  private static final Scan SCAN = scan();
  private static final List<Citation> CITATIONS = SCAN.citations();

  @Test
  @DisplayName("every test cited by main javadoc exists, and so does every member it names")
  void shouldResolveEveryCitedTestWhenTheMainJavadocIsSwept() {
    List<String> broken = new ArrayList<>();
    for (Citation citation : CITATIONS) {
      String problem = resolve(citation);
      if (problem != null) {
        broken.add(citation.describe(problem));
      }
    }

    assertThat(broken)
        .as(
            "test citations in main javadoc that do not resolve, out of %d citations in %d main"
                + " source files. The test is the source and the citation is what gets corrected:"
                + " renaming a rule means renaming it in every javadoc that names it. A class is"
                + " looked up by simple name under src/test/java; a member must be declared there"
                + " as a void method or a static final field",
            CITATIONS.size(), MAIN_SOURCES.size())
        .isEmpty();
  }

  @Test
  @DisplayName("nothing test-citation-shaped in main javadoc is a shape this test cannot read")
  void shouldReadEveryCitationShapedSpanWhenTheMainJavadocIsSwept() {
    assertThat(SCAN.unsupported())
        .as(
            "code spans naming something Test-shaped that the strict citation pattern did not"
                + " consume, in %d main source files. A shape the matcher cannot read must fail,"
                + " not vanish: a citation the sweep never saw is indistinguishable from one that"
                + " resolves. Rewrite the citation, or widen CITATION and add a control for the"
                + " new shape",
            MAIN_SOURCES.size())
        .isEmpty();
  }

  @Test
  @DisplayName("the sweep actually found citations, including ones naming a member")
  void shouldHaveFoundCitationsWhenTheSweepRan() {
    assertThat(CITATIONS)
        .as(
            "test citations found in src/main javadoc — zero means the walk or the matcher"
                + " stopped working, and the first test would pass on an empty list")
        .isNotEmpty();
    assertThat(CITATIONS.stream().filter(Citation::namesMember).toList())
        .as(
            "citations naming a member — zero means member resolution is never exercised, and a"
                + " renamed rule would pass")
        .isNotEmpty();
  }

  /** The problem with this citation, or {@code null} if it resolves. */
  private static String resolve(Citation citation) {
    Path file = TEST_CLASSES.get(citation.className());
    if (file == null) {
      return "no test class %s.java under src/test/java".formatted(citation.className());
    }
    if (!citation.namesMember()) {
      return null;
    }
    String source = RepositoryTree.read(file);
    String member = Pattern.quote(citation.member());
    boolean method = Pattern.compile("\\bvoid\\s+" + member + "\\s*\\(").matcher(source).find();
    boolean field =
        Pattern.compile("\\bstatic\\s+final\\s+[^;=]*\\b" + member + "\\s*=")
            .matcher(source)
            .find();
    if (method || field) {
      return null;
    }
    return "no member '%s' in %s — expected a void method or a static final field"
        .formatted(citation.member(), ROOT.relativize(file));
  }

  private static Scan scan() {
    List<Citation> citations = new ArrayList<>();
    List<String> unsupported = new ArrayList<>();
    for (Path file : MAIN_SOURCES) {
      String source = RepositoryTree.read(file);
      Matcher span = CODE_SPAN.matcher(source);
      while (span.find()) {
        String text = unwrap(span.group(1));
        if (!text.contains("Test")) {
          continue;
        }
        int line = lineOf(source, span.start());
        Matcher citation = CITATION.matcher(text);
        if (citation.matches()) {
          citations.add(new Citation(file, line, text, citation.group(1), citation.group(2)));
        } else if (!ANNOTATION.matcher(text).matches()) {
          unsupported.add(
              "%s:%d  %s  — %s".formatted(ROOT.relativize(file), line, text, SUPPORTED));
        }
      }
    }
    return new Scan(List.copyOf(citations), List.copyOf(unsupported));
  }

  /** A span's raw text with any javadoc line wrap joined and its continuation asterisk dropped. */
  private static String unwrap(String raw) {
    return WRAP.matcher(raw).replaceAll(" ").trim().replaceAll("\\s+", " ");
  }

  /** The one-based line number of an offset into a file's text. */
  private static int lineOf(String source, int offset) {
    return (int) source.substring(0, offset).chars().filter(c -> c == '\n').count() + 1;
  }

  private static Map<String, Path> testClasses() {
    Map<String, Path> byName = new LinkedHashMap<>();
    for (Path file : sources("src/test/java")) {
      String name = file.getFileName().toString().replace(".java", "");
      byName.put(name, file);
    }
    return Map.copyOf(byName);
  }

  private static List<Path> sources(String relative) {
    try (Stream<Path> tree = Files.walk(ROOT.resolve(relative))) {
      return tree.filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".java"))
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
