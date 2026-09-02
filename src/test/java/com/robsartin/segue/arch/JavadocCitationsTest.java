package com.robsartin.segue.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
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
 * test types into the API documentation's link space. So the citation is prose in a {@code @code}
 * span, and prose is what nothing checked until this class. A renamed {@link ArchitectureTest} rule
 * used to drift silently in every place it was named — five files name {@link
 * ArchitectureTest#theExporterOnlyReads} alone.
 *
 * <p><b>A citation is recognised by lookup, not by its name.</b> The first version of this class
 * only considered spans whose text contained {@code Test}, which is a name gate wearing a
 * recogniser's clothes: {@link com.robsartin.segue.port.GraphStore} cites {@link
 * com.robsartin.segue.port.GraphStoreContract} and {@link com.robsartin.segue.ingest.LocalEntity}
 * cites {@code Fixture}, both real test classes, both invisible to it — no citation, no complaint,
 * no output, which is the exact failure this class exists to close. Every span of the citation
 * shape is now looked up instead, and it is where the name resolves that decides:
 *
 * <ul>
 *   <li>a class under {@code src/test/java} — <b>checked</b>, member and all;
 *   <li>a class under {@code src/main/java} — <b>skipped</b>, deliberately. Those ~335 spans can
 *       become real {@code @link} tags and be checked by the compiler now that {@code javadoc}
 *       gates, which is a better guarantee than this one and a separate piece of work;
 *   <li>a bare name that resolves to neither — <b>skipped</b>. {@code Duration}, {@code Optional}
 *       and a hundred others are types being named in prose, and a bare name carries nothing to
 *       tell a mistyped class from a mentioned one;
 *   <li><b>a name with a member that resolves to neither — red.</b> Except:
 *   <li>a name the build can load — <b>skipped</b>. See below.
 * </ul>
 *
 * <p><b>{@code System.out} is not a typo and {@code SomeTest.gone} is.</b> Both are the same shape,
 * so shape cannot separate them and the rule is loadability: a simple name that the test JVM can
 * load — from a system module or from a jar on the runtime classpath — is a type this code names,
 * not a citation of a test that has been renamed away. {@code Map.of}, {@code Integer.parseInt},
 * {@code StatusPrinter2.printInCaseOfErrorsOrWarnings} and Spring AI's {@code
 * AbstractSpringAiSchemaModule.checkRequired} all pass on that rule, the last two without being
 * imported by the file that names them. A typo'd class name loads from nowhere, which is what makes
 * it a typo. The index is over simple names only — deliberately, since the citation is a simple
 * name too — so the rule is permissive by construction and errs towards silence, not towards a
 * false red on a library type nobody in this repository controls.
 *
 * <p><b>A citation wraps, and the wrap carries a leading asterisk.</b> Twenty of the citations in
 * the tree today sit on the second line of a javadoc span, so the raw text between the braces reads
 * {@code "\n * ArchitectureTest.seedNeverOpensAStore"}. A per-line matcher sees no citation at all
 * on either line and reports the file clean — which is how the design note's own sweep counted 31
 * sites where there are 54. The scan below therefore reads whole files, joins the wrap, and drops
 * the continuation asterisk before matching.
 *
 * <p><b>Nothing test-shaped escapes quietly.</b> Beside the lookup, a <i>mention</i> predicate reds
 * on every span whose text contains {@code Test} that the citation shape did not consume at all: a
 * whitespace-separated {@code ArchitectureTest theRule}, a generic parameter, an argument list
 * inside the parentheses. They occur zero times today and would otherwise vanish with no output. A
 * lenient matcher feeding a guard turns "cannot read this" into "there is nothing here", which is
 * the defect this class exists to close, not to reproduce.
 *
 * <p><b>One exclusion, named:</b> a span whose whole text is an annotation —
 * {@code @SpringBootTest} in {@link com.robsartin.segue.app.SegueApplication} is the single
 * occurrence — is not a citation of anything in {@code src/test}. It names a type on the compile
 * classpath, and the leading {@code @} is what says so.
 *
 * <p>What is deliberately NOT checked, so nobody reads more assurance into this class than it
 * gives. A package prefix is not verified against the file's directory — the class is found by
 * simple name, and a name two test files share resolves to neither: the citation reds as ambiguous,
 * naming both files, because a map that answered it with whichever file sorted last would be a
 * guard that lies. A duplicate nobody cites is not this class's business and passes. Only a braced
 * span is swept, so a citation in bare prose or a line comment is invisible to it — {@link
 * com.robsartin.segue.mcp.ToolResults} names {@code ToolResultsTest} in a {@code //} comment, and
 * bracing it is what would bring it in. The mention predicate is still keyed on the word {@code
 * Test}, so a malformed citation of a test class <i>not</i> named {@code …Test} — the {@code
 * GraphStoreContract} kind — would vanish rather than red; the lookup catches it only in the shape
 * the pattern can read. A simple name holding an underscore is not read as a class at all, which is
 * what keeps {@code BY_CODE.values()} out. A member is matched by declaration text, a {@code void
 * name(} method or a {@code static final … name =} field, not by loading the class: matching the
 * source keeps the rule readable in the failure message, and a citation of an inherited or
 * generated member would red rather than pass. And a {@code @code} span is read wherever it occurs
 * in a main source file, javadoc or string literal alike; that errs strict.
 */
class JavadocCitationsTest {

  private static final Path ROOT = RepositoryTree.root();

  /**
   * An inline code span and everything up to its closing brace. Reluctant, so nested spans on one
   * line stay separate; the brace is what ends it, as javadoc has no escape for one inside. The
   * word boundary is load-bearing: without it {@code @codex} would be read as a span of {@code x}.
   */
  private static final Pattern CODE_SPAN = Pattern.compile("\\{@code\\b(.*?)}", Pattern.DOTALL);

  /**
   * The one citation shape this class reads: an optional package prefix of lowercase segments, an
   * UpperCamelCase simple name, an optional member joined by {@code .} or {@code #}, and optional
   * empty parentheses. Anchored — a partial match is not a match. The simple name must hold a
   * lowercase letter and no underscore, so a constant such as {@code BY_CODE.values()} is not read
   * as a class naming a member.
   */
  private static final Pattern CITATION =
      Pattern.compile(
          "(?:[a-z][A-Za-z0-9_]*(?:\\.[a-z][A-Za-z0-9_]*)*\\.)?"
              + "([A-Z][A-Za-z0-9]*[a-z][A-Za-z0-9]*)"
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

  /**
   * One pass over the main sources: the test citations it must check, the ones that name a member
   * of nothing at all, the shapes it could not read, and how many spans each skip rule absorbed.
   */
  private record Scan(
      List<Citation> citations,
      List<String> unresolvable,
      List<String> unsupported,
      int mainClassCitations,
      int loadableTypes,
      int bareUnknownNames) {}

  /** Every {@code .java} under {@code src/main/java}, walked once, sorted. */
  private static final List<Path> MAIN_SOURCES = sources("src/main/java");

  /** Simple names of the classes declared under {@code src/main/java}. */
  private static final Set<String> MAIN_CLASSES = simpleNames(MAIN_SOURCES);

  /**
   * Simple class name to <i>every</i> file declaring it under {@code src/test/java}. A list, not a
   * path, because a last-one-wins map would answer a cited name with an arbitrary file.
   */
  private static final Map<String, List<Path>> TEST_CLASSES = testClasses();

  /** Simple names of every type this JVM can load — built on demand, see {@link #loadable()}. */
  private static Set<String> loadableNames;

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
            "test citations in main javadoc that do not resolve, out of %d citations of src/test"
                + " classes in %d main source files. The test is the source and the citation is"
                + " what gets corrected: renaming a rule means renaming it in every javadoc that"
                + " names it. A class is looked up by simple name under src/test/java; a member"
                + " must be declared there as a void method or a static final field",
            CITATIONS.size(), MAIN_SOURCES.size())
        .isEmpty();
  }

  @Test
  @DisplayName("no main javadoc cites a member of a class that exists nowhere")
  void shouldResolveEveryCitedClassWhenACitationNamesAMember() {
    assertThat(SCAN.unresolvable())
        .as(
            "citations naming a member of a class that is neither in this repository nor loadable"
                + " by the build, in %d main source files. The sweep classified the rest as %d"
                + " citations of src/test classes (checked), %d of src/main classes (skipped, they"
                + " can become @link tags now that javadoc gates), %d types the JVM can load"
                + " (skipped — System.out is not a typo) and %d bare names resolving nowhere"
                + " (skipped — a bare name carries nothing to tell a mistyped class from a"
                + " mentioned one). What is left names a member of nothing, which is what a"
                + " renamed or mistyped class looks like",
            MAIN_SOURCES.size(),
            CITATIONS.size(),
            SCAN.mainClassCitations(),
            SCAN.loadableTypes(),
            SCAN.bareUnknownNames())
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
    List<Path> files = TEST_CLASSES.getOrDefault(citation.className(), List.of());
    if (files.isEmpty()) {
      return "no test class %s.java under src/test/java".formatted(citation.className());
    }
    if (files.size() > 1) {
      return "ambiguous citation — %s is declared in %s — qualify it with its package or rename one"
          .formatted(
              citation.className(),
              files.stream().map(ROOT::relativize).map(Path::toString).toList());
    }
    Path file = files.get(0);
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
    List<String> unresolvable = new ArrayList<>();
    List<String> unsupported = new ArrayList<>();
    int mainClassCitations = 0;
    int loadableTypes = 0;
    int bareUnknownNames = 0;
    for (Path file : MAIN_SOURCES) {
      String source = RepositoryTree.read(file);
      Matcher span = CODE_SPAN.matcher(source);
      while (span.find()) {
        String text = unwrap(span.group(1));
        int line = lineOf(source, span.start());
        Matcher citation = CITATION.matcher(text);
        if (!citation.matches()) {
          if (text.contains("Test") && !ANNOTATION.matcher(text).matches()) {
            unsupported.add(
                "%s:%d  %s  — %s".formatted(ROOT.relativize(file), line, text, SUPPORTED));
          }
          continue;
        }
        String className = citation.group(1);
        String member = citation.group(2);
        if (TEST_CLASSES.containsKey(className)) {
          citations.add(new Citation(file, line, text, className, member));
        } else if (MAIN_CLASSES.contains(className)) {
          mainClassCitations++;
        } else if (member == null) {
          bareUnknownNames++;
        } else if (loadable().contains(className)) {
          loadableTypes++;
        } else {
          unresolvable.add(
              "%s:%d  %s  — unresolvable citation — no class %s.java under src/main or src/test,"
                      .formatted(ROOT.relativize(file), line, text, className)
                  + " and no type of that simple name on the build's classpath. A citation of a"
                  + " test names a class this repository declares; a name that resolves nowhere is"
                  + " a typo or a rename nobody followed through");
        }
      }
    }
    return new Scan(
        List.copyOf(citations),
        List.copyOf(unresolvable),
        List.copyOf(unsupported),
        mainClassCitations,
        loadableTypes,
        bareUnknownNames);
  }

  /**
   * The simple name of every type this JVM can load: the system modules plus every jar and class
   * directory on the runtime classpath. Built once, and only when a citation actually reaches the
   * rule that needs it — a tree whose citations all resolve inside {@code src} never pays for it.
   */
  private static Set<String> loadable() {
    if (loadableNames == null) {
      loadableNames = readLoadable();
    }
    return loadableNames;
  }

  private static Set<String> readLoadable() {
    Set<String> names = new HashSet<>();
    for (ModuleReference module : ModuleFinder.ofSystem().findAll()) {
      try (ModuleReader reader = module.open();
          Stream<String> entries = reader.list()) {
        entries.forEach(entry -> addIfClass(names, entry));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
      Path path = Path.of(entry);
      if (Files.isRegularFile(path)) {
        try (JarFile jar = new JarFile(path.toFile())) {
          jar.stream().map(JarEntry::getName).forEach(name -> addIfClass(names, name));
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      } else if (Files.isDirectory(path)) {
        try (Stream<Path> tree = Files.walk(path)) {
          tree.map(Path::toString).forEach(name -> addIfClass(names, name));
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      }
    }
    return Set.copyOf(names);
  }

  /** Adds {@code Outer$Inner.class} to the index as {@code Inner}, and ignores anything else. */
  private static void addIfClass(Set<String> names, String resource) {
    if (!resource.endsWith(".class")) {
      return;
    }
    String name = resource.replace(File.separatorChar, '/');
    name = name.substring(name.lastIndexOf('/') + 1, name.length() - ".class".length());
    names.add(name.substring(name.lastIndexOf('$') + 1));
  }

  /** A span's raw text with any javadoc line wrap joined and its continuation asterisk dropped. */
  private static String unwrap(String raw) {
    return WRAP.matcher(raw).replaceAll(" ").trim().replaceAll("\\s+", " ");
  }

  /** The one-based line number of an offset into a file's text. */
  private static int lineOf(String source, int offset) {
    return (int) source.substring(0, offset).chars().filter(c -> c == '\n').count() + 1;
  }

  private static Map<String, List<Path>> testClasses() {
    Map<String, List<Path>> byName = new LinkedHashMap<>();
    for (Path file : sources("src/test/java")) {
      String name = file.getFileName().toString().replace(".java", "");
      byName.computeIfAbsent(name, unused -> new ArrayList<>()).add(file);
    }
    return Map.copyOf(byName);
  }

  private static Set<String> simpleNames(List<Path> files) {
    Set<String> names = new LinkedHashSet<>();
    for (Path file : files) {
      names.add(file.getFileName().toString().replace(".java", ""));
    }
    return Set.copyOf(names);
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
