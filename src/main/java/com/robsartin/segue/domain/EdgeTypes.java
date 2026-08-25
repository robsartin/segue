package com.robsartin.segue.domain;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The controlled vocabulary. In the real system these are DB rows so a new relation type is a data
 * change, not a redeploy - this class is the spike's stand-in, and deliberately spans three domains
 * to prove the model does not need per-domain dialects.
 *
 * <p>Music, film and literature relations all sit in one flat namespace because they are all just
 * relations between the same six node kinds. That is the whole cross-domain bet, stated in one
 * file.
 */
public final class EdgeTypes {

  private static final Map<String, EdgeType> BY_CODE = new LinkedHashMap<>();

  // --- people and groups -------------------------------------------------
  public static final EdgeType MEMBER_OF =
      register(EdgeType.direct("MEMBER_OF", "P463", "member of"));

  /**
   * A roster stated from the group's side, and the cheap half of the fix for issue #20: without it
   * a band expands to nothing at all, because P463 lives on the member. Registered DIRECT because
   * Wikidata really does say "group has part person" — inverting it would produce an edge whose
   * label reads backwards.
   *
   * <p><b>Reverse-P463 strictly dominates this.</b> Measured on Nick Cave and the Bad Seeds
   * (Q1051182): P527 lists 8 members, while {@code ?person wdt:P463 wd:Q1051182} returns 10 — the
   * same 8 plus Mick Harvey and Blixa Bargeld. Keep this registration as the degraded fallback for
   * when the Query Service is unreachable, not as the answer. See ADR 36.
   */
  public static final EdgeType HAS_PART = register(EdgeType.direct("HAS_PART", "P527", "has part"));

  // --- creative roles (Wikidata states these on the work) ----------------
  public static final EdgeType PERFORMED =
      register(EdgeType.inverted("PERFORMED", "P175", "performed"));
  public static final EdgeType AUTHORED =
      register(EdgeType.inverted("AUTHORED", "P50", "authored"));
  public static final EdgeType DIRECTED =
      register(EdgeType.inverted("DIRECTED", "P57", "directed"));
  public static final EdgeType WROTE_SCREENPLAY_FOR =
      register(EdgeType.inverted("WROTE_SCREENPLAY_FOR", "P58", "wrote the screenplay for"));
  public static final EdgeType COMPOSED_FOR =
      register(EdgeType.inverted("COMPOSED_FOR", "P86", "composed the music for"));
  public static final EdgeType ACTED_IN =
      register(EdgeType.inverted("ACTED_IN", "P161", "acted in"));

  // --- work to work ------------------------------------------------------
  public static final EdgeType BASED_ON = register(EdgeType.direct("BASED_ON", "P144", "based on"));
  public static final EdgeType PART_OF = register(EdgeType.direct("PART_OF", "P361", "part of"));

  // --- influence and affinity -------------------------------------------
  public static final EdgeType INFLUENCED_BY =
      register(EdgeType.direct("INFLUENCED_BY", "P737", "influenced by"));

  /** No source states this; it is derived from co-credits or proposed by a model. */
  public static final EdgeType COLLABORATED_WITH =
      register(EdgeType.derived("COLLABORATED_WITH", "collaborated with", true));

  /** Statistical similarity - last.fm and friends. Never authoritative. */
  public static final EdgeType SIMILAR_TO =
      register(EdgeType.derived("SIMILAR_TO", "similar to", true));

  private EdgeTypes() {}

  private static EdgeType register(EdgeType t) {
    BY_CODE.put(t.code(), t);
    return t;
  }

  public static Optional<EdgeType> byCode(String code) {
    return Optional.ofNullable(BY_CODE.get(code));
  }

  /**
   * The whole vocabulary in registration order, as an immutable copy.
   *
   * <p>Deliberately a copy rather than {@code BY_CODE.values()}: that view is live onto the backing
   * map, so any caller could have emptied the vocabulary at runtime. ADR 22 makes this a
   * <em>controlled</em> namespace borrowed from Wikidata properties, and {@code ClaimMapper} reads
   * it as the ingest property whitelist - a whitelist callers can edit is not controlled, and
   * clearing it would silently turn every ingest run into a no-op rather than failing loudly.
   */
  public static Collection<EdgeType> all() {
    return List.copyOf(BY_CODE.values());
  }
}
