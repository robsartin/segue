package com.robsartin.segue.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Optional;
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
  @DisplayName(
      "all() is a live view over the registry, not a defensive copy — noted for later cleanup")
  void allIsALiveMutableView() {
    // Pins EdgeTypes.all()'s current implementation (`return BY_CODE.values();`): the same
    // backing Map.values() view is handed out every call rather than a fresh, safe copy. This
    // is a real defect worth filing separately — EdgeTypesTest cannot fix src/main here — but
    // callers should not rely on the returned Collection being unmodifiable.
    Optional<?> first = Optional.of(EdgeTypes.all());
    Optional<?> second = Optional.of(EdgeTypes.all());

    assertThat(first.get()).isSameAs(second.get());
  }
}
