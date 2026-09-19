package com.robsartin.segue.support;

import com.robsartin.segue.domain.NodeKind;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Which {@link NodeKind}s a list's {@code kind} column value may turn out to be.
 *
 * <p><b>One home, two readers</b> (#342). {@code Expectations} builds each kind's expectation on
 * top of this, and the owner-claim tool's batch mint reads it to decide a minted entity's node kind
 * from the review row's list kind.
 *
 * <p>A second copy would let the two disagree about what a {@code musician} is — the one place
 * where disagreeing means minting an entity under the wrong kind, into a log that is append-only
 * and is never edited.
 *
 * <p><b>The occupation and class sets stay in {@code seed}.</b> They are resolver knowledge: they
 * exist to tell six same-named humans apart against Wikidata, and nothing outside that tool has
 * anything to ask them.
 *
 * <p><b>A kind that is not registered folds to nothing</b>, rather than to every kind. The two
 * callers read that differently on purpose, and each says why where it reads it.
 *
 * <p>{@code Expectations} treats its own gap as constraining nothing. The batch mint refuses a file
 * carrying a kind this table has never seen, because such a file is not one the seed tool wrote.
 */
public final class ListKinds {

  private static final Map<String, Set<NodeKind>> BY_KIND = new LinkedHashMap<>();

  static {
    // A musician on this list is as often a band as a person, so both kinds are allowed and the
    // seed tool's occupation check only bites on the ones that turn out to be human.
    put("musician", NodeKind.PERSON, NodeKind.GROUP);
    put("composer", NodeKind.PERSON);
    put("conductor", NodeKind.PERSON);
    put("comedian", NodeKind.PERSON, NodeKind.GROUP);
    put("author", NodeKind.PERSON);
    put("actor", NodeKind.PERSON);
    put("director", NodeKind.PERSON);
    put("broadcaster", NodeKind.PERSON);
    put("a-cappella", NodeKind.GROUP);
    put("tribute", NodeKind.GROUP);
    put("orchestra", NodeKind.GROUP);
    put("choir", NodeKind.GROUP);
    put("ensemble", NodeKind.GROUP);
    put("org", NodeKind.GROUP);
    put("tv-show", NodeKind.WORK);
    put("film", NodeKind.WORK);
    put("book", NodeKind.WORK);
    // A fictional character has no NodeKind of its own — ADR 21 has six and none of them is
    // "character" — so it lands in CONCEPT, which is what an unmapped P31 always becomes.
    put("character", NodeKind.CONCEPT);
    put("public-figure", NodeKind.PERSON);
    put("puppeteer", NodeKind.PERSON);
  }

  private ListKinds() {}

  private static void put(String kind, NodeKind... kinds) {
    if (BY_KIND.put(kind, Set.of(kinds)) != null) {
      throw new IllegalStateException("two registrations claim the list kind " + kind);
    }
  }

  /** Every list kind this table registers, in registration order. */
  public static Set<String> registered() {
    return Set.copyOf(BY_KIND.keySet());
  }

  /** The node kinds one list kind may be; empty where the table does not register it. */
  public static Set<NodeKind> nodeKinds(String kind) {
    Objects.requireNonNull(kind, "kind");
    return BY_KIND.getOrDefault(kind.trim().toLowerCase(Locale.ROOT), Set.of());
  }
}
