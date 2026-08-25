package com.robsartin.segue.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * EdgeTypes is the whitelist increment 3's Wikidata ingest maps property codes onto. Nothing
 * exercises it yet, so a typo like {@code MEMBER_0F} (zero for O) would sit unnoticed until a real
 * ingest run silently failed to resolve it.
 */
class EdgeTypesTest {

  @Test
  @DisplayName("every registered code resolves via byCode")
  void everyCodeResolves() {
    for (EdgeType type : EdgeTypes.all()) {
      assertThat(EdgeTypes.byCode(type.code())).contains(type);
    }
  }

  @Test
  @DisplayName("all() returns the expected number of entries")
  void allReturnsExpectedCount() {
    assertThat(EdgeTypes.all()).hasSize(12);
  }

  @Test
  @DisplayName("every constant's code() equals its own field name")
  void codeMatchesFieldName() throws IllegalAccessException {
    for (Field field : EdgeTypes.class.getDeclaredFields()) {
      if (field.getType() != EdgeType.class) {
        continue;
      }
      assertThat(Modifier.isStatic(field.getModifiers())).isTrue();
      EdgeType value = (EdgeType) field.get(null);
      assertThat(value.code())
          .as("field %s should register under its own name", field.getName())
          .isEqualTo(field.getName());
    }
  }

  @Test
  @DisplayName("every registered Wikidata property maps to exactly one edge type")
  void wikidataPropertiesAreDistinct() {
    // ClaimMapper derives its whitelist from EdgeTypes, keyed by wikidataProperty. Two types
    // claiming the same property would make one vanish from ingest silently (ClaimMapper's
    // static block now throws on that, but the vocabulary itself should never collide).
    List<String> properties =
        EdgeTypes.all().stream().map(EdgeType::wikidataProperty).filter(Objects::nonNull).toList();

    assertThat(properties).doesNotHaveDuplicates();
  }

  @Test
  @DisplayName("byCode on an unknown code returns empty")
  void unknownCodeIsEmpty() {
    assertThat(EdgeTypes.byCode("NOT_A_REAL_CODE")).isEmpty();
  }

  @Test
  @DisplayName("inverted creative-role types record wikidataInverted")
  void invertedTypesAreFlagged() {
    List<EdgeType> inverted =
        List.of(
            EdgeTypes.PERFORMED,
            EdgeTypes.AUTHORED,
            EdgeTypes.DIRECTED,
            EdgeTypes.WROTE_SCREENPLAY_FOR,
            EdgeTypes.COMPOSED_FOR,
            EdgeTypes.ACTED_IN);

    assertThat(inverted).allMatch(EdgeType::wikidataInverted);
    assertThat(inverted).noneMatch(EdgeType::symmetric);
    assertThat(inverted).allMatch(t -> t.wikidataProperty() != null);
  }

  @Test
  @DisplayName("direct types are neither inverted nor symmetric, and carry a Wikidata property")
  void directTypesAreFlagged() {
    List<EdgeType> direct =
        List.of(
            EdgeTypes.MEMBER_OF, EdgeTypes.BASED_ON, EdgeTypes.PART_OF, EdgeTypes.INFLUENCED_BY);

    assertThat(direct).noneMatch(EdgeType::wikidataInverted);
    assertThat(direct).noneMatch(EdgeType::symmetric);
    assertThat(direct).allMatch(t -> t.wikidataProperty() != null);
  }

  @Test
  @DisplayName("derived types are symmetric, not wikidata-inverted, and have no Wikidata property")
  void derivedTypesAreFlagged() {
    List<EdgeType> derived = List.of(EdgeTypes.COLLABORATED_WITH, EdgeTypes.SIMILAR_TO);

    assertThat(derived).allMatch(EdgeType::symmetric);
    assertThat(derived).noneMatch(EdgeType::wikidataInverted);
    assertThat(derived).allMatch(t -> t.wikidataProperty() == null);
  }

  @Test
  @DisplayName("all() hands out an immutable copy, not a live view onto the registry")
  void allIsAnImmutableCopy() {
    // ADR 22 makes the edge vocabulary a controlled, borrowed-from-Wikidata namespace, and
    // increment 3's ingest reads that registry as its property whitelist (ClaimMapper). While
    // all() returned BY_CODE.values(), every caller held a live handle on the backing map and
    // could empty the vocabulary at runtime; a namespace any caller can edit is not controlled.
    Collection<EdgeType> vocabulary = EdgeTypes.all();

    // Probe with a type that was never registered, and assert remove() before clear(): an
    // unmodifiable collection throws even for an absent element, whereas the old live view
    // answered false and left the map alone. The red run therefore fails on this first
    // assertion without emptying the static registry out from under the rest of the suite.
    EdgeType neverRegistered = EdgeType.derived("NOT_A_REGISTERED_TYPE", "probe", false);

    assertThatThrownBy(() -> vocabulary.remove(neverRegistered))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(vocabulary::clear).isInstanceOf(UnsupportedOperationException.class);
  }
}
