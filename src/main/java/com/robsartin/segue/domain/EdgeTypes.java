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
   * <p><b>Registered {@code fallbackOnly}, and that word is now load-bearing.</b> Wikidata defines
   * P527 as the inverse of both {@link #MEMBER_OF} (P463) and {@link #PART_OF} (P361): one
   * relationship, stated from the opposite end. ADR 36 always described this as "the degraded
   * fallback for when the Query Service is unreachable, not the answer" — but nothing implemented
   * that, so both ends were ingested and every membership became two edges. Issue #33 measured 4 of
   * 23 HAS_PART edges shadowing a MEMBER_OF over the same pair, which is two identical routes
   * through {@code find_paths} and two slots against one {@code maxNewEdges} bound.
   *
   * <p>What {@code fallbackOnly} buys, mechanically: {@code ReverseClaims} never asks about P527,
   * and {@code WikidataSourceAdapter} drops the forward P527 claims whenever the reverse pass ran.
   * When the Query Service is down there is no better direction to defer to, so these claims are
   * kept and a band still expands to its roster.
   *
   * <p><b>Reverse-P463 strictly dominates this when it is available.</b> Measured on Nick Cave and
   * the Bad Seeds (Q1051182): P527 lists 8 members, while {@code ?person wdt:P463 wd:Q1051182}
   * returns 10 — the same 8 plus Mick Harvey and Blixa Bargeld. Preferring the fallback would
   * quietly lose two of the most significant Bad Seeds.
   */
  public static final EdgeType HAS_PART =
      register(EdgeType.fallbackOnly("HAS_PART", "P527", "has part"));

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

  // --- recognition -------------------------------------------------------

  /**
   * The award, prize or honour an entity received — and the first relation in this vocabulary that
   * is not a collaboration.
   *
   * <p><b>Why it had to exist (issue #32).</b> Every other Wikidata-backed type here records people
   * working <em>together</em>: co-credits on one work, membership of one group. That models music
   * and film well, where a work has a director, a composer and a cast, and it models literature
   * barely at all, because a novel has exactly one author. Three science-fiction novelists, added
   * and expanded, produced three neighbourhoods with no node in common and {@code find_paths}
   * returned nothing for any pair of them — not "these are distant", but "there is no route", which
   * for the payoff feature is the same as being broken.
   *
   * <p><b>Registered DIRECT, because Wikidata states P166 on the recipient.</b> {@code person P166
   * award}, so the subject stays on the left and the edge reads "William Gibson RECEIVED_AWARD Hugo
   * Award for Best Novel". Inverting it would file the award as the recipient of the person, and
   * every citation {@code find_paths} printed would read backwards.
   *
   * <p><b>Not {@code fallbackOnly}.</b> The issue-#33 condition is that a property is the other end
   * of one already registered here; the award-side way of stating this fact is P1346 ("winner"),
   * which this vocabulary does not register, so there is no second end being ingested and nothing
   * to deduplicate.
   *
   * <p><b>Why an award and not a genre, an occupation or a label.</b> Those were the obvious
   * candidates for the same problem and they were measured against the Query Service rather than
   * argued about: P106 occupation → "novelist" is a <b>35,977</b>-item node, P136 genre → "science
   * fiction" is <b>16,552</b>, the largest P264 record label is <b>11,350</b>, and P166 → "Hugo
   * Award for Best Novel" is <b>127</b>. {@code Gibson → science fiction → Scalzi} is two hops at
   * perfect confidence and explains nothing; {@code Gibson → Hugo Award for Best Novel → Scalzi} is
   * the same shape and is a real segue. Two orders of magnitude is the whole argument, and ADR 38
   * records both the numbers and the questions this deliberately leaves open — including the
   * general hub-degree rule that would decide the next property mechanically.
   */
  public static final EdgeType RECEIVED_AWARD =
      register(EdgeType.direct("RECEIVED_AWARD", "P166", "received"));

  // --- aboutness -----------------------------------------------------------

  /**
   * What a work is about — the second single-property admission made the way ADR 38 made the first:
   * measure, admit one, argue it in an ADR (issue #111).
   *
   * <p><b>Why it had to exist.</b> The vocabulary above this comment models creative
   * <em>collaboration</em> — co-credits, membership, influence, recognition. Two single-authored
   * technical books share no author, no award, no genre in this vocabulary at all, so a bookshelf
   * of them is a set of disconnected islands and {@code find_paths} returns nothing between any
   * pair. {@code ABOUT} gives a route through what the books are about rather than who wrote them.
   *
   * <p><b>Registered DIRECT, and this corrects issue #111's own text.</b> The issue that opened
   * this work asserted P921 is "stated on the work, pointing at the subject, so it is {@code
   * inverted}" — but {@code inverted} means the STORED direction is the reverse of the STATED one,
   * and it is not that here. Compare {@link #AUTHORED}: Wikidata states {@code book P50 person},
   * and segue stores the reverse, {@code person AUTHORED book} — that mismatch is what {@code
   * inverted} means. P921 states {@code book P921 subject}, and segue wants exactly that, {@code
   * book ABOUT subject} — the stored direction and the stated direction agree, which by {@link
   * EdgeType#direct}'s own contract makes this DIRECT.
   *
   * <p><b>Not {@code fallbackOnly}.</b> The issue-#33 condition is that Wikidata defines another
   * property as this one's inverse and that property is already registered here. P921's inverse
   * label item is Q70782961 ("main subject of"), not a property — Wikidata has no separate {@code
   * Pxxx} for "has main subject" to register, checked rather than assumed. There is no second end
   * to ingest and nothing to deduplicate.
   */
  public static final EdgeType ABOUT = register(EdgeType.direct("ABOUT", "P921", "about"));

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
