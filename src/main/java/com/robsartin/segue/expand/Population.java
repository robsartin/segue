package com.robsartin.segue.expand;

/**
 * Which population a run covered, where it was not simply every promotion (#307, #313).
 *
 * <p><b>Sealed, and carried as one {@link java.util.Optional}, so a run cannot be two
 * populations.</b> The flags that select them are exclusive at the command line; this is the same
 * exclusivity one level in, where no parser has to be trusted for it. {@code ExpansionReport}
 * switches over this with no {@code default}, so a third population has to decide what it prints
 * rather than fall through to another population's sentence.
 *
 * <p><b>Each shape carries one operator-supplied fact and no room for an entity id.</b>
 *
 * <p>{@code Instant.toString} emits no letter but {@code T} or {@code Z}.
 *
 * <p>{@code KnownNeverExpanded} carries a basename, which {@code KnownListInput} is the one home of
 * — the basename, never the path (ADR 51, ADR 63).
 */
public sealed interface Population permits RatedSince, KnownNeverExpanded {}
