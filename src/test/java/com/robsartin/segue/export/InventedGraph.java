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
 * <p><b>The QIDs are invented in the sense that nobody looked them up — not in the sense that they
 * denote nothing.</b> Some of the ids below resolve to real Wikidata entities. They were picked
 * from a {@code Q900xxx} range described as a placeholder range shared with {@code
 * fixture.Fixture}; that range was never free, and {@code Fixture} has since moved to ids
 * Wikibase's item-id grammar refuses (ADR 58). This file has not, because that family is shared
 * across many unrelated test files and moving one of them would split the convention rather than
 * mend it. Tracked as <a href="https://github.com/robsartin/segue/issues/171">issue #171</a>;
 * nothing here depends on what any id denotes.
 *
 * <p>Nothing in this file is derived from a real graph, a real list or a real rating: ADR 40 and
 * issue #37 are explicit that this repository is public and that the personal data lives outside
 * it.
 */
final class InventedGraph {

  static final String WREN = "Q900101";
  static final String KETTLES = "Q900102";
  static final String HOLLOW_TIDE = "Q900103";
  static final String MARLOW = "Q900104";
  static final String PRIZE = "Q900105";

  /**
   * The id Wikidata turned out to have for something the owner had already minted (#92).
   * Allocatable shape, from the same invented family as the ids above and carrying the same issue
   * #171 debt.
   */
  static final String PRESSING = "Q900106";

  /**
   * Two ids the owner minted. Two leading zeros, which Wikibase's item-id grammar can never
   * allocate (ADR 58, ADR 59) - so these are deliberately not from the {@code Q900xxx} family, and
   * issue #171 does not reach them.
   */
  static final String ALMANAC = "Q001";

  static final String DEMO = "Q002";

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
