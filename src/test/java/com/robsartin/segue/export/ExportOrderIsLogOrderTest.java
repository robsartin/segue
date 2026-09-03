package com.robsartin.segue.export;

import static com.robsartin.segue.export.InventedGraph.ALMANAC;
import static com.robsartin.segue.export.InventedGraph.BYPASS;
import static com.robsartin.segue.export.InventedGraph.DEMO;
import static com.robsartin.segue.export.InventedGraph.HOLLOW_TIDE;
import static com.robsartin.segue.export.InventedGraph.KETTLES;
import static com.robsartin.segue.export.InventedGraph.LEDGER;
import static com.robsartin.segue.export.InventedGraph.MARLOW;
import static com.robsartin.segue.export.InventedGraph.PRESSING;
import static com.robsartin.segue.export.InventedGraph.PRIZE;
import static com.robsartin.segue.export.InventedGraph.TWICE;
import static com.robsartin.segue.export.InventedGraph.WREN;
import static com.robsartin.segue.export.InventedGraph.edge;
import static com.robsartin.segue.export.InventedGraph.merged;
import static com.robsartin.segue.export.InventedGraph.minted;
import static com.robsartin.segue.export.InventedGraph.node;
import static com.robsartin.segue.export.InventedGraph.owned;
import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.export.InventedGraph.FakeAssertionLog;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Issue #207: <b>two exports of one unchanged log are byte-identical, and the order they agree on
 * is the log's own.</b>
 *
 * <p><b>The defect this was written red against.</b> {@link LogProjection} copied its node map with
 * {@code Map.copyOf}, whose iteration order is unspecified and <em>salted per JVM</em>. Nothing
 * asserted export order, so a DOT or GraphML diff between two runs over one unchanged graph was
 * noise, and a real change hid in it. ADR 43 made {@code recommend}'s output byte-identical for
 * exactly this reason, and ADR 59 records {@code Map.copyOf} breaking it once already in {@code
 * Equivalences} — where {@code canonicalByLocal} now keeps log order for the same argument.
 *
 * <p><b>Why log order and not a sort.</b> Log order is the one order every reader here already
 * agrees on ({@code KnownList.promoted}, {@code Equivalences.resolve}); it is a fact of the data
 * rather than a choice, and it does not reorder the whole picture every time an id changes shape,
 * as sorting by QID would have done when issue #171 changed a hundred of them. {@link
 * #shouldReverseTheDrawnOrderWhenTheLogsClaimsAreReversed} is what makes that a claim about the log
 * rather than about any fixed order: a fold that sorted would not move.
 *
 * <p><b>The salt cannot be varied inside one JVM</b> — {@code ImmutableCollections.SALT32L} is
 * drawn once at class initialisation — so {@link #shouldRenderTheSameBytesWhenAnotherJvmFoldsIt}
 * runs the identical render in a forked JVM and diffs the two outputs. That is the control the
 * design document asks for; {@link #shouldDrawNodesAndEdgesInTheOrderTheLogClaimsThem} is the
 * deterministic pin beside it, and it is the stronger of the two. Two salts can agree by chance -
 * they did, on one run of the re-planted {@code Map.copyOf} control, where the fork stayed green
 * and the three pins reddened - so the fork is the shape of the defect and the pins are what catch
 * it: an order that is a pure function of the log cannot depend on a salt at all.
 *
 * <p>Every entity here is invented (ADR 40, issue #37).
 */
class ExportOrderIsLogOrderTest {

  /**
   * Ten node claims in an order that is neither alphabetical by QID nor grouped by kind, so that a
   * fold which sorted, grouped or hashed could not pass by coincidence.
   */
  private static List<LoggedAssertion> nodeClaims() {
    return List.of(
        node(MARLOW, NodeKind.PERSON, "Ines Marlow"),
        node(KETTLES, NodeKind.GROUP, "The Paper Kettles"),
        node(WREN, NodeKind.PERSON, "Wren Alderman"),
        node(HOLLOW_TIDE, NodeKind.GROUP, "Hollow Tide"),
        node(PRIZE, NodeKind.CONCEPT, "The Invented Prize"),
        minted(LEDGER, NodeKind.WORK, "the Watermark ledger"),
        minted(ALMANAC, NodeKind.WORK, "The Salt Almanac"),
        minted(TWICE, NodeKind.WORK, "the Salt Almanac again"),
        minted(DEMO, NodeKind.WORK, "the Kettles demo"),
        node(BYPASS, NodeKind.WORK, "a local-shaped id a source named"));
  }

  /** Seven edge claims, appended after the nodes and in an order of their own. */
  private static List<LoggedAssertion> edgeClaims() {
    return List.of(
        edge(WREN, KETTLES, "MEMBER_OF"),
        edge(MARLOW, HOLLOW_TIDE, "MEMBER_OF"),
        owned(ALMANAC, WREN, "INFLUENCED_BY"),
        edge(MARLOW, WREN, "INFLUENCED_BY"),
        owned(LEDGER, PRIZE, "INFLUENCED_BY"),
        owned(TWICE, DEMO, "INFLUENCED_BY"),
        edge(BYPASS, HOLLOW_TIDE, "INFLUENCED_BY"));
  }

  /** No merge in this fixture, so every node's position is its own first claim's. */
  private static final List<String> CLAIMED_IN_ORDER =
      List.of(MARLOW, KETTLES, WREN, HOLLOW_TIDE, PRIZE, LEDGER, ALMANAC, TWICE, DEMO, BYPASS);

  private static final List<String> EDGES_IN_ORDER =
      List.of(
          WREN + "->" + KETTLES,
          MARLOW + "->" + HOLLOW_TIDE,
          ALMANAC + "->" + WREN,
          MARLOW + "->" + WREN,
          LEDGER + "->" + PRIZE,
          TWICE + "->" + DEMO,
          BYPASS + "->" + HOLLOW_TIDE);

  @Test
  @DisplayName("the exported picture draws nodes and edges in the order the log claims them")
  void shouldDrawNodesAndEdgesInTheOrderTheLogClaimsThem() throws IOException {
    FakeAssertionLog log = logOf(nodeClaims());

    String dot = render(new DotWriter(), log);
    String graphml = render(new GraphMlWriter(), log);

    assertThat(idsIn(DOT_NODE, dot))
        .as(
            "a DOT diff between two exports of one unchanged log must show a change to the graph"
                + " and nothing else, which it can only do if the order is the log's")
        .containsExactlyElementsOf(CLAIMED_IN_ORDER);
    assertThat(idsIn(GRAPHML_NODE, graphml))
        .as("the same order in the format a reader works in, not only in the one they look at")
        .containsExactlyElementsOf(CLAIMED_IN_ORDER);
    assertThat(pairsIn(DOT_EDGE, dot))
        .as("edges are folded into a list in log order, and nothing may re-order them either")
        .containsExactlyElementsOf(EDGES_IN_ORDER);
    assertThat(pairsIn(GRAPHML_EDGE, graphml)).containsExactlyElementsOf(EDGES_IN_ORDER);
  }

  @Test
  @DisplayName(
      "reversing the log's claims reverses the drawn order, because the order is the log's")
  void shouldReverseTheDrawnOrderWhenTheLogsClaimsAreReversed() throws IOException {
    List<LoggedAssertion> reversed = new ArrayList<>(nodeClaims());
    Collections.reverse(reversed);
    List<String> backwards = new ArrayList<>(CLAIMED_IN_ORDER);
    Collections.reverse(backwards);

    String dot = render(new DotWriter(), logOf(reversed));

    assertThat(idsIn(DOT_NODE, dot))
        .as(
            "this is what says the order is the LOG's and not merely a stable one: a fold that"
                + " sorted by QID, or grouped by kind, would draw the identical picture from a"
                + " reversed log")
        .containsExactlyElementsOf(backwards)
        .isNotEqualTo(CLAIMED_IN_ORDER);
  }

  @Test
  @DisplayName("a stand-in node a merge created is drawn ahead of every node the log claims")
  void shouldDrawAStandInAheadOfEveryClaimedNode() throws IOException {
    FakeAssertionLog log = logOf(nodeClaims());
    log.with(merged(ALMANAC, PRESSING));

    String dot = render(new DotWriter(), log);

    List<String> expected = new ArrayList<>();
    expected.add(PRESSING);
    expected.addAll(CLAIMED_IN_ORDER);
    assertThat(idsIn(DOT_NODE, dot))
        .as(
            "a stand-in has no claim of its own, so it has no position of its own either: it is"
                + " seeded by a pre-pass that finishes before the fold begins (#178), and this"
                + " test is where that ruling is written down rather than implied")
        .containsExactlyElementsOf(expected);
  }

  @Test
  @DisplayName("a second JVM folding the same log writes the same bytes, salt and all")
  void shouldRenderTheSameBytesWhenAnotherJvmFoldsIt() throws Exception {
    FakeAssertionLog log = logOf(nodeClaims());
    String here = render(new DotWriter(), log) + SEPARATOR + render(new GraphMlWriter(), log);

    String there = inAnotherJvm();

    assertThat(there)
        .as(
            "Map.copyOf's iteration order is salted per JVM, and the salt cannot be varied inside"
                + " one of them - so a fork is the only place the defect issue #207 closes is"
                + " visible as itself")
        .isEqualTo(here);
  }

  // ---- the fork ---------------------------------------------------------

  /** Between the two renders in the forked JVM's output. Not a character either format writes. */
  private static final String SEPARATOR = " ---- ";

  /** What the forked JVM runs. Not a tool: this class is on the test runtime classpath. */
  public static void main(String[] args) throws IOException {
    FakeAssertionLog log = logOf(nodeClaims());
    System.out.print(render(new DotWriter(), log) + SEPARATOR + render(new GraphMlWriter(), log));
  }

  /**
   * The same two renders, in a JVM of their own.
   *
   * <p>The classpath is this JVM's, so the fork runs against exactly what this build compiled — the
   * argument {@code StdioPurityTest} makes for launching the application the same way. A blank
   * classpath fails the test rather than skipping it: an instrument that can report success by
   * never having run is the thing this repository files issues to close.
   */
  private static String inAnotherJvm() throws IOException, InterruptedException {
    String classpath = System.getProperty("java.class.path", "");
    assertThat(classpath).as("the forked JVM needs this one's classpath").isNotBlank();
    Process forked =
        new ProcessBuilder(
                System.getProperty("java.home") + "/bin/java",
                "-cp",
                classpath,
                ExportOrderIsLogOrderTest.class.getName())
            .redirectErrorStream(false)
            .start();
    String out = new String(forked.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    String err = new String(forked.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    int status = forked.waitFor();
    assertThat(status).as("the forked JVM said: %s", err).isZero();
    return out;
  }

  // ---- shared -----------------------------------------------------------

  private static FakeAssertionLog logOf(List<LoggedAssertion> nodes) {
    FakeAssertionLog log = new FakeAssertionLog();
    log.with(nodes.toArray(new LoggedAssertion[0]));
    log.with(edgeClaims().toArray(new LoggedAssertion[0]));
    return log;
  }

  private static String render(ViewWriter writer, FakeAssertionLog log) throws IOException {
    try (TinkerGraphStore graph = new TinkerGraphStore()) {
      StringWriter out = new StringWriter();
      writer.write(new ViewSelector(graph, log).full(), out);
      return out.toString();
    }
  }

  private static final Pattern DOT_NODE =
      Pattern.compile("^ {2}\"([^\"]+)\" \\[label=", Pattern.MULTILINE);
  private static final Pattern DOT_EDGE =
      Pattern.compile("^ {2}\"([^\"]+)\" -> \"([^\"]+)\"", Pattern.MULTILINE);
  private static final Pattern GRAPHML_NODE = Pattern.compile("<node id=\"([^\"]+)\">");
  private static final Pattern GRAPHML_EDGE =
      Pattern.compile("<edge id=\"[^\"]+\" source=\"([^\"]+)\" target=\"([^\"]+)\">");

  private static List<String> idsIn(Pattern pattern, String rendered) {
    List<String> found = new ArrayList<>();
    Matcher matcher = pattern.matcher(rendered);
    while (matcher.find()) {
      found.add(matcher.group(1));
    }
    assertThat(found).as("the extractor read nothing out of %s", rendered).isNotEmpty();
    return found;
  }

  private static List<String> pairsIn(Pattern pattern, String rendered) {
    List<String> found = new ArrayList<>();
    Matcher matcher = pattern.matcher(rendered);
    while (matcher.find()) {
      found.add(matcher.group(1) + "->" + matcher.group(2));
    }
    assertThat(found).as("the extractor read no edges out of %s", rendered).isNotEmpty();
    return found;
  }
}
