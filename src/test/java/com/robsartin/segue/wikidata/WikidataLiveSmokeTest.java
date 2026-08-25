package com.robsartin.segue.wikidata;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.Candidate;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.port.ExpandContext;
import com.robsartin.segue.port.ExpandResult;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The positive control. Everything else in this package replays a recorded fixture, and a recorded
 * fixture passes forever against a dead endpoint — it cannot detect that Wikidata changed its
 * response shape.
 *
 * <p>Tagged {@code live} and excluded from CI, because it needs the network and can fail for
 * reasons unrelated to this code. Run it on purpose: {@code ./gradlew liveTest}.
 *
 * <p>This test has already paid for itself: on its first run it caught that the QID used here was
 * David Tennant's, not Nick Cave's. Every fixture-backed test in this package would have carried
 * that error indefinitely.
 */
@Tag("live")
class WikidataLiveSmokeTest {

  /** Nick Cave. A real, stable identifier with relations across music, film and literature. */
  private static final String CAVE = "Q192668";

  /**
   * The Proposition. A work, not a person — Wikidata states creative relations (director, composer,
   * writer) ON the work, not on the person (see the class-level known limitation in ClaimMapper),
   * so a person seed is not guaranteed to have any whitelisted claims to find. Expanding a work is.
   */
  private static final String PROPOSITION = "Q1194713";

  private final WikidataEntityResolver resolver = new WikidataEntityResolver(new WikidataClient());

  @Test
  @DisplayName("wbsearchentities still returns id, label and description")
  void searchStillWorks() {
    List<Candidate> hits = resolver.search("Nick Cave", null, 5);

    assertThat(hits).isNotEmpty();
    assertThat(hits).allSatisfy(c -> assertThat(c.qid()).matches("Q\\d+"));
    assertThat(hits).anySatisfy(c -> assertThat(c.description()).isNotNull());
  }

  @Test
  @DisplayName("wbgetentities still yields a labelled entity with a mappable P31")
  void fetchStillWorks() {
    Optional<NodeAssertion> cave = resolver.fetch(CAVE);

    assertThat(cave).isPresent();
    assertThat(cave.orElseThrow().label()).isEqualTo("Nick Cave");
    assertThat(cave.orElseThrow().kind()).isEqualTo(NodeKind.PERSON);
  }

  @Test
  @DisplayName("a real expansion still produces whitelisted, attributed claims")
  void expansionStillWorks() {
    ExpandResult result =
        new WikidataSourceAdapter(resolver, java.time.Clock.systemUTC())
            .expand(
                new NodeRecord(PROPOSITION, NodeKind.WORK, "The Proposition"),
                new ExpandContext(50));
    List<AssertionRecord> claims = result.assertions();

    // expand() swallows WikidataUnavailableException and returns empty (see
    // WikidataSourceAdapter) — allSatisfy alone passes vacuously on that empty list, so this
    // would stay green even if Wikidata were unreachable. isNotEmpty is the actual detection.
    assertThat(claims).isNotEmpty();
    // Not asserting a count: Wikidata changes. Asserting the shape still holds.
    assertThat(claims)
        .allSatisfy(
            c -> {
              assertThat(c.provenance().sourceId()).isEqualTo("wikidata");
              assertThat(c.provenance().confidence()).isBetween(0.80, 1.00);
              assertThat(c.typeCode()).isNotBlank();
            });
  }
}
