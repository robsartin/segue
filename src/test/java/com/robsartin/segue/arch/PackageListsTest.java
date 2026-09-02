package com.robsartin.segue.arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The tree names the dev-tool and adapter packages; {@code ArchitectureTest}'s two constants are
 * held to it here — issue #165.
 *
 * <p>Before this class, {@link ArchitectureTest#DEV_TOOL_PACKAGES} and {@link
 * ArchitectureTest#ADAPTER_PACKAGES} were the source of truth for every sibling fence that reads
 * them, so a package the constant did not name was fenced by nothing. #165 measured it: a new dev
 * tool planted under {@code src/main}, reaching {@code export}, {@code recommend} and {@code
 * IngestService}, left every one of {@code ArchitectureTest}'s rules green. Comparing the guide to
 * the constant (issue #145) cannot close that either — a document against a document, both stale
 * together. The set has to come from the tree, and that is what these tests derive.
 *
 * <p>The constants stay: they are the readable list twenty javadocs and the guide cite, and editing
 * one is a deliberate act. They are simply no longer the authority. A new dev-tool package, or a
 * new adapter, reds here until the constant names it; a constant naming a package the tree no
 * longer has reds too.
 */
class PackageListsTest {

  private static final String BASE = "com.robsartin.segue";

  /**
   * The production classes, imported once exactly as {@code ArchitectureTest} imports them — the
   * typed class graph, not a text predicate over source. Grepping {@code src/main} for {@code
   * implements GraphStore} is the parser hole this repo keeps finding; ArchUnit already knows.
   */
  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(new ImportOption.DoNotIncludeTests())
          .importPackages(BASE);

  /**
   * A {@code JavaExec} registration opens at the very start of a line, and closes at the first line
   * that is exactly {@code "}"} — the only shape {@code build.gradle.kts} uses.
   *
   * <p>Recognising one form is safe only because {@link #devToolsFromGradle()} also asserts that
   * every line mentioning {@code JavaExec} is one of these. Without that guard the pattern was
   * form-blind: Kotlin's other registration form, {@code tasks.register("x", JavaExec::class)},
   * registered a real task the derivation never saw, and an indented open vanished the same way.
   */
  private static final Pattern JAVA_EXEC_BLOCK =
      Pattern.compile("^tasks\\.register<JavaExec>\\(\"(\\w+)\"\\)\\s*\\{$");

  /** The whole main class, spelled out in one string literal: no variable, no concatenation. */
  private static final Pattern MAIN_CLASS_LINE =
      Pattern.compile(
          "^\\s*mainClass\\.set\\(\"" + BASE.replace(".", "\\.") + "\\.(\\w+)\\.\\w+\"\\)$");

  /**
   * Any plain string literal passed to {@code mainClass.set(...)}, with no requirement on its
   * shape. Distinguishes a literal that simply has more than one package segment under {@link
   * #BASE} (which {@link #MAIN_CLASS_LINE} rejects on shape) from a value built from a variable or
   * concatenation, so the two failures can be told apart in the assertion message below.
   */
  private static final Pattern MAIN_CLASS_LITERAL =
      Pattern.compile("^\\s*mainClass\\.set\\(\"([^\"]*)\"\\)$");

  @Test
  @DisplayName("the JavaExec registrations name exactly the dev-tool packages the constant lists")
  void shouldNameEveryDevToolPackageWhenGradleRegistersTheJavaExecTasks() {
    assertThat(devToolsFromGradle())
        .as(
            "build.gradle.kts, the mainClass packages of every register<JavaExec> block, against"
                + " ArchitectureTest.DEV_TOOL_PACKAGES — a tool with a task and no entry is fenced"
                + " by nothing, which is the hole issue #165 measured")
        .isEqualTo(new TreeSet<>(ArchitectureTest.DEV_TOOL_PACKAGES));
  }

  @Test
  @DisplayName("the *Cli entry points name exactly the dev-tool packages the constant lists")
  void shouldNameEveryDevToolPackageWhenAClassEndsInCliAndDeclaresAMain() {
    Set<String> fromCli = devToolsFromCliClasses();

    assertThat(fromCli)
        .as(
            "src/main, every class named *Cli declaring public static void main, by package,"
                + " against ArchitectureTest.DEV_TOOL_PACKAGES")
        .isEqualTo(new TreeSet<>(ArchitectureTest.DEV_TOOL_PACKAGES));
    assertThat(fromCli)
        .as(
            "the same set derived from build.gradle.kts. Two signals because the disagreement is"
                + " itself the finding: a tool with a JavaExec task and no *Cli, or a *Cli nobody"
                + " can run, is a defect a single-signal check would discard")
        .isEqualTo(devToolsFromGradle());
  }

  @Test
  @DisplayName("the port implementors name exactly the adapter packages the constant lists")
  void shouldNameEveryAdapterPackageWhenAClassImplementsAPortInterface() {
    assertThat(adaptersFromPortImplementors())
        .as(
            "src/main, every class assignable to an interface in ..port.., by package and without"
                + " port itself, against ArchitectureTest.ADAPTER_PACKAGES — the one list"
                + " adaptersDoNotDependOnEachOther and adaptersDoNotDependUpward both read")
        .isEqualTo(new TreeSet<>(ArchitectureTest.ADAPTER_PACKAGES));
  }

  // --- derivation from the tree -------------------------------------------------------------

  /**
   * The packages named by the {@code mainClass} of every {@code JavaExec} registration in {@code
   * build.gradle.kts}.
   *
   * <p><b>The shape this parses, stated so that a departure from it is a failure and not a silent
   * miss.</b> A registration is a line reading exactly {@code tasks.register<JavaExec>("name")
   * &#123;} at column zero, and runs to the first line that is exactly {@code &#125;}. Inside that
   * block exactly one line mentions {@code mainClass}, and that line reads {@code
   * mainClass.set("com.robsartin.segue.<pkg>.<Class>")} and nothing else. A block with no {@code
   * mainClass}, with two, with one assigned from a variable, or with one built by concatenation
   * fails naming the block. This repo's recurring defect is a parser that reports clean on what it
   * cannot parse, so every unparsed shape reds here instead of vanishing.
   *
   * <p><b>And the opening line itself is checked, not just what is inside it.</b> Every line
   * mentioning {@code JavaExec} must be one of those opens — all eight are today — so a
   * registration written any other legal way ({@code tasks.register("x", JavaExec::class)}, or an
   * indented open) fails by line number and content instead of silently not being a dev tool. The
   * strictness one level down is worth nothing while the block open can vanish, which is how a
   * planted eighth task passed every test in this class. Whole-line {@code //} comments are the one
   * exception, because a comment registers nothing; a trailing comment after code is still checked.
   */
  static Set<String> devToolsFromGradle() {
    List<String> lines =
        RepositoryTree.read(RepositoryTree.root().resolve("build.gradle.kts")).lines().toList();
    Set<String> packages = new TreeSet<>();
    int blocks = 0;
    for (int line = 0; line < lines.size(); line++) {
      Matcher open = JAVA_EXEC_BLOCK.matcher(lines.get(line));
      if (!open.matches()) {
        continue;
      }
      String task = open.group(1);
      blocks++;
      int end = line + 1;
      while (end < lines.size() && !lines.get(end).equals("}")) {
        end++;
      }
      assertThat(end)
          .as(
              "build.gradle.kts, register<JavaExec>(\"%s\") — a block closing at its own line",
              task)
          .isLessThan(lines.size());

      List<String> mentions =
          lines.subList(line + 1, end).stream().filter(l -> l.contains("mainClass")).toList();
      assertThat(mentions)
          .as(
              "build.gradle.kts, register<JavaExec>(\"%s\") — exactly one line mentioning mainClass",
              task)
          .hasSize(1);

      Matcher mainClass = MAIN_CLASS_LINE.matcher(mentions.getFirst());
      if (MAIN_CLASS_LITERAL.matcher(mentions.getFirst()).matches()) {
        assertThat(mainClass.matches())
            .as(
                "build.gradle.kts, register<JavaExec>(\"%s\") — its mainClass is a literal, but"
                    + " this derivation only recognises one package segment directly under"
                    + " %s.<pkg>.<Class>; either move the class so it lives one segment under %s,"
                    + " or extend this derivation deliberately if a nested package is intended: %s",
                task, BASE, BASE, mentions.getFirst())
            .isTrue();
      } else {
        assertThat(mainClass.matches())
            .as(
                "build.gradle.kts, register<JavaExec>(\"%s\") — its mainClass spelled out as"
                    + " mainClass.set(\"%s.<pkg>.<Class>\"), not built from a variable, so that this"
                    + " derivation cannot quietly skip a tool it failed to read: %s",
                task, BASE, mentions.getFirst())
            .isTrue();
      }
      packages.add(mainClass.group(1));
      line = end;
    }
    assertThat(blocks)
        .as(
            "build.gradle.kts — at least one register<JavaExec> block. Finding none would make every"
                + " dev-tool assertion here vacuously true")
        .isPositive();

    List<String> unrecognised = new ArrayList<>();
    for (int line = 0; line < lines.size(); line++) {
      String text = lines.get(line);
      if (text.strip().startsWith("//")) {
        continue;
      }
      if (text.contains("JavaExec") && !JAVA_EXEC_BLOCK.matcher(text).matches()) {
        unrecognised.add((line + 1) + ": " + text);
      }
    }
    assertThat(unrecognised)
        .as(
            "build.gradle.kts — every line mentioning JavaExec is a block this derivation opens on,"
                + " written as tasks.register<JavaExec>(\"name\") { at the start of a line. A"
                + " registration in any other legal form — tasks.register(\"name\","
                + " JavaExec::class), or an indented open — registers a real task this parser never"
                + " sees, so it is named here rather than quietly not being a dev tool")
        .isEmpty();

    return packages;
  }

  /**
   * The packages holding a class named {@code *Cli} that declares {@code public static void
   * main(String[])}.
   *
   * <p>Both halves of that predicate carry weight. {@code app} declares a {@code main} — {@code
   * SegueApplication} — and is not a dev tool; {@code export} declares a second one ({@code
   * HoverableSvg}) inside a package the {@code *Cli} already names, so neither disturbs the set.
   */
  static Set<String> devToolsFromCliClasses() {
    Set<String> packages = new TreeSet<>();
    for (JavaClass candidate : CLASSES) {
      if (candidate.getSimpleName().endsWith("Cli") && declaresMain(candidate)) {
        topLevelPackage(candidate).ifPresent(packages::add);
      }
    }
    return packages;
  }

  /**
   * The packages holding a class assignable to an interface in {@code ..port..}, minus {@code port}
   * itself.
   *
   * <p>Assignability, not a declared {@code implements}: a class reaching a port interface through
   * a superclass or a second interface is an adapter on the same grounds.
   */
  static Set<String> adaptersFromPortImplementors() {
    Set<JavaClass> portInterfaces = new HashSet<>();
    for (JavaClass candidate : CLASSES) {
      if (candidate.isInterface() && inPackage(candidate, "port")) {
        portInterfaces.add(candidate);
      }
    }
    assertThat(portInterfaces)
        .as(
            "interfaces in %s.port — finding none would make this derivation vacuously empty and"
                + " agree with nothing",
            BASE)
        .isNotEmpty();

    Set<String> packages = new TreeSet<>();
    for (JavaClass candidate : CLASSES) {
      if (candidate.getAllRawInterfaces().stream().anyMatch(portInterfaces::contains)) {
        topLevelPackage(candidate).filter(p -> !p.equals("port")).ifPresent(packages::add);
      }
    }
    return packages;
  }

  private static boolean declaresMain(JavaClass candidate) {
    return candidate.getMethods().stream().anyMatch(PackageListsTest::isMainMethod);
  }

  private static boolean isMainMethod(JavaMethod method) {
    return method.getName().equals("main")
        && method.getModifiers().contains(JavaModifier.PUBLIC)
        && method.getModifiers().contains(JavaModifier.STATIC)
        && method.getRawReturnType().getName().equals("void")
        && method.getRawParameterTypes().size() == 1
        && isStringArray(method.getRawParameterTypes().getFirst());
  }

  /**
   * ArchUnit names the parameter type {@code [Ljava.lang.String;}, so ask the type, not the name.
   */
  private static boolean isStringArray(JavaClass parameter) {
    return parameter.isArray() && parameter.getComponentType().getName().equals("java.lang.String");
  }

  private static boolean inPackage(JavaClass candidate, String simplePackage) {
    return topLevelPackage(candidate).filter(simplePackage::equals).isPresent();
  }

  /**
   * {@code com.robsartin.segue.sqlite.Sub} is in {@code sqlite}; the base package itself is in
   * none.
   */
  private static Optional<String> topLevelPackage(JavaClass candidate) {
    String inPackage = candidate.getPackageName();
    if (!inPackage.startsWith(BASE + ".")) {
      return Optional.empty();
    }
    String rest = inPackage.substring(BASE.length() + 1);
    int dot = rest.indexOf('.');
    return Optional.of(dot < 0 ? rest : rest.substring(0, dot));
  }
}
