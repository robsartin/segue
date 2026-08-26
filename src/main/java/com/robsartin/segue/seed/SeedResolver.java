package com.robsartin.segue.seed;

import com.robsartin.segue.domain.Candidate;
import com.robsartin.segue.port.EntityResolver;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One batch of names, resolved.
 *
 * <p>Two shapes of batching, for two different reasons.
 *
 * <p><b>Across names</b>, because the extra round trip that reveals {@code P31} and {@code P106}
 * takes fifty identifiers at a time. Searching every name in the batch first and fetching the facts
 * for all of their candidates together turns thousands of calls into dozens.
 *
 * <p><b>Across spellings</b>, because a fallback spelling is a guess about what the user meant and
 * should cost nothing when the literal string already answered. Pass one asks the literal spellings
 * only; each later pass carries just the names that pass one could not settle.
 *
 * <p>Nothing here writes to a store. The tool resolves and reports; adding an entity to the graph
 * is {@code add_entity}'s job, through {@code IngestService}, which is the only writer there is
 * (ADR 19).
 */
public final class SeedResolver {

  private static final Comparator<Decision> STRONGEST_FIRST =
      Comparator.comparingInt(SeedResolver::strength).reversed();

  private final EntityResolver resolver;
  private final WikidataFacts facts;
  private final int candidatesPerSpelling;

  public SeedResolver(EntityResolver resolver, WikidataFacts facts, int candidatesPerSpelling) {
    this.resolver = Objects.requireNonNull(resolver, "resolver");
    this.facts = Objects.requireNonNull(facts, "facts");
    if (candidatesPerSpelling < 1) {
      throw new IllegalArgumentException("candidatesPerSpelling must be at least 1");
    }
    this.candidatesPerSpelling = candidatesPerSpelling;
  }

  /** A decision for every group, keyed by {@link NameGroup#key()}. */
  public Map<String, Decision> resolve(List<NameGroup> groups) {
    Objects.requireNonNull(groups, "groups");
    Map<String, Decision> best = new LinkedHashMap<>();
    List<NameGroup> outstanding = List.copyOf(groups);
    for (int pass = 0; !outstanding.isEmpty(); pass++) {
      List<NameGroup> asking = withSpelling(outstanding, pass);
      if (asking.isEmpty()) {
        break;
      }
      Map<String, Decision> round = askOnce(asking, pass);
      List<NameGroup> next = new ArrayList<>();
      for (NameGroup group : asking) {
        Decision decision = round.get(group.key());
        best.merge(group.key(), decision, SeedResolver::stronger);
        if (!best.get(group.key()).accepted()) {
          next.add(group);
        }
      }
      outstanding = next;
    }
    return Map.copyOf(best);
  }

  /** One search per group on the given spelling, then one fetch for every candidate at once. */
  private Map<String, Decision> askOnce(List<NameGroup> groups, int pass) {
    Map<String, List<String>> candidateQids = new LinkedHashMap<>();
    Set<String> everyQid = new LinkedHashSet<>();
    for (NameGroup group : groups) {
      List<String> qids =
          resolver.search(group.spellings().get(pass), null, candidatesPerSpelling).stream()
              .map(Candidate::qid)
              .toList();
      candidateQids.put(group.key(), qids);
      everyQid.addAll(qids);
    }

    Map<String, CandidateFacts> byQid = facts.factsFor(everyQid);

    Map<String, Decision> decisions = new LinkedHashMap<>();
    for (NameGroup group : groups) {
      // Search order is kept: it is what "the closest hit" means on a review line.
      List<CandidateFacts> candidates =
          candidateQids.get(group.key()).stream().map(byQid::get).filter(Objects::nonNull).toList();
      decisions.put(
          group.key(),
          Adjudicator.decide(group.spellings().get(pass), group.expectation(), candidates));
    }
    return decisions;
  }

  private static List<NameGroup> withSpelling(List<NameGroup> groups, int pass) {
    return groups.stream().filter(group -> group.spellings().size() > pass).toList();
  }

  /**
   * The better of two reports on one name.
   *
   * <p>A fallback spelling that finds nothing must not erase what the literal spelling found: an
   * answer worth reviewing beats no answer at all, and an accepted answer beats both.
   */
  private static Decision stronger(Decision first, Decision second) {
    return STRONGEST_FIRST.compare(first, second) <= 0 ? first : second;
  }

  private static int strength(Decision decision) {
    return switch (decision.outcome()) {
      case ACCEPTED -> 2;
      case REVIEW -> 1;
      case UNRESOLVED -> 0;
    };
  }
}
