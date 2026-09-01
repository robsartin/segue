package com.robsartin.segue.own;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.robsartin.segue.domain.NodeKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

  private static final String DATABASE = "/graphs/some.db";

  private static OwnCli.Options parse(String... args) {
    return OwnCli.parse(withDatabase(args), null, "/home/invented");
  }

  /** Every valid invocation now names --db, so every test of anything else has to name it too. */
  private static String[] withDatabase(String[] args) {
    if (args.length == 0 || List.of(args).contains("--db")) {
      return args;
    }
    String[] named = new String[args.length + 2];
    named[0] = args[0];
    named[1] = "--db";
    named[2] = DATABASE;
    System.arraycopy(args, 1, named, 3, args.length - 1);
    return named;
  }

  @Test
  @DisplayName("should read the kind and the label when minting")
  void shouldReadTheKindAndTheLabelWhenMinting() {
    OwnCli.Mint mint = (OwnCli.Mint) parse("mint", "--kind", "WORK", "--label", "A Pressed Record");

    assertThat(mint.kind()).isEqualTo(NodeKind.WORK);
    assertThat(mint.label()).isEqualTo("A Pressed Record");
    assertThat(mint.dryRun()).isFalse();
    assertThat(mint.database()).isEqualTo(Path.of(DATABASE));
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
  @DisplayName("should take no value when --dry-run is given")
  void shouldTakeNoValueWhenDryRunIsGiven() {
    assertThat(parse("mint", "--kind", "PERSON", "--label", "someone", "--dry-run").dryRun())
        .isTrue();
  }

  @Test
  @DisplayName(
      "should refuse when --db is not given, naming the flag and the path it would have used")
  void shouldRefuseWhenTheDatabaseIsNotNamed() {
    // The invocation from issue #179 itself: `./gradlew own --args="mint --kind WORK --label x"`,
    // which Gradle resolved to :ownClaim and ran against the owner's real log because --db was
    // not part of the copied line. The message names the flag and the path it would have used,
    // so the owner's next command is a copy-paste rather than a lookup.
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                OwnCli.parse(
                    new String[] {"mint", "--kind", "WORK", "--label", "x"},
                    null,
                    "/home/invented"))
        .withMessageContaining("--db")
        .withMessageContaining(Path.of("/home/invented", ".segue", "segue.db").toString());
  }

  @Test
  @DisplayName("should refuse before opening any database when --dry-run is given without --db")
  void shouldRefuseBeforeOpeningAnyDatabaseWhenDryRunIsGivenWithoutTheDatabase() {
    // RetractCliTest's rule, and for the same reason: the property is an ORDER. If the refusal
    // came after the Files.exists check the operator would be told "no segue database at …",
    // which reads as a missing file rather than a missing flag.
    Path home = dir.resolve("home");

    assertThatThrownBy(
            () ->
                OwnCli.run(
                    new String[] {"mint", "--kind", "WORK", "--label", "x", "--dry-run"},
                    null,
                    home.toString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--db")
        .hasMessageNotContaining("no segue database");

    assertThat(Files.exists(home.resolve(".segue").resolve("segue.db")))
        .as("no database was opened, so none was created")
        .isFalse();
  }

  @Test
  @DisplayName("should refuse when no operation is named")
  void shouldRefuseWhenNoOperationIsNamed() {
    // The message, not only the type. Every refusal in this class is an IllegalArgumentException,
    // so a type-only assertion passes when the parse fails for some entirely unrelated reason -
    // and then reports success for a refusal that never tested what it claims to.
    assertThatIllegalArgumentException()
        .isThrownBy(OwnCliTest::parse)
        .withMessageContaining("an operation is required");
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
  @DisplayName("should refuse when the database is given twice")
  void shouldRefuseWhenTheDatabaseIsGivenTwice() {
    // Every other flag is checked for a repeat; --db was not, so it took the last silently. A
    // path argument is the worst one to resolve that way: the operator sees the first --db they
    // typed and the claim lands in the second database.
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                parse(
                    "mint", "--db", "/one.db", "--db", "/two.db", "--kind", "PERSON", "--label",
                    "x"))
        .withMessageContaining("--db");
  }

  @Test
  @DisplayName("should refuse when a flag has no value")
  void shouldRefuseWhenAFlagHasNoValue() {
    // "needs a value" and not "is required": reading past the end of the arguments and never
    // seeing the flag at all are different bugs with the same exception type, and only the
    // message tells them apart.
    assertThatIllegalArgumentException()
        .isThrownBy(() -> parse("mint", "--kind"))
        .withMessageContaining("--kind needs a value");
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
