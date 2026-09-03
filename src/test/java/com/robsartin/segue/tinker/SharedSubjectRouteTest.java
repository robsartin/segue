package com.robsartin.segue.tinker;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.EdgeTypes;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.PathResult;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.port.GraphStore;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Issue #111's acceptance criterion, at the graph-store level: two books that share only a subject
 * — no author, no award, no collaboration — are connected by a route once {@code ABOUT} exists, and
 * are NOT connected without it.
 *
 * <p>The negative case is not decoration. A test that shows a route only with the feature present
 * does not show the feature produced it — see {@code SharedAwardRouteTest}'s doc comment for the
 * same argument made about {@code RECEIVED_AWARD}, and the general note in CLAUDE.md about
 * comparing full result sets rather than trusting the first thing that comes back.
 */
class SharedSubjectRouteTest {

  private static final String BOOK_A = "Q0900101";
  private static final String BOOK_B = "Q0900102";
  private static final String SUBJECT = "Q0900103";

  private static final Instant ASSERTED_AT = Instant.parse("2026-08-28T09:00:00Z");

  private GraphStore store;

  @AfterEach
  void tearDown() {
    if (store != null) {
      store.close();
    }
  }

  private static NodeRecord book(String qid, String label) {
    return new NodeRecord(qid, NodeKind.WORK, label);
  }

  private static AssertionRecord about(String bookQid, String ref) {
    return new AssertionRecord(
        bookQid,
        SUBJECT,
        EdgeTypes.ABOUT.code(),
        null,
        null,
        new Provenance("wikidata", ref, ASSERTED_AT, 1.00));
  }

  @Test
  @DisplayName("two books that share only a subject route to each other through it")
  void booksShareOnlyASubjectAndStillRoute() {
    store = new TinkerGraphStore();
    store.upsertNode(book(BOOK_A, "Some Technical Book"));
    store.upsertNode(book(BOOK_B, "Some Other Technical Book"));
    store.upsertNode(new NodeRecord(SUBJECT, NodeKind.CONCEPT, "A Shared Subject"));
    store.record(about(BOOK_A, "S-book-a-about"));
    store.record(about(BOOK_B, "S-book-b-about"));

    List<PathResult> routes = store.paths(BOOK_A, BOOK_B, 2);

    assertThat(routes).isNotEmpty();
    PathResult route = routes.getFirst();
    assertThat(route.length()).isEqualTo(2);
    assertThat(route.hops())
        .extracting(hop -> hop.edge().typeCode())
        .containsExactly("ABOUT", "ABOUT");
  }

  @Test
  @DisplayName("without the ABOUT edges, the same two books have no route — the property caused it")
  void withoutTheAboutEdgesThereIsNoRoute() {
    store = new TinkerGraphStore();
    store.upsertNode(book(BOOK_A, "Some Technical Book"));
    store.upsertNode(book(BOOK_B, "Some Other Technical Book"));
    store.upsertNode(new NodeRecord(SUBJECT, NodeKind.CONCEPT, "A Shared Subject"));
    // Deliberately no store.record(...) calls: same nodes, no ABOUT edges.

    List<PathResult> routes = store.paths(BOOK_A, BOOK_B, 2);

    assertThat(routes).isEmpty();
  }
}
