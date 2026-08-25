package com.robsartin.segue.mcp;

import com.robsartin.segue.domain.Candidate;
import com.robsartin.segue.domain.EdgeRecord;
import com.robsartin.segue.domain.Hop;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.PathResult;
import com.robsartin.segue.domain.Provenance;
import java.util.List;

/**
 * Domain to wire-view translation (FIX 2 of the increment-4a final review).
 *
 * <p>{@link SegueService} is the anti-corruption layer between the domain and the published MCP
 * schema: it computes with domain types and hands this class the result to translate at the last
 * moment, right before wrapping it in a {@link ToolResult}. The domain records stay free to change
 * shape — increment 5's taste layer is expected to push on them — without that being, by
 * construction, a breaking change to every client that has learned this tool surface's schema.
 */
final class ViewMapper {

  private ViewMapper() {}

  static NodeView toNodeView(NodeRecord node) {
    return new NodeView(node.qid(), node.kind(), node.label());
  }

  static CandidateView toCandidateView(Candidate candidate) {
    return new CandidateView(
        candidate.qid(), candidate.label(), candidate.description(), candidate.kind());
  }

  static List<CandidateView> toCandidateViews(List<Candidate> candidates) {
    return candidates.stream().map(ViewMapper::toCandidateView).toList();
  }

  static ProvenanceView toProvenanceView(Provenance provenance) {
    return new ProvenanceView(
        provenance.sourceId(),
        provenance.sourceRef(),
        provenance.assertedAt(),
        provenance.confidence());
  }

  static EdgeView toEdgeView(EdgeRecord edge) {
    return new EdgeView(
        edge.fromQid(),
        edge.toQid(),
        edge.typeCode(),
        edge.validFrom(),
        edge.validTo(),
        edge.sources().stream().map(ViewMapper::toProvenanceView).toList());
  }

  static HopView toHopView(Hop hop) {
    return new HopView(
        toNodeView(hop.from()),
        toEdgeView(hop.edge()),
        toNodeView(hop.to()),
        hop.traversedBackwards());
  }

  static PathView toPathView(PathResult path) {
    return new PathView(path.hops().stream().map(ViewMapper::toHopView).toList());
  }

  static List<PathView> toPathViews(List<PathResult> paths) {
    return paths.stream().map(ViewMapper::toPathView).toList();
  }
}
