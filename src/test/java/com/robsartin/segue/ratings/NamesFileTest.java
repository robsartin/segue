package com.robsartin.segue.ratings;

import static com.robsartin.segue.ratings.InventedRatings.CANONICAL;
import static com.robsartin.segue.ratings.InventedRatings.CANONICAL_LABEL;
import static com.robsartin.segue.ratings.InventedRatings.NOVEL;
import static com.robsartin.segue.ratings.InventedRatings.NOVEL_LABEL;
import static com.robsartin.segue.ratings.InventedRatings.QUARTET;
import static com.robsartin.segue.ratings.InventedRatings.QUARTET_LABEL;
import static com.robsartin.segue.ratings.InventedRatings.VANISHED;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What reaches the upload: names, one per line, under one header line that says what the file is.
 * Every label here is invented — see {@link InventedRatings}.
 */
class NamesFileTest {

  private static String written(List<String> promotions, Map<String, String> labels)
      throws IOException {
    StringWriter out = new StringWriter();
    NamesFile.write(promotions, labels, out);
    return out.toString();
  }

  @Test
  @DisplayName("the file says what it is and how many names it holds, on one line")
  void shouldSayItIsPersonalDataAndCountTheNamesWhenItWritesTheHeader() throws IOException {
    String file = written(List.of(QUARTET), Map.of(QUARTET, QUARTET_LABEL));

    assertThat(file.lines().findFirst())
        .as("a file copied, pasted or attached somewhere else still says what it is (ADR 43)")
        .contains(NamesFile.PERSONAL_DATA_HEADER + " 1 name(s), one per line.");
  }

  @Test
  @DisplayName("one name per line, and nothing else on the line")
  void shouldWriteOneNamePerLineWhenTheGraphNamesEveryPromotion() throws IOException {
    String file =
        written(List.of(QUARTET, NOVEL), Map.of(QUARTET, QUARTET_LABEL, NOVEL, NOVEL_LABEL));

    assertThat(file.lines().skip(1))
        .as("the upload wants names; a qid column would make this a different file")
        .containsExactly(NOVEL_LABEL, QUARTET_LABEL);
  }

  @Test
  @DisplayName("the names are sorted, so two runs over one table produce one file")
  void shouldSortByNameWhenMoreThanOnePromotionIsWritten() throws IOException {
    String file =
        written(
            List.of(QUARTET, CANONICAL),
            Map.of(QUARTET, QUARTET_LABEL, CANONICAL, CANONICAL_LABEL));

    assertThat(file.indexOf(QUARTET_LABEL))
        .as("\"The Invented Quartet\" sorts before \"The Name A Source Gave It\" by code point")
        .isLessThan(file.indexOf(CANONICAL_LABEL));
  }

  @Test
  @DisplayName("no promotion is still a readable file, not an empty one")
  void shouldWriteAHeaderAndNothingElseWhenNothingIsPromoted() throws IOException {
    assertThat(written(List.of(), Map.of()))
        .isEqualTo(NamesFile.PERSONAL_DATA_HEADER + " 0 name(s), one per line.\n");
  }

  @Test
  @DisplayName("a promotion the graph cannot name is written as its qid and counted, never dropped")
  void shouldWriteTheQidWhenTheGraphCannotNameAPromotion() throws IOException {
    StringWriter out = new StringWriter();

    int unnamed = NamesFile.write(List.of(QUARTET, VANISHED), Map.of(QUARTET, QUARTET_LABEL), out);

    assertThat(out.toString().lines().skip(1))
        .as("dropping it would lose something the owner said yes to, leaving only a count")
        .containsExactly(VANISHED, QUARTET_LABEL);
    assertThat(unnamed).isEqualTo(1);
  }
}
