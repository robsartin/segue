package com.robsartin.segue.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Objects;
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
    assertThat(EdgeTypes.all()).hasSize(13);
  }

  @Test
  @DisplayName("HAS_PART is registered on P527, stated the way Wikidata states it")
  void hasPartIsRegistered() {
    // Issue #20: a band's roster is P527 on the GROUP, so registering it is the one thing that
    // makes a group expand to anything at all without a Query Service call. It is a degraded
    // fallback, not the fix — reverse-P463 strictly dominates it (10 Bad Seeds against P527's
    // 8, verified live) — so it is registered DIRECT rather than inverted: Wikidata really does
    // say "group has part person", and flipping it would produce an edge whose label reads
    // backwards.
    assertThat(EdgeTypes.HAS_PART.wikidataProperty()).isEqualTo("P527");
    assertThat(EdgeTypes.HAS_PART.wikidataInverted()).isFalse();
    assertThat(EdgeTypes.HAS_PART.symmetric()).isFalse();
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
