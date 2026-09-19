package com.robsartin.segue.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Every name here is invented; see {@link NamesTest}. */
class SeedFilesTest {

  @TempDir Path dir;

  private Path write(String name, String content) throws IOException {
    Path path = dir.resolve(name);
    Files.writeString(path, content);
    return path;
  }

  @Test
  @DisplayName("the list is three columns, and a quoted field may contain a comma")
  void readsTheList() throws IOException {
    Path list =
        write(
            "list.csv",
            """
            name,kind,status
            Velvet Ossuary,musician,APPROVED
            "Bramble, Vale & Ashgrove",musician,REJECTED

            """);

    List<SeedRow> rows = SeedFiles.readList(list);

    assertThat(rows).hasSize(2);
    assertThat(rows.get(1).name()).isEqualTo("Bramble, Vale & Ashgrove");
    assertThat(rows.get(1).status()).isEqualTo("REJECTED");
  }

  @Test
  @DisplayName("a row with an empty status reads, because a hand-written list carries none")
  void shouldReadTheRowWhenTheStatusFieldIsEmpty() throws IOException {
    // The reading list is written by hand, and a tour status is a fact about scheduling that a
    // hand list has nothing to say about. The column is carried through untouched, as SeedRow's
    // own note says, so "untouched" has to include empty.
    Path list =
        write(
            "reading.csv",
            """
            name,kind,status
            The Salt Almanac,book,
            Marguerite Vale,author,
            """);

    List<SeedRow> rows = SeedFiles.readList(list);

    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).kind()).isEqualTo("book");
    assertThat(rows.get(0).status()).isEmpty();
    assertThat(rows.get(1).status()).isEmpty();
  }

  @Test
  @DisplayName("a file that is not this list is refused rather than misread")
  void rejectsAnUnexpectedHeader() throws IOException {
    Path list = write("wrong.csv", "artist,genre\nVelvet Ossuary,folk\n");

    assertThatThrownBy(() -> SeedFiles.readList(list))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("name,kind,status");
  }

  @Test
  @DisplayName("spellings of one act become one group that remembers all of them")
  void groupsSpellingsOfOneAct() {
    List<SeedRow> rows =
        List.of(
            new SeedRow("The Tin Lanterns", "musician", "APPROVED"),
            new SeedRow("Tin Lanterns", "musician", "APPROVED"),
            new SeedRow("Marguerite Vale", "composer", "APPROVED"),
            new SeedRow("Marguerite Vale", "conductor", "APPROVED"));

    List<NameGroup> groups = NameGroup.of(rows);

    assertThat(groups).hasSize(2);
    assertThat(groups.get(0).rows()).hasSize(2);
    assertThat(groups.get(0).spellings()).contains("The Tin Lanterns", "Tin Lanterns");
    // One person, two roles.
    assertThat(groups.get(1).kinds()).containsExactly("composer", "conductor");
  }

  @Test
  @DisplayName("every raw spelling is tried before any invented fallback")
  void rawSpellingsComeBeforeFallbacks() {
    List<NameGroup> groups =
        NameGroup.of(
            List.of(
                new SeedRow("Sir Halcyon Drift", "musician", "APPROVED"),
                new SeedRow("The Sir Halcyon Drift", "musician", "APPROVED")));

    assertThat(groups).hasSize(1);
    assertThat(groups.get(0).spellings())
        .containsExactly("Sir Halcyon Drift", "The Sir Halcyon Drift", "Halcyon Drift");
  }

  @Test
  @DisplayName("a Discogs disambiguator keeps two same-named acts apart")
  void aDisambiguatorSuffixIsNotFoldedAway() {
    // The whole point of the suffix is that these are two DIFFERENT acts with one name.
    // Folding it away would merge them, which is the opposite of what it is for; stripping it
    // is only ever offered as a fallback spelling, and only for the act that carries it.
    List<NameGroup> groups =
        NameGroup.of(
            List.of(
                new SeedRow("Ashgrove", "musician", "APPROVED"),
                new SeedRow("Ashgrove (4)", "musician", "APPROVED")));

    assertThat(groups).hasSize(2);
    assertThat(groups.get(1).spellings()).containsExactly("Ashgrove (4)", "Ashgrove");
  }
}
