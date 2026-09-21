package com.robsartin.segue.expand;

/**
 * What a dry run would visit, without visiting it.
 *
 * @param considered every entity the run was handed — which, when {@code --rated-since} was given,
 *     is the promoted population <b>after</b> the instant filtered it, and which, when {@code
 *     --known} was given, is the file's entities after the never-expanded rule filtered them
 *     (#313). What either flag removed is not counted here; it is named on the block's own clause,
 *     so a pasted block says what population it covered
 * @param inTheGraph those the projection holds a node for and that are not {@link
 *     com.robsartin.segue.domain.LocalEntity#isLocal} — what {@code UNKNOWN_ENTITY} would refuse is
 *     the rest
 * @param minted those {@link com.robsartin.segue.domain.LocalEntity#isLocal} answers true for,
 *     which {@code LOCAL_ENTITY} would refuse
 * @param toAdd those the run would add before expanding — not {@link
 *     com.robsartin.segue.domain.LocalEntity#isLocal} and with no node — which is zero on every run
 *     that was not given {@code --add}, because only such a run adds anything. Kept disjoint from
 *     {@link #minted} by {@link ExpandRun#dryRun}'s same first-check rule, so an {@code --add} run
 *     closes exactly: {@code considered == inTheGraph + minted + toAdd}
 */
public record Preflight(int considered, int inTheGraph, int minted, int toAdd) {}
