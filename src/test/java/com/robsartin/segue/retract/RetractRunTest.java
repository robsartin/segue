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
  private static final String SECOND_WORKING_TITLE = "Q00900202";
  private static final String CAUGHT_UP = "Q10000900301";
  private static final String SECOND_CANONICAL = "Q10000900302";

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
        .anySatisfy(note -> assertThat(note).contains(CAUGHT_UP).contains("1 edge"));
  }

  @Test
  @DisplayName(
      "a later, unrelated retraction does not re-report an earlier retraction's stranded edges")
  void shouldNotReReportAnEarlierRetractionsStrandedEdgesWhenRetractingSomethingElse() {
    log.append(new NodeAssertion(OTHER, NodeKind.PERSON, "Ines Marlow", SOURCE));
    log.append(LocalEntity.minted(WORKING_TITLE, NodeKind.WORK, "a working title", NOW));
    log.append(SameAs.declared(WORKING_TITLE, CAUGHT_UP, NOW));
    log.append(OwnerEdge.claimed(OTHER, CAUGHT_UP, "INFLUENCED_BY", NOW));
    log.append(new NodeAssertion(PAINTING, NodeKind.WORK, "A Landscape", SOURCE));

    run.run(options(WORKING_TITLE, "the mint was a mistake", false), notes::add);
    notes.clear();
    run.run(options(PAINTING, "unrelated retraction", false), notes::add);

    assertThat(notes)
        .as(
            "CAUGHT_UP was stranded by the FIRST retraction and already reported then; a second,"
                + " unrelated retraction must not name it again (#224)")
        .noneMatch(note -> note.contains(CAUGHT_UP));
  }

  @Test
  @DisplayName("two sources corroborating one stranded edge count as one edge, not two")
  void shouldCountAStrandedEdgeOnceWhenTwoSourcesCorroborateIt() {
    Provenance secondSource =
        new Provenance("invented2", "invented:2", Instant.parse("2026-01-02T00:00:00Z"), 1.0);
    log.append(new NodeAssertion(OTHER, NodeKind.PERSON, "Ines Marlow", SOURCE));
    log.append(LocalEntity.minted(WORKING_TITLE, NodeKind.WORK, "a working title", NOW));
    log.append(SameAs.declared(WORKING_TITLE, CAUGHT_UP, NOW));
    log.append(new AssertionRecord(OTHER, CAUGHT_UP, "INFLUENCED_BY", null, null, SOURCE));
    log.append(new AssertionRecord(OTHER, CAUGHT_UP, "INFLUENCED_BY", null, null, secondSource));

    run.run(options(WORKING_TITLE, "the mint was a mistake", true), notes::add);

    assertThat(notes)
        .as(
            "LogProjection.withdrawnEdges counts by edge key so two sources naming one"
                + " relationship are one withdrawn edge, not two - this report has to agree"
                + " (#224)")
        .anySatisfy(note -> assertThat(note).contains(CAUGHT_UP).contains("1 edge"));
  }

  @Test
  @DisplayName(
      "an edge naming two ids this retraction newly strands is reported under both, and the"
          + " distinct total across all lines matches the export's count")
  void shouldReportADistinctTotalWhenOneEdgeNamesTwoNewlyStrandedIds() {
    // WORKING_TITLE is merged onto CAUGHT_UP, then corrected onto SECOND_CANONICAL - two SameAs
    // rows off the same local id, so retracting it strands BOTH canonical ids (retractedStandIns
    // does not pick only the last-wins merge). The kept edge names both directly, so it
    // truthfully belongs to each id's own line - but LogProjection.withdrawnEdges counts it once,
    // and the report's closing total has to agree with that, not with the sum of the per-id lines.
    log.append(LocalEntity.minted(WORKING_TITLE, NodeKind.WORK, "a working title", NOW));
    log.append(SameAs.declared(WORKING_TITLE, CAUGHT_UP, NOW));
    log.append(SameAs.declared(WORKING_TITLE, SECOND_CANONICAL, NOW));
    log.append(OwnerEdge.claimed(CAUGHT_UP, SECOND_CANONICAL, "INFLUENCED_BY", NOW));

    run.run(options(WORKING_TITLE, "corrected twice, then retracted", true), notes::add);

    assertThat(notes)
        .as(
            "each id's own line is true - the edge does name it - but the closing line has to"
                + " give the DISTINCT total across all of them, matching the export (#224)")
        .anySatisfy(note -> assertThat(note).contains(CAUGHT_UP).contains("1 edge"))
        .anySatisfy(note -> assertThat(note).contains(SECOND_CANONICAL).contains("1 edge"))
        .anySatisfy(note -> assertThat(note).contains("1 distinct edge"));
  }

  @Test
  @DisplayName("retracting a canonical id whose merge is already gone strands no edges and says so")
  void shouldReportNoStrandingLineWhenTheRetractedCanonicalIdStrandsNothing() {
    // A source claimed the canonical id, the owner merged something onto it, and the local side
    // was retracted first - so the merge is already gone and the id is still held by the source's
    // own node claim. Retracting the canonical id NOW takes that node claim away too, which is
    // what puts the id into retractedStandIns for the first time. But every edge naming it was
    // dropped by its own retraction (Retractions.survives, either endpoint), so there is nothing
    // for a stranding line to report: the merge went at the EARLIER retraction, the id named
    // would be the one the operator just typed, and the count would be zero (#224).
    log.append(new NodeAssertion(CAUGHT_UP, NodeKind.GROUP, "The Caught Up", SOURCE));
    log.append(LocalEntity.minted(WORKING_TITLE, NodeKind.WORK, "a working title", NOW));
    log.append(SameAs.declared(WORKING_TITLE, CAUGHT_UP, NOW));

    run.run(options(WORKING_TITLE, "the mint was a mistake", false), notes::add);
    notes.clear();
    run.run(options(CAUGHT_UP, "and the entity itself was wrong", true), notes::add);

    assertThat(notes)
        .as(
            "a stranding line exists to name edges that stop projecting; one that names none is"
                + " telling the operator about a merge that went at an earlier retraction (#224)")
        .noneMatch(note -> note.contains("0 edge(s)"));
  }

  @Test
  @DisplayName(
      "an edge that reaches a newly-emptied canonical id only through a merge is reported, not"
          + " silently dropped")
  void shouldReportAStrandedEdgeThatReachesTheEmptiedCanonicalIdOnlyThroughAMerge() {
    // #228: CAUGHT_UP's stand-in is given to it TWICE, by two different local ids in turn.
    // WORKING_TITLE merges onto it first and is then retracted; a merge appended AFTER that
    // retraction survives it (Retractions reaches backwards only), so SECOND_WORKING_TITLE's own
    // merge onto CAUGHT_UP - and a second, later merge of WORKING_TITLE onto the same id - both
    // stand. The owner edge below names WORKING_TITLE, the retracted LOCAL id, not CAUGHT_UP by
    // name; it resolves to CAUGHT_UP only through canonicalByLocal. Retracting
    // SECOND_WORKING_TITLE takes away the only surviving node claim that gave CAUGHT_UP a
    // stand-in - WORKING_TITLE's own mint was retracted long before - so CAUGHT_UP is newly
    // emptied, and the edge has to be reported under it even though it never once wrote that id's
    // name.
    log.append(new NodeAssertion(OTHER, NodeKind.PERSON, "Ines Marlow", SOURCE));
    log.append(LocalEntity.minted(WORKING_TITLE, NodeKind.WORK, "a working title", NOW));
    log.append(SameAs.declared(WORKING_TITLE, CAUGHT_UP, NOW));
    log.append(new Retraction(WORKING_TITLE, "the mint was a mistake", NOW));
    log.append(
        LocalEntity.minted(SECOND_WORKING_TITLE, NodeKind.WORK, "another working title", NOW));
    log.append(SameAs.declared(SECOND_WORKING_TITLE, CAUGHT_UP, NOW));
    log.append(SameAs.declared(WORKING_TITLE, CAUGHT_UP, NOW));
    log.append(OwnerEdge.claimed(OTHER, WORKING_TITLE, "INFLUENCED_BY", NOW));

    run.run(options(SECOND_WORKING_TITLE, "this one was a mistake too", true), notes::add);

    assertThat(notes)
        .as(
            "the edge names WORKING_TITLE, which folds onto CAUGHT_UP through the surviving"
                + " re-merge - bucketing by the claim's raw endpoint instead of the one the fold"
                + " resolves silently dropped it from every line and from the distinct total"
                + " (#228)")
        .anySatisfy(note -> assertThat(note).contains(CAUGHT_UP).contains("1 edge"));
  }

  @Test
  @DisplayName("a single newly-stranded id gets no closing distinct-total line")
  void shouldNotAddAClosingLineWhenOnlyOneCanonicalIdIsNewlyStranded() {
    log.append(new NodeAssertion(OTHER, NodeKind.PERSON, "Ines Marlow", SOURCE));
    log.append(LocalEntity.minted(WORKING_TITLE, NodeKind.WORK, "a working title", NOW));
    log.append(SameAs.declared(WORKING_TITLE, CAUGHT_UP, NOW));
    log.append(OwnerEdge.claimed(OTHER, CAUGHT_UP, "INFLUENCED_BY", NOW));

    run.run(options(WORKING_TITLE, "the mint was a mistake", true), notes::add);

    assertThat(notes)
        .as(
            "only one id is newly stranded, so its own line already says the whole story and a"
                + " closing total would just repeat it (#224)")
        .noneMatch(note -> note.contains("distinct edge"));
  }
}
