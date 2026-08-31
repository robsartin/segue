package com.robsartin.segue.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the degree floor admitted and what it held out, as figures a later run can be read against
 * (issue #135).
 */
class FloorReadingTest {

  /** Invented, per ADR 51 and issue #141: these qids are arbitrary and name nothing real. */
  private static Recommendation candidate(String qid, int degree, int intermediates) {
    List<SharedIntermediate> shared =
        IntStream.range(0, intermediates)
            .mapToObj(i -> new SharedIntermediate("Q900101", "Q9002" + i, 4, 1.0))
            .toList();
    return new Recommendation(
        new NodeRecord(qid, NodeKind.GROUP, "invented " + qid), 0.5, degree, shared);
  }

  @Test
  @DisplayName("counts the ranked entries sitting exactly on the floor, because they move first")
  void countsHeadEntriesOnTheFloor() {
    List<Recommendation> head =
        List.of(candidate("Q900301", 5, 1), candidate("Q900302", 5, 2), candidate("Q900303", 9, 3));

    FloorReading reading = FloorReading.of(head, head, 5, 0, 0);

    assertThat(reading.headOnTheFloor()).isEqualTo(2);
    assertThat(reading.head()).isEqualTo(3);
    assertThat(reading.floor()).isEqualTo(5);
  }

  @Test
  @DisplayName("counts the ranked entries whose every edge is already being counted as evidence")
  void countsHeadEntriesWhereEveryEdgeIsEvidence() {
    List<Recommendation> head =
        List.of(candidate("Q900301", 5, 5), candidate("Q900302", 9, 3), candidate("Q900303", 2, 2));

    FloorReading reading = FloorReading.of(head, head, 5, 0, 0);

    assertThat(reading.headEveryEdgeCounted()).isEqualTo(2);
  }

  @Test
  @DisplayName("the median degree is a degree some candidate has, not an average of two")
  void medianDegreeIsAnActualDegree() {
    List<Recommendation> pool = List.of(candidate("Q900301", 2, 1), candidate("Q900302", 8, 1));

    FloorReading reading = FloorReading.of(pool, pool, 2, 0, 0);

    assertThat(reading.poolMedianDegree()).isEqualTo(8);
    assertThat(reading.headMedianDegree()).isEqualTo(8);
  }

  @Test
  @DisplayName("the pool and the ranked head are counted separately, because only the head is read")
  void poolAndHeadAreCountedSeparately() {
    List<Recommendation> pool =
        List.of(candidate("Q900301", 5, 1), candidate("Q900302", 7, 1), candidate("Q900303", 9, 1));
    List<Recommendation> head = pool.subList(0, 2);

    FloorReading reading = FloorReading.of(pool, head, 5, 0, 0);

    assertThat(reading.pool()).isEqualTo(3);
    assertThat(reading.head()).isEqualTo(2);
    assertThat(reading.poolMedianDegree()).isEqualTo(7);
    assertThat(reading.headMedianDegree()).isEqualTo(7);
  }

  @Test
  @DisplayName("what the floor held out is carried through, degree-one growth counted apart")
  void carriesWhatTheFloorHeldOut() {
    List<Recommendation> head = List.of(candidate("Q900301", 5, 1));

    FloorReading reading = FloorReading.of(head, head, 5, 7669, 5874);

    assertThat(reading.heldOut()).isEqualTo(7669);
    assertThat(reading.heldOutAtDegreeOne()).isEqualTo(5874);
  }

  @Test
  @DisplayName("a run that found nothing reads as zeroes rather than throwing")
  void emptyRunReadsAsZeroes() {
    FloorReading reading = FloorReading.of(List.of(), List.of(), 5, 0, 0);

    assertThat(reading.pool()).isZero();
    assertThat(reading.head()).isZero();
    assertThat(reading.poolMedianDegree()).isZero();
    assertThat(reading.headMedianDegree()).isZero();
    assertThat(reading.headOnTheFloor()).isZero();
    assertThat(reading.headEveryEdgeCounted()).isZero();
  }
}
