package com.robsartin.segue.expand;

/**
 * What a dry run would visit, without visiting it.
 *
 * @param considered every promotion the run was handed
 * @param inTheGraph those the projection holds a node for and that are not {@link
 *     com.robsartin.segue.domain.LocalEntity#isLocal} — what {@code UNKNOWN_ENTITY} would refuse is
 *     the rest
 * @param minted those {@link com.robsartin.segue.domain.LocalEntity#isLocal} answers true for,
 *     which {@code LOCAL_ENTITY} would refuse
 */
public record Preflight(int considered, int inTheGraph, int minted) {}
