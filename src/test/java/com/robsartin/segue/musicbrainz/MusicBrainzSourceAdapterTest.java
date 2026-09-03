package com.robsartin.segue.musicbrainz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.port.ExpandContext;
import com.robsartin.segue.port.ExpandResult;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MusicBrainzSourceAdapterTest {

  /**
   * The same committed fixture {@code MusicBrainzClientTest} reads, and the same entity: Quintette
   * du Hot Club de France, mbid {@code ee55e4e8-…}, a French jazz ensemble that stopped existing in
   * 1948. Its choice is argued in {@code MusicBrainzClientTest}'s javadoc and is a reproducible API
   * probe, never a statement about anyone's taste (ADR 51).
   *
   * <p><b>The QIDs below cannot denote anything.</b> This test is where the problem behind <a
   * href="https://github.com/robsartin/segue/issues/141">issue #141</a> was found: the ids then in
   * use came from a {@code Q9000xx} range assumed to be free, it was not, and so this file really
   * did tie a real MBID to an unrelated real person's QID. They now carry a leading zero, which
   * Wikibase's item-id grammar refuses, so no allocation can give them a referent (ADR 58). No
   * assertion below depends on what any of them denotes, because none of them can denote.
   */
  private static final String QUINTET_MBID = "ee55e4e8-807d-49b1-8470-d1c0898ed7cb";

  private static final String QUINTET_QID = "Q0900001";

  // The first three "member of band" relations in the committed fixture, in the order it states
  // them. Fixture order is load-bearing for the truncation test below and nowhere else.
  private static final String FIRST_MEMBER_MBID = "9afdcb99-d4ba-41b4-b4b2-467a712bd9fa";
  private static final String SECOND_MEMBER_MBID = "7bad5ad3-0333-4661-9b26-44114adf5595";
  private static final String THIRD_MEMBER_MBID = "63eaba3d-5d77-427f-aa94-d8bb2593b99f";

  private static final String FIRST_MEMBER_QID = "Q0900002";
  private static final String SECOND_MEMBER_QID = "Q0900003";
  private static final String THIRD_MEMBER_QID = "Q0900004";

  /** MBIDs and QIDs that appear in no fixture, for relations written by hand in this test. */
  private static final String STUB_MEMBER_MBID = "11111111-1111-1111-1111-111111111111";

  private static final String OTHER_STUB_MEMBER_MBID = "22222222-2222-2222-2222-222222222222";

  /**
   * An MBID with a newline in it, and the same value as it appears inside a JSON string. Legal JSON
   * — {@code \\n} is an escape every parser reads — and illegal in a {@code Provenance} sourceRef,
   * which is the whole of issue #147.
   */
  private static final String NEWLINE_MBID = "33333333-3333-3333-3333-333333333333\nx";

  private static final String NEWLINE_MBID_AS_JSON = "33333333-3333-3333-3333-333333333333\\nx";

  private static final String STUB_MEMBER_QID = "Q0900010";

  private static final String OTHER_STUB_MEMBER_QID = "Q0900011";

  private static final Instant ASSERTED_AT = Instant.parse("2026-08-30T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(ASSERTED_AT, ZoneOffset.UTC);

  @Test
  @DisplayName("should support only the kinds MusicBrainz describes when asked about every kind")
  void shouldSupportOnlyTheKindsMusicBrainzDescribesWhenAskedAboutEveryKind() {
    MusicBrainzSourceAdapter adapter = adapter(Map.of());

    // Iterating the enum rather than listing six cases: a seventh kind is covered the day it is
    // added instead of silently escaping the assertion (ExpansionBoundsTest uses the same shape).
    Set<NodeKind> described = EnumSet.of(NodeKind.PERSON, NodeKind.GROUP);
    for (NodeKind kind : NodeKind.values()) {
      assertThat(adapter.supports(kind))
          .as("supports(%s)", kind)
          .isEqualTo(described.contains(kind));
    }
  }

  @Test
  @DisplayName("should return an empty result when no MBID is known for the seed")
  void shouldReturnAnEmptyResultWhenNoMbidIsKnownForTheSeed() {
    MusicBrainzSourceAdapter adapter = adapter(Map.of());

    ExpandResult result = adapter.expand(quintet(), new ExpandContext(200));

    assertThat(result.assertions()).isEmpty();
    assertThat(result.sourceUnavailable()).isFalse();
    assertThat(result.truncated()).isFalse();
  }

  @Test
  @DisplayName("should emit one assertion per resolvable relation when the mapping knows two")
  void shouldEmitOneAssertionPerResolvableRelationWhenTheMappingKnowsTwo() {
    MusicBrainzSourceAdapter adapter =
        adapter(
            mapping(
                QUINTET_MBID, QUINTET_QID,
                FIRST_MEMBER_MBID, FIRST_MEMBER_QID,
                SECOND_MEMBER_MBID, SECOND_MEMBER_QID));

    ExpandResult result = adapter.expand(quintet(), new ExpandContext(200));

    assertThat(result.assertions()).hasSize(2);
    // MusicBrainz states this relation "backward" on the group — the target is the member and the
    // group is the band — so the edge runs member MEMBER_OF band, which is what P463 means.
    assertThat(result.assertions())
        .extracting(AssertionRecord::fromQid, AssertionRecord::toQid, AssertionRecord::typeCode)
        .containsExactlyInAnyOrder(
            tuple(FIRST_MEMBER_QID, QUINTET_QID, "MEMBER_OF"),
            tuple(SECOND_MEMBER_QID, QUINTET_QID, "MEMBER_OF"));
    assertThat(result.assertions())
        .allSatisfy(a -> assertThat(a.provenance().sourceId()).isEqualTo("musicbrainz"));
    assertThat(result.assertions())
        .allSatisfy(a -> assertThat(a.provenance().assertedAt()).isEqualTo(ASSERTED_AT));
    // ADR 23: a MusicBrainz relation is structured and carries no citation.
    assertThat(result.assertions())
        .allSatisfy(a -> assertThat(a.provenance().confidence()).isEqualTo(0.80));
    // The sourceRef names the MusicBrainz record that was read AND the relation within it, so a
    // reader can get back to the claim rather than only to the artist page.
    assertThat(result.assertions())
        .extracting(a -> a.provenance().sourceRef())
        .allSatisfy(ref -> assertThat(ref).contains("artist/" + QUINTET_MBID));
    assertThat(result.assertions())
        .extracting(a -> a.provenance().sourceRef())
        .contains("artist/" + QUINTET_MBID + "#member of band:" + FIRST_MEMBER_MBID);
    assertThat(result.sourceUnavailable()).isFalse();
    assertThat(result.truncated()).isFalse();
  }

  @Test
  @DisplayName("should produce no assertion for a relation whose neighbour has no QID")
  void shouldProduceNoAssertionForARelationWhoseNeighbourHasNoQid() {
    // The fixture states 22 "member of band" relations; the mapping knows one of the members.
    MusicBrainzSourceAdapter adapter =
        adapter(mapping(QUINTET_MBID, QUINTET_QID, FIRST_MEMBER_MBID, FIRST_MEMBER_QID));

    ExpandResult result = adapter.expand(quintet(), new ExpandContext(200));

    assertThat(result.assertions()).hasSize(1);
    assertThat(result.assertions().getFirst().fromQid()).isEqualTo(FIRST_MEMBER_QID);
    // 21 neighbours were dropped for want of a QID. That is ADR 22 clause 2 declining to reach
    // them, not a failure, so nothing is flagged.
    assertThat(result.sourceUnavailable()).isFalse();
    assertThat(result.truncated()).isFalse();
  }

  @Test
  @DisplayName("should produce no assertion for a relation type outside the whitelist")
  void shouldProduceNoAssertionForARelationTypeOutsideTheWhitelist() {
    // The fixture's 24 relations are 22 "member of band" and 2 "named after artist". Nothing in
    // EdgeTypes carries "named after", so it is skipped rather than guessed at — and skipping is
    // normal operation for this source, not an error.
    MusicBrainzSourceAdapter adapter = adapter(everyMbidInTheFixture());

    ExpandResult result = adapter.expand(quintet(), new ExpandContext(200));

    assertThat(result.assertions()).hasSize(22);
    assertThat(result.assertions())
        .allSatisfy(a -> assertThat(a.typeCode()).isEqualTo("MEMBER_OF"));
    assertThat(result.truncated()).isFalse();
  }

  @Test
  @DisplayName("should truncate at maxNewEdges and say so when more relations are whitelisted")
  void shouldTruncateAtMaxNewEdgesAndSaySoWhenMoreRelationsAreWhitelisted() {
    MusicBrainzSourceAdapter adapter =
        adapter(
            mapping(
                QUINTET_MBID, QUINTET_QID,
                FIRST_MEMBER_MBID, FIRST_MEMBER_QID,
                SECOND_MEMBER_MBID, SECOND_MEMBER_QID,
                THIRD_MEMBER_MBID, THIRD_MEMBER_QID));

    ExpandResult result = adapter.expand(quintet(), new ExpandContext(2));

    // The bound is applied to the whitelisted relations BEFORE any QID is resolved — the design
    // note's GAP 5: the real cost of this source is one bridge lookup per neighbour, so a bound
    // spent after resolving would not bound anything. The first two relations the fixture states
    // are the two kept.
    assertThat(result.assertions()).hasSize(2);
    assertThat(result.assertions())
        .extracting(AssertionRecord::fromQid)
        .containsExactlyInAnyOrder(FIRST_MEMBER_QID, SECOND_MEMBER_QID);
    assertThat(result.truncated()).isTrue();
  }

  @Test
  @DisplayName("should report the source unavailable when the client cannot answer")
  void shouldReportTheSourceUnavailableWhenTheClientCannotAnswer() {
    MusicBrainzSourceAdapter adapter =
        new MusicBrainzSourceAdapter(
            MusicBrainzClient.readingFrom(Path.of("no-such-fixture.json")),
            StubIdentity.of(mapping(QUINTET_MBID, QUINTET_QID)),
            CLOCK);

    ExpandResult result = adapter.expand(quintet(), new ExpandContext(200));

    assertThat(result.sourceUnavailable()).isTrue();
    assertThat(result.assertions()).isEmpty();
  }

  @Test
  @DisplayName("should report the source unavailable when the identity bridge cannot answer")
  void shouldReportTheSourceUnavailableWhenTheIdentityBridgeCannotAnswer() {
    // Issue #148. Before the bridge had a failure channel this case was indistinguishable from
    // "MusicBrainz holds no record bridged to this seed": both were an empty Optional, and the
    // adapter read the empty one as the normal no-bridge answer and flagged nothing. An outage
    // therefore reached the caller as "this artist has no members".
    MusicBrainzSourceAdapter adapter =
        new MusicBrainzSourceAdapter(
            MusicBrainzClient.readingFrom(fixture("artist-with-relations.json")),
            new UnavailableIdentity(),
            CLOCK);

    ExpandResult result = adapter.expand(quintet(), new ExpandContext(200));

    assertThat(result.sourceUnavailable()).isTrue();
    assertThat(result.assertions()).isEmpty();
  }

  @Test
  @DisplayName("should report the source unavailable when the bridge fails resolving neighbours")
  void shouldReportTheSourceUnavailableWhenTheBridgeFailsResolvingNeighbours() {
    // The other of the bridge's two calls, and the one with more to lose: the seed resolved, the
    // relations arrived, and only the MBID-to-QID pass fell over. Dropping every neighbour for
    // want of a QID is also normal operation (49% of them), so this failure was silent too.
    MusicBrainzSourceAdapter adapter =
        new MusicBrainzSourceAdapter(
            MusicBrainzClient.readingFrom(fixture("artist-with-relations.json")),
            new UnavailableOnBatch(StubIdentity.of(mapping(QUINTET_MBID, QUINTET_QID))),
            CLOCK);

    ExpandResult result = adapter.expand(quintet(), new ExpandContext(200));

    assertThat(result.sourceUnavailable()).isTrue();
    assertThat(result.assertions()).isEmpty();
  }

  @Test
  @DisplayName("should drop a begin date below day precision and keep one that reaches it")
  void shouldDropABeginDateBelowDayPrecisionAndKeepOneThatReachesIt(@TempDir Path dir)
      throws IOException {
    // Constructed here rather than read off the committed fixture: that fixture's 24 relations all
    // carry a JSON-null begin and end, so it cannot exercise variable precision at all. The shapes
    // below are the ones the design note measured on a live probe — "1960", "1962-08-16".
    Path written =
        writeRelations(
            dir,
            """
            {"type": "member of band", "direction": "backward",
             "begin": "1960", "end": "1962-08-16", "ended": true,
             "artist": {"id": "%s", "name": "A Stub Musician"}}
            """
                .formatted(STUB_MEMBER_MBID));
    MusicBrainzSourceAdapter adapter =
        adapterReading(
            written, mapping(QUINTET_MBID, QUINTET_QID, STUB_MEMBER_MBID, STUB_MEMBER_QID));

    ExpandResult result = adapter.expand(quintet(), new ExpandContext(200));

    AssertionRecord assertion = result.assertions().getFirst();
    // ClaimMapper.qualifierDate is the precedent: a year-precision date read as a LocalDate would
    // feed false day-level precision into validAt() time-travel queries. Dropped, not rounded.
    assertThat(assertion.validFrom()).isNull();
    assertThat(assertion.validTo()).isEqualTo(LocalDate.of(1962, 8, 16));
  }

  @Test
  @DisplayName("should drop both endpoints of an inverted validity window rather than throw")
  void shouldDropBothEndpointsOfAnInvertedValidityWindowRatherThanThrow(@TempDir Path dir)
      throws IOException {
    // AssertionRecord's constructor rejects validTo before validFrom, and that throw would escape
    // expand() and take the whole expansion with it. ClaimMapper meets the same hazard on the same
    // two fields and keeps the claim while dropping the nonsense; this follows it.
    Path written =
        writeRelations(
            dir,
            """
            {"type": "member of band", "direction": "backward",
             "begin": "1962-08-16", "end": "1960-08-12", "ended": true,
             "artist": {"id": "%s", "name": "A Stub Musician"}}
            """
                .formatted(STUB_MEMBER_MBID));
    MusicBrainzSourceAdapter adapter =
        adapterReading(
            written, mapping(QUINTET_MBID, QUINTET_QID, STUB_MEMBER_MBID, STUB_MEMBER_QID));

    ExpandResult result = adapter.expand(quintet(), new ExpandContext(200));

    assertThat(result.assertions()).hasSize(1);
    assertThat(result.assertions().getFirst().validFrom()).isNull();
    assertThat(result.assertions().getFirst().validTo()).isNull();
  }

  @Test
  @DisplayName("should identify itself as musicbrainz when asked for its id")
  void shouldIdentifyItselfAsMusicbrainzWhenAskedForItsId() {
    // Asserted on its own, not only through provenance().sourceId(): id() is what
    // EdgeRecord.corroboration() counts distinct values of and what GAP 4's per-source attribution
    // would key on, so a change to the literal alone must not pass unnoticed.
    assertThat(adapter(Map.of()).id()).isEqualTo("musicbrainz");
  }

  @Test
  @DisplayName("should ask the bridge about no more MBIDs than the bound allows")
  void shouldAskTheBridgeAboutNoMoreMbidsThanTheBoundAllows(@TempDir Path dir) throws IOException {
    // The obligation the design note wanted recorded on expand (GAP 5): this source's cost is one
    // bridge lookup per neighbour, against a service that asks for ~1 request a second, so the
    // bound has to be spent BEFORE the bridge rather than after. Applying maxNewEdges to the
    // finished assertions instead would leave every other test in this class green while the
    // adapter asked the bridge about all 22 neighbours, which is the whole cost it is meant to
    // bound. Only a recorded call can see the difference.
    RecordingIdentity identity =
        new RecordingIdentity(
            StubIdentity.of(
                mapping(
                    QUINTET_MBID, QUINTET_QID,
                    FIRST_MEMBER_MBID, FIRST_MEMBER_QID,
                    SECOND_MEMBER_MBID, SECOND_MEMBER_QID)));
    MusicBrainzSourceAdapter adapter =
        new MusicBrainzSourceAdapter(
            MusicBrainzClient.readingFrom(fixture("artist-with-relations.json")), identity, CLOCK);

    adapter.expand(quintet(), new ExpandContext(2));

    assertThat(identity.asked).hasSizeLessThanOrEqualTo(2);
    assertThat(identity.asked).containsExactly(FIRST_MEMBER_MBID, SECOND_MEMBER_MBID);
  }

  @Test
  @DisplayName("should produce no assertion when the bridge returns something that is not a QID")
  void shouldProduceNoAssertionWhenTheBridgeReturnsSomethingThatIsNotAQid() {
    // GAP 9: AssertionRecord requires its endpoints non-null and nothing more, so a bad value is
    // logged happily and blows up later at the node that names it. Whatever implements
    // MusicBrainzIdentity is reading QIDs out of somebody's database — Wikidata's P434 in the
    // shipped wiring — so this is arriving external data rather than a programming error, the same
    // case ClaimMapper's non-QID object-id guard refuses for Wikidata. The adapter does not know
    // which bridge is behind the seam, which is exactly why it checks.
    //
    // Since issue #163 the drop happens one layer earlier, in the bridge itself: a BridgedIdentity
    // may not hold a non-QID, so a bridge reading one out of its source drops the row rather than
    // constructing one and throwing out of expand(). StubIdentity carries that guard, as every
    // implementor now must — it was the seam's default until qidsFor was retired and took the
    // default with it. The assertions below are unchanged because the observable answer is — no
    // assertion, no flag — and the adapter's own GAP 9 guard remains behind it.
    MusicBrainzSourceAdapter adapter =
        adapter(mapping(QUINTET_MBID, QUINTET_QID, FIRST_MEMBER_MBID, "https://example.invalid/x"));

    ExpandResult result = adapter.expand(quintet(), new ExpandContext(200));

    assertThat(result.assertions()).isEmpty();
    assertThat(result.sourceUnavailable()).isFalse();
  }

  @Test
  @DisplayName("should return an empty result when the bridge answers with an MBID carrying a tab")
  void shouldReturnAnEmptyResultWhenTheBridgeAnswersWithAnMbidCarryingATab() {
    // Issue #147. The GAP 9 guard below validates targetQid and argues that it must not depend on
    // which bridge is wired — and seedMbid, from the same interface, went straight into sourceRef,
    // where Provenance's compact constructor throws on a tab or a newline. That
    // IllegalArgumentException escapes expand(), and SegueService.expandEntity has no try around
    // adapter.expand, so one malformed string aborted the whole expansion across every adapter
    // instead of costing this one its result.
    MusicBrainzSourceAdapter adapter =
        adapter(
            mapping(QUINTET_MBID + "\tinjected", QUINTET_QID, FIRST_MEMBER_MBID, FIRST_MEMBER_QID));

    ExpandResult result = adapter.expand(quintet(), new ExpandContext(200));

    // The same answer "MusicBrainz has no record bridged to this QID" gets: there is nothing this
    // adapter could cite, so there is nothing to fetch — and nothing to flag either.
    assertThat(result.assertions()).isEmpty();
    assertThat(result.sourceUnavailable()).isFalse();
    assertThat(result.truncated()).isFalse();
  }

  @Test
  @DisplayName("should skip only the relation whose own MBID carries a newline")
  void shouldSkipOnlyTheRelationWhoseOwnMbidCarriesANewline(@TempDir Path dir) throws IOException {
    // The second half of #147: relation.targetMbid() reaches sourceRef from a MusicBrainz response
    // — contributor-entered data — and was as unguarded as seedMbid. A newline is legal in JSON and
    // illegal in a Provenance sourceRef, so one bad row took the other 21 with it.
    Path written =
        writeRelations(
            dir,
            """
            {"type": "member of band", "direction": "backward",
             "artist": {"id": "%s", "name": "A Stub Musician"}}
            """
                .formatted(NEWLINE_MBID_AS_JSON),
            """
            {"type": "member of band", "direction": "backward",
             "artist": {"id": "%s", "name": "Another Stub Musician"}}
            """
                .formatted(STUB_MEMBER_MBID));
    MusicBrainzSourceAdapter adapter =
        adapterReading(
            written,
            mapping(
                QUINTET_MBID, QUINTET_QID,
                NEWLINE_MBID, OTHER_STUB_MEMBER_QID,
                STUB_MEMBER_MBID, STUB_MEMBER_QID));

    ExpandResult result = adapter.expand(quintet(), new ExpandContext(200));

    // Degrading, not aborting: the well-formed relation still becomes an edge.
    assertThat(result.assertions()).hasSize(1);
    assertThat(result.assertions().getFirst().fromQid()).isEqualTo(STUB_MEMBER_QID);
    assertThat(result.sourceUnavailable()).isFalse();
    // Two relations under a bound of 200, so this says only that nothing was cut short — it is not
    // evidence about WHERE the guard sits. The test below is, and this one deliberately does not
    // claim it.
    assertThat(result.truncated()).isFalse();
  }

  @Test
  @DisplayName("should spend neither the bound nor a bridge lookup on an uncitable relation")
  void shouldSpendNeitherTheBoundNorABridgeLookupOnAnUncitableRelation(@TempDir Path dir)
      throws IOException {
    // Where the neighbour-MBID guard sits is argued twice in the adapter's javadoc — "before the
    // bound is spent", and "costs the bridge nothing — an unciteable neighbour is never even asked
    // about" — and until this test nothing held it to either claim. Fix round 1's reviewer proved
    // that by moving the guard out of isMappable into the loop after limit(maxNewEdges) and after
    // the bridge lookup: every other test in this file stayed green.
    //
    // A bound of 1 with the uncitable relation stated FIRST is what separates the two placements.
    // In isMappable it never enters the bounded list, so the bound buys the citable relation and
    // the bridge is asked about that one. After the bound it wins the only slot, the bridge is
    // asked about a string that cannot be an MBID, truncated goes true and no assertion survives.
    Path written =
        writeRelations(
            dir,
            """
            {"type": "member of band", "direction": "backward",
             "artist": {"id": "%s", "name": "A Stub Musician"}}
            """
                .formatted(NEWLINE_MBID_AS_JSON),
            """
            {"type": "member of band", "direction": "backward",
             "artist": {"id": "%s", "name": "Another Stub Musician"}}
            """
                .formatted(STUB_MEMBER_MBID));
    RecordingIdentity identity =
        new RecordingIdentity(
            StubIdentity.of(
                mapping(
                    QUINTET_MBID, QUINTET_QID,
                    NEWLINE_MBID, OTHER_STUB_MEMBER_QID,
                    STUB_MEMBER_MBID, STUB_MEMBER_QID)));
    MusicBrainzSourceAdapter adapter =
        new MusicBrainzSourceAdapter(MusicBrainzClient.readingFrom(written), identity, CLOCK);

    ExpandResult result = adapter.expand(quintet(), new ExpandContext(1));

    // The bridge is asked about the citable neighbour and never about the other one. Asserted with
    // containsExactly rather than a size: the whole point is WHICH mbid was asked about.
    assertThat(identity.asked).containsExactly(STUB_MEMBER_MBID);
    assertThat(result.assertions()).hasSize(1);
    assertThat(result.assertions().getFirst().fromQid()).isEqualTo(STUB_MEMBER_QID);
    // The bound bought a real relation, so one of the two mappable relations is all there was.
    assertThat(result.truncated()).isFalse();
  }

  @Test
  @DisplayName(
      "should drop a relation whose direction MusicBrainz did not state or this does not know")
  void shouldDropARelationWhoseDirectionMusicBrainzDidNotStateOrThisDoesNotKnow(@TempDir Path dir)
      throws IOException {
    // The class javadoc says direction is read on every relation. A relation with no direction, or
    // one this adapter does not recognise, cannot be oriented at all — so it is dropped rather
    // than defaulted to either end, which would assert a membership backwards half the time.
    Path written =
        writeRelations(
            dir,
            """
            {"type": "member of band",
             "artist": {"id": "%s", "name": "A Stub Musician"}}
            """
                .formatted(STUB_MEMBER_MBID),
            """
            {"type": "member of band", "direction": "sideways",
             "artist": {"id": "%s", "name": "Another Stub Musician"}}
            """
                .formatted(OTHER_STUB_MEMBER_MBID));
    MusicBrainzSourceAdapter adapter =
        adapterReading(
            written,
            mapping(
                QUINTET_MBID, QUINTET_QID,
                STUB_MEMBER_MBID, STUB_MEMBER_QID,
                OTHER_STUB_MEMBER_MBID, OTHER_STUB_MEMBER_QID));

    ExpandResult result = adapter.expand(quintet(), new ExpandContext(200));

    assertThat(result.assertions()).isEmpty();
    assertThat(result.truncated()).isFalse();
  }

  @Test
  @DisplayName("should not report truncation when the relations exactly fill the bound")
  void shouldNotReportTruncationWhenTheRelationsExactlyFillTheBound(@TempDir Path dir)
      throws IOException {
    // truncated is OBSERVED — the bounded list compared against the full one — not inferred from
    // the result being as large as the bound. Sitting exactly at the bound is the case that
    // separates the two: inferring it would report a complete answer as cut short, and issue #65's
    // rule is that the flag belongs to the result that actually hit the bound.
    Path written =
        writeRelations(
            dir,
            """
            {"type": "member of band", "direction": "backward",
             "artist": {"id": "%s", "name": "A Stub Musician"}}
            """
                .formatted(STUB_MEMBER_MBID),
            """
            {"type": "member of band", "direction": "backward",
             "artist": {"id": "%s", "name": "Another Stub Musician"}}
            """
                .formatted(OTHER_STUB_MEMBER_MBID));
    MusicBrainzSourceAdapter adapter =
        adapterReading(
            written,
            mapping(
                QUINTET_MBID, QUINTET_QID,
                STUB_MEMBER_MBID, STUB_MEMBER_QID,
                OTHER_STUB_MEMBER_MBID, OTHER_STUB_MEMBER_QID));

    ExpandResult result = adapter.expand(quintet(), new ExpandContext(2));

    assertThat(result.assertions()).hasSize(2);
    assertThat(result.truncated()).isFalse();
  }

  private static NodeRecord quintet() {
    return new NodeRecord(QUINTET_QID, NodeKind.GROUP, "An Invented Ensemble", List.of());
  }

  private static MusicBrainzSourceAdapter adapter(Map<String, String> mbidToQid) {
    return adapterReading(fixture("artist-with-relations.json"), mbidToQid);
  }

  private static MusicBrainzSourceAdapter adapterReading(
      Path response, Map<String, String> mbidToQid) {
    return new MusicBrainzSourceAdapter(
        MusicBrainzClient.readingFrom(response), StubIdentity.of(mbidToQid), CLOCK);
  }

  /** One {@code artist-rels}-shaped response holding exactly the relations given. */
  private static Path writeRelations(Path dir, String... relations) throws IOException {
    Path file = dir.resolve("relations.json");
    Files.writeString(
        file, "{\"relations\": [" + String.join(",", relations) + "]}", StandardCharsets.UTF_8);
    return file;
  }

  /** Every MBID the committed fixture names, each given an invented QID. */
  private static Map<String, String> everyMbidInTheFixture() {
    Map<String, String> mapping = new LinkedHashMap<>();
    mapping.put(QUINTET_MBID, QUINTET_QID);
    int next = 900100;
    for (ArtistRelation relation :
        MusicBrainzClient.readingFrom(fixture("artist-with-relations.json"))
            .artistRelations(QUINTET_MBID)) {
      mapping.putIfAbsent(relation.targetMbid(), "Q" + next++);
    }
    return Map.copyOf(mapping);
  }

  private static Map<String, String> mapping(String... pairs) {
    Map<String, String> mapping = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      mapping.put(pairs[i], pairs[i + 1]);
    }
    return Map.copyOf(mapping);
  }

  /** A bridge that cannot reach whatever is behind it, in either direction. */
  private static final class UnavailableIdentity implements MusicBrainzIdentity {

    @Override
    public Optional<String> mbidFor(String qid) {
      throw new MusicBrainzIdentityUnavailableException("the bridge did not answer");
    }

    @Override
    public Map<String, BridgedIdentity> identitiesFor(Collection<String> mbids) {
      throw new MusicBrainzIdentityUnavailableException("the bridge did not answer");
    }
  }

  /** A bridge that resolves the seed and then falls over on the neighbourhood batch. */
  private static final class UnavailableOnBatch implements MusicBrainzIdentity {

    private final MusicBrainzIdentity delegate;

    private UnavailableOnBatch(MusicBrainzIdentity delegate) {
      this.delegate = delegate;
    }

    @Override
    public Optional<String> mbidFor(String qid) {
      return delegate.mbidFor(qid);
    }

    @Override
    public Map<String, BridgedIdentity> identitiesFor(Collection<String> mbids) {
      throw new MusicBrainzIdentityUnavailableException("the bridge did not answer");
    }
  }

  /**
   * A {@link MusicBrainzIdentity} that records the batch it was handed. Wrapping {@link
   * StubIdentity} rather than replacing it keeps the resolution behaviour in one place; the only
   * thing added is the observation.
   */
  private static final class RecordingIdentity implements MusicBrainzIdentity {

    private final MusicBrainzIdentity delegate;
    private final List<String> asked = new ArrayList<>();

    private RecordingIdentity(MusicBrainzIdentity delegate) {
      this.delegate = delegate;
    }

    @Override
    public Optional<String> mbidFor(String qid) {
      return delegate.mbidFor(qid);
    }

    @Override
    public Map<String, BridgedIdentity> identitiesFor(Collection<String> mbids) {
      asked.addAll(mbids);
      return delegate.identitiesFor(mbids);
    }
  }

  private static Path fixture(String name) {
    try {
      return Path.of(
          MusicBrainzSourceAdapterTest.class.getResource("/musicbrainz/" + name).toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }
}
