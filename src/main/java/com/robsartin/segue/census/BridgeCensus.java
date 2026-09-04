package com.robsartin.segue.census;

import com.robsartin.segue.domain.EdgeRecord;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.export.LogProjection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * What the MusicBrainz adapter reached, and how much of it the graph can describe (ADR 55, issue
 * #167).
 *
 * <p><b>Issue #227 asked for "how many have classes the bridge supplied", and the log cannot say
 * so.</b> {@code MusicBrainzSourceAdapter.toNeighbour} stamps a bridge-supplied neighbour claim
 * {@code new Provenance("wikidata", neighbour.qid(), assertedAt, 1.00)}, and its own Javadoc says
 * why: the claim "is byte-identical to what {@code ReverseClaims} and {@code
 * WikidataEntityResolver.fetch} would have produced for the same entity, because it is the same
 * claim from the same source". Both of those build exactly that provenance — same source id, same
 * reference, same confidence. Stamping it {@code musicbrainz} would attribute Wikidata's classes to
 * a database that states none, which ADR 61 refuses. So there is no marker to count, deliberately,
 * and separating the two would be a change to what the log records rather than a count over it.
 *
 * <p><b>What is counted instead is the shape of the residual ADR 55 and #167 ask about</b>: how
 * many entities a MusicBrainz-sourced edge names, and how many of those the fold can describe at
 * all. An entity reached and undescribed is the one that costs a fetch.
 *
 * <p><b>"Carries a MusicBrainz id" is read as "a MusicBrainz-sourced edge names it".</b> No MBID is
 * stored per entity anywhere — {@code NodeRecord} is {@code (qid, kind, label, instanceOf)} by ADR
 * 22 clause 2 — and the only MBIDs in the log are inside the {@code sourceRef} of a MusicBrainz
 * edge claim. Counting distinct MBIDs would mean parsing a citation, which is the one kind of
 * string this tool exists not to print.
 *
 * @param entitiesReached distinct endpoints of folded edges carrying a {@code musicbrainz}
 *     provenance
 * @param entitiesReachedWithClasses how many of those have a node in the fold stating at least one
 *     {@code P31} class — the half ADR 42 says a kind can be re-derived from
 */
public record BridgeCensus(int entitiesReached, int entitiesReachedWithClasses) {

  /**
   * The source id a MusicBrainz claim carries in the log.
   *
   * <p>A literal here rather than the adapter's own constant, for two reasons that point the same
   * way. {@code ArchitectureTest.theCensusOpensNothingElse} bans {@code musicbrainz} as a package,
   * for the exporter's reason — the adapter offers a census nothing but an HTTP client. And the log
   * holds <em>text</em>: ADR 19 forbids deleting a row, so a claim written by an adapter version
   * that no longer exists is still counted, and reading the value the log actually holds is the
   * question rather than a shortcut to it.
   */
  private static final String MUSICBRAINZ = "musicbrainz";

  public static BridgeCensus of(LogProjection projection) {
    Objects.requireNonNull(projection, "projection");
    Set<String> reached = new LinkedHashSet<>();
    for (EdgeRecord edge : projection.edges()) {
      if (edge.sources().stream().map(Provenance::sourceId).anyMatch(MUSICBRAINZ::equals)) {
        reached.add(edge.fromQid());
        reached.add(edge.toQid());
      }
    }
    int described =
        (int)
            reached.stream()
                .map(projection.nodes()::get)
                .filter(node -> node != null && !node.instanceOf().isEmpty())
                .count();
    return new BridgeCensus(reached.size(), described);
  }
}
