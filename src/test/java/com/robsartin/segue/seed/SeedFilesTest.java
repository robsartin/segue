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

  @Test
  @DisplayName("the first write creates a header and the second does not repeat it")
  void appendsUnderOneHeader() throws IOException {
    Path out = dir.resolve("mapping.csv");
    SeedFiles.append(out, List.of(row("Velvet Ossuary", "Q090000201")));
    SeedFiles.append(out, List.of(row("Ashgrove", "Q090000202")));

    assertThat(Files.readAllLines(out))
        .hasSize(3)
        .first()
        .isEqualTo("name,kind,status,qid,label,confidence,reason");
  }

  @Test
  @DisplayName("a value carrying a comma or a quote survives the round trip")
  void quotesWhatNeedsQuoting() throws IOException {
    Path out = dir.resolve("mapping.csv");
    SeedFiles.append(
        out,
        List.of(
            new ResolutionRow(
                "Bramble, Vale & Ashgrove",
                "musician",
                "APPROVED",
                "Q090000203",
                "Bramble \"Vale\" Ashgrove",
                Outcome.ACCEPTED,
                "name, kind and occupation agree")));

    assertThat(SeedFiles.readRows(out))
        .singleElement()
        .satisfies(
            read -> {
              assertThat(read.name()).isEqualTo("Bramble, Vale & Ashgrove");
              assertThat(read.label()).isEqualTo("Bramble \"Vale\" Ashgrove");
              assertThat(read.reason()).isEqualTo("name, kind and occupation agree");
            });
  }

  @Test
  @DisplayName("a re-run does not redo what either output file already holds")
  void resumesFromBothOutputFiles() throws IOException {
    Path mapping = dir.resolve("mapping.csv");
    Path review = dir.resolve("review.csv");
    SeedFiles.append(mapping, List.of(row("The Velvet Ossuary", "Q090000204")));
    SeedFiles.append(review, List.of(row("Ashgrove", null)));

    var done = SeedFiles.alreadyResolved(List.of(mapping, review));

    // Keyed by the folded name, so the run that wrote "The Velvet Ossuary" also covers the
    // row spelled "Velvet Ossuary".
    assertThat(done).contains(Names.fold("Velvet Ossuary"), Names.fold("Ashgrove"));
  }

  @Test
  @DisplayName("nothing done yet is not an error")
  void resumingFromNothing() {
    assertThat(SeedFiles.alreadyResolved(List.of(dir.resolve("absent.csv")))).isEmpty();
  }

  private static ResolutionRow row(String name, String qid) {
    return new ResolutionRow(
        name, "musician", "APPROVED", qid, "label", Outcome.ACCEPTED, "because");
  }
}
