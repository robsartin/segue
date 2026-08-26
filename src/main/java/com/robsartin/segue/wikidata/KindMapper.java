package com.robsartin.segue.wikidata;

import com.robsartin.segue.domain.NodeKind;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
   * <p>Real entities carry several. The first RECOGNISED one wins, so an obscure class listed ahead
   * of "human" does not shadow it.
   */
  public static NodeKind fromInstanceOf(List<String> instanceOfQids) {
    if (instanceOfQids == null) {
      return NodeKind.CONCEPT;
    }
    return instanceOfQids.stream()
        .map(BY_CLASS::get)
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(NodeKind.CONCEPT);
  }

  /** Whether this class is in the whitelist, so callers can report what they could not map. */
  public static boolean isMapped(String classQid) {
    return BY_CLASS.containsKey(classQid);
  }
}
