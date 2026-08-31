package com.robsartin.segue.ratings;

import static com.robsartin.segue.ratings.InventedRatings.EARLY;
import static com.robsartin.segue.ratings.InventedRatings.LATE;
import static com.robsartin.segue.ratings.InventedRatings.MINTED;
import static com.robsartin.segue.ratings.InventedRatings.MINTED_LABEL;
import static com.robsartin.segue.ratings.InventedRatings.NOVEL;
import static com.robsartin.segue.ratings.InventedRatings.NOVEL_LABEL;
import static com.robsartin.segue.ratings.InventedRatings.NOVEL_NOTE;
import static com.robsartin.segue.ratings.InventedRatings.QUARTET;
import static com.robsartin.segue.ratings.InventedRatings.QUARTET_LABEL;
import static com.robsartin.segue.ratings.InventedRatings.QUARTET_NOTE;
import static com.robsartin.segue.ratings.InventedRatings.VANISHED;
import static com.robsartin.segue.ratings.InventedRatings.minted;
import static com.robsartin.segue.ratings.InventedRatings.node;
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
  void namesAnEntityTheOwnerMinted() throws IOException {
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
