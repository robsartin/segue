package com.robsartin.segue.wikidata;

import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Maps Wikidata's {@code P31} (instance of) onto segue's six kinds.
 *
 * <p>Wikidata has tens of thousands of classes; segue has six, permanently (ADR 21). Walking {@code
 * P279} (subclass of) upward to find a known root would be more faithful and would cost an extra
 * round trip per unknown class, on a hierarchy deep enough that the walk is its own project. A
 * short whitelist plus an honest fallback is the trade.
 *
 * <p>Unmapped classes become {@link NodeKind#CONCEPT} and are reported by {@link
 * #isMapped(String)}, so the whitelist can grow from real data rather than speculation.
 *
 * <p>An entity can state several classes this list knows, and when they disagree the {@code
 * PRECEDENCE} below decides which kind wins - never the order the classes arrived in.
 */
public final class KindMapper {

  private static final Map<String, NodeKind> BY_CLASS = new LinkedHashMap<>();

  static {
    // people
    put("Q5", NodeKind.PERSON); // human
    // groups
    put("Q215380", NodeKind.GROUP); // musical group
    put("Q43229", NodeKind.GROUP); // organization
    put("Q2088357", NodeKind.GROUP); // musical ensemble
    put("Q4830453", NodeKind.GROUP); // business
    put("Q891723", NodeKind.GROUP); // public company
    // Wikidata does not settle on "musical group" for acts. These are the other classes a real
    // list of nine hundred acts actually used, measured while building the bulk seeding tool
    // (issue #49) rather than guessed — which is what the note above asks for. Every one was
    // looked up and confirmed by label AND description before it was written down. Without them
    // a quarter of the bands on that list resolved to CONCEPT.
    put("Q5741069", NodeKind.GROUP); // rock band
    put("Q9212979", NodeKind.GROUP); // musical duo
    put("Q19351429", NodeKind.GROUP); // a cappella group
    put("Q42998", NodeKind.GROUP); // orchestra
    put("Q131186", NodeKind.GROUP); // choir
    put("Q1538570", NodeKind.GROUP); // gospel choir
    put("Q207338", NodeKind.GROUP); // string quartet
    put("Q163740", NodeKind.GROUP); // nonprofit organization
    put("Q16334295", NodeKind.GROUP); // group of humans
    put("Q13473501", NodeKind.GROUP); // collective
    // Four more group classes, from the issue-#52 sweep described below.
    put("Q414147", NodeKind.GROUP); // academy of sciences
    put("Q56816954", NodeKind.GROUP); // heavy metal band
    put("Q18510489", NodeKind.GROUP); // comedy troupe
    put("Q178790", NodeKind.GROUP); // labor union
    // works
    put("Q11424", NodeKind.WORK); // film
    put("Q482994", NodeKind.WORK); // album
    put("Q7725634", NodeKind.WORK); // literary work
    put("Q571", NodeKind.WORK); // book
    put("Q134556", NodeKind.WORK); // single
    put("Q7366", NodeKind.WORK); // song
    put("Q5398426", NodeKind.WORK); // television series
    put("Q47461344", NodeKind.WORK); // written work
    put("Q3305213", NodeKind.WORK); // painting
    put("Q2431196", NodeKind.WORK); // audiovisual work
    // Wikidata does not settle on "film" or "album" for works either, and the consequence is
    // worse than it was for bands. Issue #52 demotes routes through a high-degree CONCEPT
    // intermediate as hubs, which is only honest while CONCEPT means "we could not place this".
    // Measured over every CONCEPT node in a real 25,815-node graph that could be an intermediate
    // at all (degree >= 2, 1,416 of them): 1,058 were works wearing a class this list did not
    // know — 667 of them "musical work/composition" alone, and one of them the best connector in
    // the whole graph. Every QID was looked up and confirmed by label AND description.
    put("Q105543609", NodeKind.WORK); // musical work/composition
    put("Q21191270", NodeKind.WORK); // television series episode
    put("Q110039749", NodeKind.WORK); // Saturday Night Live sketch
    put("Q506240", NodeKind.WORK); // television film
    put("Q24862", NodeKind.WORK); // short film
    put("Q1261214", NodeKind.WORK); // television special
    put("Q15416", NodeKind.WORK); // television program
    put("Q58483083", NodeKind.WORK); // dramatico-musical work
    put("Q55850593", NodeKind.WORK); // music track with vocals
    put("Q193977", NodeKind.WORK); // music video
    put("Q1259759", NodeKind.WORK); // miniseries
    put("Q202866", NodeKind.WORK); // animated film
    put("Q10590726", NodeKind.WORK); // video album
    // places
    put("Q515", NodeKind.PLACE); // city
    put("Q6256", NodeKind.PLACE); // country
    put("Q532", NodeKind.PLACE); // village
    put("Q3957", NodeKind.PLACE); // town
    put("Q35657", NodeKind.PLACE); // U.S. state
    put("Q82794", NodeKind.PLACE); // region
    // events
    put("Q1656682", NodeKind.EVENT); // planned event
    put("Q182832", NodeKind.EVENT); // concert
    put("Q132241", NodeKind.EVENT); // festival
    put("Q198", NodeKind.EVENT); // war
  }

  /**
   * Which kind wins when an entity's stated classes disagree, most decisive first (issue #87, ADR
   * 21).
   *
   * <p>Entities routinely state several classes, and the order they arrive in is noise: the entity
   * JSON lists statements oldest first, {@link ReverseClaims} collects them into a set keyed on
   * whatever order SPARQL bound the rows, and neither is a claim about which class matters most.
   * The list below is the whole rule, and it gives the same answer whatever the order.
   *
   * <p>It is argued from entities that really do state two kinds, not from taste:
   *
   * <ul>
   *   <li><b>PERSON first.</b> {@code Q5} (human) is the least ambiguous statement Wikidata makes.
   *       A solo singer also typed as a musical group is a person carrying a loose second class,
   *       never a band carrying a loose "human".
   *   <li><b>WORK next.</b> A thing that is both a work and something else is the work: a concert
   *       film is a film, a residency released as a record is the album, a television series also
   *       typed as an organisation is the series, and a comedy also typed as a city is the comedy.
   *       WORK is also the kind {@code PERFORMED} and {@code AUTHORED} point at.
   *   <li><b>GROUP over EVENT.</b> An organisation that runs a conference is the organisation; the
   *       conference is an edge away, and usually an entity of its own.
   *   <li><b>PLACE last of the five.</b> Every conflict involving a place class observed so far is
   *       that class attached loosely to something that is not a place - which is the failure this
   *       ordering exists to stop.
   *   <li><b>CONCEPT last of all.</b> It means "we could not place this" (ADR 22), so it must never
   *       outrank a class the whitelist does recognise.
   * </ul>
   *
   * <p>Deliberately NOT "the most specific class wins" through {@code P279}. That needs a subclass
   * walk, which is a network call, and both projections re-derive kinds offline (ADR 42) - a mapper
   * that reached the network could not run there at all. It would also not settle the case that
   * prompted this: neither "city" nor "film" is a subclass of the other.
   */
  private static final List<NodeKind> PRECEDENCE =
      List.of(
          NodeKind.PERSON,
          NodeKind.WORK,
          NodeKind.GROUP,
          NodeKind.EVENT,
          NodeKind.PLACE,
          NodeKind.CONCEPT);

  static {
    if (Set.copyOf(PRECEDENCE).size() != NodeKind.values().length) {
      // An unranked kind would sort ahead of every ranked one, silently. Adding a constant to
      // NodeKind has to mean deciding where it sits here.
      throw new IllegalStateException(
          "precedence must rank every kind exactly once: " + PRECEDENCE);
    }
  }

  private KindMapper() {}

  private static void put(String qid, NodeKind kind) {
    NodeKind prior = BY_CLASS.put(qid, kind);
    if (prior != null) {
      // Two registrations for the same Wikidata class is a whitelist bug: one of them would
      // silently vanish rather than raising an error.
      throw new IllegalStateException("two kinds claim " + qid + ": " + prior + " and " + kind);
    }
  }

  /**
   * The kind implied by an entity's {@code P31} values.
   *
   * <p>Real entities carry several, most of them classes this list has never heard of. Those are
   * skipped, so an obscure class listed ahead of "human" does not shadow it. When more than one
   * class IS recognised and they disagree, {@code PRECEDENCE} decides - not the order the classes
   * happened to arrive in, which is an accident of how they were fetched (issue #87).
   */
  public static NodeKind fromInstanceOf(List<String> instanceOfQids) {
    if (instanceOfQids == null) {
      return NodeKind.CONCEPT;
    }
    return instanceOfQids.stream()
        .map(BY_CLASS::get)
        .filter(Objects::nonNull)
        .min(Comparator.comparingInt(PRECEDENCE::indexOf))
        .orElse(NodeKind.CONCEPT);
  }

  /**
   * The same claim with its kind re-derived from the {@code P31} values it recorded.
   *
   * <p>This is what makes an improvement to the whitelist above reach entities the graph already
   * has. Before issue #60 the derived kind was the only thing kept, so every one of the 17 classes
   * added by issues #49 and #52 could only take effect by fetching each entity from Wikidata again
   * - which is issue #55, and which cost two full re-seeds. A claim that carries its own {@code
   * P31} can be re-derived by any projection, offline and for free.
   *
   * <p><b>A claim that states no classes is returned untouched.</b> Not every source is Wikidata:
   * one that classifies without stating classes has nothing to re-derive from, and its own answer
   * is the best available rather than a gap to fill with {@link NodeKind#CONCEPT}.
   *
   * <p><b>When classes ARE stated, this list is the authority, including when it answers
   * CONCEPT.</b> Keeping a stale kind whenever the mapping came back CONCEPT would make the
   * whitelist a ratchet - additions would propagate and corrections never would - and a class
   * removed because it was wrong has to reach existing nodes exactly as a class added does.
   *
   * <p>It lives here, beside the table it re-applies, rather than in either projection: {@code
   * GraphProjector} and {@code LogProjection} both call it, and two copies of this rule would be
   * free to disagree about a graph and a picture of that graph.
   */
  public static NodeAssertion rederive(NodeAssertion claim) {
    return claim.instanceOf().isEmpty()
        ? claim
        : claim.withKind(fromInstanceOf(claim.instanceOf()));
  }

  /** Whether this class is in the whitelist, so callers can report what they could not map. */
  public static boolean isMapped(String classQid) {
    return BY_CLASS.containsKey(classQid);
  }
}
