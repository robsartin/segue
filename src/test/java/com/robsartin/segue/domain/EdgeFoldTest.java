package com.robsartin.segue.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.fixture.Fixture;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The reference fold: what every GraphStore implementation must agree with, computed straight from
 * the assertion list. If an adapter disagrees with this, the adapter is wrong. Converted from the
 * old DomainSelfTest.
 */
class EdgeFoldTest {

  @Test
  @DisplayName("27 assertions fold into exactly 23 edges, and every edge keeps its sources")
  void assertionsCollapseIntoEdges() {
    Map<String, EdgeRecord> edges = fold();

    // 27 assertions: 3 pairs sharing a (from, type, to) triple from two real sources, plus the
    // owner's claim folding onto a fourth triple a real source already asserted (#92) — four
    // folds, 27 assertions fold to 23 edges. The 27th is the owner's standalone claim over two
    // local entities (#176), which folds onto nothing.
    assertThat(edges.size()).isEqualTo(23);
    assertThat(edges.values()).allSatisfy(e -> assertThat(e.sources()).isNotEmpty());
  }

  @Test
  @DisplayName("the Bad Seeds lineup in June 1984 excludes Ellis and includes Blixa")
  void timeTravelTo1984() {
    List<String> lineup = badSeedsLineupOn(LocalDate.of(1984, 6, 1));

    assertThat(lineup).hasSize(3);
    assertThat(lineup).doesNotContain(Fixture.ELLIS); // joined 1994
    assertThat(lineup).contains(Fixture.BLIXA); // 1983-2003
  }

  @Test
  @DisplayName("the Bad Seeds lineup in June 2010 drops Mick Harvey and includes Ellis")
  void timeTravelTo2010() {
    List<String> lineup = badSeedsLineupOn(LocalDate.of(2010, 6, 1));

    assertThat(lineup).doesNotContain(Fixture.HARVEY_MICK); // left 2009
    assertThat(lineup).contains(Fixture.ELLIS);
  }

  @Test
  @DisplayName("corroboration counts distinct sources, and no model-only edge reaches two")
  void corroborationCountsDistinctSources() {
    List<EdgeRecord> corroborated =
        fold().values().stream().filter(e -> e.corroboration() >= 2).toList();

    assertThat(corroborated).hasSize(3);
    assertThat(corroborated).noneMatch(EdgeRecord::isUncorroboratedHypothesis);
  }

  @Test
  @DisplayName("model hypotheses stay quarantined")
  void hypothesesRemainQuarantined() {
    List<EdgeRecord> hypotheses =
        fold().values().stream().filter(EdgeRecord::isUncorroboratedHypothesis).toList();

    assertThat(hypotheses).hasSize(2);
    assertThat(hypotheses)
        .allSatisfy(e -> assertThat(e.bestConfidence()).isLessThanOrEqualTo(0.30));
  }

  @Test
  @DisplayName("two different relationship types between the same pair stay separate edges")
  void multigraphKeepsParallelTypes() {
    List<EdgeRecord> caveToProposition =
        fold().values().stream()
            .filter(e -> e.fromQid().equals(Fixture.CAVE) && e.toQid().equals(Fixture.PROPOSITION))
            .toList();

    assertThat(caveToProposition).hasSize(2);
    assertThat(caveToProposition)
        .extracting(EdgeRecord::typeCode)
        .containsExactlyInAnyOrder("WROTE_SCREENPLAY_FOR", "COMPOSED_FOR");
  }

  private static List<String> badSeedsLineupOn(LocalDate when) {
    return fold().values().stream()
        .filter(e -> e.toQid().equals(Fixture.BAD_SEEDS) && e.typeCode().equals("MEMBER_OF"))
        .filter(e -> e.validAt(when))
        .map(EdgeRecord::fromQid)
        .sorted()
        .toList();
  }

  /** Mirrors what each adapter must do: merge by (from, type, to), appending provenance. */
  private static Map<String, EdgeRecord> fold() {
    Map<String, EdgeRecord> byKey = new LinkedHashMap<>();
    for (AssertionRecord a : Fixture.assertions()) {
      EdgeRecord existing = byKey.get(a.edgeKey());
      if (existing == null) {
        byKey.put(
            a.edgeKey(),
            new EdgeRecord(
                a.fromQid(),
                a.toQid(),
                a.typeCode(),
                a.validFrom(),
                a.validTo(),
                List.of(a.provenance())));
      } else {
        List<Provenance> merged = new ArrayList<>(existing.sources());
        merged.add(a.provenance());
        byKey.put(
            a.edgeKey(),
            new EdgeRecord(
                a.fromQid(),
                a.toQid(),
                a.typeCode(),
                existing.validFrom() != null ? existing.validFrom() : a.validFrom(),
                existing.validTo() != null ? existing.validTo() : a.validTo(),
                merged));
      }
    }
    return byKey;
  }
}
