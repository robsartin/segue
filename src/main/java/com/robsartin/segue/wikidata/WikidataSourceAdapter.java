package com.robsartin.segue.wikidata;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.port.ExpandContext;
import com.robsartin.segue.port.ExpandResult;
import com.robsartin.segue.port.SourceAdapter;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

/**
 * Expansion from Wikidata.
 *
 * <p>Wikidata is first among sources deliberately: no API key, cross-domain by construction, and it
 * supplies both the QID identity spine and the edge vocabulary (ADR 22).
 *
 * <p><b>Two passes, because Wikidata states a relation once and only once.</b> The forward pass
 * ({@link ClaimMapper}) reads the claims stated ON the seed. The reverse pass ({@link
 * ReverseClaims}) asks the Query Service which items point AT it. Neither is redundant: a film's
 * director is stated on the film, so only the forward pass finds it when expanding the film, and
 * only the reverse pass finds it when expanding the director. Running just the forward pass is what
 * made a person expand to four edges and a band to zero — issue #20, decided in ADR 36.
 *
 * <p><b>Forward first when the bound bites.</b> The two lists are concatenated in that order before
 * {@code maxNewEdges} is applied, so a claim Wikidata states on the seed itself outranks one merely
 * pointing at it. Those are the better-evidenced ones — a forward claim can carry a reference and
 * validity qualifiers, and a truthy reverse triple carries neither (see {@link ReverseClaims}).
 *
 * <p><b>Failures degrade rather than propagate.</b> The eventual caller is a language model, and a
 * partial result it can see and act on beats an exception it can only retry. An unreachable
 * Wikidata yields an empty expansion, not a thrown error — and a Query Service that fails after the
 * Action API succeeded yields the forward claims, flagged, rather than throwing away an answer that
 * was already in hand.
 */
public final class WikidataSourceAdapter implements SourceAdapter {

  private static final String SOURCE_ID = "wikidata";

  private final WikidataEntityResolver resolver;
  private final ReverseClaims reverse;
  private final Clock clock;

  /**
   * @param resolver reads claims stated on an entity, over the Action API
   * @param queryClient a client aimed at the Query Service — {@link WikidataClient#queryService()}
   *     in production. Passed in rather than constructed here so the reverse pass is testable
   *     against a stub, like every other call this package makes.
   */
  public WikidataSourceAdapter(
      WikidataEntityResolver resolver, WikidataClient queryClient, Clock clock) {
    this.resolver = Objects.requireNonNull(resolver, "resolver");
    this.reverse = new ReverseClaims(Objects.requireNonNull(queryClient, "queryClient"));
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public String id() {
    return SOURCE_ID;
  }

  @Override
  public boolean supports(NodeKind kind) {
    // Wikidata spans every kind segue models. That breadth is the reason it is the first source.
    return true;
  }

  @Override
  public ExpandResult expand(NodeRecord seed, ExpandContext ctx) {
    Objects.requireNonNull(seed, "seed");
    Objects.requireNonNull(ctx, "ctx");

    JsonNode entity;
    try {
      entity = resolver.entity(seed.qid());
    } catch (WikidataUnavailableException e) {
      // Deliberately swallowed rather than thrown. The tool layer reports the shortfall to
      // the model via ExpandResult.sourceUnavailable(); throwing here would turn a partial
      // answer into no answer.
      return ExpandResult.unavailable();
    }
    if (entity == null) {
      // Wikidata has never heard of this seed. Spending a Query Service call on it would ask a
      // shared, rate-limited service a question with no possible answer.
      return ExpandResult.of(List.of());
    }

    // One instant for both passes: the forward and reverse halves of a single expansion learned
    // the same things at the same moment, and two clock reads would say otherwise (ADR 20).
    Instant assertedAt = clock.instant();
    List<AssertionRecord> mapped = new ArrayList<>(ClaimMapper.map(seed.qid(), entity, assertedAt));

    boolean sourceUnavailable = false;
    boolean truncated = false;
    List<NodeAssertion> neighbors = List.of();
    try {
      ReverseClaims.Result found = reverse.lookup(seed.qid(), ctx.maxNewEdges(), assertedAt);
      mapped.addAll(found.assertions());
      neighbors = found.neighbors();
      truncated = found.truncated();
    } catch (WikidataUnavailableException e) {
      // The forward claims are already in hand and are worth returning. Flagging the shortfall
      // is what stops "the band has no members" from being reported as a fact about the band.
      sourceUnavailable = true;
    }

    List<AssertionRecord> limited = mapped.stream().limit(ctx.maxNewEdges()).toList();
    truncated |= limited.size() < mapped.size();
    return new ExpandResult(limited, neighbors, sourceUnavailable, truncated);
  }
}
