package com.robsartin.segue.domain;

import java.util.List;
import java.util.Objects;

/**
 * The payoff feature: an explanation you can cite. Every hop carries the sources
 * that justify it, so "you like this because..." is auditable rather than
 * mysterious.
 */
public record PathResult(List<Hop> hops) {

    public PathResult {
        hops = List.copyOf(Objects.requireNonNull(hops, "hops"));
    }

    public int length() {
        return hops.size();
    }

    /** A path is only as trustworthy as its shakiest hop. */
    public double weakestConfidence() {
        return hops.stream().mapToDouble(h -> h.edge().bestConfidence()).min().orElse(0.0);
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        for (Hop h : hops) sb.append("      ").append(h.describe()).append('\n');
        return sb.toString();
    }
}
