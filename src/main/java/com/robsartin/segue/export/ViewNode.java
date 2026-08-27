package com.robsartin.segue.export;

import com.robsartin.segue.domain.NodeKind;
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
 */
public record ViewNode(String qid, NodeKind kind, String label, Integer affinity) {

  public ViewNode {
    Objects.requireNonNull(qid, "qid");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(label, "label");
  }

  /** A world-fact node: everything the graph knows, and nothing about what anyone thinks of it. */
  public ViewNode(String qid, NodeKind kind, String label) {
    this(qid, kind, label, null);
  }

  /** The same entity, carrying a rating. Only {@link AffinityOverlay} calls this. */
  public ViewNode withAffinity(int rating) {
    return new ViewNode(qid, kind, label, rating);
  }
}
