package com.robsartin.segue.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.wikidata.StubWikidataServer;
import com.robsartin.segue.wikidata.WikidataClient;
import com.robsartin.segue.wikidata.WikidataEntityResolver;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Offline against the stub server. Every name is invented; see {@link NamesTest}. */
class SeedRunTest {

  @TempDir Path dir;

  private static final String TWO_HITS =
      "{\"search\":[{\"id\":\"Q90000401\",\"label\":\"x\",\"description\":\"y\"}],\"success\":1}";
  private static final String OTHER_HIT =
      "{\"search\":[{\"id\":\"Q90000402\",\"label\":\"x\",\"description\":\"y\"}],\"success\":1}";

  private static final String FACTS =
      """
      {"entities":{
        "Q90000401":{"id":"Q90000401","labels":{"en":{"value":"Velvet Ossuary"}},
          "sitelinks":{"enwiki":{},"frwiki":{}},
          "claims":{"P31":[{"mainsnak":{"snaktype":"value","datavalue":{"value":{"id":"Q215380"}}}}]}},
        "Q90000402":{"id":"Q90000402","labels":{"en":{"value":"Something Else"}},
          "sitelinks":{"enwiki":{}},
          "claims":{"P31":[{"mainsnak":{"snaktype":"value","datavalue":{"value":{"id":"Q215380"}}}}]}}
      }}
      """;

  private SeedRun runAgainst(StubWikidataServer stub) {
    WikidataClient client = new WikidataClient(stub.baseUri());
    return new SeedRun(
        new SeedResolver(new WikidataEntityResolver(client), new WikidataFacts(client), 5),
        dir.resolve("mapping.csv"),
        dir.resolve("review.csv"),
        10);
  }

  @Test
  @DisplayName("accepted names go to the mapping and the rest go to review, one row per input line")
  void splitsTheOutput() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody(TWO_HITS);
      stub.enqueueBody(OTHER_HIT);
      stub.enqueueBody(FACTS);

      SeedSummary summary =
          runAgainst(stub)
              .run(
                  List.of(
                      new SeedRow("Velvet Ossuary", "musician", "APPROVED"),
                      new SeedRow("The Velvet Ossuary", "musician", "SEED"),
                      new SeedRow("Bramble Sons", "musician", "APPROVED")));

      assertThat(summary.rows()).isEqualTo(3);
      assertThat(summary.groups()).isEqualTo(2);
      assertThat(summary.accepted()).isEqualTo(1);
      assertThat(summary.review()).isEqualTo(1);

      // Two spellings of one act each get their own mapping row, both carrying the one QID.
      assertThat(SeedFiles.readRows(dir.resolve("mapping.csv")))
          .hasSize(2)
          .allSatisfy(row -> assertThat(row.qid()).isEqualTo("Q90000401"));
      assertThat(SeedFiles.readRows(dir.resolve("review.csv"))).hasSize(1);
    }
  }

  @Test
  @DisplayName("a re-run asks Wikidata nothing it has already answered")
  void resumesWithoutRepeatingWork() {
    List<SeedRow> rows = List.of(new SeedRow("Velvet Ossuary", "musician", "APPROVED"));
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody(TWO_HITS);
      stub.enqueueBody(FACTS);
      runAgainst(stub).run(rows);
      int afterFirstRun = stub.requestCount();

      SeedSummary summary = runAgainst(stub).run(rows);

      assertThat(stub.requestCount()).isEqualTo(afterFirstRun);
      assertThat(summary.skipped()).isEqualTo(1);
      assertThat(summary.accepted()).isZero();
      assertThat(SeedFiles.readRows(dir.resolve("mapping.csv"))).hasSize(1);
    }
  }

  @Test
  @DisplayName("the summary reports the split without rounding it away")
  void summaryReportsTheSplit() {
    SeedSummary summary = new SeedSummary(913, 887, 3, 600, 200, 87);

    assertThat(String.join(" ", summary.lines()))
        .contains("913")
        .contains("887")
        .contains("600")
        .contains("200")
        .contains("87");
  }
}
