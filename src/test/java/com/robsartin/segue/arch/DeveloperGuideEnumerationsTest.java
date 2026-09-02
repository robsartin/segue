package com.robsartin.segue.arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The developer guide enumerates several sets the code defines. This re-derives each set from the
 * tree and fails on any difference in either direction — issue #145.
 *
 * <p>Every check here exists because issue #91 falsified the document it guards: the ArchUnit rule
 * table named two rules that no longer existed and omitted three that did; the layering diagram was
 * missing a package and four import edges; the testing-strategy table named three live classes
 * where five exist and one stub server where two do. Each was re-derived by a throwaway script and
 * repaired by hand, and none of those scripts was wired to anything, so the next branch would have
 * falsified all three again.
 *
 * <p><b>The guide's formatting is load-bearing here</b>, and each test names the shape it parses. A
 * guide edit that keeps the facts but changes the shape fails these tests rather than passing them
 * silently, which is the safe direction.
 *
 * <p>What is deliberately NOT checked, so that nobody reads more assurance into this class than it
 * gives: the prose in the "Depends on" column of the package table, and every other sentence in the
 * guide. Only the enumerations below are derived.
 */
class DeveloperGuideEnumerationsTest {

  private static final Path ROOT = RepositoryTree.root();
  private static final String GUIDE = RepositoryTree.read(ROOT.resolve("docs/developer-guide.md"));
  private static final Path MAIN = ROOT.resolve("src/main/java/com/robsartin/segue");
  private static final Path TESTS = ROOT.resolve("src/test/java/com/robsartin/segue");

  /** The guide states counts in words next to some of the sets it enumerates. */
  private static final List<String> NUMBER_WORDS =
      List.of(
          "zero",
          "one",
          "two",
          "three",
          "four",
          "five",
          "six",
          "seven",
          "eight",
          "nine",
          "ten",
          "eleven",
          "twelve",
          "thirteen",
          "fourteen",
          "fifteen",
          "sixteen",
          "seventeen",
          "eighteen",
          "nineteen",
          "twenty");

  @Test
  @DisplayName("every ArchRule the architecture test declares is annotated @ArchTest")
  void shouldRunEveryRuleWhenTheArchitectureTestDeclaresOne() {
    Set<String> notRun =
        Stream.of(ArchitectureTest.class.getDeclaredFields())
            .filter(f -> ArchRule.class.isAssignableFrom(f.getType()))
            .filter(f -> !f.isAnnotationPresent(ArchTest.class))
            .map(Field::getName)
            .collect(Collectors.toCollection(TreeSet::new));

    assertThat(notRun)
        .as(
            "an ArchRule field with no @ArchTest is never evaluated — an inert fence, which is the"
                + " defect issues #139 and #140 are both instances of")
        .isEmpty();
  }

  @Test
  @DisplayName("the guide's ArchUnit table names exactly the rules the architecture test runs")
  void shouldNameEveryArchUnitRuleWhenTheGuideTabulatesThem() {
    Set<String> declared = archRuleNames();

    Set<String> tabulated =
        firstColumnNames(section("### Which rules a machine enforces")).stream()
            .collect(Collectors.toCollection(TreeSet::new));

    assertThat(tabulated)
        .as(
            "docs/developer-guide.md, 'Which rules a machine enforces' — one row per rule, rule"
                + " names in backticks in the first column")
        .isEqualTo(declared);
  }

  @Test
  @DisplayName("the guide's layering diagram draws exactly the packages under src/main")
  void shouldDrawEveryPackageWhenTheGuideDiagramsTheLayering() {
    Set<String> drawn =
        matches(Pattern.compile("^\\s*(\\w+)\\[", Pattern.MULTILINE), layeringDiagram(), 1);

    assertThat(drawn)
        .as(
            "docs/developer-guide.md, the mermaid diagram under 'The layering' — one node per package")
        .isEqualTo(mainPackages());
  }

  @Test
  @DisplayName("the guide's layering diagram draws exactly the cross-package imports src/main has")
  void shouldDrawEveryImportEdgeWhenTheGuideDiagramsTheLayering() {
    Set<String> drawn = new TreeSet<>();
    Matcher m =
        Pattern.compile(
                "^\\s*(\\w+)\\s*[-=.]+>\\s*(?:\\|\"[^\"]*\"\\|\\s*)?(\\w+)\\s*$", Pattern.MULTILINE)
            .matcher(layeringDiagram());
    while (m.find()) {
      drawn.add(m.group(1) + " --> " + m.group(2));
    }

    assertThat(drawn)
        .as(
            "docs/developer-guide.md, the mermaid diagram under 'The layering' — an edge for every"
                + " 'import com.robsartin.segue.<other package>' in src/main, which is the"
                + " derivation the paragraph above the diagram claims")
        .isEqualTo(importEdges());
  }

  @Test
  @DisplayName(
      "the guide names exactly the adapter packages ArchitectureTest fences, and counts them")
  void shouldNameEveryAdapterWhenTheGuideDescribesTheLayering() {
    Matcher sentence =
        Pattern.compile("The (\\w+)\\s+adapters \\(([^)]*)\\)").matcher(section("## The layering"));
    assertThat(sentence.find())
        .as(
            "docs/developer-guide.md, 'The layering' — a sentence reading 'The <count> adapters (`a`, `b`, ...)'")
        .isTrue();

    List<String> declared = ArchitectureTest.ADAPTER_PACKAGES;

    assertThat(backticked(sentence.group(2)))
        .as(
            "the adapter list in that sentence, against ArchitectureTest.ADAPTER_PACKAGES — the one"
                + " list adaptersDoNotDependOnEachOther and adaptersDoNotDependUpward both read")
        .isEqualTo(declared);
    assertThat(sentence.group(1))
        .as("the count word in front of it")
        .isEqualTo(NUMBER_WORDS.get(declared.size()));
  }

  @Test
  @DisplayName("the guide names exactly the dev-side tools the build registers, and counts them")
  void shouldNameEveryDevToolWhenTheGuideDescribesTheLayering() {
    Matcher sentence =
        Pattern.compile(
                "^(`\\w+`(?:, `\\w+`)* and `\\w+`) are the (\\w+) dev-side tools",
                Pattern.MULTILINE)
            .matcher(section("## The layering"));
    assertThat(sentence.find())
        .as(
            "docs/developer-guide.md, 'The layering' — a sentence beginning a line, reading '`a`,"
                + " `b`, ... and `z` are the <count> dev-side tools'")
        .isTrue();

    Set<String> registered = PackageListsTest.devToolsFromGradle();

    assertThat(new TreeSet<>(backticked(sentence.group(1))))
        .as(
            "the tool list in that sentence, compared as a set because the guide's order is"
                + " chronological, against the packages build.gradle.kts registers a JavaExec task"
                + " for. Against the derivation and not against"
                + " ArchitectureTest.DEV_TOOL_PACKAGES, so that a stale sentence cannot agree with"
                + " a stale constant (issue #165); PackageListsTest holds the constant to the same"
                + " set")
        .isEqualTo(registered);
    assertThat(sentence.group(2))
        .as("the count word in front of it")
        .isEqualTo(NUMBER_WORDS.get(registered.size()));
  }

  @Test
  @DisplayName("the guide's package table has one row per package under src/main")
  void shouldGiveEveryPackageARowWhenTheGuideTabulatesWhatEachIsFor() {
    Set<String> tabulated =
        firstColumnNames(section("### What each package is for")).stream()
            .collect(Collectors.toCollection(TreeSet::new));

    assertThat(tabulated)
        .as(
            "docs/developer-guide.md, 'What each package is for' — one row per package, the package"
                + " name in backticks in the first column. Only the row set is checked; the"
                + " 'Depends on' column is prose")
        .isEqualTo(mainPackages());
  }

  @Test
  @DisplayName(
      "the guide's testing table names exactly the live-tagged test classes, and counts them")
  void shouldNameEveryLiveTaggedClassWhenTheGuideTabulatesTheTestingStrategy() {
    Set<String> tagged =
        testSources()
            .filter(p -> RepositoryTree.read(p).contains("@Tag(\"live\")"))
            .map(DeveloperGuideEnumerationsTest::simpleName)
            .collect(Collectors.toCollection(TreeSet::new));

    String row = tableRowContaining("@Tag(\"live\")");
    Set<String> named =
        backticked(row).stream()
            .filter(t -> t.matches("[A-Z][A-Za-z0-9]*Test"))
            .collect(Collectors.toCollection(TreeSet::new));

    assertThat(named)
        .as(
            "docs/developer-guide.md, 'The testing strategy' — the live row names each @Tag(\"live\")"
                + " class in backticks")
        .isEqualTo(tagged);
    assertThat(row)
        .as("the live row also states the count in words, and that count is not checked by the set")
        .contains(NUMBER_WORDS.get(tagged.size()) + " classes");
  }

  @Test
  @DisplayName("the guide's testing table names exactly the stub HTTP servers under src/test")
  void shouldNameEveryStubServerWhenTheGuideTabulatesTheTestingStrategy() {
    Set<String> stubs =
        testSources()
            .filter(p -> !p.getFileName().toString().endsWith("Test.java"))
            .filter(p -> RepositoryTree.read(p).contains("com.sun.net.httpserver.HttpServer"))
            .map(DeveloperGuideEnumerationsTest::simpleName)
            .collect(Collectors.toCollection(TreeSet::new));

    Set<String> named =
        backticked(tableRowContaining("Stubbed HTTP")).stream()
            .filter(t -> t.contains("/"))
            .map(t -> t.substring(t.lastIndexOf('/') + 1))
            .collect(Collectors.toCollection(TreeSet::new));

    assertThat(named)
        .as(
            "docs/developer-guide.md, 'The testing strategy' — the stubbed-HTTP row names each"
                + " server as `package/ClassName`. A stub server is a helper in src/test (not a"
                + " *Test class) that opens the JDK's own HttpServer")
        .isEqualTo(stubs);
  }

  // --- derivation from the tree -------------------------------------------------------------

  static Set<String> archRuleNames() {
    return Stream.of(ArchitectureTest.class.getDeclaredFields())
        .filter(f -> ArchRule.class.isAssignableFrom(f.getType()))
        .filter(f -> f.isAnnotationPresent(ArchTest.class))
        .map(Field::getName)
        .collect(Collectors.toCollection(TreeSet::new));
  }

  private static Set<String> mainPackages() {
    try (Stream<Path> entries = Files.list(MAIN)) {
      return entries
          .filter(Files::isDirectory)
          .map(p -> p.getFileName().toString())
          .collect(Collectors.toCollection(TreeSet::new));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Set<String> importEdges() {
    Set<String> packages = mainPackages();
    Pattern projectImport =
        Pattern.compile(
            "^import (?:static )?com\\.robsartin\\.segue\\.(\\w+)\\.", Pattern.MULTILINE);
    Set<String> edges = new TreeSet<>();
    for (String from : packages) {
      try (Stream<Path> files = Files.walk(MAIN.resolve(from))) {
        files
            .filter(p -> p.getFileName().toString().endsWith(".java"))
            .forEach(
                p -> {
                  Matcher m = projectImport.matcher(RepositoryTree.read(p));
                  while (m.find()) {
                    String to = m.group(1);
                    if (!to.equals(from) && packages.contains(to)) {
                      edges.add(from + " --> " + to);
                    }
                  }
                });
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return edges;
  }

  private static Stream<Path> testSources() {
    try (Stream<Path> files = Files.walk(TESTS)) {
      return files.filter(p -> p.getFileName().toString().endsWith(".java")).toList().stream();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String simpleName(Path javaFile) {
    String name = javaFile.getFileName().toString();
    return name.substring(0, name.length() - ".java".length());
  }

  // --- parsing the guide ---------------------------------------------------------------------

  private static String layeringDiagram() {
    String layering = section("## The layering");
    int start = layering.indexOf("```mermaid");
    assertThat(start).as("a mermaid diagram under 'The layering'").isNotNegative();
    int end = layering.indexOf("```", start + "```mermaid".length());
    return layering.substring(start, end);
  }

  private static String section(String heading) {
    int start = GUIDE.indexOf(heading);
    assertThat(start).as("the heading '%s' in docs/developer-guide.md", heading).isNotNegative();
    int end = GUIDE.length();
    for (String marker : List.of("\n## ", "\n### ")) {
      int next = GUIDE.indexOf(marker, start + heading.length());
      if (next >= 0 && next < end) {
        end = next;
      }
    }
    return GUIDE.substring(start, end);
  }

  private static String tableRowContaining(String needle) {
    List<String> rows =
        section("## The testing strategy")
            .lines()
            .filter(l -> l.startsWith("|") && l.contains(needle))
            .toList();
    assertThat(rows)
        .as("exactly one row of the testing-strategy table containing '%s'", needle)
        .hasSize(1);
    return rows.getFirst();
  }

  private static List<String> firstColumnNames(String table) {
    return table
        .lines()
        .filter(l -> l.startsWith("|") && !l.startsWith("| ---"))
        .map(l -> l.split("\\|")[1])
        .flatMap(cell -> backticked(cell).stream())
        .toList();
  }

  private static List<String> backticked(String text) {
    return matchList(Pattern.compile("`([^`]+)`"), text, 1);
  }

  private static List<String> matchList(Pattern pattern, String text, int group) {
    return pattern.matcher(text).results().map(r -> r.group(group)).toList();
  }

  private static Set<String> matches(Pattern pattern, String text, int group) {
    return new TreeSet<>(matchList(pattern, text, group));
  }
}
