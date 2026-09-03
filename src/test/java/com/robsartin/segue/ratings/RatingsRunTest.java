package com.robsartin.segue.ratings;

import static com.robsartin.segue.ratings.InventedRatings.CANONICAL;
import static com.robsartin.segue.ratings.InventedRatings.CANONICAL_LABEL;
import static com.robsartin.segue.ratings.InventedRatings.CORRECTED_CANONICAL;
import static com.robsartin.segue.ratings.InventedRatings.EARLY;
import static com.robsartin.segue.ratings.InventedRatings.LATE;
import static com.robsartin.segue.ratings.InventedRatings.MINTED;
import static com.robsartin.segue.ratings.InventedRatings.MINTED_LABEL;
import static com.robsartin.segue.ratings.InventedRatings.NEIGHBOUR;
import static com.robsartin.segue.ratings.InventedRatings.NOVEL;
import static com.robsartin.segue.ratings.InventedRatings.NOVEL_LABEL;
import static com.robsartin.segue.ratings.InventedRatings.NOVEL_NOTE;
import static com.robsartin.segue.ratings.InventedRatings.QUARTET;
import static com.robsartin.segue.ratings.InventedRatings.QUARTET_LABEL;
import static com.robsartin.segue.ratings.InventedRatings.QUARTET_NOTE;
import static com.robsartin.segue.ratings.InventedRatings.VANISHED;
import static com.robsartin.segue.ratings.InventedRatings.merged;
import static com.robsartin.segue.ratings.InventedRatings.minted;
import static com.robsartin.segue.ratings.InventedRatings.node;
import static com.robsartin.segue.ratings.InventedRatings.owned;
import static com.robsartin.segue.ratings.InventedRatings.retract;
import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.ratings.InventedRatings.FakeAffinityStore;
import com.robsartin.segue.ratings.InventedRatings.FakeAssertionLog;
import com.robsartin.segue.ratings.RatingsCli.Options;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The join, which is the only thing this class does: ratings from one port, labels from the other,
 * neither store aware the other exists (ADR 33).
 *
 * <p>Every rating, label and note is invented - see {@link InventedRatings}.
 */
class RatingsRunTest {

  @TempDir private Path dir;

  private final List<String> notes = new ArrayList<>();
  private final List<Boolean> fileExistedWhenNoted = new ArrayList<>();

  private Path out;

  @BeforeEach
  void setUp() {
    out = dir.resolve("ratings.txt");
  }

  private void note(String line) {
    notes.add(line);
    fileExistedWhenNoted.add(Files.exists(out));
  }

  private List<AffinityRow> run(FakeAffinityStore ratings, FakeAssertionLog log, SortOrder sort)
      throws IOException {
    return new RatingsRun(ratings, log)
        .run(new Options(dir.resolve("segue.db"), out, sort), this::note);
  }

  @Test
  @DisplayName("a rating reads as a name, because the label comes from the graph's own claim")
  void joinsLabelsOntoRatings() throws IOException {
    FakeAffinityStore ratings =
        new FakeAffinityStore()
            .rated(QUARTET, 5, QUARTET_NOTE, EARLY)
            .rated(NOVEL, 3, NOVEL_NOTE, LATE);
    FakeAssertionLog log =
        new FakeAssertionLog().with(node(QUARTET, QUARTET_LABEL), node(NOVEL, NOVEL_LABEL));

    List<AffinityRow> rows = run(ratings, log, SortOrder.RATING);

    assertThat(rows)
        .extracting(AffinityRow::qid, AffinityRow::label, AffinityRow::rating, AffinityRow::note)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple(QUARTET, QUARTET_LABEL, 5, QUARTET_NOTE),
            org.assertj.core.groups.Tuple.tuple(NOVEL, NOVEL_LABEL, 3, NOVEL_NOTE));
  }

  @Test
  @DisplayName(
      "an entity the owner minted reads as a name too, because the owner's claim is a claim")
  void shouldNameAnEntityWhenTheOwnerMintedItHimself() throws IOException {
    FakeAffinityStore ratings = new FakeAffinityStore().rated(MINTED, 4, null, EARLY);
    FakeAssertionLog log = new FakeAssertionLog().with(minted(MINTED, MINTED_LABEL));

    List<AffinityRow> rows = run(ratings, log, SortOrder.RATING);

    assertThat(rows)
        .singleElement()
        .extracting(AffinityRow::label, AffinityRow::displayLabel)
        .as("a minted entity IS in the graph, so the listing must not say it is not")
        .containsExactly(MINTED_LABEL, MINTED_LABEL);
  }

  @Test
  @DisplayName("should name the canonical id when a merge has carried the rating onto it")
  void shouldNameTheCanonicalIdWhenAMergeHasCarriedTheRatingOntoIt() throws IOException {
    FakeAffinityStore ratings =
        new FakeAffinityStore().rated(MINTED, 5, null, EARLY).rated(CANONICAL, 5, null, LATE);
    FakeAssertionLog log =
        new FakeAssertionLog().with(minted(MINTED, MINTED_LABEL), merged(MINTED, CANONICAL));

    List<AffinityRow> rows = run(ratings, log, SortOrder.RATING);

    assertThat(rows)
        .extracting(AffinityRow::qid, AffinityRow::displayLabel)
        .as(
            "the merge put a node under the canonical id, so the listing must not say it is not"
                + " in the graph")
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple(MINTED, MINTED_LABEL),
            org.assertj.core.groups.Tuple.tuple(CANONICAL, MINTED_LABEL));
  }

  @Test
  @DisplayName("should say a canonical id is not in the graph when a later merge corrected it")
  void shouldSayACanonicalIdIsNotInTheGraphWhenALaterMergeCorrectedIt() throws IOException {
    // A rating an earlier build carried onto the wrong canonical id stays - AffinityStore has no
    // delete (ADR 39, ADR 46) - and since #221 that id has no node. "(not in the graph)" is what
    // that string is for: a rating that outlived its node. Naming it with the merged entity's
    // label would be this listing insisting on a node both folds have stopped making.
    FakeAffinityStore ratings = new FakeAffinityStore().rated(CANONICAL, 5, null, LATE);
    FakeAssertionLog log =
        new FakeAssertionLog()
            .with(
                minted(MINTED, MINTED_LABEL),
                merged(MINTED, CANONICAL),
                merged(MINTED, CORRECTED_CANONICAL));

    assertThat(run(ratings, log, SortOrder.RATING))
        .extracting(AffinityRow::qid, AffinityRow::displayLabel)
        .containsExactly(org.assertj.core.groups.Tuple.tuple(CANONICAL, AffinityRow.NO_LABEL));
  }

  @Test
  @DisplayName(
      "should keep a canonical id's label when a surviving edge names it directly, though a"
          + " later merge corrected it")
  void shouldKeepACanonicalIdsLabelWhenASurvivingEdgeNamesItDirectlyThoughALaterMergeCorrectedIt()
      throws IOException {
    // The counterpart to the test above: here an owner edge names CANONICAL directly WHILE it
    // still stands, so Equivalences.stands (#221 fix round 1) answers true for it and this
    // listing offers its label rather than NO_LABEL - the same widening
    // TwiceMergedIdLeavesNoOrphanTest pins for the two graph folds, seen here through the third
    // of the stand-in rule's four homes.
    FakeAffinityStore ratings = new FakeAffinityStore().rated(CANONICAL, 5, null, LATE);
    FakeAssertionLog log =
        new FakeAssertionLog()
            .with(
                minted(MINTED, MINTED_LABEL),
                merged(MINTED, CANONICAL),
                owned(NEIGHBOUR, CANONICAL),
                merged(MINTED, CORRECTED_CANONICAL));

    assertThat(run(ratings, log, SortOrder.RATING))
        .extracting(AffinityRow::qid, AffinityRow::displayLabel)
        .containsExactly(org.assertj.core.groups.Tuple.tuple(CANONICAL, MINTED_LABEL));
  }

  @Test
  @DisplayName("should keep the source's name for a canonical id a source had already claimed")
  void shouldKeepTheSourcesNameWhenASourceHadAlreadyClaimedTheCanonicalId() throws IOException {
    FakeAffinityStore ratings =
        new FakeAffinityStore().rated(MINTED, 5, null, EARLY).rated(CANONICAL, 4, null, LATE);
    FakeAssertionLog log =
        new FakeAssertionLog()
            .with(
                node(CANONICAL, CANONICAL_LABEL),
                minted(MINTED, MINTED_LABEL),
                merged(MINTED, CANONICAL));

    assertThat(run(ratings, log, SortOrder.RATING))
        .extracting(AffinityRow::qid, AffinityRow::label)
        .as("carry stands in only where nothing has claimed the canonical node; a source wins")
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple(MINTED, MINTED_LABEL),
            org.assertj.core.groups.Tuple.tuple(CANONICAL, CANONICAL_LABEL));
  }

  @Test
  @DisplayName("should name a canonical id from what was merged into it when only it is rated")
  void shouldNameACanonicalIdFromWhatWasMergedIntoItWhenOnlyItIsRated() throws IOException {
    FakeAffinityStore ratings = new FakeAffinityStore().rated(CANONICAL, 4, null, LATE);
    FakeAssertionLog log =
        new FakeAssertionLog().with(minted(MINTED, MINTED_LABEL), merged(MINTED, CANONICAL));

    assertThat(run(ratings, log, SortOrder.RATING))
        .extracting(AffinityRow::qid, AffinityRow::displayLabel)
        .as("the only name this node has ever had is the one the merge carried onto it")
        .containsExactly(org.assertj.core.groups.Tuple.tuple(CANONICAL, MINTED_LABEL));
  }

  @Test
  @DisplayName("the last claim about an entity wins the label, matching upsertNode")
  void takesTheLatestLabel() throws IOException {
    FakeAffinityStore ratings = new FakeAffinityStore().rated(QUARTET, 4, null, EARLY);
    FakeAssertionLog log =
        new FakeAssertionLog()
            .with(node(QUARTET, "An Earlier Invented Name"), node(QUARTET, QUARTET_LABEL));

    assertThat(run(ratings, log, SortOrder.RATING))
        .extracting(AffinityRow::label)
        .containsExactly(QUARTET_LABEL);
  }

  @Test
  @DisplayName("a rating the graph has no claim for is still listed, and the count is reported")
  void keepsARatingWhoseEntityIsGone() throws IOException {
    FakeAffinityStore ratings =
        new FakeAffinityStore().rated(QUARTET, 5, null, EARLY).rated(VANISHED, 2, null, LATE);
    FakeAssertionLog log = new FakeAssertionLog().with(node(QUARTET, QUARTET_LABEL));

    List<AffinityRow> rows = run(ratings, log, SortOrder.RATING);

    assertThat(rows).hasSize(2);
    assertThat(rows)
        .filteredOn(row -> row.qid().equals(VANISHED))
        .singleElement()
        .extracting(AffinityRow::label)
        .isNull();
    assertThat(notes).anyMatch(line -> line.contains("1 rating(s) name an entity"));
  }

  @Test
  @DisplayName(
      "a rating on a retracted entity lists as \"(not in the graph)\", because get_entity would"
          + " report it gone too")
  void shouldNotLabelARatingWhenTheEntityWasRetracted() throws IOException {
    FakeAffinityStore ratings = new FakeAffinityStore().rated(QUARTET, 5, null, EARLY);
    FakeAssertionLog log =
        new FakeAssertionLog().with(node(QUARTET, QUARTET_LABEL), retract(QUARTET));

    List<AffinityRow> rows = run(ratings, log, SortOrder.RATING);

    assertThat(rows)
        .singleElement()
        .extracting(AffinityRow::label, AffinityRow::displayLabel)
        .as("Labels' own javadoc: a label here must be the label get_entity would return")
        .containsExactly(null, AffinityRow.NO_LABEL);
  }

  @Test
  @DisplayName(
      "a retracted LocalEntity is folded too, because both claim types name an entity (#92)")
  void shouldNotLabelARatingWhenTheOwnersMintedEntityWasRetracted() throws IOException {
    FakeAffinityStore ratings = new FakeAffinityStore().rated(MINTED, 4, null, EARLY);
    FakeAssertionLog log =
        new FakeAssertionLog().with(minted(MINTED, MINTED_LABEL), retract(MINTED));

    List<AffinityRow> rows = run(ratings, log, SortOrder.RATING);

    assertThat(rows)
        .singleElement()
        .extracting(AffinityRow::label, AffinityRow::displayLabel)
        .as("a fold that honours retraction only for NodeAssertion is a silent half-fix")
        .containsExactly(null, AffinityRow.NO_LABEL);
  }

  @Test
  @DisplayName(
      "a claim made after the retraction still counts, the way re-adding an entity comes back"
          + " naturally (ADR 44)")
  void shouldLabelARatingWhenTheClaimCameAfterTheRetraction() throws IOException {
    FakeAffinityStore ratings = new FakeAffinityStore().rated(QUARTET, 5, null, EARLY);
    FakeAssertionLog log =
        new FakeAssertionLog()
            .with(
                node(QUARTET, "An Earlier Invented Name"),
                retract(QUARTET),
                node(QUARTET, QUARTET_LABEL));

    List<AffinityRow> rows = run(ratings, log, SortOrder.RATING);

    assertThat(rows)
        .singleElement()
        .extracting(AffinityRow::label)
        .as("survives compares the claim's position to the last retraction of that qid")
        .isEqualTo(QUARTET_LABEL);
  }

  @Test
  @DisplayName(
      "an entity that was never retracted is unaffected, even when the log holds a retraction of"
          + " something else")
  void shouldLabelARatingWhenTheEntityWasNeverRetracted() throws IOException {
    FakeAffinityStore ratings =
        new FakeAffinityStore().rated(QUARTET, 5, null, EARLY).rated(NOVEL, 3, null, LATE);
    FakeAssertionLog log =
        new FakeAssertionLog()
            .with(node(QUARTET, QUARTET_LABEL), node(NOVEL, NOVEL_LABEL), retract(VANISHED));

    List<AffinityRow> rows = run(ratings, log, SortOrder.RATING);

    assertThat(rows)
        .extracting(AffinityRow::qid, AffinityRow::label)
        .as("the fold must be scoped to the retracted qid, not the presence of any retraction")
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple(QUARTET, QUARTET_LABEL),
            org.assertj.core.groups.Tuple.tuple(NOVEL, NOVEL_LABEL));
  }

  @Test
  @DisplayName(
      "a retracted merge still carries no label onto the canonical id, and this is #92's own"
          + " behaviour, not new")
  void shouldNotLabelTheCanonicalIdWhenTheMergeWasRetracted() throws IOException {
    // MINTED is rated directly too (not only CANONICAL), on
    // shouldNameTheCanonicalIdWhenAMergeHasCarriedTheRatingOntoIt's precedent: this is what makes
    // MINTED's label reach labels regardless of the merge, so the assertion below tests the merge
    // branch's own retraction guard rather than the earlier wanted-qid gate that also blocks it.
    FakeAffinityStore ratings =
        new FakeAffinityStore().rated(MINTED, 5, null, EARLY).rated(CANONICAL, 5, null, LATE);
    FakeAssertionLog log =
        new FakeAssertionLog()
            .with(minted(MINTED, MINTED_LABEL), merged(MINTED, CANONICAL), retract(CANONICAL));

    List<AffinityRow> rows = run(ratings, log, SortOrder.RATING);

    assertThat(rows)
        .extracting(AffinityRow::qid, AffinityRow::label)
        .as("a SameAs naming a retracted entity on either side must not carry a label onto it")
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple(MINTED, MINTED_LABEL),
            org.assertj.core.groups.Tuple.tuple(CANONICAL, null));
  }

  @Test
  @DisplayName("the personal-data warning is the first note, before the file exists")
  void warnsBeforeItWrites() throws IOException {
    run(
        new FakeAffinityStore().rated(QUARTET, 5, QUARTET_NOTE, EARLY),
        new FakeAssertionLog(),
        SortOrder.RATING);

    assertThat(notes.get(0)).isEqualTo(RatingsRun.PERSONAL_DATA_WARNING);
    assertThat(fileExistedWhenNoted.get(0)).isFalse();
  }

  @Test
  @DisplayName("no note carries a label, a note or anything else a person wrote")
  void reportsCountsAndNothingPersonal() throws IOException {
    run(
        new FakeAffinityStore()
            .rated(QUARTET, 5, QUARTET_NOTE, EARLY)
            .rated(NOVEL, 1, NOVEL_NOTE, LATE),
        new FakeAssertionLog().with(node(QUARTET, QUARTET_LABEL), node(NOVEL, NOVEL_LABEL)),
        SortOrder.RATING);

    assertThat(notes)
        .noneMatch(line -> line.contains(QUARTET_LABEL))
        .noneMatch(line -> line.contains(NOVEL_LABEL))
        .noneMatch(line -> line.contains(QUARTET_NOTE))
        .noneMatch(line -> line.contains(NOVEL_NOTE))
        .noneMatch(line -> line.contains(QUARTET))
        .noneMatch(line -> line.contains(NOVEL));
  }

  @Test
  @DisplayName("the rows reach the file, sorted the way the run was asked to sort them")
  void writesTheTable() throws IOException {
    run(
        new FakeAffinityStore()
            .rated(QUARTET, 1, QUARTET_NOTE, LATE)
            .rated(NOVEL, 5, NOVEL_NOTE, EARLY),
        new FakeAssertionLog().with(node(QUARTET, QUARTET_LABEL), node(NOVEL, NOVEL_LABEL)),
        SortOrder.RATING);

    String written = Files.readString(out);

    assertThat(written).contains(QUARTET_LABEL).contains(NOVEL_LABEL).contains(NOVEL_NOTE);
    assertThat(written.indexOf(NOVEL_LABEL)).isLessThan(written.indexOf(QUARTET_LABEL));
    assertThat(notes).anyMatch(line -> line.contains(out.toString()));
  }

  @Test
  @DisplayName("nothing rated: the log is never read, because there is nothing to name")
  void doesNotTouchTheLogWhenNothingIsRated() throws IOException {
    FakeAssertionLog log = new FakeAssertionLog().with(node(QUARTET, QUARTET_LABEL));

    List<AffinityRow> rows = run(new FakeAffinityStore(), log, SortOrder.RECENT);

    assertThat(rows).isEmpty();
    assertThat(log.reads()).isZero();
    assertThat(Files.readString(out)).contains("no ratings");
  }
}
