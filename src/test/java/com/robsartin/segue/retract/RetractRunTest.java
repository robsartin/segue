package com.robsartin.segue.retract;

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
 * Invented people, invented bands, and QIDs that were not looked up - which is not the same as QIDs
 * that denote nothing. Some of the {@code Q900xxx} ids here resolve to real Wikidata entities. The
 * range was described as this project's placeholder range; it was never free, and {@code
 * fixture.Fixture} has since moved to ids Wikibase's item-id grammar refuses (ADR 58). This file
 * has not, because the family is shared across many unrelated test files - see <a
 * href="https://github.com/robsartin/segue/issues/171">issue #171</a>. Nothing here depends on what
 * any id denotes, and nothing here is derived from a real graph (ADR 40, issue #37).
 */
class RetractRunTest {

  private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");
  private static final Provenance SOURCE =
      new Provenance("invented", "invented:1", Instant.parse("2026-01-01T00:00:00Z"), 1.0);

  private static final String WRONG = "Q0900101";
  private static final String PAINTING = "Q0900102";
  private static final String OTHER = "Q0900103";
  private static final String WORKING_TITLE = "Q00900201";
  private static final String CAUGHT_UP = "Q10000900301";

  private AssertionLog log;
  private RetractRun run;
  private List<String> notes;

  @BeforeEach
  void setUp() {
    log = SqliteAssertionLog.inMemory();
    run = new RetractRun(log, Clock.fixed(NOW, ZoneOffset.UTC));
    notes = new ArrayList<>();
  }

  @AfterEach
  void tearDown() {
    log.close();
  }

  private RetractCli.Options options(String qid, String reason, boolean dryRun) {
    return new RetractCli.Options(Path.of("unused.db"), qid, reason, dryRun);
  }

  private void seedAWronglyExpandedEntity() {
    log.append(new NodeAssertion(WRONG, NodeKind.GROUP, "The Wrong Ones", SOURCE));
    log.append(new NodeAssertion(PAINTING, NodeKind.WORK, "A Landscape", SOURCE));
    log.append(new NodeAssertion(OTHER, NodeKind.PERSON, "Ines Marlow", SOURCE));
    log.append(new AssertionRecord(WRONG, PAINTING, "PERFORMED", null, null, SOURCE));
    log.append(new AssertionRecord(OTHER, WRONG, "INFLUENCED_BY", null, null, SOURCE));
  }

  @Test
  @DisplayName("a retraction is appended, and every original claim is still in the log")
  void appendsWithoutDisturbingTheLog() {
    seedAWronglyExpandedEntity();
    List<LoggedAssertion> before = log.readAll();

    run.run(options(WRONG, "resolved to the painters, not the band", false), notes::add);

    assertThat(log.readAll()).startsWith(before.toArray(new LoggedAssertion[0]));
    assertThat(log.readAll())
        .last()
        .isEqualTo(new Retraction(WRONG, "resolved to the painters, not the band", NOW));
  }

  @Test
  @DisplayName("the report names the entity's label and counts what will stop projecting")
  void reportsTheLabelAndTheCounts() {
    // The one safety feature that matters. The failure this whole issue is about is a QID that
    // is not the entity somebody thought it was, and a retraction of the wrong QID is the same
    // mistake one level up - so the tool says whose claims these are before it takes them out.
    seedAWronglyExpandedEntity();

    RetractRun.Effect effect = run.run(options(WRONG, "wrong entity", false), notes::add);

    assertThat(effect.label()).isEqualTo("The Wrong Ones");
    assertThat(effect.nodeClaims()).isEqualTo(1);
    assertThat(effect.edgeClaims()).isEqualTo(2);
    assertThat(notes).anyMatch(note -> note.contains("The Wrong Ones") && note.contains(WRONG));
  }

  @Test
  @DisplayName("the report comes before the append, so an operator sees it whatever happens next")
  void reportsBeforeItWrites() {
    // ExportRun's rule, for a stronger reason: there is no output file to inspect afterwards.
    seedAWronglyExpandedEntity();
    List<Integer> logSizeWhenTheLabelWasNamed = new ArrayList<>();

    run.run(
        options(WRONG, "wrong entity", false),
        note -> {
          if (note.contains("The Wrong Ones")) {
            logSizeWhenTheLabelWasNamed.add(log.readAll().size());
          }
        });

    assertThat(logSizeWhenTheLabelWasNamed)
        .as("log size when the label and counts were reported — the append had not happened yet")
        .containsExactly(5);
  }

  @Test
  @DisplayName("a dry run reports exactly the same thing and appends nothing")
  void dryRunWritesNothing() {
    seedAWronglyExpandedEntity();

    RetractRun.Effect effect = run.run(options(WRONG, "wrong entity", true), notes::add);

    assertThat(effect.nodeClaims()).isEqualTo(1);
    assertThat(effect.edgeClaims()).isEqualTo(2);
    assertThat(log.readAll()).hasSize(5);
  }

  @Test
  @DisplayName("retracting an entity the log has never projected is refused, not quietly recorded")
  void refusesAnEntityWithNothingToRetract() {
    // A mistyped QID would otherwise append a retraction that does nothing, forever, in a log
    // that is never edited. Refusing costs a re-run; the alternative is a permanent row that
    // reads as a decision somebody made.
    seedAWronglyExpandedEntity();

    assertThatThrownBy(() -> run.run(options("Q0900999", "wrong entity", false), notes::add))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Q0900999");

    assertThat(log.readAll()).hasSize(5);
  }

  @Test
  @DisplayName("retracting something already retracted is refused for the same reason")
  void refusesAnEntityAlreadyRetracted() {
    seedAWronglyExpandedEntity();
    run.run(options(WRONG, "wrong entity", false), notes::add);

    assertThatThrownBy(() -> run.run(options(WRONG, "wrong entity again", false), notes::add))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("an entity re-added after a retraction can be retracted again")
  void canRetractSomethingThatCameBack() {
    // The fourth question ADR 44 answers: there is no un-retraction, and coming back is just a
    // fresh claim. Which means it has to be retractable again, by the ordinary path.
    seedAWronglyExpandedEntity();
    run.run(options(WRONG, "wrong entity", false), notes::add);
    log.append(new NodeAssertion(WRONG, NodeKind.GROUP, "The Right Ones", SOURCE));

    RetractRun.Effect effect = run.run(options(WRONG, "wrong again", false), notes::add);

    assertThat(effect.label()).isEqualTo("The Right Ones");
    assertThat(effect.nodeClaims()).isEqualTo(1);
    assertThat(effect.edgeClaims()).isZero();
  }

  @Test
  @DisplayName("retracting a merged local id reports the edges that go with its stand-in")
  void shouldReportTheStrandedEdgesWhenTheRetractedIdWasMerged() {
    log.append(new NodeAssertion(OTHER, NodeKind.PERSON, "Ines Marlow", SOURCE));
    log.append(LocalEntity.minted(WORKING_TITLE, NodeKind.WORK, "a working title", NOW));
    log.append(SameAs.declared(WORKING_TITLE, CAUGHT_UP, NOW));
    log.append(OwnerEdge.claimed(OTHER, CAUGHT_UP, "INFLUENCED_BY", NOW));

    run.run(options(WORKING_TITLE, "the mint was a mistake", true), notes::add);

    assertThat(notes)
        .as(
            "the merge goes with the local id, and it was the only thing holding a node under the"
                + " canonical id - so the edge claimed against that id stops projecting too, and"
                + " the operator has to be told before the row is written (#224)")
        .anySatisfy(note -> assertThat(note).contains(CAUGHT_UP).contains("1 edge claim"));
  }
}
