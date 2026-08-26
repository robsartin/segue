package com.robsartin.segue.seed;

import com.robsartin.segue.domain.NodeKind;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The input list's {@code kind} column, translated into something checkable.
 *
 * <p><b>Every QID below was looked up and confirmed by label AND description</b> before it was
 * written down. A guessed identifier is not a small error: nothing downstream can tell it from a
 * correct one, and it is believed forever.
 *
 * <p>The occupation sets are deliberately generous and deliberately incomplete. They were built by
 * measuring what {@code P106} values the real candidates actually carry, and an entity whose
 * occupations are outside them is sent to review rather than rejected — so a gap here costs a
 * manual check, never a wrong answer.
 *
 * <p><b>This is not a graph edge.</b> Issue #32 excluded {@code P106} from the edge vocabulary
 * because "novelist" is a 36,000-item hub node that would make every novelist adjacent to every
 * other. Reading the property to decide which of six same-named humans is the right one creates no
 * edge and adds nothing to the graph; the two uses have nothing in common but the property number.
 */
public final class Expectations {

  private static final Set<NodeKind> ANY_KIND = EnumSet.allOf(NodeKind.class);

  // Music. Q639669 musician, Q177220 singer, Q36834 composer, Q855091 guitarist,
  // Q753110 songwriter, Q488205 singer-songwriter, Q55960555 recording artist,
  // Q15981151 jazz musician, Q486748 pianist, Q6168364 jazz guitarist, Q806349 bandleader,
  // Q9648008 banjoist, Q12374149 rock musician, Q183945 record producer, Q1259917 violinist,
  // Q12800682 saxophonist, Q97572814 wind instrument player, Q19723482 mandolinist,
  // Q13138067 string player, Q61996187 classical pianist, Q386854 drummer,
  // Q1415090 film score composer, Q101084010 folk musician, Q2252262 rapper, Q158852 conductor,
  // Q822146 lyricist, Q13219637 cellist, Q12377274 trumpeter, Q584301 bassist,
  // Q1214796 double-bassist, Q1075651 keyboardist, Q130857 disc jockey, Q765778 organist,
  // Q12902372 flautist, Q118397797 flutist, Q110829086 classical flautist, Q3127709 harpist,
  // Q4351403 percussionist, Q1327329 multi-instrumentalist, Q2865819 opera singer,
  // Q20850090 harmonicist, Q3922505 DJ producer.
  private static final Set<String> MUSIC =
      Set.of(
          "Q639669",
          "Q177220",
          "Q36834",
          "Q855091",
          "Q753110",
          "Q488205",
          "Q55960555",
          "Q15981151",
          "Q486748",
          "Q6168364",
          "Q806349",
          "Q9648008",
          "Q12374149",
          "Q183945",
          "Q1259917",
          "Q12800682",
          "Q97572814",
          "Q19723482",
          "Q13138067",
          "Q61996187",
          "Q386854",
          "Q1415090",
          "Q101084010",
          "Q2252262",
          "Q158852",
          "Q822146",
          "Q13219637",
          "Q12377274",
          "Q584301",
          "Q1214796",
          "Q1075651",
          "Q130857",
          "Q765778",
          "Q12902372",
          "Q118397797",
          "Q110829086",
          "Q3127709",
          "Q4351403",
          "Q1327329",
          "Q2865819",
          "Q20850090",
          "Q3922505");

  // Q33999 actor, Q10800557 film actor, Q10798782 television actor, Q2259451 stage actor,
  // Q2405480 voice actor, Q116695160 video game actor.
  private static final Set<String> ACTING =
      Set.of("Q33999", "Q10800557", "Q10798782", "Q2259451", "Q2405480", "Q116695160");

  // Q245068 comedian, Q18545066 stand-up comedian, Q12406482 humorist, Q674067 mime artist.
  private static final Set<String> COMEDY_ONLY =
      Set.of("Q245068", "Q18545066", "Q12406482", "Q674067");

  // Q36180 writer, Q482980 author, Q6625963 novelist, Q18844224 science fiction writer,
  // Q49757 poet, Q214917 playwright, Q4853732 children's writer, Q3579035 travel writer,
  // Q18814623 autobiographer, Q1930187 journalist, Q1607826 editor, Q11774202 essayist,
  // Q4964182 philosopher, Q28389 screenwriter, Q8246794 blogger, Q18939491 diarist,
  // Q901 scientist, Q1650915 researcher, Q1622272 university teacher.
  private static final Set<String> WRITING =
      Set.of(
          "Q36180",
          "Q482980",
          "Q6625963",
          "Q18844224",
          "Q49757",
          "Q214917",
          "Q4853732",
          "Q3579035",
          "Q18814623",
          "Q1930187",
          "Q1607826",
          "Q11774202",
          "Q4964182",
          "Q28389",
          "Q8246794",
          "Q18939491",
          "Q901",
          "Q1650915",
          "Q1622272");

  // Q2526255 film director, Q3455803 director, Q3282637 film producer,
  // Q578109 television producer, Q28389 screenwriter, Q1053574 executive producer.
  /**
   * Comedy, plus acting.
   *
   * <p>Wikidata rarely types a working comedian as "comedian"; it types them as film actor,
   * television actor, stage actor or voice actor. Measured against a real list, requiring the word
   * sent well-known comedians to review with an unambiguous answer sitting in front of them.
   */
  private static final Set<String> COMEDY = union(COMEDY_ONLY, ACTING);

  private static final Set<String> DIRECTING =
      Set.of("Q2526255", "Q3455803", "Q3282637", "Q578109", "Q28389", "Q1053574");

  // Q2722764 radio personality, Q947873 television presenter, Q44508716 television personality,
  // Q1930187 journalist, Q15077007 podcaster, Q15143191 science communicator, Q901 scientist.
  private static final Set<String> BROADCASTING =
      Set.of("Q2722764", "Q947873", "Q44508716", "Q1930187", "Q15077007", "Q15143191", "Q901");

  private static Set<String> union(Set<String> first, Set<String> second) {
    Set<String> out = new LinkedHashSet<>(first);
    out.addAll(second);
    return Set.copyOf(out);
  }

  private static final Map<String, Expectation> BY_KIND = new LinkedHashMap<>();

  static {
    // A musician on this list is as often a band as a person, so both kinds are allowed and the
    // occupation check only bites on the ones that turn out to be human.
    put("musician", EnumSet.of(NodeKind.PERSON, NodeKind.GROUP), MUSIC);
    put("composer", EnumSet.of(NodeKind.PERSON), MUSIC);
    put("conductor", EnumSet.of(NodeKind.PERSON), MUSIC);
    put("comedian", EnumSet.of(NodeKind.PERSON, NodeKind.GROUP), COMEDY);
    put("author", EnumSet.of(NodeKind.PERSON), WRITING);
    put("actor", EnumSet.of(NodeKind.PERSON), ACTING);
    put("director", EnumSet.of(NodeKind.PERSON), DIRECTING);
    put("broadcaster", EnumSet.of(NodeKind.PERSON), BROADCASTING);
    // Groups: no occupation exists to check, so the kind is the whole test.
    put("a-cappella", EnumSet.of(NodeKind.GROUP), Set.of());
    put("tribute", EnumSet.of(NodeKind.GROUP), Set.of());
    put("orchestra", EnumSet.of(NodeKind.GROUP), Set.of());
    put("choir", EnumSet.of(NodeKind.GROUP), Set.of());
    put("ensemble", EnumSet.of(NodeKind.GROUP), Set.of());
    put("org", EnumSet.of(NodeKind.GROUP), Set.of());
    put("tv-show", EnumSet.of(NodeKind.WORK), Set.of());
    // A fictional character has no NodeKind of its own — ADR 21 has six and none of them is
    // "character" — so it lands in CONCEPT, which is what an unmapped P31 always becomes.
    put("character", EnumSet.of(NodeKind.CONCEPT), Set.of());
    // No usable occupation vocabulary, so these constrain the kind and nothing else.
    put("public-figure", EnumSet.of(NodeKind.PERSON), Set.of());
    put("puppeteer", EnumSet.of(NodeKind.PERSON), Set.of());
  }

  private Expectations() {}

  private static void put(String kind, Set<NodeKind> kinds, Set<String> occupations) {
    Expectation prior = BY_KIND.put(kind, new Expectation(kinds, occupations));
    if (prior != null) {
      throw new IllegalStateException("two expectations claim the kind " + kind);
    }
  }

  /**
   * What a candidate for this {@code kind} column value must look like.
   *
   * <p>An unrecognised value constrains nothing rather than rejecting everything: a kind this table
   * has never seen is the table's gap, and turning it into a wrong answer would be worse than
   * turning it into an unchecked one.
   */
  public static Expectation forKind(String kind) {
    Objects.requireNonNull(kind, "kind");
    return BY_KIND.getOrDefault(kind.trim().toLowerCase(Locale.ROOT), unconstrained());
  }

  /**
   * The union across every role one name is listed under.
   *
   * <p>A name that appears as both composer and conductor is one person with two roles, not two
   * people, so either role's vocabulary satisfies it. If any role is unrecognised the union
   * constrains no occupation at all — the alternative would let a known role narrow a name whose
   * other role this table cannot judge.
   */
  public static Expectation forKinds(Collection<String> kinds) {
    Objects.requireNonNull(kinds, "kinds");
    Set<NodeKind> nodeKinds = EnumSet.noneOf(NodeKind.class);
    Set<String> occupations = new LinkedHashSet<>();
    boolean anyUnconstrained = false;
    for (String kind : kinds) {
      Expectation expectation = forKind(kind);
      nodeKinds.addAll(expectation.kinds());
      if (expectation.checksOccupation()) {
        occupations.addAll(expectation.occupations());
      } else {
        anyUnconstrained = true;
      }
    }
    return new Expectation(nodeKinds, anyUnconstrained ? Set.of() : occupations);
  }

  private static Expectation unconstrained() {
    return new Expectation(ANY_KIND, Set.of());
  }
}
