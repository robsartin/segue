package com.robsartin.segue.wikidata;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.NodeKind;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Wikidata has tens of thousands of classes and segue has six kinds (ADR 21). This is the
 * deliberately small bridge, plus an honest fallback for everything else.
 */
class KindMapperTest {

  @Test
  @DisplayName("a human is a PERSON")
  void mapsHuman() {
    assertThat(KindMapper.fromInstanceOf(List.of("Q5"))).isEqualTo(NodeKind.PERSON);
  }

  @Test
  @DisplayName("bands and organisations are GROUPs")
  void mapsGroups() {
    assertThat(KindMapper.fromInstanceOf(List.of("Q215380"))).isEqualTo(NodeKind.GROUP);
    assertThat(KindMapper.fromInstanceOf(List.of("Q43229"))).isEqualTo(NodeKind.GROUP);
  }

  @Test
  @DisplayName("films, albums and books are WORKs")
  void mapsWorks() {
    assertThat(KindMapper.fromInstanceOf(List.of("Q11424"))).isEqualTo(NodeKind.WORK);
    assertThat(KindMapper.fromInstanceOf(List.of("Q482994"))).isEqualTo(NodeKind.WORK);
    assertThat(KindMapper.fromInstanceOf(List.of("Q7725634"))).isEqualTo(NodeKind.WORK);
  }

  @Test
  @DisplayName("cities and countries are PLACEs")
  void mapsPlaces() {
    assertThat(KindMapper.fromInstanceOf(List.of("Q515"))).isEqualTo(NodeKind.PLACE);
    assertThat(KindMapper.fromInstanceOf(List.of("Q6256"))).isEqualTo(NodeKind.PLACE);
  }

  @Test
  @DisplayName("an unmapped class falls back to CONCEPT rather than guessing")
  void unmappedFallsBackToConcept() {
    // ADR 22: record what we could not map rather than inventing a kind for it.
    assertThat(KindMapper.fromInstanceOf(List.of("Q099999999"))).isEqualTo(NodeKind.CONCEPT);
    assertThat(KindMapper.isMapped("Q099999999")).isFalse();
    assertThat(KindMapper.isMapped("Q5")).isTrue();
  }

  @Test
  @DisplayName("no instance-of claims at all is CONCEPT, not a crash")
  void emptyIsConcept() {
    assertThat(KindMapper.fromInstanceOf(List.of())).isEqualTo(NodeKind.CONCEPT);
  }

  @Test
  @DisplayName("an unmapped class does not shadow a mapped one")
  void unmappedClassesAreSkipped() {
    // Real entities carry several P31 values, most of them classes this list has never heard
    // of. Those are skipped rather than counted, which is what stops an obscure class
    // shadowing "human".
    assertThat(KindMapper.fromInstanceOf(List.of("Q099999999", "Q5"))).isEqualTo(NodeKind.PERSON);
  }

  @Test
  @DisplayName("a film that Wikidata also calls a city is a WORK, whichever order it states them")
  void aWorkOutranksAPlaceStatedBesideIt() {
    // Issue #87. Q1219310 "National Lampoon's Vacation" states P31 = Q11424 (film) AND Q515
    // (city) - the second statement is unsourced and simply wrong upstream, but it is really
    // there. The old rule took the first RECOGNISED class, so a comedy became a PLACE the
    // moment something handed the classes over city-first, which nothing prevents: the SPARQL
    // reverse lookup collects them into a set keyed on row order, and Wikidata's own JSON
    // orders them by statement age. Order is not a signal and must not decide the kind.
    assertThat(KindMapper.fromInstanceOf(List.of("Q515", "Q11424"))).isEqualTo(NodeKind.WORK);
    assertThat(KindMapper.fromInstanceOf(List.of("Q11424", "Q515"))).isEqualTo(NodeKind.WORK);
  }

  @Test
  @DisplayName("when several kinds are stated, the more specific claim wins by a fixed precedence")
  void precedenceResolvesEveryPairOfKinds() {
    // The precedence is PERSON > WORK > GROUP > EVENT > PLACE, and it is asserted here in both
    // directions so that no case can pass by accident of ordering. Every pair below was found
    // in a real graph, not invented: a singer typed as a musical group as well as a human, a
    // concert film typed as a concert, a television series typed as an organisation, and the
    // film above.
    assertOrderIndependently("Q5", "Q215380", NodeKind.PERSON); // human over musical group
    assertOrderIndependently("Q11424", "Q182832", NodeKind.WORK); // film over concert
    assertOrderIndependently("Q5398426", "Q43229", NodeKind.WORK); // TV series over organisation
    assertOrderIndependently("Q43229", "Q1656682", NodeKind.GROUP); // organisation over event
    assertOrderIndependently("Q132241", "Q515", NodeKind.EVENT); // festival over city
  }

  @Test
  @DisplayName("a mapped class beats CONCEPT however many unmapped classes surround it")
  void aMappedClassBeatsTheFallback() {
    // CONCEPT is the answer for "we could not place this" (ADR 22), so it can never outrank a
    // class the list does recognise - otherwise a single unknown class would erase a known one.
    assertThat(KindMapper.fromInstanceOf(List.of("Q099999999", "Q515", "Q099999998")))
        .isEqualTo(NodeKind.PLACE);
  }

  @Test
  @DisplayName("the specific ways Wikidata says 'band' all map to GROUP")
  void theWaysWikidataSaysBand() {
    // Q215380 "musical group" is the one everybody assumes. It is not the only one in use, and
    // an act typed with any of the others fell through to CONCEPT — which then failed the bulk
    // seeding tool's kind check and sent a perfectly good band to review (issue #49). These
    // classes were MEASURED against a real list of nine hundred acts, not guessed, which is the
    // growth path this class's own note asks for; every QID was looked up and confirmed by
    // label AND description before it was written down.
    assertThat(KindMapper.fromInstanceOf(List.of("Q5741069"))) // rock band
        .isEqualTo(NodeKind.GROUP);
    assertThat(KindMapper.fromInstanceOf(List.of("Q9212979"))) // musical duo
        .isEqualTo(NodeKind.GROUP);
    assertThat(KindMapper.fromInstanceOf(List.of("Q19351429"))) // a cappella group
        .isEqualTo(NodeKind.GROUP);
    assertThat(KindMapper.fromInstanceOf(List.of("Q42998"))) // orchestra
        .isEqualTo(NodeKind.GROUP);
    assertThat(KindMapper.fromInstanceOf(List.of("Q131186"))) // choir
        .isEqualTo(NodeKind.GROUP);
    assertThat(KindMapper.fromInstanceOf(List.of("Q1538570"))) // gospel choir
        .isEqualTo(NodeKind.GROUP);
    assertThat(KindMapper.fromInstanceOf(List.of("Q207338"))) // string quartet
        .isEqualTo(NodeKind.GROUP);
    assertThat(KindMapper.fromInstanceOf(List.of("Q163740"))) // nonprofit organization
        .isEqualTo(NodeKind.GROUP);
    // A loose collective of session players is typed neither as a band nor as an organisation.
    // It is still, unambiguously, a group of people — which is all NodeKind.GROUP claims.
    assertThat(KindMapper.fromInstanceOf(List.of("Q16334295"))) // group of humans
        .isEqualTo(NodeKind.GROUP);
    assertThat(KindMapper.fromInstanceOf(List.of("Q13473501"))) // collective
        .isEqualTo(NodeKind.GROUP);
  }

  @Test
  @DisplayName("the specific ways Wikidata says 'work' all map to WORK")
  void theWaysWikidataSaysWork() {
    // Same class of bug as the bands above, found the same way and with worse consequences.
    // Issue #52 is about award hubs dominating routes, and the rule that demotes them is
    // "a high-degree CONCEPT intermediate is a hub". That rule is only honest if CONCEPT
    // means "we could not place this", not "this is a work we forgot to whitelist". Measured
    // over every CONCEPT node in a real 25,815-node graph that could ever BE an intermediate
    // (degree >= 2, 1,416 of them): 1,058 were works, and one of them — Saturday Night Live
    // 50th Anniversary Special, a television special connecting 14 seeds — is the single best
    // connector in the graph. Every QID below was looked up and confirmed by label AND
    // description before it was written down.
    assertThat(KindMapper.fromInstanceOf(List.of("Q105543609"))) // musical work/composition
        .isEqualTo(NodeKind.WORK);
    assertThat(KindMapper.fromInstanceOf(List.of("Q21191270"))) // television series episode
        .isEqualTo(NodeKind.WORK);
    assertThat(KindMapper.fromInstanceOf(List.of("Q110039749"))) // Saturday Night Live sketch
        .isEqualTo(NodeKind.WORK);
    assertThat(KindMapper.fromInstanceOf(List.of("Q506240"))) // television film
        .isEqualTo(NodeKind.WORK);
    assertThat(KindMapper.fromInstanceOf(List.of("Q24862"))) // short film
        .isEqualTo(NodeKind.WORK);
    assertThat(KindMapper.fromInstanceOf(List.of("Q1261214"))) // television special
        .isEqualTo(NodeKind.WORK);
    assertThat(KindMapper.fromInstanceOf(List.of("Q15416"))) // television program
        .isEqualTo(NodeKind.WORK);
    assertThat(KindMapper.fromInstanceOf(List.of("Q58483083"))) // dramatico-musical work
        .isEqualTo(NodeKind.WORK);
    assertThat(KindMapper.fromInstanceOf(List.of("Q55850593"))) // music track with vocals
        .isEqualTo(NodeKind.WORK);
    assertThat(KindMapper.fromInstanceOf(List.of("Q193977"))) // music video
        .isEqualTo(NodeKind.WORK);
    assertThat(KindMapper.fromInstanceOf(List.of("Q1259759"))) // miniseries
        .isEqualTo(NodeKind.WORK);
    assertThat(KindMapper.fromInstanceOf(List.of("Q202866"))) // animated film
        .isEqualTo(NodeKind.WORK);
    assertThat(KindMapper.fromInstanceOf(List.of("Q10590726"))) // video album
        .isEqualTo(NodeKind.WORK);
  }

  @Test
  @DisplayName("the remaining ways Wikidata says 'group' map to GROUP too")
  void theOtherWaysWikidataSaysGroup() {
    // The same sweep turned up four more group classes on nodes a route can pass through.
    assertThat(KindMapper.fromInstanceOf(List.of("Q414147"))) // academy of sciences
        .isEqualTo(NodeKind.GROUP);
    assertThat(KindMapper.fromInstanceOf(List.of("Q56816954"))) // heavy metal band
        .isEqualTo(NodeKind.GROUP);
    assertThat(KindMapper.fromInstanceOf(List.of("Q18510489"))) // comedy troupe
        .isEqualTo(NodeKind.GROUP);
    assertThat(KindMapper.fromInstanceOf(List.of("Q178790"))) // labor union
        .isEqualTo(NodeKind.GROUP);
  }

  @Test
  @DisplayName("an edition or translation of a work is a WORK")
  void shouldMapToWorkWhenTheClassIsVersionEditionOrTranslation() {
    // The class that held the most CONCEPT nodes in the first census reading (issue #261,
    // 2026-09-05). Wikidata uses it to say "this item is a specific edition, adaptation or
    // translation of a work" — which is a work, in the sense PERFORMED and AUTHORED point at.
    // Label and description confirmed live before this line was written.
    assertThat(KindMapper.fromInstanceOf(List.of("Q3331189"))) // version, edition or translation
        .isEqualTo(NodeKind.WORK);
  }

  @Test
  @DisplayName("an extended play is a WORK")
  void shouldMapToWorkWhenTheClassIsExtendedPlay() {
    // The same family as album (Q482994). Issue #261.
    assertThat(KindMapper.fromInstanceOf(List.of("Q169930"))) // extended play
        .isEqualTo(NodeKind.WORK);
  }

  @Test
  @DisplayName("a seven-inch single is a WORK")
  void shouldMapToWorkWhenTheClassIsSevenInchSingle() {
    // A physical format of a single (Q134556, already WORK). Issue #261.
    assertThat(KindMapper.fromInstanceOf(List.of("Q6128115"))) // 7-inch single
        .isEqualTo(NodeKind.WORK);
  }

  @Test
  @DisplayName("an animated short film is a WORK")
  void shouldMapToWorkWhenTheClassIsAnimatedShortFilm() {
    // Both of its parents, animated film (Q202866) and short film (Q24862), are already WORK;
    // the table does not walk P279, so the class needs its own line. Issue #261.
    assertThat(KindMapper.fromInstanceOf(List.of("Q17517379"))) // animated short film
        .isEqualTo(NodeKind.WORK);
  }

  @Test
  @DisplayName("an audio track is a WORK")
  void shouldMapToWorkWhenTheClassIsAudioTrack() {
    // The same family as song (Q7366) and music track with vocals (Q55850593). Its parent
    // "musical work" (Q2188189) is NOT the "musical work/composition" (Q105543609) the table
    // holds — two similarly named classes, and only the latter is registered. Issue #261.
    assertThat(KindMapper.fromInstanceOf(List.of("Q7302866"))) // audio track
        .isEqualTo(NodeKind.WORK);
  }

  @Test
  @DisplayName("a concert tour is an EVENT")
  void shouldMapToEventWhenTheClassIsConcertTour() {
    // A series of concerts, the way a festival (Q132241, already EVENT) is. Issue #261.
    assertThat(KindMapper.fromInstanceOf(List.of("Q1573906"))) // concert tour
        .isEqualTo(NodeKind.EVENT);
  }

  @Test
  @DisplayName("an award is still a CONCEPT, which is what makes 'high-degree CONCEPT' mean 'hub'")
  void awardsStayConcepts() {
    // ADR 38 chose CONCEPT for award nodes deliberately, and issue #52 depends on that choice
    // staying true: the whitelist grew above to stop works masquerading as concepts, NOT to
    // start placing awards. If an award class is ever added here, the hub rule in PathRanking
    // silently stops firing.
    assertThat(KindMapper.fromInstanceOf(List.of("Q618779"))) // award
        .isEqualTo(NodeKind.CONCEPT);
    assertThat(KindMapper.fromInstanceOf(List.of("Q1046088"))) // hall of fame
        .isEqualTo(NodeKind.CONCEPT);
    assertThat(KindMapper.fromInstanceOf(List.of("Q378427"))) // literary award
        .isEqualTo(NodeKind.CONCEPT);
    assertThat(KindMapper.fromInstanceOf(List.of("Q38033430"))) // class of award
        .isEqualTo(NodeKind.CONCEPT);
  }

  @Test
  @DisplayName("a fictional human stays a CONCEPT, deliberately")
  void shouldStayConceptWhenTheClassIsFictionalHuman() {
    // Issue #261's one judgment call. Q15632617 is not Q5: PERSON is the kind the recommender
    // explores and the MusicBrainz adapter describes (ADR 54), so mapping it would put
    // characters in the candidate pool and send them to a source that cannot know them. A
    // character that appears in many works is exactly the shape the hub rule demotes.
    assertThat(KindMapper.fromInstanceOf(List.of("Q15632617"))) // fictional human
        .isEqualTo(NodeKind.CONCEPT);
  }

  @Test
  @DisplayName("film, television and animated characters stay CONCEPT, as fictional human does")
  void shouldStayConceptWhenTheClassIsAFictionalCharacter() {
    // Issue #265 turns issue #261's fictional-human ruling into a family rule: a character shared
    // by several works is a subject those works have in common, not something anyone did, and
    // that is the hub shape the CONCEPT-gated rules exist to demote.
    assertThat(KindMapper.fromInstanceOf(List.of("Q15773347"))) // film character
        .isEqualTo(NodeKind.CONCEPT);
    assertThat(KindMapper.fromInstanceOf(List.of("Q15773317"))) // television character
        .isEqualTo(NodeKind.CONCEPT);
    assertThat(KindMapper.fromInstanceOf(List.of("Q15711870"))) // animated character
        .isEqualTo(NodeKind.CONCEPT);
  }

  @Test
  @DisplayName("a Wikimedia artist discography stays CONCEPT: it is the page, not the releases")
  void shouldStayConceptWhenTheClassIsAWikimediaArtistDiscography() {
    // Issue #294, the reading after the first expander run. A Wikimedia list of an artist's
    // releases is a Wikipedia page ABOUT the releases, not a release: many works and people point
    // at it and nobody did anything with it, which is the hub shape the CONCEPT-gated rules exist
    // to demote (ADR 31, amended by issue #52). Mapping it to WORK would put a page in the
    // recommender's candidate pool. Label and description confirmed live on 2026-09-08, on the
    // issue.
    assertThat(KindMapper.fromInstanceOf(List.of("Q104635718"))) // Wikimedia artist discography
        .isEqualTo(NodeKind.CONCEPT);
  }

  @Test
  @DisplayName("an encyclopedia article stays CONCEPT: it is the page, not the subject")
  void shouldStayConceptWhenTheClassIsAnEncyclopediaArticle() {
    // Issue #294. An article is a page ABOUT a subject; the subject is the entity worth
    // recommending and usually has a node of its own. Mapping the article to WORK would offer a
    // reference page as something to explore, and would take a high-degree hub out of the reach of
    // the rule that demotes routes through one. Confirmed live on 2026-09-08, on the issue.
    assertThat(KindMapper.fromInstanceOf(List.of("Q13433827"))) // encyclopedia article
        .isEqualTo(NodeKind.CONCEPT);
  }

  @Test
  @DisplayName("a biographical article stays CONCEPT: it is the entry, not the person")
  void shouldStayConceptWhenTheClassIsABiographicalArticle() {
    // Issue #300, the class pass below issue #294's. Wikidata describes this class as an "article
    // in a dictionary or encyclopedia": the same shape as encyclopedia article, which #294 pinned
    // for this reason. The person the entry is about is the entity worth recommending
    // and usually has a node of its own, so mapping the entry to WORK would offer a reference page
    // as something to explore and would take a high-degree hub out of the reach of the rule that
    // demotes routes through one (ADR 31, amended by issue #52). Label and description confirmed
    // live on 2026-09-08, on the issue.
    assertThat(KindMapper.fromInstanceOf(List.of("Q19389637"))) // biographical article
        .isEqualTo(NodeKind.CONCEPT);
  }

  @Test
  @DisplayName("a discography stays CONCEPT: it is the catalogue, not the recordings")
  void shouldStayConceptWhenTheClassIsADiscography() {
    // Issue #300. Wikidata describes this class as the "study and cataloging of published sound
    // recordings" — a discipline, and a catalogue OF releases rather than a release. Issue #300's
    // live lookup records the Wikimedia artist-discography class #294 pinned as a subclass of this
    // one, so promoting it would undo that pin one level up. Many works and people point at a
    // catalogue and nobody did anything with it, which is the hub shape the CONCEPT-gated rules
    // exist to demote (ADR 31, amended by issue #52). Label and description confirmed live on
    // 2026-09-08, on the issue.
    assertThat(KindMapper.fromInstanceOf(List.of("Q273057"))) // discography
        .isEqualTo(NodeKind.CONCEPT);
  }

  @Test
  @DisplayName("a single release is a WORK")
  void shouldMapToWorkWhenTheClassIsSingleRelease() {
    // The second census reading (issue #265, 2026-09-05). A release of a single is WORK the way
    // single (Q134556) and the seven-inch single (Q6128115) already are. Label and description
    // confirmed live before this line was written.
    assertThat(KindMapper.fromInstanceOf(List.of("Q108352496"))) // single release
        .isEqualTo(NodeKind.WORK);
  }

  @Test
  @DisplayName("a comic book issue is a WORK")
  void shouldMapToWorkWhenTheClassIsComicBookIssue() {
    // An issue of a published comic: a published work, the way book (Q571) is. Issue #265.
    assertThat(KindMapper.fromInstanceOf(List.of("Q140727568"))) // comic book issue
        .isEqualTo(NodeKind.WORK);
  }

  @Test
  @DisplayName("a video game is a WORK")
  void shouldMapToWorkWhenTheClassIsVideoGame() {
    // Its own direct parent, audiovisual work (Q2431196), is already WORK; the table does not
    // walk P279, so the class needs its own line. Issue #265.
    assertThat(KindMapper.fromInstanceOf(List.of("Q7889"))) // video game
        .isEqualTo(NodeKind.WORK);
  }

  private static void assertOrderIndependently(String a, String b, NodeKind expected) {
    assertThat(KindMapper.fromInstanceOf(List.of(a, b))).isEqualTo(expected);
    assertThat(KindMapper.fromInstanceOf(List.of(b, a))).isEqualTo(expected);
  }
}
