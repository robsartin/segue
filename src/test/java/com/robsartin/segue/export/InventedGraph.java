package com.robsartin.segue.export;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.port.AssertionLog;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Invented people, invented bands, invented QIDs.
 *
 * <p>Q90xxxx is the placeholder range this project already uses in {@code fixture.Fixture}, chosen
 * so nothing here can be mistaken for a real Wikidata identifier. Nothing in this file is derived
 * from a real graph, a real list or a real rating: ADR 40 and issue #37 are explicit that this
 * repository is public and that the personal data lives outside it.
 */
final class InventedGraph {

  static final String WREN = "Q900101";
  static final String KETTLES = "Q900102";
  static final String HOLLOW_TIDE = "Q900103";
  static final String MARLOW = "Q900104";
  static final String PRIZE = "Q900105";

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
