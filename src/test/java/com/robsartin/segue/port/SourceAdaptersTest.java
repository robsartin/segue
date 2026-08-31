package com.robsartin.segue.port;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The assumption issue #148's attribution rests on, checked rather than assumed.
 *
 * <p>{@code SegueService.expandEntity} names a shortfall's source by {@link SourceAdapter#id()},
 * which makes that string load-bearing in two ways it was not before: it identifies <i>which</i>
 * source, so two adapters sharing one would produce a message naming an ambiguity; and it reaches a
 * {@code ToolResult} detail and a log line, so a tab or a newline in it would corrupt one. {@link
 * com.robsartin.segue.domain.Provenance} already refuses those two characters in {@code sourceId},
 * and {@link SourceAdapter#id()} says it <i>is</i> that {@code sourceId} — but that check only
 * fires when an assertion is emitted, and an adapter reporting itself unavailable emits none. So
 * the one case attribution most needs the id for is the one nothing validated.
 *
 * <p>Checked at wiring time rather than per expansion: a bad id is a configuration mistake, and the
 * useful moment to hear about it is when the bean is built.
 */
class SourceAdaptersTest {

  @Test
  @DisplayName("should accept adapters whose ids are distinct and printable")
  void shouldAcceptAdaptersWhoseIdsAreDistinctAndPrintable() {
    assertThatCode(() -> new SourceAdapters(List.of(adapter("wikidata"), adapter("musicbrainz"))))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("should accept no adapters at all")
  void shouldAcceptNoAdaptersAtAll() {
    assertThatCode(() -> new SourceAdapters(List.of())).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("should refuse two adapters sharing one id, which attribution could not tell apart")
  void shouldRefuseTwoAdaptersSharingOneId() {
    assertThatThrownBy(() -> new SourceAdapters(List.of(adapter("wikidata"), adapter("wikidata"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("wikidata");
  }

  @Test
  @DisplayName("should refuse a blank id, which names no source")
  void shouldRefuseABlankId() {
    assertThatThrownBy(() -> new SourceAdapters(List.of(adapter("  "))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("should refuse an id carrying a newline, which would break the line it is put in")
  void shouldRefuseAnIdCarryingANewline() {
    assertThatThrownBy(() -> new SourceAdapters(List.of(adapter("wiki\ndata"))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("should refuse an id carrying a tab, which the provenance codec also refuses")
  void shouldRefuseAnIdCarryingATab() {
    assertThatThrownBy(() -> new SourceAdapters(List.of(adapter("wiki\tdata"))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static SourceAdapter adapter(String id) {
    return new SourceAdapter() {
      @Override
      public String id() {
        return id;
      }

      @Override
      public boolean supports(NodeKind kind) {
        return true;
      }

      @Override
      public ExpandResult expand(NodeRecord seed, ExpandContext ctx) {
        return ExpandResult.of(List.of());
      }
    };
  }
}
