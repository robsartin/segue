package com.robsartin.segue.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.robsartin.segue.export.ExportCli.Options;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExportCliTest {

  private static final String HOME = "/invented/home";

  private static Options parse(String... args) {
    return ExportCli.parse(args, null, HOME);
  }

  @Test
  @DisplayName("a neighbourhood needs an entity, and defaults to depth 1")
  void parsesANeighbourhood() {
    Options options =
        parse("--view", "neighbourhood", "--qid", "Q900101", "--out", "/tmp/n.graphml");

    assertThat(options.view()).isEqualTo(ViewKind.NEIGHBOURHOOD);
    assertThat(options.qid()).isEqualTo("Q900101");
    assertThat(options.depth()).isEqualTo(1);
    assertThat(options.format()).isEqualTo(OutputFormat.GRAPHML);
    assertThat(options.includeAffinity()).isFalse();
  }

  @Test
  @DisplayName("a route needs both ends")
  void parsesARoute() {
    Options options =
        parse(
            "--view", "route",
            "--from", "Q900101",
            "--to", "Q900104",
            "--max-hops", "4",
            "--format", "dot",
            "--out", "/tmp/r.dot");

    assertThat(options.fromQid()).isEqualTo("Q900101");
    assertThat(options.toQid()).isEqualTo("Q900104");
    assertThat(options.maxHops()).isEqualTo(4);
    assertThat(options.format()).isEqualTo(OutputFormat.DOT);
  }

  @Test
  @DisplayName("a route without both ends is a usage error, not an empty picture")
  void refusesARouteWithOneEnd() {
    assertThatThrownBy(() -> parse("--view", "route", "--from", "Q900101", "--out", "/tmp/r.dot"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--to");
  }

  @Test
  @DisplayName("a neighbourhood without an entity is a usage error")
  void refusesANeighbourhoodWithoutAnEntity() {
    assertThatThrownBy(() -> parse("--view", "neighbourhood", "--out", "/tmp/n.graphml"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--qid");
  }

  @Test
  @DisplayName("a subgraph needs the list of entities to keep")
  void refusesASubgraphWithoutAList() {
    assertThatThrownBy(() -> parse("--view", "subgraph", "--out", "/tmp/s.graphml"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--qids");
  }

  @Test
  @DisplayName("the full view is refused without --all, before any store is opened")
  void refusesTheFullViewWithoutTheFlag() {
    assertThatThrownBy(() -> parse("--view", "full", "--out", "/tmp/all.graphml"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--all");
  }

  @Test
  @DisplayName("the full view is allowed with --all")
  void acceptsTheFullViewWithTheFlag() {
    Options options = parse("--view", "full", "--all", "--out", "/tmp/all.graphml");

    assertThat(options.view()).isEqualTo(ViewKind.FULL);
  }

  @Test
  @DisplayName("--out is required, so nothing is ever written to a path nobody chose")
  void refusesWithoutAnOutputPath() {
    assertThatThrownBy(() -> parse("--view", "full", "--all"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--out");
  }

  @Test
  @DisplayName("the database defaults the way the server's does: SEGUE_DB, then the home directory")
  void resolvesTheDatabaseLikeTheServer() {
    Options fromHome = parse("--view", "full", "--all", "--out", "/tmp/all.graphml");
    Options fromEnv =
        ExportCli.parse(
            new String[] {"--view", "full", "--all", "--out", "/tmp/all.graphml"},
            "/invented/scratch.db",
            HOME);
    Options explicit =
        parse("--view", "full", "--all", "--out", "/tmp/all.graphml", "--db", "/invented/other.db");

    assertThat(fromHome.database()).isEqualTo(Path.of(HOME, ".segue", "segue.db"));
    assertThat(fromEnv.database()).isEqualTo(Path.of("/invented/scratch.db"));
    assertThat(explicit.database()).isEqualTo(Path.of("/invented/other.db"));
  }

  @Test
  @DisplayName("including affinity takes an explicit flag")
  void takesAnExplicitFlagForAffinity() {
    Options options =
        parse("--view", "full", "--all", "--out", "/tmp/all.graphml", "--include-affinity");

    assertThat(options.includeAffinity()).isTrue();
  }

  @Test
  @DisplayName("no view at all is a usage error")
  void refusesWithoutAView() {
    assertThatThrownBy(() -> parse("--out", "/tmp/x.graphml"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--view");
  }

  @Test
  @DisplayName("an unrecognised view is refused with the list of the ones that exist")
  void refusesAnUnknownView() {
    assertThatThrownBy(() -> parse("--view", "hairball", "--out", "/tmp/x.graphml"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("neighbourhood");
  }

  @Test
  @DisplayName("an unrecognised flag is refused rather than ignored")
  void refusesAnUnknownFlag() {
    assertThatThrownBy(
            () -> parse("--view", "full", "--all", "--out", "/tmp/x.graphml", "--write-graph"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--write-graph");
  }

  @Test
  @DisplayName("a flag with no value is refused")
  void refusesADanglingFlag() {
    assertThatThrownBy(() -> parse("--view"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--view");
  }

  @Test
  @DisplayName("a depth below one is refused: depth 0 is a picture of one dot")
  void refusesAZeroDepth() {
    assertThatThrownBy(
            () ->
                parse(
                    "--view", "neighbourhood",
                    "--qid", "Q900101",
                    "--depth", "0",
                    "--out", "/tmp/n.graphml"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("depth");
  }

  @Test
  @DisplayName("an --out ending in .dot writes DOT, with no --format at all")
  void infersDotFromTheExtension() {
    Options options =
        parse(
            "--view",
            "route",
            "--from",
            "Q900101",
            "--to",
            "Q900104",
            "--out",
            "/invented/route.dot");

    assertThat(options.format()).isEqualTo(OutputFormat.DOT);
  }

  @Test
  @DisplayName("an --out ending in .graphml writes GraphML, with no --format at all")
  void infersGraphMlFromTheExtension() {
    Options options = parse("--view", "full", "--all", "--out", "/invented/all.graphml");

    assertThat(options.format()).isEqualTo(OutputFormat.GRAPHML);
  }

  @Test
  @DisplayName("the second spelling of each format is honoured too: .gv is DOT, .xml is GraphML")
  void infersTheAlternativeExtensions() {
    Options dot = parse("--view", "full", "--all", "--out", "/invented/all.gv");
    Options graphml = parse("--view", "full", "--all", "--out", "/invented/all.xml");

    assertThat(dot.format()).isEqualTo(OutputFormat.DOT);
    assertThat(graphml.format()).isEqualTo(OutputFormat.GRAPHML);
  }

  @Test
  @DisplayName("the extension is read case-insensitively: .DOT is still DOT")
  void infersRegardlessOfCase() {
    Options dot = parse("--view", "full", "--all", "--out", "/invented/all.DOT");
    Options graphml = parse("--view", "full", "--all", "--out", "/invented/all.GraphML");

    assertThat(dot.format()).isEqualTo(OutputFormat.DOT);
    assertThat(graphml.format()).isEqualTo(OutputFormat.GRAPHML);
  }

  @Test
  @DisplayName("a dot in a directory name is not the file's extension")
  void readsTheExtensionOfTheFileAndNotTheDirectory() {
    Options fromTheFile = parse("--view", "full", "--all", "--out", "/invented/v1.dot/all.graphml");
    Options noExtensionAtAll =
        parse("--view", "full", "--all", "--out", "/invented/v1.graphml/all");

    assertThat(fromTheFile.format()).isEqualTo(OutputFormat.GRAPHML);
    assertThat(noExtensionAtAll.format()).isEqualTo(OutputFormat.DOT);
  }

  @Test
  @DisplayName("an unrecognised extension still works, on the documented default")
  void fallsBackToTheDocumentedDefault() {
    Options unknown = parse("--view", "full", "--all", "--out", "/invented/all.txt");
    Options none = parse("--view", "full", "--all", "--out", "/invented/all");

    assertThat(unknown.format()).isEqualTo(OutputFormat.DOT);
    assertThat(none.format()).isEqualTo(OutputFormat.DOT);
  }

  @Test
  @DisplayName("--format that contradicts the extension is refused, naming both")
  void refusesAFormatThatContradictsTheExtension() {
    assertThatThrownBy(
            () ->
                parse(
                    "--view", "full", "--all", "--format", "graphml", "--out", "/invented/all.dot"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--format graphml")
        .hasMessageContaining(".dot");
  }

  @Test
  @DisplayName("--format that agrees with the extension is simply obeyed")
  void acceptsAFormatThatAgreesWithTheExtension() {
    Options options =
        parse("--view", "full", "--all", "--format", "graphml", "--out", "/invented/all.GRAPHML");

    assertThat(options.format()).isEqualTo(OutputFormat.GRAPHML);
  }

  @Test
  @DisplayName("--format is obeyed when the extension names no format of its own")
  void obeysTheFlagWhenTheExtensionSaysNothing() {
    Options options =
        parse("--view", "full", "--all", "--format", "graphml", "--out", "/invented/all.txt");

    assertThat(options.format()).isEqualTo(OutputFormat.GRAPHML);
  }
}
