package com.robsartin.segue.rate;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RateRunTest {

  @Test
  @DisplayName("the deck is built from the graph, and the notes carry counts and no rating")
  void buildsADeckAndSaysWhatItDid() throws Exception {
    try (TinkerGraphStore graph = new TinkerGraphStore()) {
      graph.upsertNode(new NodeRecord("Q900001", NodeKind.GROUP, "One", List.of()));
      graph.upsertNode(new NodeRecord("Q900002", NodeKind.GROUP, "Two", List.of()));
      List<String> notes = new ArrayList<>();

      List<Card> deck =
          RateRun.buildDeck(graph, List.of("Q900001", "Q900002"), Set.of("Q900002"), 0, notes::add);

      assertThat(deck).extracting(Card::qid).containsExactly("Q900001");
      assertThat(notes).anyMatch(n -> n.contains("1 card(s)"));
      assertThat(notes).noneMatch(n -> n.matches(".*rating [1-5].*"));
    }
  }
}
