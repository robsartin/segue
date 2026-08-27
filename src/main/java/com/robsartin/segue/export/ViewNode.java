package com.robsartin.segue.export;

import com.robsartin.segue.domain.NodeKind;
import java.util.List;
import java.util.Objects;

/**
 * One entity in a {@link GraphView}, flattened to exactly what a picture needs.
 *
 * <p>Deliberately not {@link com.robsartin.segue.domain.NodeRecord}: a view is the contract between
 * selection and serialisation, and pinning it to the projection's record would make every writer a
 * downstream consumer of the graph model. It also gives the one attribute a writer needs that the
 * graph does not hold — {@code affinity}.
 *
 * <p><b>{@code affinity} is null unless the operator asked for it.</b> ADR 33 makes the world graph
 * exportable precisely because it carries no personal data; a rating is personal data, and issue
 * #37 settled that the protection is the filesystem rather than repository visibility. Nothing in
 * {@link ViewSelector} ever populates this field — only {@link AffinityOverlay} does, behind {@code
 * --include-affinity}, and it warns at the point of export.
 *
 * <p><b>{@code instanceOf} is the raw {@code P31} the claim recorded</b> (ADR 42), carried
 * unresolved and un-prettified: QIDs, in the order the source stated them. A writer decides what to
 * do with them — DOT names them in a tooltip, GraphML hands them over as an attribute to filter on
 * — which is exactly the split ADR 41 exists to keep. It is a world fact, so unlike {@code
 * affinity} it needs no flag; a source that stated no classes leaves it empty.
 */
public record ViewNode(
    String qid, NodeKind kind, String label, List<String> instanceOf, Integer affinity) {

  public ViewNode {
    Objects.requireNonNull(qid, "qid");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(label, "label");
    instanceOf = List.copyOf(Objects.requireNonNull(instanceOf, "instanceOf"));
  }

  /** A world-fact node: everything the graph knows, and nothing about what anyone thinks of it. */
  public ViewNode(String qid, NodeKind kind, String label, List<String> instanceOf) {
    this(qid, kind, label, instanceOf, null);
  }

  /** An entity whose source stated no classes of its own. */
  public ViewNode(String qid, NodeKind kind, String label) {
    this(qid, kind, label, List.of(), null);
  }

  /** The same entity, carrying a rating. Only {@link AffinityOverlay} calls this. */
  public ViewNode withAffinity(int rating) {
    return new ViewNode(qid, kind, label, instanceOf, rating);
  }
}
