package com.robsartin.segue.bakeoff;

import com.robsartin.segue.domain.EdgeRecord;
import com.robsartin.segue.domain.PathResult;
import com.robsartin.segue.jena.JenaGraphStore;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.tinker.TinkerGraphStore;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Runs the same four queries against both engines and checks they agree.
 *
 * <p>The point is not which one is faster on fifteen nodes - it is which one you
 * would rather write and read. Watch where the code lives: the Gremlin adapter's
 * path query is one traversal and its audit query is a scan; the Jena adapter's
 * audit query is four lines of SPARQL and its path query is eighty lines of
 * hand-rolled BFS.
 */
public final class BakeOff {

    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        try (GraphStore tinker = new TinkerGraphStore();
             GraphStore jena = new JenaGraphStore()) {

            Fixture.seed(tinker);
            Fixture.seed(jena);

            header("INGEST");
            System.out.printf("  nodes seeded      : %d%n", Fixture.nodes().size());
            System.out.printf("  assertions applied: %d%n", Fixture.assertions().size());
            System.out.printf("  distinct edges    : tinkergraph=%d  jena=%d%n",
                    tinker.edgeCount(), jena.edgeCount());
            check("edge counts match", tinker.edgeCount() == jena.edgeCount());
            check("assertions collapsed into fewer edges (corroboration happened)",
                    tinker.edgeCount() < Fixture.assertions().size());

            q1(tinker, jena);
            q2(tinker, jena);
            q3(tinker, jena);
            q4(tinker, jena);

            summary();
        }
    }

    // ---- Q1 ---------------------------------------------------------------

    private static void q1(GraphStore tinker, GraphStore jena) {
        header("Q1  EXPLANATION - how is Nick Cave connected to John Hillcoat?");

        // Compare the FULL route sets, not just the first result. Checking only
        // the shortest path hid a real divergence: the RDF adapter was walking
        // nodes rather than edges, which silently dropped one of the two ways
        // Cave connects to The Proposition.
        List<PathResult> t = tinker.shortestPaths(Fixture.CAVE, Fixture.HILLCOAT, 4, 50);
        List<PathResult> j = jena.shortestPaths(Fixture.CAVE, Fixture.HILLCOAT, 4, 50);

        System.out.println("  tinkergraph (shortest 3 of " + t.size() + "):");
        t.stream().limit(3).forEach(p -> System.out.print(p.render()));
        System.out.println("  jena (shortest 3 of " + j.size() + "):");
        j.stream().limit(3).forEach(p -> System.out.print(p.render()));

        check("both engines find a Cave-Hillcoat path", !t.isEmpty() && !j.isEmpty());
        check("both agree on shortest length",
                !t.isEmpty() && !j.isEmpty() && t.get(0).length() == j.get(0).length());
        check("the connection crosses music into film (2 hops via a film)",
                !t.isEmpty() && t.get(0).length() == 2);
        check("both engines enumerate the SAME set of routes",
                signatures(t).equals(signatures(j)));
        check("the multigraph survives: 3 distinct two-hop routes, two of them "
                        + "through The Proposition via different relationships",
                t.stream().filter(p -> p.length() == 2).count() == 3
                        && j.stream().filter(p -> p.length() == 2).count() == 3);

        header("Q1b TRUST - shortest is not always most trustworthy");
        List<PathResult> risky = tinker.shortestPaths(Fixture.CAVE, Fixture.MCCARTHY, 4, 5);
        for (PathResult p : risky) {
            System.out.printf("  %d hop(s), weakest confidence %.2f%n", p.length(), p.weakestConfidence());
            System.out.print(p.render());
        }
        PathResult shortest = risky.stream().min(Comparator.comparingInt(PathResult::length)).orElseThrow();
        PathResult longest = risky.stream().max(Comparator.comparingInt(PathResult::length)).orElseThrow();
        check("the one-hop route is the model's unverified guess",
                shortest.length() == 1 && shortest.weakestConfidence() <= 0.30);
        check("the longer route through The Road is better evidenced",
                longest.weakestConfidence() > shortest.weakestConfidence());
    }

    /** Canonical rendering of a route set, so the two engines can be compared exactly. */
    private static List<String> signatures(List<PathResult> paths) {
        return paths.stream()
                .map(p -> p.hops().stream()
                        .map(h -> h.from().qid()
                                + (h.traversedBackwards() ? "<-" : "-")
                                + h.edge().typeCode()
                                + (h.traversedBackwards() ? "-" : "->")
                                + h.to().qid())
                        .collect(java.util.stream.Collectors.joining(" | ")))
                .sorted()
                .toList();
    }

    // ---- Q2 ---------------------------------------------------------------

    private static void q2(GraphStore tinker, GraphStore jena) {
        header("Q2  AUDIT - what did last.fm tell us after 15 Aug?");
        Instant since = Instant.parse("2026-08-15T00:00:00Z");
        List<EdgeRecord> t = tinker.assertedBy("lastfm", since);
        List<EdgeRecord> j = jena.assertedBy("lastfm", since);
        print(t, j);
        check("both engines return the same last.fm edges", sameKeys(t, j));
        check("last.fm contributed exactly one edge", t.size() == 1);

        System.out.println();
        System.out.println("  and what did the model claim, unbacked?");
        List<EdgeRecord> tl = tinker.assertedBy("llm:claude", Instant.EPOCH);
        List<EdgeRecord> jl = jena.assertedBy("llm:claude", Instant.EPOCH);
        print(tl, jl);
        check("both engines agree on model-asserted edges", sameKeys(tl, jl));
        check("every model-asserted edge is still an uncorroborated hypothesis",
                tl.stream().allMatch(EdgeRecord::isUncorroboratedHypothesis));
    }

    // ---- Q3 ---------------------------------------------------------------

    private static void q3(GraphStore tinker, GraphStore jena) {
        header("Q3  TIME TRAVEL - who was in the Bad Seeds in June 1984?");
        LocalDate asOf = LocalDate.of(1984, 6, 1);
        List<EdgeRecord> t = tinker.validAt(Fixture.BAD_SEEDS, asOf);
        List<EdgeRecord> j = jena.validAt(Fixture.BAD_SEEDS, asOf);
        print(t, j);
        check("both engines agree on the 1984 lineup", sameKeys(t, j));
        check("three members in 1984", t.size() == 3);
        check("Warren Ellis (joined 1994) is correctly absent",
                t.stream().noneMatch(e -> e.fromQid().equals(Fixture.ELLIS)));
        check("Blixa Bargeld (left 2003) is correctly present",
                t.stream().anyMatch(e -> e.fromQid().equals(Fixture.BLIXA)));

        System.out.println();
        System.out.println("  ...and in June 2010?");
        List<EdgeRecord> later = tinker.validAt(Fixture.BAD_SEEDS, LocalDate.of(2010, 6, 1));
        print(later, jena.validAt(Fixture.BAD_SEEDS, LocalDate.of(2010, 6, 1)));
        check("Mick Harvey (left 2009) has dropped out of the 2010 lineup",
                later.stream().noneMatch(e -> e.fromQid().equals(Fixture.HARVEY_MICK)));
    }

    // ---- Q4 ---------------------------------------------------------------

    private static void q4(GraphStore tinker, GraphStore jena) {
        header("Q4  CORROBORATION - which edges do 2+ independent sources agree on?");
        List<EdgeRecord> t = tinker.corroborated(2);
        List<EdgeRecord> j = jena.corroborated(2);
        print(t, j);
        check("both engines agree on corroborated edges", sameKeys(t, j));
        check("three edges have two independent sources", t.size() == 3);
        check("no model-only edge survives the corroboration filter",
                t.stream().noneMatch(EdgeRecord::isUncorroboratedHypothesis));
    }

    // ---- output helpers ---------------------------------------------------

    private static void print(List<EdgeRecord> t, List<EdgeRecord> j) {
        System.out.println("  tinkergraph:");
        t.forEach(e -> System.out.println("      " + describe(e)));
        System.out.println("  jena:");
        j.forEach(e -> System.out.println("      " + describe(e)));
    }

    private static String describe(EdgeRecord e) {
        String window = e.validFrom() == null && e.validTo() == null
                ? ""
                : "  (" + (e.validFrom() == null ? "?" : e.validFrom())
                        + " to " + (e.validTo() == null ? "present" : e.validTo()) + ")";
        return e.key() + window
                + "  sources=" + e.sources().stream().map(p -> p.sourceId()).toList()
                + "  corroboration=" + e.corroboration();
    }

    private static boolean sameKeys(List<EdgeRecord> a, List<EdgeRecord> b) {
        return a.stream().map(EdgeRecord::key).sorted().toList()
                .equals(b.stream().map(EdgeRecord::key).sorted().toList());
    }

    private static void header(String title) {
        System.out.println();
        System.out.println("=".repeat(72));
        System.out.println("  " + title);
        System.out.println("=".repeat(72));
    }

    private static void check(String what, boolean ok) {
        System.out.printf("  [%s] %s%n", ok ? "PASS" : "FAIL", what);
        if (!ok) failures.add(what);
    }

    private static void summary() {
        header("SUMMARY");
        if (failures.isEmpty()) {
            System.out.println("  All checks passed. Both engines model the same graph identically.");
        } else {
            System.out.println("  FAILURES:");
            failures.forEach(f -> System.out.println("    - " + f));
            System.exit(1);
        }
    }
}
