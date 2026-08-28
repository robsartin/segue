package com.robsartin.segue.recommend;

import com.robsartin.segue.domain.PathResult;
import com.robsartin.segue.domain.Recommendation;
import java.util.List;
import java.util.Objects;

/**
 * One recommendation and the routes that justify it — the score joined to its receipts (ADR 45).
 *
 * <p>Two types rather than one because they are fetched differently and for different populations:
 * every candidate is scored, and only the ones somebody is going to read are explained. Putting the
 * routes on {@link Recommendation} would either make that laziness invisible or make the record a
 * lie for the thousand candidates that never get one.
 *
 * @param routes the best route from each of the known entities that contributed most, strongest
 *     first. Possibly empty, and the report says so when it is
 */
public record Explained(Recommendation candidate, List<PathResult> routes) {

  public Explained {
    Objects.requireNonNull(candidate, "candidate");
    routes = List.copyOf(Objects.requireNonNull(routes, "routes"));
  }
}
