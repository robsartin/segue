package com.robsartin.segue.own;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.Equivalences;
import com.robsartin.segue.domain.LocalEntity;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.OwnerEdge;
import com.robsartin.segue.domain.Retraction;
import com.robsartin.segue.domain.Retractions;
import com.robsartin.segue.domain.SameAs;
import com.robsartin.segue.ingest.IngestService;
import com.robsartin.segue.own.OwnCli.Assert;
import com.robsartin.segue.own.OwnCli.Merge;
import com.robsartin.segue.own.OwnCli.Mint;
import com.robsartin.segue.own.OwnCli.Options;
import com.robsartin.segue.port.AssertionLog;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Read, report, then append - {@code RetractRun}'s order, for {@code RetractRun}'s reason.
 *
 * <p><b>It writes to the log and to nothing else.</b> There is no graph here, deliberately: the
 * owner's three claims all have a graph half, but a dev-side tool has no running graph to apply it
 * to, and the projection is rebuilt from the log at the next boot (ADR 24) exactly as it is after a
 * retraction. So the append goes through {@link IngestService#claim}, which is static for the same
 * reason {@code IngestService.retract} is: requiring an instance would mean handing this tool a
 * {@code GraphStore} it must never touch, purely to satisfy a constructor.
 *
 * <p><b>The report comes before the append.</b> Two of the three operations name qids by hand, and
 * the third mints an identifier nobody has seen yet; all three land a row in a log that is never
 * edited. So the operator is told the <em>labels</em> of everything the claim touches while the log
 * is still untouched - the same safety feature {@code RetractRun} exists to provide, and for the
 * same failure: a QID that is not the entity somebody thought it was.
 *
 * <p><b>"Present" means present in the projection this invocation replays</b>, not "somewhere in
 * the log". An endpoint an earlier retraction reached is absent, because the shared {@link
 * Retractions} fold says so - the same rule both graph projections and the exporter apply; an
 * endpoint the owner has merged away is absent too, and the id it was merged into is present, on
 * the shared {@link Equivalences} fold and {@code IngestService.standIn}'s node rule. A local
 * entity minted by an <em>earlier</em> invocation is present, because it projects through {@code
 * LocalEntity.toNode()}. Minting and asserting in one run is deliberately not supported: one
 * operation per run, as {@code retractEntity} does one retraction per run.
 */
public final class OwnRun {

  private final AssertionLog log;
  private final Clock clock;

  public OwnRun(AssertionLog log, Clock clock) {
    this.log = Objects.requireNonNull(log, "log");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Make one claim.
   *
   * @return the claim that was appended - or, on a dry run, the one that would have been. Returning
   *     it is what lets {@code mint} answer with the id it allocated without the caller re-reading
   *     the log to guess which row is new
   */
  public LoggedAssertion run(Options options, Consumer<String> notes) {
    Objects.requireNonNull(options, "options");
    Objects.requireNonNull(notes, "notes");

    List<LoggedAssertion> logged = log.readAll();
    LoggedAssertion claim =
        switch (options) {
          case Mint mint -> mintEntity(logged, mint, notes);
          case Assert edge -> assertEdge(logged, edge, notes);
          case Merge merge -> declareMerge(logged, merge, notes);
        };

    if (options.dryRun()) {
      notes.accept("dry run: nothing was appended");
      return claim;
    }
    IngestService.claim(log, claim);
    notes.accept(
        "appended. The running graph is rebuilt from the log at the next boot (ADR 24), so a"
            + " server that is up does not see this claim until it restarts");
    return claim;
  }

  /**
   * Mint a local entity under an id no row in this log has ever named.
   *
   * <p>The claim is built through {@code LocalEntity.minted}, never the constructor: the two
   * leading zeros are this project's convention rather than Wikidata's grammar, and the factory is
   * where a convention is enforced (see {@code LocalEntity.minted}, and the ArchUnit rule {@code
   * ownerClaimsAreMadeThroughTheirFactories}).
   */
  private LoggedAssertion mintEntity(List<LoggedAssertion> logged, Mint mint, Consumer<String> n) {
    String qid = anIdNothingHasNamed(logged);
    n.accept(
        "minting "
            + qid
            + " \""
            + mint.label()
            + "\" ("
            + mint.kind()
            + ") — no source claims this entity; you are the source");
    return LocalEntity.minted(qid, mint.kind(), mint.label(), clock.instant());
  }

  /** Claim a relationship, refusing an endpoint the projection does not hold. */
  private LoggedAssertion assertEdge(
      List<LoggedAssertion> logged, Assert edge, Consumer<String> n) {
    Equivalences merges = Equivalences.in(logged);
    Map<String, String> present = labelsInTheProjection(logged, merges);
    String from = labelOrRefuse(present, merges, edge.fromQid());
    String to = labelOrRefuse(present, merges, edge.toQid());
    n.accept(
        "claiming "
            + edge.fromQid()
            + " \""
            + from
            + "\" "
            + edge.typeCode()
            + " "
            + edge.toQid()
            + " \""
            + to
            + "\"");
    n.accept(
        "this is your own claim, not a source's: it is exempt from the corroboration count, so it"
            + " routes but never vouches for anything (#92)");
    return OwnerEdge.claimed(edge.fromQid(), edge.toQid(), edge.typeCode(), clock.instant());
  }

  /** Declare a merge, refusing a local id this log never minted. */
  private LoggedAssertion declareMerge(
      List<LoggedAssertion> logged, Merge merge, Consumer<String> n) {
    String label = mintedInTheProjection(logged).get(merge.localQid());
    if (label == null) {
      throw new IllegalArgumentException(
          "nothing in the projection minted "
              + merge.localQid()
              + " — check the id, or it may already be retracted");
    }
    n.accept(
        "merging "
            + merge.localQid()
            + " \""
            + label
            + "\" into "
            + merge.canonicalQid()
            + ": you are saying they are the same thing");
    n.accept(
        "nothing is deleted — the local id stays resolvable and keeps its own rating, and its edges"
            + " move onto the canonical id, where they are counted once (ADR 19, ADR 44; ADR 59 as"
            + " #178 amends it)");
    // Said, not refused. A second merge of one local id is how a wrong merge is corrected -
    // Equivalences' rule is that the last surviving merge wins - so the operator who means it
    // keeps his correction, and the operator who has forgotten sees it before the log is touched.
    //
    // The graph half used to be the other way round and this line used to say so: carry COPIED the
    // edges at each merge's own row, so a twice-merged local id left a copy on both canonical ids
    // and the note had to warn that the graph kept both. Since #178 both folds resolve endpoints
    // through the same last-wins map, so the edges land on this canonical id alone - one rule for
    // the rating and the graph instead of two answers to one question.
    String already = Equivalences.in(logged).canonicalByLocal().get(merge.localQid());
    if (already != null) {
      n.accept(
          merge.localQid()
              + " was already merged into "
              + already
              + " — the last merge wins, for the rating and for the edges alike");
    }
    return SameAs.declared(merge.localQid(), merge.canonicalQid(), clock.instant());
  }

  private static String labelOrRefuse(
      Map<String, String> present, Equivalences merges, String qid) {
    String label = present.get(qid);
    if (label == null) {
      // A merged local id is refused by name rather than as an absence. It IS still in the graph -
      // nothing is deleted - so "mint or seed it first" would be false and useless advice. Since
      // #178 an edge claimed against it would not be stranded either: both folds resolve endpoints
      // over the whole log, so it would silently become an edge on the canonical id. That is spec
      // ruling 2, and it is why this refusal is a courtesy to the operator - telling him which id
      // he is really claiming about - rather than the thing that keeps the graph correct.
      String canonical = merges.canonicalByLocal().get(qid);
      if (canonical != null) {
        throw new IllegalArgumentException(
            qid
                + " was merged into "
                + canonical
                + " — claim this against "
                + canonical
                + ", which is the id the merge retired it in favour of (#92)");
      }
      throw new IllegalArgumentException(
          "nothing in the projection is "
              + qid
              + " — an owner edge joins two entities that are already there, so mint or seed it"
              + " first (it may also have been retracted)");
    }
    return label;
  }

  /**
   * Every entity the projection currently holds, and what it is called.
   *
   * <p>Both kinds of node claim, because both project to one: a sourced {@link NodeAssertion} and
   * an entity the owner minted are equally legitimate ends of an owner edge - the whole point of
   * #92 is a route that starts or ends on something Wikidata does not model. Last claim wins,
   * matching {@code upsertNode} and both folds, so the label reported is the one the projection is
   * showing right now.
   *
   * <p><b>Merges move the offer, in both directions</b>, through the shared {@link Equivalences}
   * exactly as retraction goes through the shared {@link Retractions}. A merged local id is no
   * longer offered - it is the id the owner retired, and a claim naming it is a claim about the
   * canonical entity written under the wrong name (#178, spec ruling 2). The canonical id is
   * offered, carrying the merged entity's label, because the merge put a node under it. Asking
   * retraction alone got both halves wrong at once.
   *
   * <p><b>Package-private rather than private, and the reason is named so it is not guessed at.</b>
   * This is the third of the stand-in rule's four homes (ADR 59's residual, issue #220), and {@code
   * StandInAgreesInEveryHomeTest} feeds all four one log and holds them to one answer per canonical
   * id. The alternative was to drive {@code OwnRun.run} with a dry-run {@code OwnCli.Assert} and
   * read the labels back out of the operator note, which would put a prose parser in front of a
   * guard - the shape that turns "cannot read it" into "it is not there". Nothing outside this
   * package calls it, and no ArchUnit rule changes.
   */
  static Map<String, String> labelsInTheProjection(
      List<LoggedAssertion> logged, Equivalences merges) {
    Map<String, String> labels = new LinkedHashMap<>();
    Retractions retractions = Retractions.in(logged);
    for (int i = 0; i < logged.size(); i++) {
      LoggedAssertion assertion = logged.get(i);
      if (!retractions.survives(i, assertion)) {
        continue;
      }
      if (assertion instanceof NodeAssertion node) {
        labels.put(node.qid(), node.label());
      } else if (assertion instanceof LocalEntity minted) {
        labels.put(minted.qid(), minted.label());
      } else if (assertion instanceof SameAs merge && !labels.containsKey(merge.canonicalQid())) {
        // The node stand-in, the same rule Equivalences.standIns and both folds apply: the id
        // gains a node carrying the merged entity's label where nothing has claimed one. It is in
        // the projection, so this tool has to offer it - and it is usually the id the owner wants
        // next, since a merge is normally declared the moment Wikidata catches up.
        String local = labels.get(merge.localQid());
        if (local != null) {
          labels.put(merge.canonicalQid(), local);
        }
      }
    }
    // A merged local id stops being offered as an endpoint, the shared rule saying which ids those
    // are. Retraction alone was not enough: the id stays resolvable and stays in the graph, so
    // nothing here refused it, and the edge landed on the id the owner had just retired.
    labels.keySet().removeAll(merges.merged());
    return labels;
  }

  /**
   * The entities the <em>owner</em> minted, which is the only thing a merge's local side may be.
   *
   * <p>Narrower than {@link #labelsInTheProjection} on purpose. A merge says "the thing I minted
   * turned out to be this Wikidata item"; pointing one at a sourced entity would be asserting that
   * two real Wikidata ids are the same thing, which is a different claim this tool does not make.
   */
  private static Map<String, String> mintedInTheProjection(List<LoggedAssertion> logged) {
    Map<String, String> labels = new LinkedHashMap<>();
    Retractions retractions = Retractions.in(logged);
    for (int i = 0; i < logged.size(); i++) {
      LoggedAssertion assertion = logged.get(i);
      if (retractions.survives(i, assertion) && assertion instanceof LocalEntity minted) {
        labels.put(minted.qid(), minted.label());
      }
    }
    return labels;
  }

  /**
   * The next free local id: {@code Q00} and the smallest number no row in the log has ever named.
   *
   * <p><b>Ever named, not "currently in the projection".</b> The log is append-only (ADR 19) and a
   * retraction is a claim rather than a deletion (ADR 44), so a retracted row still names its id
   * forever. Handing that id to a second entity would make every earlier row ambiguous about which
   * of the two it meant - and there is no editing them afterwards. Ids are therefore never
   * recycled.
   *
   * <p><b>Membership, not a high-water mark.</b> "One past the largest" would have to parse the
   * digits after {@code Q00}, and {@code Q0010} and {@code Q00010} parse to the same number while
   * being different ids - so a padded id written by hand would collide silently. Asking whether a
   * candidate is taken cannot: it compares the strings that actually go in the column.
   */
  private static String anIdNothingHasNamed(List<LoggedAssertion> logged) {
    Set<String> named = everNamed(logged);
    int n = 1;
    while (named.contains("Q00" + n)) {
      n++;
    }
    return "Q00" + n;
  }

  /** Every qid any row mentions, whatever it says about it and whether or not it still projects. */
  private static Set<String> everNamed(List<LoggedAssertion> logged) {
    Set<String> named = new LinkedHashSet<>();
    for (LoggedAssertion assertion : logged) {
      switch (assertion) {
        case NodeAssertion node -> named.add(node.qid());
        case AssertionRecord edge -> {
          named.add(edge.fromQid());
          named.add(edge.toQid());
        }
        case Retraction retraction -> named.add(retraction.qid());
        case LocalEntity minted -> named.add(minted.qid());
        case OwnerEdge edge -> {
          named.add(edge.fromQid());
          named.add(edge.toQid());
        }
        case SameAs merge -> {
          named.add(merge.localQid());
          named.add(merge.canonicalQid());
        }
      }
    }
    return named;
  }
}
