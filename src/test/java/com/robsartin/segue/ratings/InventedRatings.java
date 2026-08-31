package com.robsartin.segue.ratings;

import com.robsartin.segue.domain.AffinityRecord;
import com.robsartin.segue.domain.LocalEntity;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.port.AffinityStore;
import com.robsartin.segue.port.AssertionLog;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Invented ratings, invented entities, and the two fake stores this package's tests join across.
 *
 * <p><b>Every value here is made up.</b> ADR 33, as amended by issue #37, makes the filesystem the
 * boundary rather than repository visibility - this repository is public - so a fixture written
 * from a real listing is one of the few ways the only personal data segue holds could leak. The
 * qids are the graph fixture's unallocatable stand-ins (ADR 58), the labels are of things that do
 * not exist, and the notes are deliberately unlike anything anyone would write.
 */
final class InventedRatings {

  static final Instant EARLY = Instant.parse("2026-02-01T08:00:00Z");
  static final Instant LATE = Instant.parse("2026-04-01T08:00:00.500Z");

  static final String QUARTET = "Q0900001";
  static final String NOVEL = "Q0900002";
  static final String VANISHED = "Q0900003";

  /**
   * Two leading zeros: an entity the owner minted himself (#92), not one of ADR 58's
   * single-leading-zero stand-ins. Rateable like anything else in the graph, and the log holds it
   * as a {@code LocalEntity} rather than a {@code NodeAssertion} - which is the whole reason this
   * constant exists.
   */
  static final String MINTED = "Q00900042";

  static final String QUARTET_LABEL = "The Invented Quartet";
  static final String NOVEL_LABEL = "A Placeholder Novel";
  static final String MINTED_LABEL = "A Book No Source Knows";
  static final String QUARTET_NOTE = "a note about a band nobody has heard of";
  static final String NOVEL_NOTE = "a second invented note, unlike the first";

  private static final Provenance SOURCE =
      new Provenance("wikidata", "S-1", Instant.parse("2026-01-01T00:00:00Z"), 1.0);

  private InventedRatings() {}

  /** A node claim, the way the log holds one. */
  static NodeAssertion node(String qid, String label) {
    return new NodeAssertion(qid, NodeKind.WORK, label, SOURCE);
  }

  /** The owner's own claim that something exists, the way the log holds one (#92). */
  static LocalEntity minted(String qid, String label) {
    return LocalEntity.minted(qid, NodeKind.WORK, label, EARLY);
  }

  /** A log that answers {@code readAll} from a list and counts how often it is asked. */
  static final class FakeAssertionLog implements AssertionLog {

    private final List<LoggedAssertion> assertions = new ArrayList<>();
    private int reads;

    FakeAssertionLog with(LoggedAssertion... claims) {
      assertions.addAll(List.of(claims));
      return this;
    }

    int reads() {
      return reads;
    }

    @Override
    public void append(LoggedAssertion assertion) {
      throw new UnsupportedOperationException("the ratings tool never writes");
    }

    @Override
    public List<LoggedAssertion> readAll() {
      reads++;
      return List.copyOf(assertions);
    }

    @Override
    public void close() {}
  }

  /** A taste store that answers {@code readAll} from a map, in insertion order. */
  static final class FakeAffinityStore implements AffinityStore {

    private final Map<String, AffinityRecord> ratings = new LinkedHashMap<>();

    FakeAffinityStore rated(String qid, int rating, String note, Instant updatedAt) {
      ratings.put(qid, new AffinityRecord(qid, rating, note, updatedAt));
      return this;
    }

    @Override
    public void put(AffinityRecord affinity) {
      throw new UnsupportedOperationException("the ratings tool never writes");
    }

    @Override
    public void updateRating(String qid, int rating, Instant updatedAt) {
      throw new UnsupportedOperationException("the ratings tool never writes");
    }

    @Override
    public Optional<AffinityRecord> find(String qid) {
      return Optional.ofNullable(ratings.get(qid));
    }

    @Override
    public List<AffinityRecord> readAll() {
      return List.copyOf(ratings.values());
    }

    /**
     * Deliberately unusable. The note-free bulk read exists for the recommender (issue #85); the
     * listing tool reads whole rows, and a fake that answered both would let this one quietly start
     * using the wrong one without failing anything.
     */
    @Override
    public Map<String, Integer> readRatings() {
      throw new UnsupportedOperationException("the ratings tool reads whole rows, notes included");
    }

    @Override
    public void close() {}
  }
}
