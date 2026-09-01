package com.robsartin.segue.own;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.LocalEntity;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.OwnerEdge;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.domain.Retraction;
import com.robsartin.segue.domain.SameAs;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The seventh dev tool, against the real {@code SqliteAssertionLog} the way {@code RetractRunTest}
 * and {@code IngestServiceTest} use it - a double would let a claim the write half accepts and the
 * read half cannot decode pass every assertion here.
 *
 * <p>The world entities are single-leading-zero stand-ins (ADR 58); the ids the tool mints carry
 * two (issue #141); and a merge's canonical side is {@code Q900}, allocatable because a merge whose
 * canonical side were unallocatable would not be "Wikidata caught up" at all.
 */
class OwnRunTest {

  private static final Instant NOW = Instant.parse("2026-08-31T20:00:00Z");
  private static final Provenance SOURCE =
      new Provenance("invented", "invented:1", Instant.parse("2026-01-01T00:00:00Z"), 1.0);

  private static final String SOURCED = "Q0900101";
  private static final String OTHER_SOURCED = "Q0900102";
  private static final String CANONICAL = "Q900";
  private static final String NEVER_CLAIMED = "Q0900999";

  private AssertionLog log;
  private OwnRun run;
  private List<String> notes;

  @BeforeEach
  void setUp() {
    log = SqliteAssertionLog.inMemory();
    run = new OwnRun(log, Clock.fixed(NOW, ZoneOffset.UTC));
    notes = new ArrayList<>();
  }

  @AfterEach
  void tearDown() {
    log.close();
  }

  private static final Path UNUSED = Path.of("unused.db");

  private static OwnCli.Mint mint(String label, boolean dryRun) {
    return new OwnCli.Mint(UNUSED, NodeKind.WORK, label, dryRun);
  }

  private static OwnCli.Assert claim(String from, String to, boolean dryRun) {
    return new OwnCli.Assert(UNUSED, from, to, "INFLUENCED_BY", dryRun);
  }

  private static OwnCli.Merge merge(String local, boolean dryRun) {
    return new OwnCli.Merge(UNUSED, local, CANONICAL, dryRun);
  }

  private void seedASourcedEntity(String qid, String label) {
    log.append(new NodeAssertion(qid, NodeKind.PERSON, label, SOURCE));
  }

  private String mintOne(String label) {
    return ((LocalEntity) run.run(mint(label, false), notes::add)).qid();
  }

  @Test
  @DisplayName("should append exactly one claim and return the id when minting")
  void shouldAppendExactlyOneClaimAndReturnTheIdWhenMinting() {
    LoggedAssertion appended = run.run(mint("A Self-Pressed Record", false), notes::add);

    assertThat(log.readAll()).containsExactly(appended);
    assertThat(appended)
        .isInstanceOfSatisfying(
            LocalEntity.class,
            minted -> {
              assertThat(minted.label()).isEqualTo("A Self-Pressed Record");
              assertThat(minted.kind()).isEqualTo(NodeKind.WORK);
              assertThat(minted.mintedAt()).isEqualTo(NOW);
              assertThat(minted.qid()).startsWith("Q00");
            });
  }

  @Test
  @DisplayName("should mint a second entity under a different id when one is already minted")
  void shouldMintASecondEntityUnderADifferentIdWhenOneIsAlreadyMinted() {
    String first = mintOne("A Self-Pressed Record");
    String second = mintOne("An Indie Novel");

    assertThat(second).isNotEqualTo(first);
    assertThat(log.readAll()).hasSize(2);
  }

  @Test
  @DisplayName("should not offer a minted id again when the entity that held it was retracted")
  void shouldNotOfferAMintedIdAgainWhenTheEntityThatHeldItWasRetracted() {
    // The log is append-only (ADR 19) and a retraction is a claim, not a deletion (ADR 44), so
    // the retracted row still names the id forever. Handing it to a second entity would make
    // every earlier row ambiguous about which of the two it meant.
    String first = mintOne("A Self-Pressed Record");
    log.append(new Retraction(first, "never existed", NOW));

    assertThat(mintOne("An Indie Novel")).isNotEqualTo(first);
  }

  @Test
  @DisplayName("should refuse when the from endpoint of an assertion is not in the projection")
  void shouldRefuseWhenTheFromEndpointOfAnAssertionIsNotInTheProjection() {
    seedASourcedEntity(SOURCED, "Ines Marlow");

    assertThatThrownBy(() -> run.run(claim(NEVER_CLAIMED, SOURCED, false), notes::add))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(NEVER_CLAIMED);

    assertThat(log.readAll()).hasSize(1);
  }

  @Test
  @DisplayName("should refuse when the to endpoint of an assertion is not in the projection")
  void shouldRefuseWhenTheToEndpointOfAnAssertionIsNotInTheProjection() {
    seedASourcedEntity(SOURCED, "Ines Marlow");

    assertThatThrownBy(() -> run.run(claim(SOURCED, NEVER_CLAIMED, false), notes::add))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(NEVER_CLAIMED);

    assertThat(log.readAll()).hasSize(1);
  }

  @Test
  @DisplayName("should refuse when an endpoint of an assertion was retracted")
  void shouldRefuseWhenAnEndpointOfAnAssertionWasRetracted() {
    // "In the projection", not "somewhere in the log" - the same fold RetractRun measures with.
    seedASourcedEntity(SOURCED, "Ines Marlow");
    seedASourcedEntity(OTHER_SOURCED, "Ada Rourke");
    log.append(new Retraction(OTHER_SOURCED, "resolved to the wrong entity", NOW));

    assertThatThrownBy(() -> run.run(claim(SOURCED, OTHER_SOURCED, false), notes::add))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(OTHER_SOURCED);
  }

  @Test
  @DisplayName("should append the edge and name both labels when both endpoints are present")
  void shouldAppendTheEdgeAndNameBothLabelsWhenBothEndpointsArePresent() {
    seedASourcedEntity(SOURCED, "Ines Marlow");
    String minted = mintOne("A Self-Pressed Record");
    notes.clear();

    LoggedAssertion appended = run.run(claim(SOURCED, minted, false), notes::add);

    assertThat(appended).isEqualTo(new OwnerEdge(SOURCED, minted, "INFLUENCED_BY", NOW));
    assertThat(log.readAll()).last().isEqualTo(appended);
    assertThat(notes)
        .as("the labels of both ends, so a mistyped qid is visible before the log is touched")
        .anyMatch(note -> note.contains("Ines Marlow") && note.contains("A Self-Pressed Record"));
  }

  @Test
  @DisplayName("should refuse an unregistered relation type when asserting")
  void shouldRefuseAnUnregisteredRelationTypeWhenAsserting() {
    // ADR 22 clause 3, enforced once - by OwnerEdge.claimed, not by a second copy here.
    seedASourcedEntity(SOURCED, "Ines Marlow");
    seedASourcedEntity(OTHER_SOURCED, "Ada Rourke");

    assertThatThrownBy(
            () ->
                run.run(
                    new OwnCli.Assert(UNUSED, SOURCED, OTHER_SOURCED, "ADMIRES", false),
                    notes::add))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ADMIRES");

    assertThat(log.readAll()).hasSize(2);
  }

  @Test
  @DisplayName("should refuse when the local id of a merge was never minted")
  void shouldRefuseWhenTheLocalIdOfAMergeWasNeverMinted() {
    assertThatThrownBy(() -> run.run(merge("Q00900999", false), notes::add))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Q00900999");

    assertThat(log.readAll()).isEmpty();
  }

  @Test
  @DisplayName("should append the equivalence when the local id was minted")
  void shouldAppendTheEquivalenceWhenTheLocalIdWasMinted() {
    String minted = mintOne("A Self-Pressed Record");

    LoggedAssertion appended = run.run(merge(minted, false), notes::add);

    assertThat(appended).isEqualTo(new SameAs(minted, CANONICAL, NOW));
    assertThat(log.readAll()).last().isEqualTo(appended);
  }

  @Test
  @DisplayName("should append nothing when the operation is a dry run")
  void shouldAppendNothingWhenTheOperationIsADryRun() {
    seedASourcedEntity(SOURCED, "Ines Marlow");
    String minted = mintOne("A Self-Pressed Record");
    List<LoggedAssertion> before = log.readAll();

    run.run(mint("An Indie Novel", true), notes::add);
    run.run(claim(SOURCED, minted, true), notes::add);
    run.run(merge(minted, true), notes::add);

    assertThat(log.readAll()).isEqualTo(before);
    assertThat(notes).anyMatch(note -> note.contains("dry run"));
  }

  @Test
  @DisplayName("should report before it appends so the operator sees it whatever happens next")
  void shouldReportBeforeItAppendsSoTheOperatorSeesItWhateverHappensNext() {
    // RetractRun's rule, for the same reason: the log is never edited afterwards.
    seedASourcedEntity(SOURCED, "Ines Marlow");
    seedASourcedEntity(OTHER_SOURCED, "Ada Rourke");
    List<Integer> sizeWhenTheLabelsWereNamed = new ArrayList<>();

    run.run(
        claim(SOURCED, OTHER_SOURCED, false),
        note -> {
          if (note.contains("Ines Marlow")) {
            sizeWhenTheLabelsWereNamed.add(log.readAll().size());
          }
        });

    assertThat(sizeWhenTheLabelsWereNamed)
        .as("log size when both labels were reported - the append had not happened yet")
        .containsExactly(2);
  }

  @Test
  @DisplayName("should write a first-person claim when the owner asserts an edge")
  void shouldWriteAFirstPersonClaimWhenTheOwnerAssertsAnEdge() {
    // The tool writes to the log and to nothing else: projection happens on read (ADR 24). What
    // it writes is the owner's own claim - an AssertionRecord here would be the tool inventing
    // a source, which is the one thing an owner claim must never look like.
    seedASourcedEntity(SOURCED, "Ines Marlow");
    String minted = mintOne("A Self-Pressed Record");
    run.run(claim(SOURCED, minted, false), notes::add);

    assertThat(log.readAll())
        .filteredOn(AssertionRecord.class::isInstance)
        .as(
            "nothing sourced was invented: the owner's edge is an OwnerEdge, not an AssertionRecord")
        .isEmpty();
    assertThat(log.readAll()).hasSize(3);
  }
}
