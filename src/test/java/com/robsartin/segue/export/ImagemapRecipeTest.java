package com.robsartin.segue.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.NodeKind;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The developer guide's imagemap recipe, run rather than read.
 *
 * <p>Issue #99: the recipe the guide shipped was {@code dot -Tpng -o graph.png -Tcmapx -o graph.map
 * graph.dot}, which produces a picture and an imagemap and <b>no page that binds them</b>. An
 * imagemap does nothing on its own, so a reader following the guide exactly got two files and no
 * tooltip, with no way to tell whether they had made a mistake. Nothing caught it because a shell
 * snippet in a markdown file is a claim, not a check.
 *
 * <p>So this reads the recipe <b>out of the guide</b> and executes it, rather than keeping a copy
 * that could agree with a broken original. Editing the guide's block into something that does not
 * work fails this test. Issue #93 installed Graphviz in CI for exactly this: a claim about what
 * {@code dot} produces can be executed instead of asserted.
 *
 * <p><b>What this cannot check</b>, said out loud so nobody reads more into it: whether a browser
 * paints the tooltip. That is native browser chrome and appears in no DOM and no screenshot. What
 * is checked is everything up to it — that the recipe writes a page, that the page's {@code usemap}
 * names the map the recipe wrote, and that the areas carry the tooltip text. The last step was
 * verified by hand in Chrome, by hit-testing the image and reading the {@code <area>} the browser
 * returned; issue #99 records it.
 *
 * <p>Skipped where Graphviz is not installed, like {@code WhatAHoverShowsTest}: there is no render
 * to wrap.
 *
 * <p>Every fixture here is invented. ADR 51 and issue #37.
 */
class ImagemapRecipeTest {

  /** A ```bash block, and what is inside it. */
  private static final Pattern BASH_BLOCK = Pattern.compile("```bash\\n(.*?)```", Pattern.DOTALL);

  @BeforeAll
  static void requireGraphviz() {
    Graphviz.requireOrSkip("graphviz is not installed, so there is no render to wrap");
  }

  private static Path repositoryRoot() {
    Path candidate = Path.of("").toAbsolutePath();
    while (candidate != null && !Files.exists(candidate.resolve("settings.gradle.kts"))) {
      candidate = candidate.getParent();
    }
    if (candidate == null) {
      throw new IllegalStateException(
          "no settings.gradle.kts above " + Path.of("").toAbsolutePath());
    }
    return candidate;
  }

  /**
   * The guide's one imagemap recipe, found by content rather than by position so that moving the
   * section does not silently stop testing it. Two of them would mean a reader could follow the
   * wrong one, so two is a failure.
   */
  private static String recipeFromTheGuide() throws IOException {
    String guide =
        Files.readString(
            repositoryRoot().resolve("docs/developer-guide.md"), StandardCharsets.UTF_8);
    List<String> recipes =
        BASH_BLOCK
            .matcher(guide)
            .results()
            .map(result -> result.group(1))
            .filter(block -> block.contains("-Tcmapx"))
            .toList();
    assertThat(recipes).as("bash blocks in the developer guide mentioning -Tcmapx").hasSize(1);
    return recipes.get(0);
  }

  /** The DOT a real export writes — the graph name a real view produces included. */
  private static String dot() throws IOException {
    GraphView view =
        new GraphView(
            "a made-up view",
            List.of(
                new ViewNode("Q901", NodeKind.PERSON, "Wren Alderman", List.of("Q5")),
                new ViewNode("Q902", NodeKind.GROUP, "The Paper Kettles", List.of("Q215380"))),
            List.of(new ViewEdge("Q901", "Q902", "MEMBER_OF", 1.0, "invented")));
    StringWriter written = new StringWriter();
    new DotWriter().write(view, written);
    return written.toString();
  }

  private static void run(Path directory, String recipe) throws IOException {
    Process shell =
        new ProcessBuilder("bash", "-c", recipe)
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .start();
    String said = new String(shell.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    int status;
    try {
      status = shell.waitFor();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UncheckedIOException(new IOException(e));
    }
    assertThat(status).as("the recipe exited saying: %s", said).isZero();
  }

  private static String only(String page, String pattern) {
    Matcher found = Pattern.compile(pattern).matcher(page);
    assertThat(found.find()).as("no %s in the page the recipe wrote", pattern).isTrue();
    return found.group(1);
  }

  @Test
  @DisplayName("the guide's imagemap recipe writes a page whose map is bound to its picture")
  void shouldWriteABoundPageWhenTheGuidesRecipeIsFollowed(@TempDir Path directory)
      throws IOException {
    Files.writeString(directory.resolve("graph.dot"), dot());

    run(directory, recipeFromTheGuide());

    assertThat(directory.resolve("graph.png")).exists();
    String page = Files.readString(directory.resolve("graph.html"));
    assertThat(only(page, "<img[^>]*\\bsrc=\"([^\"]*)\""))
        .as("the picture the page shows")
        .isEqualTo("graph.png");
    assertThat(only(page, "<img[^>]*\\busemap=\"#([^\"]*)\""))
        .as("the map the image asks for, against the map the recipe wrote")
        .isEqualTo(only(page, "<map[^>]*\\bname=\"([^\"]*)\""));
  }

  @Test
  @DisplayName("the page the guide's recipe writes carries the class and the relationship")
  void shouldCarryTheTooltipsWhenTheGuidesRecipeIsFollowed(@TempDir Path directory)
      throws IOException {
    Files.writeString(directory.resolve("graph.dot"), dot());

    run(directory, recipeFromTheGuide());

    String page =
        Files.readString(directory.resolve("graph.html"))
            .replace("&#45;", "-")
            .replace("&gt;", ">");
    assertThat(page).contains("title=\"human\"");
    assertThat(page).contains("title=\"Wren Alderman -MEMBER_OF-> The Paper Kettles\"");
  }
}
