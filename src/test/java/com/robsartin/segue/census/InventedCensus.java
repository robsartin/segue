package com.robsartin.segue.census;

import com.robsartin.segue.domain.AffinityRecord;
import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.LocalEntity;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.OwnerEdge;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.domain.Retraction;
import com.robsartin.segue.domain.SameAs;
import com.robsartin.segue.port.AffinityStore;
import com.robsartin.segue.port.AssertionLog;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One invented log, small enough to count by hand, and the two fake stores the census reads.
 *
 * <p><b>Every value here is made up.</b> ADR 40 and issue #37 are explicit that this repository is
 * public and the personal data lives outside it. The ids take shapes Wikibase's grammar refuses — a
 * leading zero for a stand-in (ADR 58), two for one the owner minted (ADR 59), eleven digits for a
 * merge's canonical side (ADR 62) — so none of them denotes anything, ever.
 *
 * <p><b>It is not {@code export.InventedGraph} widened, and that is deliberate.</b> That class is
 * package-private in {@code export}, as {@code ratings.InventedRatings} is in {@code ratings} and
 * {@code recommend.InventedWorld} is in {@code recommend}: one invented fixture per package is this
 * repository's pattern. Reaching across for one would be the dependency direction the sibling
 * fences forbid, arriving through the test tree.
 *
 * <p><b>The log is designed so that no count is trivially right.</b> Two sources agree on one edge
 * and one source is a model, so corroboration has three buckets; one entity is retracted after it
 * has both a node claim and an edge; one edge names an endpoint nothing ever claims, so the
 * dangling count is not zero; one local id is merged twice with an owner edge naming the first
 * canonical id in between and one is merged twice with nothing naming it, so standing, superseded
 * and superseded-but-edge-referenced are each non-empty and different; one node's degree is exactly
 * ADR 57's floor and one is above it, so "at or below the floor" cannot pass with {@code &lt;}.
 */
final class InventedCensus {

  /** A source-claimed person, and the busiest node in the fixture. */
  static final String WREN = "Q0900201";

  /** A source-claimed group. */
  static final String HOLLOW = "Q0900202";

  /** A source-claimed work, and the node whose degree is exactly ADR 57's floor. */
  static final String PRIZE = "Q0900203";

  /** A source-claimed work, retracted at the end of the log along with the edge that names it. */
  static final String GONE = "Q0900204";

  /** The one entity carrying classes, and one of the two MusicBrainz reached. */
  static final String NEIGHBOUR = "Q0900205";

  /** Named as an edge endpoint and never claimed as a node — the fixture's one dangling edge. */
  static final String UNCLAIMED = "Q0900206";

  static final String THIRD = "Q0900207";
  static final String FOURTH = "Q0900208";

  /**
   * A class no whitelist knows, so a claim stating it re-derives to {@code CONCEPT} — {@code
   * KindMapper.rederive}'s "when classes ARE stated, this list is the authority, including when it
   * answers CONCEPT" (ADR 42). Using a real class id would need an entry in {@code
   * StandInQidsDenoteNothingTest}'s allowlist for no gain: what the bridge count asks is whether
   * classes are there at all.
   */
  static final String UNKNOWN_CLASS = "Q0900301";

  /** Minted, merged onto {@link #FIRST_CANONICAL} and then corrected onto {@link #CORRECTED}. */
  static final String LEDGER = "Q0021";

  /** Minted and merged once, so its stand-in carries the edges. */
  static final String SKETCH = "Q0022";

  /** Minted, merged twice, and nothing ever names the first canonical id it was merged onto. */
  static final String DOUBLE = "Q0023";

  /** Superseded, and kept alive by an owner edge claimed against it while it stood (#221). */
  static final String FIRST_CANONICAL = "Q10000900201";

  /** What {@link #LEDGER} is corrected onto. */
  static final String CORRECTED = "Q10000900202";

  /** {@link #SKETCH}'s canonical side. */
  static final String SETTLED = "Q10000900203";

  /** Superseded with nothing naming it, so it gets no stand-in at all. */
  static final String ABANDONED = "Q10000900204";

  /** What {@link #DOUBLE} is corrected onto, and the one stand-in that ends with no edge. */
  static final String REROUTED = "Q10000900205";

  static final String WREN_LABEL = "Wren Alderman";
  static final String LEDGER_LABEL = "A Ledger Nobody Printed";

  private static final Instant WHEN = Instant.parse("2026-01-01T00:00:00Z");

  private InventedCensus() {}

  static Provenance sourced() {
    return new Provenance("invented", "invented:1", WHEN, 1.0);
  }

  static Provenance secondSource() {
    return new Provenance("also-invented", "also-invented:1", WHEN, 0.8);
  }

  static Provenance guessed() {
    return new Provenance("llm:invented", "turn-1", WHEN, 0.3);
  }

  /**
   * The source id a MusicBrainz edge claim carries. A literal, because the log holds text and the
   * adapter's own constant is private to a package {@code census} may not import.
   */
  static Provenance fromMusicBrainz() {
    return new Provenance("musicbrainz", "artist/invented#member of band:invented", WHEN, 0.8);
  }

  static NodeAssertion node(String qid, NodeKind kind, String label) {
    return new NodeAssertion(qid, kind, label, sourced());
  }

  static NodeAssertion node(String qid, NodeKind kind, String label, List<String> instanceOf) {
    return new NodeAssertion(qid, kind, label, instanceOf, sourced());
  }

  static LocalEntity minted(String qid, String label) {
    return LocalEntity.minted(qid, NodeKind.WORK, label, WHEN);
  }

  static OwnerEdge owned(String from, String to) {
    return OwnerEdge.claimed(from, to, "INFLUENCED_BY", WHEN);
  }

  static SameAs merged(String localQid, String canonicalQid) {
    return SameAs.declared(localQid, canonicalQid, WHEN);
  }

  static AssertionRecord edge(String from, String to, String type, Provenance provenance) {
    return new AssertionRecord(from, to, type, null, null, provenance);
  }

  static Retraction retract(String qid) {
    return new Retraction(qid, "an invented reason, unlike anything a real one would say", WHEN);
  }

  /**
   * The fixture log, thirty rows in this exact order. Row numbers are cited by every hand-counted
   * expectation in this package, so an insertion renumbers them all.
   *
   * <p>Rows are 1-indexed from the first {@code node(} line below — the busiest node is row 1.
   */
  static List<LoggedAssertion> log() {
    return List.of(
        node(WREN, NodeKind.PERSON, WREN_LABEL),
        node(HOLLOW, NodeKind.GROUP, "The Hollow Tide"),
        node(PRIZE, NodeKind.WORK, "A Placeholder Prize"),
        node(GONE, NodeKind.WORK, "A Thing Taken Back"),
        node(NEIGHBOUR, NodeKind.PERSON, "A Neighbour", List.of(UNKNOWN_CLASS)),
        node(THIRD, NodeKind.PERSON, "A Third Invented Person"),
        node(FOURTH, NodeKind.PERSON, "A Fourth Invented Person"),
        edge(WREN, HOLLOW, "MEMBER_OF", sourced()),
        edge(WREN, HOLLOW, "MEMBER_OF", secondSource()),
        edge(NEIGHBOUR, HOLLOW, "MEMBER_OF", sourced()),
        edge(WREN, PRIZE, "INFLUENCED_BY", guessed()),
        edge(GONE, WREN, "MEMBER_OF", sourced()),
        edge(WREN, NEIGHBOUR, "MEMBER_OF", fromMusicBrainz()),
        edge(WREN, THIRD, "MEMBER_OF", sourced()),
        edge(WREN, FOURTH, "MEMBER_OF", sourced()),
        edge(THIRD, PRIZE, "INFLUENCED_BY", sourced()),
        edge(FOURTH, PRIZE, "INFLUENCED_BY", sourced()),
        edge(WREN, UNCLAIMED, "MEMBER_OF", sourced()),
        minted(LEDGER, LEDGER_LABEL),
        minted(SKETCH, "A Sketch Nobody Kept"),
        minted(DOUBLE, "A Thing Minted Twice"),
        owned(LEDGER, WREN),
        merged(LEDGER, FIRST_CANONICAL),
        owned(FIRST_CANONICAL, PRIZE),
        merged(LEDGER, CORRECTED),
        merged(SKETCH, SETTLED),
        owned(SKETCH, PRIZE),
        merged(DOUBLE, ABANDONED),
        merged(DOUBLE, REROUTED),
        retract(GONE));
  }

  /** A log that answers {@code readAll} from a list. */
  static final class FakeAssertionLog implements AssertionLog {

    private final List<LoggedAssertion> assertions = new ArrayList<>();

    FakeAssertionLog with(List<LoggedAssertion> claims) {
      assertions.addAll(claims);
      return this;
    }

    FakeAssertionLog with(LoggedAssertion... claims) {
      assertions.addAll(List.of(claims));
      return this;
    }

    @Override
    public void append(LoggedAssertion assertion) {
      throw new UnsupportedOperationException("the census never writes");
    }

    @Override
    public List<LoggedAssertion> readAll() {
      return List.copyOf(assertions);
    }

    @Override
    public void close() {}
  }

  /**
   * A taste store that answers the note-free bulk read and nothing else.
   *
   * <p>{@code readAll} and {@code find} throw, deliberately: the census reads scores through {@code
   * readRatings}, whose {@code Map<String, Integer>} has nowhere to put a note, and a fake that
   * answered the note-carrying reads would let this tool quietly start using one without failing
   * anything. That is {@code InventedRatings.FakeAffinityStore}'s discipline, inverted.
   */
  static final class FakeAffinityStore implements AffinityStore {

    private final Map<String, Integer> ratings = new LinkedHashMap<>();

    FakeAffinityStore rated(String qid, int rating) {
      ratings.put(qid, rating);
      return this;
    }

    @Override
    public void put(AffinityRecord affinity) {
      throw new UnsupportedOperationException("the census never writes");
    }

    @Override
    public void updateRating(String qid, int rating, Instant updatedAt) {
      throw new UnsupportedOperationException("the census never writes");
    }

    @Override
    public Optional<AffinityRecord> find(String qid) {
      throw new UnsupportedOperationException("the census reads scores, never a row with a note");
    }

    @Override
    public List<AffinityRecord> readAll() {
      throw new UnsupportedOperationException("the census reads scores, never a row with a note");
    }

    @Override
    public Map<String, Integer> readRatings() {
      return Map.copyOf(ratings);
    }

    @Override
    public void close() {}
  }
}
