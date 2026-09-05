package com.robsartin.segue.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * English names for the Wikidata classes a node is an instance of, for a tooltip.
 *
 * <p><b>Why a table in the source and not a lookup.</b> The graph stores {@code P31} as QIDs and
 * nothing else (ADR 42): a class label is a display string, and putting one in the assertion log
 * would mean writing a network-fetched English word into the source of truth for a purely
 * presentational need — and a schema change that ADR 42's own note says must now come with a
 * migration. The exporter cannot fetch one either: ADR 41 makes it read-only and offline, and
 * {@code ArchitectureTest.theExporterOnlyReads} is what says so. So the names live here, in the
 * layer that wants them, and an export stays a pure function of the database.
 *
 * <p><b>The fallback is the bare QID, deliberately.</b> A tooltip reading {@code Q1261214} is
 * useless and true; a tooltip that guessed would be useful and sometimes wrong, and the reader
 * would have no way to tell which. A QID is also a URL away from the answer. Nothing here is
 * derived from a subclass walk or a heuristic: every entry below was read from Wikidata's own
 * {@code labels/en} — label and description both — the way {@code KindMapper}'s whitelist was.
 *
 * <p><b>It is a display table, not a mapping table.</b> {@code KindMapper} decides what a class
 * MEANS and this decides what it is CALLED; the two lists overlap and neither is derived from the
 * other, because a class that determines no kind still deserves a name. "version, edition or
 * translation" and "concert tour" were the two largest {@code CONCEPT} classes in the reading this
 * table was first written against, and mapped to nothing at the time; issue #261 later mapped them
 * to {@code WORK} and {@code EVENT}, and this table did not change when they did, which is exactly
 * the point of its being a display table rather than a mirror of {@code KindMapper}. A class
 * missing here costs one tooltip a QID and cannot break anything else, which is why the table is
 * allowed to be a sample of the long tail rather than a promise about it. Measured on a real
 * 54,448-node graph, 861 distinct classes appear and the top 40 cover 96.6%: a table is the right
 * shape for that distribution and a complete one is not available offline at any size.
 *
 * <p><b>It lives in {@code support} rather than in {@code export} because two tools read it.</b>
 * The exporter puts a class name in a DOT tooltip; the rating deck puts it on a card. A dev tool
 * that depended on {@code export} to reach this table would inherit that package's looser fence,
 * which is the reason the ratings tool bans the dependency outright.
 */
public final class ClassLabels {

  /** What a node whose source stated no class at all gets. */
  public static final String NO_CLASS = "no stated class";

  private static final Map<String, String> BY_QID = new LinkedHashMap<>();

  static {
    // Works. The four with a fill of their own in DotWriter are the first four here.
    put("Q482994", "album");
    put("Q105543609", "musical work/composition");
    put("Q134556", "single");
    put("Q11424", "film");
    put("Q55850593", "music track with vocals");
    put("Q55850643", "music track without lyrics");
    put("Q7302866", "audio track");
    put("Q108352496", "single release");
    put("Q7366", "song");
    put("Q7725634", "literary work");
    put("Q47461344", "written work");
    put("Q1980247", "chapter");
    put("Q17489659", "group of works");
    put("Q21191270", "television series episode");
    put("Q5398426", "television series");
    put("Q3464665", "television series season");
    put("Q15416", "television program");
    put("Q1261214", "television special");
    put("Q506240", "television film");
    put("Q1259759", "miniseries");
    put("Q110039749", "Saturday Night Live sketch");
    put("Q24862", "short film");
    put("Q202866", "animated film");
    put("Q18011172", "film project");
    put("Q193977", "music video");
    put("Q10590726", "video album");
    put("Q58483083", "dramatico-musical work");
    put("Q15079786", "ballet");
    put("Q17517379", "animated short film");
    // The rest of issue #261's six: a specific edition (Q3331189), an EP (Q169930) and a
    // physical single format (Q6128115) are all WORK, the same family as album and single above.
    put("Q3331189", "version, edition or translation");
    put("Q169930", "extended play");
    // A double prime, escaped rather than typed: the value is then the same whatever charset
    // reads this file.
    put("Q6128115", "7\u2033 single");
    // The second census reading's two more works (issue #265): a comic book issue is a published
    // work the way book is, and video game's own direct parent, audiovisual work, already is one.
    put("Q140727568", "comic book issue");
    put("Q7889", "video game");
    // People and groups.
    put("Q5", "human");
    put("Q215380", "musical group");
    put("Q5741069", "rock band");
    // Characters. Not people: KindMapper leaves every one of these unmapped on purpose, and its
    // tests pin them at CONCEPT (issues #261 and #265). They are named here so a tooltip can say
    // what the node is without the graph treating it as someone.
    put("Q15632617", "fictional human");
    put("Q3658341", "literary character");
    // The rest of issue #265's family: film, television and animated characters stay CONCEPT for
    // the same reason fictional human does, right above.
    put("Q15773347", "film character");
    put("Q15773317", "television character");
    put("Q15711870", "animated character");
    // Events.
    put("Q182832", "concert");
    // Issue #261's sixth: a concert tour is EVENT, the same kind as the concerts on it.
    put("Q1573906", "concert tour");
    // Concepts: the award family (ADR 38 keeps these out of KindMapper on purpose, so a novel
    // can route through its prize rather than through the prize's own broad class) and
    // discography, which states no class KindMapper recognises.
    put("Q273057", "discography");
    put("Q618779", "award");
    put("Q38033430", "class of award");
    put("Q107655869", "group of awards");
    put("Q1364556", "music award");
    put("Q378427", "literary award");
    put("Q11448906", "science award");
  }

  private ClassLabels() {}

  private static void put(String classQid, String label) {
    String prior = BY_QID.put(classQid, label);
    if (prior != null) {
      // Two names for one class is a table bug: one of them would silently vanish.
      throw new IllegalStateException(
          "two labels claim " + classQid + ": " + prior + " and " + label);
    }
  }

  /** The class's English name, or the QID itself when the table has never heard of it. */
  public static String label(String classQid) {
    return BY_QID.getOrDefault(classQid, classQid);
  }

  /**
   * Every class a node stated, named and joined — in the order the source stated them, because that
   * order is what {@code KindMapper} reads and the first one is therefore the one that chose the
   * node's kind.
   */
  public static String describe(List<String> classQids) {
    return classQids.isEmpty()
        ? NO_CLASS
        : classQids.stream().map(ClassLabels::label).collect(Collectors.joining(", "));
  }

  /** The table itself, for the test that checks every key is a QID and every name is a name. */
  static Map<String, String> all() {
    return Map.copyOf(BY_QID);
  }
}
