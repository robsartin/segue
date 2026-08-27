package com.robsartin.segue.export;

import com.robsartin.segue.domain.AffinityRecord;
import com.robsartin.segue.port.AffinityStore;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The one thing that turns an exportable file into personal data, behind an explicit flag.
 *
 * <p>ADR 33 says it directly: "the world graph can be shared, exported or made public without
 * carrying personal data". That is what makes a world-fact export uncontroversial and it is the
 * whole reason the exporter's default carries no rating at all. Affinity is the other layer, and
 * issue #37 settled that the protection is the filesystem rather than repository visibility — this
 * repository is public. So a file that has been through this class must not be committed, must live
 * outside the working tree, and must be treated the way {@code ~/.segue/segue.db} is treated.
 *
 * <p><b>The warning is emitted at the point of export, not left to the operator's memory.</b>
 * Colouring nodes by rating is the obvious temptation and the obvious way to end up with a personal
 * file in a public repository, so the tool says so every time rather than relying on a line in a
 * document nobody re-reads.
 *
 * <p><b>One lookup per node in the view, not a bulk read.</b> {@link AffinityStore} has no {@code
 * readAll} — deliberately, per ADR 16's data minimisation — and this class does not need one: it
 * asks about exactly the entities that are already in the picture and about nothing else.
 *
 * <p>Note which ArchUnit rule this class falls under. {@code affinityNeverTouchesTheWorldFactLayer}
 * matches taste-layer types by name rather than by package (ADR 33's boundary is not a package), so
 * {@code AffinityOverlay} is fenced by it automatically: it may read the taste store and it may
 * decorate a view, and it may not reach the log, the graph, {@code IngestService} or the claim
 * records. That is exactly the right fence, and it was not written for this class.
 */
public final class AffinityOverlay {

  /**
   * Said out loud at the point of export. Names the decision and the issue rather than saying
   * "careful", because the operator's next action — where to put the file — is what depends on it.
   */
  public static final String PERSONAL_DATA_WARNING =
      "this export carries affinity ratings: it is personal data under ADR 33 and issue #37."
          + " Keep it outside the working tree and out of version control — this repository is"
          + " public.";

  private final AffinityStore store;

  public AffinityOverlay(AffinityStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  /** The same view with a rating on every node that has one. The input is left untouched. */
  public GraphView applyTo(GraphView view) {
    List<ViewNode> rated = view.nodes().stream().map(this::rate).toList();
    return new GraphView(view.description(), rated, view.edges());
  }

  private ViewNode rate(ViewNode node) {
    Optional<AffinityRecord> affinity = store.find(node.qid());
    return affinity.map(record -> node.withAffinity(record.rating())).orElse(node);
  }
}
