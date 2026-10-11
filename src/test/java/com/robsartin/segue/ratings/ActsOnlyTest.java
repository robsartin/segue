package com.robsartin.segue.ratings;

import static com.robsartin.segue.ratings.InventedRatings.EARLY;
import static com.robsartin.segue.ratings.InventedRatings.NOVEL;
import static com.robsartin.segue.ratings.InventedRatings.NOVEL_LABEL;
import static com.robsartin.segue.ratings.InventedRatings.QUARTET;
import static com.robsartin.segue.ratings.InventedRatings.QUARTET_LABEL;
import static com.robsartin.segue.ratings.InventedRatings.SEEN_LIVE;
import static com.robsartin.segue.ratings.InventedRatings.knownFile;
import static com.robsartin.segue.ratings.InventedRatings.node;
import static com.robsartin.segue.ratings.InventedRatings.performed;
import static com.robsartin.segue.ratings.InventedRatings.retract;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.ratings.InventedRatings.FakeAffinityStore;
import com.robsartin.segue.ratings.InventedRatings.FakeAssertionLog;
import com.robsartin.segue.ratings.RatingsCli.Options;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code --acts-only} (#354): every rating, label and entity here is invented. */
class ActsOnlyTest {

  private static final String SOLOIST = "Q0900006";
  private static final String SOLOIST_LABEL = "An Invented Soloist";

  @TempDir private Path dir;

  private final List<String> notes = new ArrayList<>();

  private List<String> names(FakeAffinityStore ratings, FakeAssertionLog log, boolean actsOnly)
      throws IOException {
    Path out = dir.resolve("promotions.txt");
    new RatingsRun(ratings, log)
        .run(
            new Options(
                dir.resolve("segue.db"),
                null,
                SortOrder.RATING,
                knownFile(dir, SEEN_LIVE),
                out,
                actsOnly),
            notes::add);
    return Files.readAllLines(out).stream().skip(1).toList();
  }

  private static FakeAffinityStore rated() {
    return new FakeAffinityStore()
        .rated(QUARTET, 5, null, EARLY)
        .rated(NOVEL, 5, null, EARLY)
        .rated(SOLOIST, 4, null, EARLY);
  }

  private static FakeAssertionLog log() {
    return new FakeAssertionLog()
        .with(
            node(QUARTET, NodeKind.GROUP, QUARTET_LABEL),
            node(NOVEL, NOVEL_LABEL),
            node(SOLOIST, NodeKind.PERSON, SOLOIST_LABEL),
            performed(SOLOIST, NOVEL));
  }

  @Test
  @DisplayName("without the flag every promotion is written, as before")
  void shouldWriteEveryPromotionWhenTheFlagIsAbsent() throws IOException {
    assertThat(names(rated(), log(), false))
        .containsExactlyInAnyOrder(QUARTET_LABEL, NOVEL_LABEL, SOLOIST_LABEL);
  }

  @Test
  @DisplayName("a group and a performer are acts, and a work nobody performed is not")
  void shouldKeepGroupsAndPerformersWhenActsOnly() throws IOException {
    assertThat(names(rated(), log(), true)).containsExactlyInAnyOrder(QUARTET_LABEL, SOLOIST_LABEL);
    assertThat(notes).anyMatch(n -> n.startsWith("1 promotion(s) left out"));
  }

  @Test
  @DisplayName("a retracted group is not an act")
  void shouldNotCountARetractedGroupAsAnAct() {
    FakeAssertionLog withRetraction =
        new FakeAssertionLog().with(node(QUARTET, NodeKind.GROUP, QUARTET_LABEL), retract(QUARTET));

    assertThat(Acts.in(withRetraction.readAll())).doesNotContain(QUARTET);
  }

  @Test
  @DisplayName("a later claim that the group is a work takes it back")
  void shouldFollowTheLastNodeClaim() {
    FakeAssertionLog relabelled =
        new FakeAssertionLog()
            .with(
                node(QUARTET, NodeKind.GROUP, QUARTET_LABEL),
                node(QUARTET, NodeKind.WORK, QUARTET_LABEL));

    assertThat(Acts.in(relabelled.readAll())).isEqualTo(Set.of());
  }

  @Test
  @DisplayName("--acts-only without --names is refused")
  void shouldRefuseActsOnlyWithoutNames() {
    assertThatThrownBy(
            () ->
                RatingsCli.parse(
                    new String[] {"--out", "/tmp/x.txt", "--acts-only"}, null, "/home/x"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--acts-only");
  }

  @Test
  @DisplayName("--acts-only is a flag with no value")
  void shouldParseActsOnlyAsABooleanFlag() {
    Options options =
        RatingsCli.parse(
            new String[] {"--promotions-off", "/tmp/k.csv", "--names", "/tmp/n.txt", "--acts-only"},
            null,
            "/home/x");

    assertThat(options.actsOnly()).isTrue();
  }
}
