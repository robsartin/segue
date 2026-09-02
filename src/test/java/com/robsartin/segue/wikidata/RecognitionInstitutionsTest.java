package com.robsartin.segue.wikidata;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Issue #66. The table that separates "we were both elected to this" from "we were both in this
 * band", measured against a real 54,448-node graph rather than reasoned about.
 *
 * <p>Every assertion below names a node the measurement actually found. The classes are real
 * Wikidata classes, looked up and confirmed by label AND description like {@code KindMapper}'s.
 */
class RecognitionInstitutionsTest {

  @Test
  @DisplayName("the three classes the institutions in a real graph actually wear")
  void namesTheMeasuredInstitutionClasses() {
    // American Academy of Arts and Sciences, 33 seeds, every one of them by MEMBER_OF.
    assertThat(RecognitionInstitutions.isRecognitionInstitution("Q955824")).isTrue();
    // American Academy of Arts and Letters, 8 seeds. Royal Society, 4.
    assertThat(RecognitionInstitutions.isRecognitionInstitution("Q414147")).isTrue();
    // Writers Guild of America West (11), WGA East (10), SAG-AFTRA (6), SAG (3), DGA (1).
    assertThat(RecognitionInstitutions.isRecognitionInstitution("Q178790")).isTrue();
  }

  @Test
  @DisplayName("the classes the same graph wore 70,000 nodes later")
  void namesTheClassesTheGrownGraphAdded() {
    // Issue #88, re-measured on the 123,752-node graph: four more classes, each one carrying a
    // busy institution that NEITHER existing rule could see. Degrees are in-graph edges here,
    // not seeds - the graph has grown past the point where seeds are the useful unit.
    // National Inventors Hall of Fame, 500 edges, and the Grammy Hall of Fame at 38. This is
    // the "smaller neighbour visible and unfixed" ADR 31's second amendment recorded: a hall of
    // fame classified GROUP, which the CONCEPT-degree rule cannot see at any size.
    assertThat(RecognitionInstitutions.isRecognitionInstitution("Q1046088")).isTrue();
    // Polish Writers' Union, 408. American Psychological Association, 181.
    assertThat(RecognitionInstitutions.isRecognitionInstitution("Q829080")).isTrue();
    // American Astronomical Society, 179. Zoological Society of London, 152.
    assertThat(RecognitionInstitutions.isRecognitionInstitution("Q748019")).isTrue();
    // PEN America, 76. Authors Guild, 30.
    assertThat(RecognitionInstitutions.isRecognitionInstitution("Q12057459")).isTrue();
  }

  @Test
  @DisplayName("a publisher class is not a recognition class, however academic its members look")
  void excludesThePublisherClasses() {
    // Issue #88's near-miss, and the reason this table is built from what a class MEANS rather
    // than from who happens to wear it. Every node stating "open-access publisher" in the real
    // graph is an academy - the Royal Society, the Romanian Academy, the Polish Academy of
    // Sciences - so a table fitted to the population would have taken it, and it would have
    // been wrong: the class says the body publishes, not that one is elected to it. The same
    // goes for the two publisher classes beside it. A record label is the shape that breaks it.
    assertThat(RecognitionInstitutions.isRecognitionInstitution("Q45400320")).isFalse(); // OA
    assertThat(RecognitionInstitutions.isRecognitionInstitution("Q96888669")).isFalse(); // academic
    assertThat(RecognitionInstitutions.isRecognitionInstitution("Q2085381")).isFalse(); // house
  }

  @Test
  @DisplayName("an award class is not an institution class, because ADR 38 admitted the awards")
  void excludesTheAwardClasses() {
    // A hall of fame is in the table above and a Hugo is not, which looks inconsistent until
    // you read ADR 38: P166 was registered precisely so a single-authored novel could route
    // through the prize it won. "award" is worn by the Hugo and the Nebula, and "science award"
    // by the Darwin Medal and the Balzan Prize - four to six edges each, and every one of them
    // the specific relation the vocabulary was widened to hold. Only the classes that mean
    // "a list of people who were notable" belong here.
    assertThat(RecognitionInstitutions.isRecognitionInstitution("Q618779")).isFalse(); // award
    assertThat(RecognitionInstitutions.isRecognitionInstitution("Q11448906")).isFalse(); // science
  }

  @Test
  @DisplayName("a band is not an institution, whatever Wikidata calls it")
  void leavesTheBandsAlone() {
    // The five classes the 80 GROUPs shared by 5+ seeds actually used. These are the
    // connectors the whole feature runs on and none of them may ever match.
    assertThat(RecognitionInstitutions.isRecognitionInstitution("Q215380")).isFalse(); // musical
    assertThat(RecognitionInstitutions.isRecognitionInstitution("Q5741069")).isFalse(); // rock band
    assertThat(RecognitionInstitutions.isRecognitionInstitution("Q56816954")).isFalse(); // metal
    assertThat(RecognitionInstitutions.isRecognitionInstitution("Q127334927")).isFalse(); // band
    assertThat(RecognitionInstitutions.isRecognitionInstitution("Q7558495")).isFalse(); // solo
    // Monty Python: 7 seeds, 5 of them by MEMBER_OF, and a real collaboration.
    assertThat(RecognitionInstitutions.isRecognitionInstitution("Q18510489")).isFalse(); // troupe
    assertThat(RecognitionInstitutions.isRecognitionInstitution("Q19314966")).isFalse(); // comedy
  }

  @Test
  @DisplayName("the broad organization classes are excluded, because a band can wear one")
  void excludesTheBroadOrganizationClasses() {
    // This is the trap, and it is measured rather than imagined. Every institution in the
    // graph ALSO states one of these, so a table built from what the academies have in
    // common would have caught them - and ABBA states "organization" (498 edges) while the
    // Vienna Philharmonic states "nonprofit organization". A rule resting on either would
    // demote a route through ABBA.
    assertThat(RecognitionInstitutions.isRecognitionInstitution("Q43229"))
        .isFalse(); // organization
    assertThat(RecognitionInstitutions.isRecognitionInstitution("Q163740")).isFalse(); // nonprofit
  }

  @Test
  @DisplayName("a class the table has never heard of is not an institution")
  void unknownClassesAreNotInstitutions() {
    assertThat(RecognitionInstitutions.isRecognitionInstitution("Q0900901")).isFalse();
  }

  @Test
  @DisplayName("every key is a QID and every entry says which class it names")
  void isAWellFormedTable() {
    for (Map.Entry<String, String> entry : RecognitionInstitutions.all().entrySet()) {
      assertThat(entry.getKey()).as("class id").matches("Q\\d+");
      assertThat(entry.getValue()).as("name of %s", entry.getKey()).isNotBlank();
    }
  }
}
