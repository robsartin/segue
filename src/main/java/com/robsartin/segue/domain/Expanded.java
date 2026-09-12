package com.robsartin.segue.domain;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which entities an expansion has been run over — read off what the log recorded, never guessed
 * from degree (issue #311).
 *
 * <p>An expansion leaves its seed's id in every edge it records, in one of two shapes. A Wikidata
 * forward claim carries the statement id, and a statement id begins with the subject's qid followed
 * by {@code $}. A reverse-discovered edge carries {@code wdqs:<other>:<property>:<seed>}, and the
 * seed is the last field. This class reads the reference apart on those separators rather than
 * testing a prefix or a suffix, which is what makes it exact: {@code Q12} is not {@code Q123}
 * whichever end you look from.
 *
 * <p><b>The forward shape's qid prefix appears in both cases in the wild, on one and the same
 * entity, and the match is case-insensitive because of it.</b> One hand-checked lowercase id, found
 * in issue #311's review, raised the question, and it read as though the rule were "always
 * lowercase". Asking the live API instead found statement ids of both cases on one entity, live
 * side by side; what was measured is that distribution and not a reason for it.
 * WikidataLiveSmokeTest holds the measurement and its counts; they are not restated here, and
 * ClaimMapper stores whichever shape it is handed, verbatim, as the reference. No fixture in this
 * repository carries a real statement id at all (the spec's own premise correction #5 records that
 * every recorded response falls back to the property-and-object form), so nothing offline could
 * have caught a pattern that silently refused part of them. Requiring uppercase would have dropped
 * the lowercase-minted part of the forward arm's contribution and over-counted "never expanded" on
 * the real graph, with no error and no signal — exactly the "a lenient parser turns 'cannot read
 * this' into 'there is nothing here'" shape. The digits and the dollar separator stay exact; only
 * the leading letter's case is relaxed, and only on this arm.
 *
 * <p><b>The shape is the whole rule, and the trap it avoids is a real row.</b> The reverse pass
 * also records each neighbour it discovered as a node claim whose reference is that neighbour's own
 * bare qid. A rule phrased "the reference ends with the qid" would read every discovered neighbour
 * as having expanded itself, and the count would collapse to "every node in the graph".
 *
 * <p><b>It does not read the MusicBrainz adapter's references, and that is a stated limit rather
 * than an oversight.</b> Those name the seed's MBID, not its qid. Every expansion runs every
 * adapter that supports the seed's kind and the Wikidata adapter supports every kind, so a
 * MusicBrainz expansion is accompanied by a Wikidata expansion of the same seed in the same call.
 * The residual — a call in which Wikidata was unavailable and MusicBrainz was not — leaves an
 * entity this rule calls unexpanded, which is a true statement about what Wikidata recorded and
 * errs towards "expand it". MusicBrainz is not the only residual of that shape: an expansion that
 * ran and returned nothing at all leaves no row either, and is indistinguishable here from one that
 * never ran; so is a forward-only expansion every one of whose statements carries no Wikidata
 * {@code id} and falls back to {@code ClaimMapper}'s {@code <property>:<objectQid>} reference,
 * which the forward arm cannot read a seed out of. Both are consistent with what "expanded" means
 * here — no row in the log cites the entity as a seed — and both err the same conservative way.
 *
 * <p><b>One caller reads it today, and it lives here for the shape {@code KindMapper.rederive} (ADR
 * 42) and {@link Retractions} (ADR 44) already set.</b> The census reads it to count what has never
 * been expanded. A second reader is expected — the re-expansion pass the census's own number is
 * meant to gate — and one rule here is what will keep the two from disagreeing about who has been
 * expanded. The promotion expander already shipped (ADR 66) is <b>not</b> that reader: it composes
 * its population from {@code KnownList.promoted} and the merges and never asks this question.
 *
 * <p>It holds no graph, opens nothing and makes no network call: a list of rows in, a set of qids
 * out.
 *
 * @param seeds the entities some row cites as an expansion's seed. Membership is the only thing
 *     ever asked of it, so no order is promised and none is read
 */
public record Expanded(Set<String> seeds) {

  private static final Pattern QID = Pattern.compile("Q\\d+");

  /**
   * The forward arm's qid prefix, case-insensitive on the leading letter, because Wikidata mints
   * statement GUIDs in both cases and one real entity carries both — the class javadoc above has
   * the finding, WikidataLiveSmokeTest has the measurement. The digits and the dollar separator
   * stay exact; only the letter's case is relaxed, and only here — {@link #QID} still governs the
   * reverse arm, whose reference ReverseClaims builds itself and always in the canonical uppercase
   * form.
   */
  private static final Pattern FORWARD_QID = Pattern.compile("[Qq]\\d+");

  /** What {@code ReverseClaims} puts in front of a reference it built from a truthy triple. */
  private static final String FROM_THE_QUERY_SERVICE = "wdqs:";

  public Expanded {
    seeds = Set.copyOf(Objects.requireNonNull(seeds, "seeds"));
  }

  /**
   * Every entity this log cites as the seed of an expansion.
   *
   * <p><b>The rows as they were written, never a fold of them.</b> That an expansion ran is a fact
   * the append-only log keeps, so it is read from the rows themselves; reading a projection instead
   * would flip a seed back to never-expanded as soon as a retraction dropped the edges carrying its
   * reference. What this answers is therefore "some row cites this as a seed", never "this has
   * current expansion data" — a caller counting a coverage gap wants the first.
   */
  public static Expanded in(List<LoggedAssertion> log) {
    Objects.requireNonNull(log, "log");
    Set<String> seeds = new LinkedHashSet<>();
    for (LoggedAssertion assertion : log) {
      // Exhaustive over the sealed interface, with no default: a seventh kind of claim has to
      // decide here whether it can carry an expansion's reference, rather than fall through.
      Provenance provenance =
          switch (assertion) {
            case NodeAssertion claim -> claim.provenance();
            case AssertionRecord claim -> claim.provenance();
            case Retraction ignored -> null;
            case LocalEntity ignored -> null;
            case OwnerEdge ignored -> null;
            case SameAs ignored -> null;
          };
      if (provenance == null) {
        continue;
      }
      String seed = seedOf(provenance.sourceRef());
      if (seed != null) {
        seeds.add(seed);
      }
    }
    return new Expanded(seeds);
  }

  /** Whether some row cites this entity as an expansion's seed. */
  public boolean covers(String qid) {
    Objects.requireNonNull(qid, "qid");
    return seeds.contains(qid);
  }

  /**
   * The seeds on the side a population is counted on, by the fold that population is read by.
   *
   * <p><b>Defensive rather than reachable today.</b> {@link #in} reads the rows as they were
   * written, so a row recorded before a merge cites the id the owner has since retired; asked about
   * a population already folded, it would report an entity as never expanded on work that was
   * really done. No writer in {@code src/main} records a seed on a retired side — a merge retires
   * an id the expander no longer visits — so this exists so that one fold, one side and every count
   * stay true whatever a later writer does (#311, #313).
   */
  public Expanded onTheCanonicalSide(Equivalences merges) {
    Objects.requireNonNull(merges, "merges");
    Set<String> resolved = new LinkedHashSet<>();
    for (String seed : seeds) {
      resolved.add(merges.canonical(seed));
    }
    return new Expanded(resolved);
  }

  /**
   * The seed a reference names, or null where it names none.
   *
   * <p>Read apart on the separator each shape actually uses. A reference that is neither shape —
   * {@code ClaimMapper}'s fallback for a statement carrying no id, a MusicBrainz citation, the bare
   * qid a discovered neighbour's node claim carries, or an owner claim's null — yields nothing at
   * all.
   */
  private static String seedOf(String sourceRef) {
    if (sourceRef == null) {
      return null;
    }
    if (sourceRef.startsWith(FROM_THE_QUERY_SERVICE)) {
      return qidOrNull(sourceRef.substring(sourceRef.lastIndexOf(':') + 1));
    }
    int statement = sourceRef.indexOf('$');
    return statement < 0 ? null : forwardQidOrNull(sourceRef.substring(0, statement));
  }

  private static String qidOrNull(String candidate) {
    return QID.matcher(candidate).matches() ? candidate : null;
  }

  /**
   * The forward arm's candidate, normalised to the canonical uppercase qid so {@link #covers} can
   * compare it against the uppercase ids every caller holds. Matching case-insensitively and then
   * normalising — rather than lower-casing both sides at the comparison — keeps {@link #seeds}
   * holding the same shape of id everywhere in this class.
   */
  private static String forwardQidOrNull(String candidate) {
    if (!FORWARD_QID.matcher(candidate).matches()) {
      return null;
    }
    return "Q" + candidate.substring(1);
  }
}
