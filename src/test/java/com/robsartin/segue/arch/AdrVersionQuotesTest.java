package com.robsartin.segue.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * No ADR may quote a tool version that disagrees with {@code gradle/libs.versions.toml} unless this
 * class names it as a point-in-time snapshot — issue #374.
 *
 * <p>ADR 35 states the rule: <b>versions live in {@code libs.versions.toml} or a BOM and never in
 * prose.</b> ADR 34, one number earlier, names six of them in its Decision, and the 2026-11-01
 * drift sweep found two had moved underneath it. Nothing caught that, because a version in prose
 * and a version in the catalogue are two sources for one fact and only one of them is read by the
 * build.
 *
 * <p><b>The allowlist is the disagreements, and the assertion is exact in both directions.</b> A
 * new quote that disagrees reds — that is the guard. A quote that agrees today reds the day the
 * catalogue moves past it, which is the drift this class exists to catch at the moment it happens
 * rather than at the next sweep. And an allowlisted pair that is no longer in the tree, or that has
 * come back into agreement, reds too, so an entry cannot rot here describing a quote nobody has.
 *
 * <p><b>Being on the allowlist is not a repair.</b> ADR 1 makes an ADR immutable, so a stale quote
 * is never edited out; it is recorded as a point-in-time snapshot in a dated amendment on the ADR
 * that carries it, and the entry below names that amendment. The list holds the ADR's own quoted
 * value and never the catalogue's current one — a second copy of the live version is the defect
 * this class is about.
 *
 * <p><b>The tool names are derived, not listed.</b> Every alias in the catalogue's {@code
 * [versions]} table becomes a prose pattern by splitting its camel case, so {@code
 * googleJavaFormat} matches "google-java-format" and {@code springBoot} matches "Spring Boot". A
 * dependency added to the catalogue is therefore scanned for without anyone remembering to add it
 * here.
 *
 * <p><b>What is deliberately NOT checked</b>, so nobody reads more assurance into this class than
 * it gives: a version named in prose for something the catalogue does not hold. "Java 25", "JUnit
 * 5" as the thing ADR 34 moved away from, and a Jackson major that resolves from a BOM are all
 * outside it — the oracle here is the catalogue, and it cannot answer for what it does not contain.
 */
class AdrVersionQuotesTest {

  private static final Path ROOT = RepositoryTree.root();
  private static final Path ADR = ROOT.resolve("docs/adr");
  private static final Path CATALOGUE = ROOT.resolve("gradle/libs.versions.toml");

  /** One quoted version: the ADR file, the catalogue alias it names, and the number it gives. */
  record Quote(String file, String alias, String version) {}

  private static final Comparator<Quote> BY_FILE_THEN_ALIAS =
      Comparator.comparing(Quote::file).thenComparing(Quote::alias).thenComparing(Quote::version);

  /**
   * ADR 34's Decision pins six tool versions, read on the day it was accepted. Two of the six have
   * since moved in the catalogue, and ADR 1 forbids editing either number out of an accepted
   * decision. Its 2026-11-03 amendment (issue #374) records both as the point-in-time snapshot they
   * are and names {@code gradle/libs.versions.toml} as the authority; the versions those two tools
   * are on <em>now</em> are in that file and are not restated here or there.
   */
  private static final List<Quote> SNAPSHOTS_RECORDED_BY_AMENDMENT =
      List.of(
          new Quote(
              "0034-jvm-quality-gates-junit-6-spotless-jacoco-archunit.md", "archunit", "1.5.0"),
          new Quote(
              "0034-jvm-quality-gates-junit-6-spotless-jacoco-archunit.md", "spotless", "8.10.0"));

  @Test
  @DisplayName("every tool version quoted in docs/adr agrees with gradle/libs.versions.toml")
  void shouldAgreeWithTheCatalogueWhenAnAdrQuotesAToolVersion() {
    assertThat(disagreeingQuotes())
        .as(
            "a version quoted in docs/adr/*.md that disagrees with gradle/libs.versions.toml must"
                + " be allowlisted here as (file, alias, version), in both directions. Versions"
                + " live in the catalogue or a BOM and never in prose (ADR 35); an ADR that has"
                + " one anyway is immutable, so the fix is a dated amendment recording the quote"
                + " as a point-in-time snapshot and citing the catalogue, never an edit. An entry"
                + " listed here that the tree no longer disagrees about is a stale entry to"
                + " remove")
        .containsExactlyElementsOf(allowlist());
  }

  @Test
  @DisplayName("a tool named in prose beside a dotted number is read as a version quote")
  void shouldReadAQuoteWhenAnAdrNamesAToolAndADottedNumber() {
    Map<String, String> catalogue =
        Map.of("spotless", "8.10.3", "googleJavaFormat", "1.36.1", "springAi", "2.0.1");

    assertThat(
            quotesIn(
                "- **Formatting** via **Spotless 8.10.0** with **google-java-format 1.36.1**,"
                    + " on Spring AI 2.0.1.",
                catalogue))
        .containsExactlyInAnyOrder(
            new Quote("x.md", "spotless", "8.10.0"),
            new Quote("x.md", "googleJavaFormat", "1.36.1"),
            new Quote("x.md", "springAi", "2.0.1"));
  }

  @Test
  @DisplayName("a bare major, an artefact suffix and an unrelated number are not version quotes")
  void shouldReadNoQuoteWhenTheNumberIsNotAToolVersion() {
    Map<String, String> catalogue = Map.of("junit", "6.1.3", "archunit", "1.5.1", "jena", "6.2.0");

    assertThat(
            quotesIn(
                """
                It specifies JUnit 5, and `archunit-junit6` exists. ADR 45, issue #274,
                Java 25, 1.5.1, Jena, and a rule named onlyJackson3.
                """,
                catalogue))
        .isEmpty();
  }

  @Test
  @DisplayName("the catalogue's [versions] table is read, and nothing below it is")
  void shouldReadOnlyTheVersionsTableWhenTheCatalogueIsParsed() {
    assertThat(
            versionsIn(
                """
                [versions]
                spotless = "8.10.3"
                googleJavaFormat = "1.36.1"

                [libraries]
                assertj = { module = "org.assertj:assertj-core", version.ref = "assertj" }

                [plugins]
                spotless = { id = "com.diffplug.spotless", version.ref = "spotless" }
                """))
        .containsExactly(Map.entry("spotless", "8.10.3"), Map.entry("googleJavaFormat", "1.36.1"));
  }

  /** An alias and its version, one per line, in the {@code [versions]} table and nowhere else. */
  private static final Pattern VERSION_LINE =
      Pattern.compile("^\\s*([A-Za-z][A-Za-z0-9_-]*)\\s*=\\s*\"([^\"]+)\"\\s*$");

  /**
   * The catalogue's {@code [versions]} table, in file order. The scan stops at the next table
   * heading, because {@code [libraries]} and {@code [plugins]} hold lines of the same shape for
   * module coordinates rather than versions.
   */
  static Map<String, String> versionsIn(String toml) {
    Map<String, String> aliases = new LinkedHashMap<>();
    boolean inVersions = false;
    for (String line : toml.lines().toList()) {
      String trimmed = line.strip();
      if (trimmed.startsWith("[")) {
        inVersions = trimmed.equals("[versions]");
        continue;
      }
      if (!inVersions) {
        continue;
      }
      Matcher matcher = VERSION_LINE.matcher(line);
      if (matcher.matches()) {
        aliases.put(matcher.group(1), matcher.group(2));
      }
    }
    return aliases;
  }

  /**
   * The prose spelling of a catalogue alias: its camel-case words, separated in the document by a
   * space, a hyphen or nothing. {@code googleJavaFormat} reads "google-java-format" in ADR 34 and
   * {@code springAi} reads "Spring AI" in ADR 27, and neither spelling is written down twice.
   */
  static Pattern proseNameOf(String alias) {
    String words =
        Stream.of(alias.split("(?<=[a-z0-9])(?=[A-Z])|[-_]"))
            .map(word -> Pattern.quote(word.toLowerCase(java.util.Locale.ROOT)))
            .reduce((a, b) -> a + "[- ]?" + b)
            .orElseThrow();
    return Pattern.compile(
        "(?i)\\b" + words + "[*`_]{0,2} +[*`_]{0,2}(\\d+\\.\\d+(?:\\.\\d+)*)\\b");
  }

  /**
   * Every version quote in one document, duplicates removed. The number must carry at least one dot
   * to be read as a version: "JUnit 5" is ADR 34 naming the major it moved away from, and a bare
   * major is not a pin the catalogue can be compared against. The separator must be a space, so the
   * {@code 6} in {@code archunit-junit6} is an artefact name rather than a quote.
   */
  static List<Quote> quotesIn(String markdown, Map<String, String> catalogue) {
    List<Quote> found = new ArrayList<>();
    for (String alias : catalogue.keySet()) {
      Matcher matcher = proseNameOf(alias).matcher(markdown);
      while (matcher.find()) {
        Quote quote = new Quote("x.md", alias, matcher.group(1));
        if (!found.contains(quote)) {
          found.add(quote);
        }
      }
    }
    return found;
  }

  private static List<Quote> allowlist() {
    List<Quote> all = new ArrayList<>(SNAPSHOTS_RECORDED_BY_AMENDMENT);
    all.sort(BY_FILE_THEN_ALIAS);
    return all;
  }

  /**
   * Every {@code *.md} in {@code docs/adr} whose quoted version is not the one the catalogue holds.
   * The index is scanned too: a version pasted into {@code README.md} is a quote like any other.
   */
  private static List<Quote> disagreeingQuotes() {
    Map<String, String> catalogue = versionsIn(RepositoryTree.read(CATALOGUE));
    List<Quote> found = new ArrayList<>();
    try (Stream<Path> entries = Files.list(ADR)) {
      for (Path file : entries.sorted().toList()) {
        String name = file.getFileName().toString();
        if (!name.endsWith(".md")) {
          continue;
        }
        for (Quote quote : quotesIn(RepositoryTree.read(file), catalogue)) {
          if (!quote.version().equals(catalogue.get(quote.alias()))) {
            found.add(new Quote(name, quote.alias(), quote.version()));
          }
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    found.sort(BY_FILE_THEN_ALIAS);
    return found;
  }
}
