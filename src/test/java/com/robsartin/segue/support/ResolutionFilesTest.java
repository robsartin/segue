package com.robsartin.segue.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Every name here is invented; see {@link com.robsartin.segue.seed.NamesTest}. */
class ResolutionFilesTest {

  @TempDir Path dir;

  @Test
  @DisplayName("the first write creates a header and the second does not repeat it")
  void appendsUnderOneHeader() throws IOException {
    Path out = dir.resolve("mapping.csv");
    ResolutionFiles.append(out, List.of(row("Velvet Ossuary", "Q090000201")));
    ResolutionFiles.append(out, List.of(row("Ashgrove", "Q090000202")));

    assertThat(Files.readAllLines(out))
        .hasSize(3)
        .first()
        .isEqualTo("name,kind,status,qid,label,confidence,reason");
  }

  @Test
  @DisplayName("a value carrying a comma or a quote survives the round trip")
  void quotesWhatNeedsQuoting() throws IOException {
    Path out = dir.resolve("mapping.csv");
    ResolutionFiles.append(
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

    assertThat(ResolutionFiles.readRows(out))
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
    ResolutionFiles.append(mapping, List.of(row("The Velvet Ossuary", "Q090000204")));
    ResolutionFiles.append(review, List.of(row("Ashgrove", null)));

    var done = ResolutionFiles.alreadyResolved(List.of(mapping, review));

    // Keyed by the folded name, so the run that wrote "The Velvet Ossuary" also covers the
    // row spelled "Velvet Ossuary".
    assertThat(done).contains(NameFold.fold("Velvet Ossuary"), NameFold.fold("Ashgrove"));
  }

  @Test
  @DisplayName("nothing done yet is not an error")
  void resumingFromNothing() {
    assertThat(ResolutionFiles.alreadyResolved(List.of(dir.resolve("absent.csv")))).isEmpty();
  }

  private static ResolutionRow row(String name, String qid) {
    return new ResolutionRow(
        name, "musician", "APPROVED", qid, "label", Outcome.ACCEPTED, "because");
  }
}
