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
 * <p><b>Each shape carries one operator-supplied fact, and only one of the two can carry text.</b>
 *
 * <p>{@code Instant.toString} emits no letter but {@code T} or {@code Z}, so {@code RatedSince} has
 * nowhere to put an entity id whatever the flag was spelled as.
 *
 * <p>{@code KnownNeverExpanded} carries a file's basename, and a basename is text the operator
 * typed — it can be qid-shaped, which is what {@code ExpansionIsSafeToPasteTest}'s positive control
 * over the flagged path is for. What this type holds is not a guarantee about the characters: it is
 * that the value is the basename and never the path, which {@code support.KnownListInput} is the
 * one home of (ADR 51, ADR 63).
 */
public sealed interface Population permits RatedSince, KnownNeverExpanded {}
