package com.robsartin.segue.fixture;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.Qid;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The fixture's identifiers must be ones Wikidata can never allocate (ADR 58, issue #141).
 *
 * <p><b>Why this test exists.</b> The fixture ids were chosen in the {@code Q9000xx} range on the
 * assumption that a high number would be free. It was not: all but one of them resolved to a real
 * Wikidata entity — a German village, a Hungarian academic, a brewery — so the fixture was quietly
 * asserting that a real person's identifier is a musician named "Nick Cave". The one exception is a
 * deleted item, not a free number. The repository's own standing rule is never to invent an
 * external identifier, and picking an unused-looking number is inventing one.
 *
 * <p><b>Why a range cannot fix it.</b> Wikidata keeps allocating, so any range verified as free
 * today has a shelf life. The only durable answer is a form the identifier grammar itself refuses,
 * which is what this test pins.
 *
 * <p>This is the offline half. {@code WikidataLiveSmokeTest} holds the other half: the grammar is a
 * claim about a remote system, and only a live call can say it still holds.
 */
class FixtureQidsDenoteNothingTest {

  /**
   * Wikibase's item-id grammar, from WikibaseDataModel {@code src/Entity/ItemId.php}, which reads
   * {@code /^Q[1-9]\d{0,9}\z/i}. The first digit may not be a zero, so an id with a leading zero is
   * not merely unallocated — it is unallocatable, and stays that way however many items Wikidata
   * mints. Confirmed against both of Wikidata's APIs: {@code Q0900001} is rejected outright ({@code
   * invalid-path-parameter}), where an unallocated but well-formed id such as {@code Q999999999}
   * answers {@code resource-not-found}.
   */
  private static final Pattern WIKIBASE_ITEM_ID = Pattern.compile("Q[1-9]\\d{0,9}");

  private static List<String> fixtureQids() {
    List<String> qids = new ArrayList<>();
    for (Field field : Fixture.class.getDeclaredFields()) {
      if (Modifier.isPublic(field.getModifiers())
          && Modifier.isStatic(field.getModifiers())
          && field.getType() == String.class) {
        try {
          qids.add((String) field.get(null));
        } catch (IllegalAccessException e) {
          throw new AssertionError("cannot read " + field.getName(), e);
        }
      }
    }
    return qids;
  }

  @Test
  @DisplayName(
      "every Fixture qid is one Wikibase's grammar refuses, so Wikidata can never allocate it")
  void shouldUseAnIdWikidataCannotAllocateWhenTheFixtureNamesAnEntity() {
    assertThat(fixtureQids())
        .isNotEmpty()
        .allSatisfy(
            qid ->
                assertThat(WIKIBASE_ITEM_ID.matcher(qid).matches())
                    .as(
                        "%s is a well-formed Wikidata item id, so Wikidata may allocate it -"
                            + " and every fixture id chosen this way already denotes something",
                        qid)
                    .isFalse());
  }

  @Test
  @DisplayName("every Fixture qid still satisfies segue's own qid rule")
  void shouldStillSatisfySegueQidRuleWhenTheIdIsUnallocatable() {
    assertThat(fixtureQids())
        .isNotEmpty()
        .allSatisfy(qid -> assertThat(Qid.looksLikeAQid(qid)).as("%s", qid).isTrue());
  }
}
