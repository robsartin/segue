package com.robsartin.segue.export;

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
 * other, because a class that determines no kind still deserves a name — "version, edition or
 * translation" and "concert tour" are the two largest CONCEPT classes in a real graph and both map
 * to nothing. A class missing here costs one tooltip a QID and cannot break anything else, which is
 * why the table is allowed to be a sample of the long tail rather than a promise about it. Measured
 * on a real 54,448-node graph, 861 distinct classes appear and the top 40 cover 96.6%: a table is
 * the right shape for that distribution and a complete one is not available offline at any size.
 */
final class ClassLabels {

  /** What a node whose source stated no class at all gets. */
  static final String NO_CLASS = "no stated class";

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
    // People and groups.
    put("Q5", "human");
    put("Q215380", "musical group");
    put("Q5741069", "rock band");
    put("Q15632617", "fictional human");
    put("Q3658341", "literary character");
    // Concepts, including the two largest classes in a real graph that map to no kind at all.
    put("Q3331189", "version, edition or translation");
    put("Q1573906", "concert tour");
    put("Q169930", "extended play");
    // A double prime, escaped rather than typed: the value is then the same whatever charset
    // reads this file.
    put("Q6128115", "7\u2033 single");
    put("Q273057", "discography");
    put("Q618779", "award");
    put("Q38033430", "class of award");
    put("Q107655869", "group of awards");
    put("Q1364556", "music award");
    put("Q378427", "literary award");
    put("Q11448906", "science award");
    put("Q182832", "concert");
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
  static String label(String classQid) {
    return BY_QID.getOrDefault(classQid, classQid);
  }

  /**
   * Every class a node stated, named and joined — in the order the source stated them, because that
   * order is what {@code KindMapper} reads and the first one is therefore the one that chose the
   * node's kind.
   */
  static String describe(List<String> classQids) {
    return classQids.isEmpty()
        ? NO_CLASS
        : classQids.stream().map(ClassLabels::label).collect(Collectors.joining(", "));
  }

  /** The table itself, for the test that checks every key is a QID and every name is a name. */
  static Map<String, String> all() {
    return Map.copyOf(BY_QID);
  }
}
