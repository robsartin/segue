package com.robsartin.segue.own;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.robsartin.segue.domain.NodeKind;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Everything that can be refused is refused before a database is opened - {@code RetractCliTest}'s
 * rule, for a tool with three operations instead of one.
 *
 * <p>The stand-ins here take a single leading zero (ADR 58): well-formed, and never allocatable by
 * Wikidata. The one exception is a merge's canonical side, which must be allocatable or the merge
 * is not "Wikidata caught up" at all - it uses the same {@code Q900} this branch's other merge
 * tests use.
 */
class OwnCliTest {

  @TempDir Path dir;

  private static OwnCli.Options parse(String... args) {
    return OwnCli.parse(args, null, "/home/invented");
  }

  @Test
  @DisplayName("should read the kind and the label when minting")
  void shouldReadTheKindAndTheLabelWhenMinting() {
    OwnCli.Mint mint = (OwnCli.Mint) parse("mint", "--kind", "WORK", "--label", "A Pressed Record");

    assertThat(mint.kind()).isEqualTo(NodeKind.WORK);
    assertThat(mint.label()).isEqualTo("A Pressed Record");
    assertThat(mint.dryRun()).isFalse();
    assertThat(mint.database()).isEqualTo(Path.of("/home/invented", ".segue", "segue.db"));
  }

  @Test
  @DisplayName("should read both endpoints and the type when asserting")
  void shouldReadBothEndpointsAndTheTypeWhenAsserting() {
    OwnCli.Assert claim =
        (OwnCli.Assert)
            parse("assert", "--from", "Q00900042", "--to", "Q0900101", "--type", "INFLUENCED_BY");

    assertThat(claim.fromQid()).isEqualTo("Q00900042");
    assertThat(claim.toQid()).isEqualTo("Q0900101");
    assertThat(claim.typeCode()).isEqualTo("INFLUENCED_BY");
  }

  @Test
  @DisplayName("should read both sides when merging")
  void shouldReadBothSidesWhenMerging() {
    OwnCli.Merge merge =
        (OwnCli.Merge) parse("merge", "--local", "Q00900042", "--canonical", "Q900");

    assertThat(merge.localQid()).isEqualTo("Q00900042");
    assertThat(merge.canonicalQid()).isEqualTo("Q900");
  }

  @Test
  @DisplayName("should let SEGUE_DB win over the home directory when both are available")
  void shouldLetSegueDbWinOverTheHomeDirectoryWhenBothAreAvailable() {
    OwnCli.Options options =
        OwnCli.parse(
            new String[] {"mint", "--kind", "PERSON", "--label", "someone"},
            "/elsewhere/segue.db",
            "/home/invented");

    assertThat(options.database()).isEqualTo(Path.of("/elsewhere/segue.db"));
  }

  @Test
  @DisplayName("should take no value when --dry-run is given")
  void shouldTakeNoValueWhenDryRunIsGiven() {
    assertThat(parse("mint", "--kind", "PERSON", "--label", "someone", "--dry-run").dryRun())
        .isTrue();
  }

  @Test
  @DisplayName("should refuse when no operation is named")
  void shouldRefuseWhenNoOperationIsNamed() {
    assertThatIllegalArgumentException().isThrownBy(OwnCliTest::parse);
  }

  @Test
  @DisplayName("should refuse when the operation is not one of the three")
  void shouldRefuseWhenTheOperationIsNotOneOfTheThree() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> parse("delete", "--qid", "Q0900101"))
        .withMessageContaining("delete");
  }

  @Test
  @DisplayName("should refuse when minting without a label")
  void shouldRefuseWhenMintingWithoutALabel() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> parse("mint", "--kind", "PERSON"))
        .withMessageContaining("--label");
  }

  @Test
  @DisplayName("should refuse when minting without a kind")
  void shouldRefuseWhenMintingWithoutAKind() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> parse("mint", "--label", "someone"))
        .withMessageContaining("--kind");
  }

  @Test
  @DisplayName("should name the six kinds when the kind is not one of them")
  void shouldNameTheSixKindsWhenTheKindIsNotOneOfThem() {
    // NodeKind is deliberately six ontological kinds and not a domain vocabulary, so "MUSICIAN"
    // is the mistake a first-time user makes. The refusal has to say what the six are.
    assertThatIllegalArgumentException()
        .isThrownBy(() -> parse("mint", "--kind", "MUSICIAN", "--label", "someone"))
        .withMessageContaining("PERSON");
  }

  @Test
  @DisplayName("should refuse when an endpoint of an assertion is not a qid")
  void shouldRefuseWhenAnEndpointOfAnAssertionIsNotAQid() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> parse("assert", "--from", "the-highwaymen", "--to", "Q0900101", "--type", "X"))
        .withMessageContaining("--from");
  }

  @Test
  @DisplayName("should refuse when an option belongs to a different operation")
  void shouldRefuseWhenAnOptionBelongsToADifferentOperation() {
    // --local is a merge's option. Accepting it here and ignoring it would silently mint an
    // entity the operator believed they were merging.
    assertThatIllegalArgumentException()
        .isThrownBy(() -> parse("mint", "--kind", "PERSON", "--label", "x", "--local", "Q00900042"))
        .withMessageContaining("--local");
  }

  @Test
  @DisplayName("should refuse when a flag has no value")
  void shouldRefuseWhenAFlagHasNoValue() {
    assertThatIllegalArgumentException().isThrownBy(() -> parse("mint", "--kind"));
  }

  @Test
  @DisplayName("should refuse a database that is not there rather than creating one")
  void shouldRefuseADatabaseThatIsNotThereRatherThanCreatingOne() {
    // RecommendationsAreNeverLoggedTest.anAbsentDatabaseIsRefused's rule, and it matters more
    // here: SqliteAssertionLog's constructor would create the file and its schema, so a mistyped
    // --db would mint the owner's first local entity into a database nobody asked for.
    Path absent = dir.resolve("nothing.db");

    assertThatThrownBy(
            () ->
                OwnCli.main(
                    new String[] {
                      "mint", "--db", absent.toString(), "--kind", "WORK", "--label", "a book"
                    }))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no segue database");

    assertThat(Files.exists(absent)).as("the database was not created").isFalse();
  }
}
