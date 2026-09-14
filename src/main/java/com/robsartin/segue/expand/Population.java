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
 * <p><b>Each shape carries one operator-supplied fact, and two of the three can carry text.</b>
 *
 * <p>{@code Instant.toString} emits no letter but {@code T} or {@code Z}, so {@code RatedSince} has
 * nowhere to put an entity id whatever the flag was spelled as.
 *
 * <p>{@code KnownNeverExpanded} and {@code SecondHopNeighbours} each carry a file's basename, text
 * the operator typed. It can be qid-shaped — {@code ExpansionIsSafeToPasteTest}'s positive control
 * over the flagged path is for exactly that. What either type holds is not a guarantee about the
 * characters: the value is the basename, never the path — {@code support.KnownListInput} is the one
 * home of that rule (ADR 51, ADR 63).
 */
public sealed interface Population permits RatedSince, KnownNeverExpanded, SecondHopNeighbours {}
