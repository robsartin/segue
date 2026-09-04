package com.robsartin.segue.export;

import com.robsartin.segue.export.ExportCli.Options;
import com.robsartin.segue.support.QidList;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Select, then report, then write — in that order, which is the order the whole thing turns on.
 *
 * <p>This class is the one place a {@link ViewKind} and an {@link OutputFormat} are in the same
 * method, and it is deliberately thin: it dispatches to the selector, optionally decorates, says
 * how big the answer is, and hands the result to a writer. Every interesting decision belongs to
 * one side or the other, and neither side knows the other exists.
 *
 * <p><b>Counts before the write, not after.</b> A dev tool that reports "wrote 31,000 edges" after
 * writing them has told the operator something they could have acted on a moment too late. Every
 * note goes through {@code notes} in order, and the size line is emitted while the output file
 * still does not exist — which is what {@code ExportRunTest} asserts, rather than trusting the
 * reading order of this method.
 *
 * <p>Notes go to a {@link Consumer} rather than to a logger of this class's own so that the
 * ordering is observable from a test. The command line supplies {@code log::info} (ADR 30: SLF4J is
 * the only logging API, and there is no {@code System.out} anywhere in this project).
 */
public final class ExportRun {

  private final ViewSelector selector;

  /** Null unless the operator passed {@code --include-affinity}. See {@link AffinityOverlay}. */
  private final AffinityOverlay overlay;

  private final ViewWriter writer;

  public ExportRun(ViewSelector selector, AffinityOverlay overlay, ViewWriter writer) {
    this.selector = Objects.requireNonNull(selector, "selector");
    this.overlay = overlay;
    this.writer = Objects.requireNonNull(writer, "writer");
  }

  /**
   * Run one export.
   *
   * @return the view that was written, so a caller can assert on it without re-reading the file
   */
  public GraphView run(Options options, Consumer<String> notes) throws IOException {
    Objects.requireNonNull(options, "options");
    Objects.requireNonNull(notes, "notes");

    // First, before a view exists and long before a file does: the operator is about to produce
    // personal data, and this is the moment that is worth knowing (ADR 33, issue #37).
    if (overlay != null) {
      notes.accept(AffinityOverlay.PERSONAL_DATA_WARNING);
    }

    GraphView view = select(options);
    if (overlay != null) {
      view = overlay.applyTo(view);
    }

    notes.accept(view.description());
    notes.accept(view.describeSize());
    // Two ways an edge in the log reaches no export, on one line so that neither is read as the
    // whole story (#224, final review). A dangling edge is a defect - LogProjection.danglingEdges
    // is the count whose own javadoc says it should be zero - and a withdrawn one is the fold
    // obeying a retraction. Reporting only the first left an operator with a quietly smaller
    // export and nothing in the tool saying why: the retraction tool's own report covers the
    // moment of the retraction, and every later export was silent.
    if (options.view().readsTheWholeLog()
        && (selector.danglingEdges() > 0 || selector.withdrawnEdges() > 0)) {
      notes.accept(
          selector.danglingEdges()
              + " edge(s) in the log name an entity that was never claimed as a node, and "
              + selector.withdrawnEdges()
              + " named a canonical id a retraction emptied (#224); neither kind is in this"
              + " export");
    }

    // Then whatever the format itself has to say about this view — DOT drops its edge labels on a
    // view too dense to draw them (issue #70), and a tool that loses information silently invites
    // someone to wonder where it went. Asked here rather than answered here: which format is
    // holding the pen is deliberately not this class's business.
    writer.note(view).ifPresent(notes);

    try (Writer out = Files.newBufferedWriter(options.out(), StandardCharsets.UTF_8)) {
      writer.write(view, out);
    }
    notes.accept("wrote " + options.out());
    return view;
  }

  /** The only switch over {@link ViewKind} in the exporter. */
  private GraphView select(Options options) {
    return switch (options.view()) {
      case ROUTE -> selector.route(options.fromQid(), options.toQid(), options.maxHops());
      case NEIGHBOURHOOD -> selector.neighbourhood(options.qid(), options.depth());
      case SUBGRAPH -> selector.subgraph(QidList.read(options.qidList()));
      case FULL -> selector.full();
    };
  }
}
