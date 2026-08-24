package com.robsartin.segue.domain;

import java.util.StringJoiner;

/**
 * One step of an explanation. {@code traversedBackwards} records that the walk
 * went against the edge's stored direction, so rendering can say "was directed
 * by" rather than "directed".
 */
public record Hop(NodeRecord from, EdgeRecord edge, NodeRecord to, boolean traversedBackwards) {

    public String describe() {
        String verb = traversedBackwards
                ? "<-[" + edge.typeCode() + "]-"
                : "-[" + edge.typeCode() + "]->";
        StringJoiner cites = new StringJoiner(", ", " [", "]");
        for (Provenance p : edge.sources()) {
            cites.add(p.sourceRef() == null ? p.sourceId() : p.sourceId() + " " + p.sourceRef());
        }
        return from.label() + " " + verb + " " + to.label() + cites;
    }
}
