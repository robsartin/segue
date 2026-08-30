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
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MusicBrainzSourceAdapterTest {

  /**
   * The same committed fixture {@code MusicBrainzClientTest} reads, and the same entity: Quintette
   * du Hot Club de France, mbid {@code ee55e4e8-…}, a French jazz ensemble that stopped existing in
   * 1948. Its choice is argued in {@code MusicBrainzClientTest}'s javadoc and is a reproducible API
   * probe, never a statement about anyone's taste (ADR 51). Every QID below is invented — the
   * {@code Q9000xx} range this repository's tests use — because a real QID would tie an invented
   * mapping to a real entity.
   */
  private static final String QUINTET_MBID = "ee55e4e8-807d-49b1-8470-d1c0898ed7cb";

  private static final String QUINTET_QID = "Q900001";

  // The first three "member of band" relations in the committed fixture, in the order it states
  // them. Fixture order is load-bearing for the truncation test below and nowhere else.
  private static final String FIRST_MEMBER_MBID = "9afdcb99-d4ba-41b4-b4b2-467a712bd9fa";
  private static final String SECOND_MEMBER_MBID = "7bad5ad3-0333-4661-9b26-44114adf5595";
  private static final String THIRD_MEMBER_MBID = "63eaba3d-5d77-427f-aa94-d8bb2593b99f";

  private static final String FIRST_MEMBER_QID = "Q900002";
  private static final String SECOND_MEMBER_QID = "Q900003";
  private static final String THIRD_MEMBER_QID = "Q900004";

  /** An MBID and QID that appear in no fixture, for relations written by hand in this test. */
  private static final String STUB_MEMBER_MBID = "11111111-1111-1111-1111-111111111111";

  private static final String STUB_MEMBER_QID = "Q900010";

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

  private static Path fixture(String name) {
    try {
      return Path.of(
          MusicBrainzSourceAdapterTest.class.getResource("/musicbrainz/" + name).toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }
}
