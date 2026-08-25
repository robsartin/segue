package com.robsartin.segue.wikidata;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.Candidate;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.port.ExpandContext;
import com.robsartin.segue.port.ExpandResult;
import java.time.Clock;
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
   * The Proposition (2005 film by John Hillcoat). A work, not a person — Wikidata states creative
   * relations (director, composer, writer) ON the work, not on the person (see the class-level
   * known limitation in ClaimMapper), so a person seed is not guaranteed to have any whitelisted
   * claims to find. Expanding a work is. Confirmed live at https://www.wikidata.org/wiki/Q180337.
   *
   * <p>The stub-backed fixtures in this package (proposition-claims.json et al.) originally used a
   * different, real-but-unrelated QID here — see CLAUDE.md's gotchas section — rather than a
   * deliberately invalid placeholder like {@code Fixture}'s {@code Q9000xx} range. A fixture about
   * a real entity should be true about it, so the fixtures now use this same id.
   */
  private static final String PROPOSITION = "Q180337";

  /**
   * Nick Cave and the Bad Seeds. A GROUP, and the seed that expanded to nothing at all before ADR
   * 36: the band's own item carries only P527, and P463 lives on each member.
   */
  private static final String BAD_SEEDS = "Q1051182";

  private final WikidataEntityResolver resolver = new WikidataEntityResolver(new WikidataClient());

  private WikidataSourceAdapter adapter() {
    return new WikidataSourceAdapter(resolver, WikidataClient.queryService(), Clock.systemUTC());
  }

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
        adapter()
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

  @Test
  @DisplayName("a PERSON seed now reaches the works that name them, not just their memberships")
  void personSeedReachesTheWorks() {
    // Issue #20's first symptom, against the live API. Before ADR 36 this returned exactly four
    // MEMBER_OF edges, because those are the only vocabulary claims stated on Nick Cave's own
    // item. A count is not asserted — Wikidata changes — but "strictly more than the four
    // forward claims, and at least one creative role among them" is the property that broke.
    ExpandResult result =
        adapter()
            .expand(new NodeRecord(CAVE, NodeKind.PERSON, "Nick Cave"), new ExpandContext(200));

    assertThat(result.sourceUnavailable()).isFalse();
    assertThat(result.assertions()).hasSizeGreaterThan(4);
    assertThat(result.assertions())
        .extracting(AssertionRecord::typeCode)
        .containsAnyOf("COMPOSED_FOR", "ACTED_IN", "WROTE_SCREENPLAY_FOR", "DIRECTED", "AUTHORED");
    // The inline identities are the reason this costs two calls rather than seventy-odd.
    assertThat(result.neighbors()).isNotEmpty();
    assertThat(result.neighbors()).allSatisfy(n -> assertThat(n.label()).isNotBlank());
  }

  @Test
  @DisplayName("a GROUP seed now finds its members, which reverse-P463 knows and P527 does not")
  void groupSeedFindsItsMembers() {
    // Issue #20's second symptom: this seed returned ZERO edges. Both halves of the fix show up
    // here — P527 gives HAS_PART from the band's own item, and the reverse P463 lookup gives
    // MEMBER_OF from the members', which is the half that includes Mick Harvey and Blixa
    // Bargeld. Asserting on MEMBER_OF specifically is what would catch a regression to the
    // P527-only fallback, since that alone would still leave this list non-empty.
    ExpandResult result =
        adapter()
            .expand(
                new NodeRecord(BAD_SEEDS, NodeKind.GROUP, "Nick Cave and the Bad Seeds"),
                new ExpandContext(200));

    assertThat(result.sourceUnavailable()).isFalse();
    assertThat(result.assertions()).isNotEmpty();
    assertThat(result.assertions())
        .filteredOn(a -> a.typeCode().equals("MEMBER_OF"))
        .extracting(AssertionRecord::fromQid)
        .contains(CAVE);
  }

  @Test
  @DisplayName("the Query Service still answers the reverse question for the whole vocabulary")
  void reverseLookupStillWorks() {
    // The positive control for ADR 36 specifically. A recorded SPARQL fixture cannot tell you
    // that WDQS changed its result shape, renamed a binding, or started refusing the query.
    ReverseClaims.Result found =
        new ReverseClaims(WikidataClient.queryService())
            .lookup(CAVE, 200, Clock.systemUTC().instant());

    assertThat(found.assertions()).isNotEmpty();
    assertThat(found.assertions())
        .allSatisfy(
            a -> {
              assertThat(a.provenance().sourceId()).isEqualTo("wikidata");
              assertThat(a.provenance().confidence()).isEqualTo(0.80);
              assertThat(a.typeCode()).isNotBlank();
            });
    assertThat(found.neighbors()).isNotEmpty();
    assertThat(found.neighbors()).allSatisfy(n -> assertThat(n.qid()).matches("Q\\d+"));
  }
}
