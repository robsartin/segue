package com.robsartin.segue.wikidata;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.Candidate;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.fixture.Fixture;
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
   * deliberately unallocatable stand-in like {@code Fixture}'s. A fixture about a real entity
   * should be true about it, so the fixtures now use this same id.
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
  @DisplayName("the classes added for issue #52 still place a real entity where they claim")
  void theWorkClassesStillMapRealEntities() {
    // The positive control for the whitelist itself, and the reason it is here rather than in
    // KindMapperTest: an offline test asserts that Q1261214 maps to WORK, which is true of
    // whatever Q1261214 turns out to be. Only a live call can say that the entity typed with it
    // is the television special this change was written for. Issue #52's rule reads "high-degree
    // CONCEPT" as "hub", so a class QID that is quietly wrong does not fail — it demotes a good
    // route and nothing notices.
    assertThat(kindOf("Q131806449")) // Saturday Night Live 50th Anniversary Special
        .isEqualTo(NodeKind.WORK);
    assertThat(kindOf("Q6650163")) // Little Girl Blue, a musical work/composition
        .isEqualTo(NodeKind.WORK);
    assertThat(kindOf("Q486688")) // Mötley Crüe, typed only as a heavy metal band
        .isEqualTo(NodeKind.GROUP);
    // The negative half, and the one that matters most: awards must stay CONCEPT (ADR 38), or
    // the specificity rule stops firing. The Kennedy Center Honors is the awkward case — its
    // only P31 is "award" itself, which is why excluding hubs by class was rejected.
    assertThat(kindOf("Q1738793")).isEqualTo(NodeKind.CONCEPT); // Kennedy Center Honors
  }

  private NodeKind kindOf(String qid) {
    return resolver
        .fetch(qid)
        .orElseThrow(() -> new AssertionError("no such entity: " + qid))
        .kind();
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
    // Issue #20's second symptom: this seed returned ZERO edges. The roster now arrives from the
    // reverse P463 lookup, which is the half that includes Mick Harvey and Blixa Bargeld.
    // Asserting on MEMBER_OF specifically is what would catch a regression to the P527-only
    // fallback, since that alone would still leave this list non-empty.
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
  @DisplayName("one membership is one edge, from both ends, against the live API")
  void oneMembershipIsOneEdge() {
    // Issue #33's acceptance criterion, executable. P463 and P527 are Wikidata inverses, so
    // before this both ends of the same membership were ingested: expanding Cave gave
    // `Cave MEMBER_OF Bad Seeds` AND `Bad Seeds HAS_PART Cave`, and expanding the band gave the
    // same pair from the other side. A fixture cannot prove this is fixed against the real data
    // — the fixture only holds the rows its author kept — so the check lives here.
    ExpandResult person =
        adapter()
            .expand(new NodeRecord(CAVE, NodeKind.PERSON, "Nick Cave"), new ExpandContext(200));
    ExpandResult band =
        adapter()
            .expand(
                new NodeRecord(BAD_SEEDS, NodeKind.GROUP, "Nick Cave and the Bad Seeds"),
                new ExpandContext(200));

    assertThat(person.sourceUnavailable()).isFalse();
    assertThat(band.sourceUnavailable()).isFalse();
    // With the Query Service reachable there is no degraded path, so P527 contributes nothing.
    assertThat(person.assertions())
        .extracting(AssertionRecord::typeCode)
        .doesNotContain("HAS_PART");
    assertThat(band.assertions()).extracting(AssertionRecord::typeCode).doesNotContain("HAS_PART");
    // The pair that was doubled: exactly one edge over it, in the direction that reads forwards.
    assertThat(person.assertions())
        .filteredOn(a -> a.fromQid().equals(BAD_SEEDS) || a.toQid().equals(BAD_SEEDS))
        .allSatisfy(
            a -> {
              assertThat(a.typeCode()).isEqualTo("MEMBER_OF");
              assertThat(a.fromQid()).isEqualTo(CAVE);
            });
    assertThat(band.assertions())
        .filteredOn(a -> a.fromQid().equals(CAVE) || a.toQid().equals(CAVE))
        .singleElement()
        .satisfies(a -> assertThat(a.typeCode()).isEqualTo("MEMBER_OF"));
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

  /**
   * The negative control for the test fixture's identifiers, and the test that would have caught
   * issue #141 the day the fixture was written.
   *
   * <p>The fixture ids were picked in the {@code Q9000xx} range on the assumption that a high
   * number would be unused. All but one of them resolved, the exception being a deleted item rather
   * than a free number. The fixture therefore told the suite that a real Wikidata entity is a
   * musician, a band or a film — and the offline tests, which never call Wikidata, could not
   * notice. That is this repository's own rule turned on itself: never invent an external
   * identifier, because a fixture confirms the error forever and only a live test catches it.
   *
   * <p>The fixture now uses ids with a leading zero, which Wikibase's item-id grammar refuses
   * outright — it reads {@code Q[1-9]} followed by up to nine more digits — so there is no number
   * Wikidata can reach that would make one of them denote something. {@code
   * FixtureQidsDenoteNothingTest} pins that grammar offline; this test is the standing check that
   * Wikidata still agrees.
   */
  @Test
  @DisplayName("no Fixture qid resolves to a real Wikidata entity")
  void shouldResolveNothingWhenAskedForAFixtureQid() {
    assertThat(Fixture.nodes())
        .isNotEmpty()
        .allSatisfy(
            node ->
                assertThat(resolver.fetch(node.qid()))
                    .as(
                        "%s is a real Wikidata entity, so the fixture is asserting things about"
                            + " something that exists",
                        node.qid())
                    .isEmpty());
  }
}
