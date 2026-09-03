package com.robsartin.segue.export;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.LocalEntity;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.OwnerEdge;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.domain.SameAs;
import com.robsartin.segue.port.AssertionLog;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Invented people, invented bands, invented QIDs.
 *
 * <p><b>The QIDs denote nothing, and are shaped so that they never can.</b> They were once picked
 * from a {@code Q900xxx} range described as free; it was not, and most of them resolved to real
 * entities. Every id here now takes a shape Wikibase's item-id grammar refuses — a leading zero for
 * a stand-in (ADR 58), two for one the owner minted (ADR 59), and eleven digits for the two that
 * stand on a merge's canonical side and so may not carry a leading zero (ADR 62). Issue #171 moved
 * them; nothing here depends on what any id denotes.
 *
 * <p>Nothing in this file is derived from a real graph, a real list or a real rating: ADR 40 and
 * issue #37 are explicit that this repository is public and that the personal data lives outside
 * it.
 */
final class InventedGraph {

  static final String WREN = "Q0900101";

  /**
   * A merge's canonical side ({@code BothFoldsAgreeTest}'s {@code merged(DEMO, KETTLES)}, via
   * {@link #merged}), so {@code SameAs.declared} runs it through {@code Qid.checkCanonicalSide} and
   * the leading zero its neighbours took is not available to it. It carries ADR 62's eleven-digit
   * shape instead, keeping the digits of the band-A id it migrated from in its last places.
   */
  static final String KETTLES = "Q10000900102";

  static final String HOLLOW_TIDE = "Q0900103";
  static final String MARLOW = "Q0900104";
  static final String PRIZE = "Q0900105";

  /**
   * The id Wikidata turned out to have for something the owner had already minted (#92) — a merge's
   * canonical side ({@code BothFoldsAgreeTest}, via {@link #merged}), so it takes ADR 62's
   * eleven-digit shape for the reason {@link #KETTLES} does, and keeps the digits of the band-A id
   * it migrated from in its last places.
   */
  static final String PRESSING = "Q10000900106";

  /**
   * A second canonical id, for a merge whose local side carries edges on both sides of itself
   * (#178). ADR 62's shape, for the reason {@link #KETTLES} takes it, keeping the band-A digits.
   */
  static final String WATERMARK = "Q10000900107";

  /**
   * A third, for the merge whose local side was named by a plain node claim rather than minted
   * (#178, spec ruling 2 - "a later claim naming the local id, by a path that bypasses the tool").
   * ADR 62's shape, same as the other two canonical sides here.
   */
  static final String STANDING = "Q10000900108";

  /**
   * A fourth canonical id: the wrong Wikidata item, named by a merge the owner has since corrected
   * (#221). ADR 62's eleven-digit shape, for the reason {@link #KETTLES} takes it, keeping the
   * band-A digits of the ids beside it.
   */
  static final String MISHEARD = "Q10000900109";

  /**
   * Two ids the owner minted. Two leading zeros, which Wikibase's item-id grammar can never
   * allocate (ADR 58, ADR 59) - so these are deliberately not from the {@code Q900xxx} family, and
   * issue #171 does not reach them.
   */
  static final String ALMANAC = "Q001";

  static final String DEMO = "Q002";

  /** A third, and the one the folds are asked the hardest question about (#178). */
  static final String LEDGER = "Q003";

  /**
   * A fourth, deliberately NOT minted anywhere: it is named by a plain {@link NodeAssertion}, the
   * shape spec ruling 2 says the fold must not assume away (#178). Unreachable from today's sources
   * - no source can allocate a {@code Q00} id - and that is exactly why only a test can hold the
   * two folds to answering it alike.
   */
  static final String BYPASS = "Q004";

  /**
   * A fifth, minted and then merged onto the <em>same</em> canonical id as {@link #ALMANAC} — the
   * owner minting one thing twice and saying so, which is a real path (#178). An owner edge between
   * the two folds to an edge from that canonical id to itself, and a self-loop is a claim that a
   * thing relates to itself, which neither a source nor the owner ever made.
   */
  static final String TWICE = "Q005";

  /**
   * A class no whitelist knows, so a claim stating it re-derives to {@code CONCEPT} — {@code
   * KindMapper.rederive}'s "when classes ARE stated, this list is the authority, including when it
   * answers CONCEPT" (ADR 42). ADR 58's leading-zero shape, the next free number in this file's own
   * sequence, so it needs no entry in {@code StandInQidsDenoteNothingTest}'s allowlist (#222).
   */
  static final String UNKNOWN_CLASS = "Q0900109";

  /**
   * A sixth id the owner minted, and the one issue #221 turns on: merged onto {@link #MISHEARD} and
   * then — the correction — onto {@link #WATERMARK}. Two leading zeros, for {@link #ALMANAC}'s
   * reason.
   */
  static final String CORRECTED = "Q006";

  private static final Instant WHEN = Instant.parse("2026-01-01T00:00:00Z");

  private InventedGraph() {}

  static Provenance sourced() {
    return new Provenance("invented", "invented:1", WHEN, 1.0);
  }

  static Provenance secondSource() {
    return new Provenance("also-invented", "also-invented:1", WHEN, 0.8);
  }

  static Provenance guessed() {
    return new Provenance("llm:invented", "turn-1", WHEN, 0.3);
  }

  static NodeAssertion node(String qid, NodeKind kind, String label) {
    return new NodeAssertion(qid, kind, label, sourced());
  }

  /** A node claim that also recorded the classes its kind was derived from (issue #60). */
  static NodeAssertion node(String qid, NodeKind kind, String label, List<String> instanceOf) {
    return new NodeAssertion(qid, kind, label, instanceOf, sourced());
  }

  /** An entity the owner minted: no provenance, because the owner minting it is the source. */
  static LocalEntity minted(String qid, NodeKind kind, String label) {
    return LocalEntity.minted(qid, kind, label, WHEN);
  }

  /** A relationship the owner asserted himself. */
  static OwnerEdge owned(String from, String to, String type) {
    return OwnerEdge.claimed(from, to, type, WHEN);
  }

  /** The owner saying the thing he minted turned out to be a Wikidata item. */
  static SameAs merged(String localQid, String canonicalQid) {
    return SameAs.declared(localQid, canonicalQid, WHEN);
  }

  static AssertionRecord edge(String from, String to, String type) {
    return edge(from, to, type, sourced());
  }

  static AssertionRecord edge(String from, String to, String type, Provenance provenance) {
    return new AssertionRecord(from, to, type, null, null, provenance);
  }

  /**
   * An in-memory {@link AssertionLog}. Appends are how a test builds one; the exporter never calls
   * that method, and {@code ArchitectureTest.theExporterOnlyReads} is what says so.
   */
  static final class FakeAssertionLog implements AssertionLog {

    private final List<LoggedAssertion> assertions = new ArrayList<>();

    FakeAssertionLog with(LoggedAssertion... more) {
      assertions.addAll(List.of(more));
      return this;
    }

    @Override
    public void append(LoggedAssertion assertion) {
      assertions.add(assertion);
    }

    @Override
    public List<LoggedAssertion> readAll() {
      return List.copyOf(assertions);
    }

    @Override
    public void close() {}
  }
}
