package com.robsartin.segue.bakeoff;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.EdgeRecord;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.Provenance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Zero-dependency checks on the domain model and the fixture. Runs with nothing
 * but a JDK:
 *
 * <pre>
 *   javac -d /tmp/out $(find src/main/java/dev/rob/affinity/{domain,port} -name '*.java') \
 *         src/main/java/dev/rob/affinity/bakeoff/{Fixture,DomainSelfTest}.java
 *   java -cp /tmp/out com.robsartin.segue.bakeoff.DomainSelfTest
 * </pre>
 *
 * <p>It verifies the invariants both GraphStore implementations are expected to
 * preserve, by computing the expected answers directly from the assertion list.
 * If an adapter disagrees with this, the adapter is wrong.
 */
public final class DomainSelfTest {

    private static int failed = 0;

    public static void main(String[] args) {
        recordsRejectBadInput();
        assertionsCollapseIntoEdges();
        temporalFilteringIsCorrect();
        corroborationCountsDistinctSources();
        multigraphKeepsParallelTypes();

        System.out.println();
        if (failed == 0) {
            System.out.println("All domain self-tests passed.");
        } else {
            System.out.println(failed + " domain self-test(s) FAILED.");
            System.exit(1);
        }
    }

    private static void recordsRejectBadInput() {
        section("record invariants");
        check("rejects a non-Wikidata qid", throwsIae(
                () -> new NodeRecord("nick-cave", NodeKind.PERSON, "Nick Cave")));
        check("rejects confidence above 1", throwsIae(
                () -> new Provenance("wikidata", "ref", Instant.now(), 1.5)));
        check("rejects tabs in a source ref (would corrupt the codec)", throwsIae(
                () -> new Provenance("wikidata", "a\tb", Instant.now(), 1.0)));
        check("rejects a validity window that ends before it starts", throwsIae(
                () -> new AssertionRecord("Q1", "Q2", "MEMBER_OF",
                        LocalDate.of(2000, 1, 1), LocalDate.of(1990, 1, 1),
                        new Provenance("wikidata", null, Instant.now(), 1.0))));
    }

    private static void assertionsCollapseIntoEdges() {
        section("assertions collapse into edges");
        Map<String, EdgeRecord> edges = fold();
        System.out.printf("      %d assertions -> %d edges%n", Fixture.assertions().size(), edges.size());
        check("fewer edges than assertions", edges.size() < Fixture.assertions().size());
        check("every edge keeps at least one source",
                edges.values().stream().allMatch(e -> !e.sources().isEmpty()));
    }

    private static void temporalFilteringIsCorrect() {
        section("time travel");
        Map<String, EdgeRecord> edges = fold();
        List<String> lineup1984 = edges.values().stream()
                .filter(e -> e.toQid().equals(Fixture.BAD_SEEDS) && e.typeCode().equals("MEMBER_OF"))
                .filter(e -> e.validAt(LocalDate.of(1984, 6, 1)))
                .map(EdgeRecord::fromQid).sorted().toList();
        System.out.println("      Bad Seeds, June 1984: " + lineup1984);
        check("three members in 1984", lineup1984.size() == 3);
        check("Ellis (joined 1994) absent", !lineup1984.contains(Fixture.ELLIS));
        check("Blixa (1983-2003) present", lineup1984.contains(Fixture.BLIXA));

        List<String> lineup2010 = edges.values().stream()
                .filter(e -> e.toQid().equals(Fixture.BAD_SEEDS) && e.typeCode().equals("MEMBER_OF"))
                .filter(e -> e.validAt(LocalDate.of(2010, 6, 1)))
                .map(EdgeRecord::fromQid).sorted().toList();
        System.out.println("      Bad Seeds, June 2010: " + lineup2010);
        check("Mick Harvey (left 2009) absent in 2010", !lineup2010.contains(Fixture.HARVEY_MICK));
        check("Ellis present in 2010", lineup2010.contains(Fixture.ELLIS));
    }

    private static void corroborationCountsDistinctSources() {
        section("corroboration");
        Map<String, EdgeRecord> edges = fold();
        List<EdgeRecord> corroborated = edges.values().stream()
                .filter(e -> e.corroboration() >= 2).toList();
        corroborated.forEach(e -> System.out.println("      " + e.key()
                + " " + e.sources().stream().map(Provenance::sourceId).toList()));
        check("exactly three edges have two independent sources", corroborated.size() == 3);
        check("no model-only edge is corroborated",
                corroborated.stream().noneMatch(EdgeRecord::isUncorroboratedHypothesis));

        List<EdgeRecord> hypotheses = edges.values().stream()
                .filter(EdgeRecord::isUncorroboratedHypothesis).toList();
        hypotheses.forEach(e -> System.out.println("      hypothesis: " + e.key()
                + " conf=" + e.bestConfidence()));
        check("two model hypotheses remain quarantined", hypotheses.size() == 2);
    }

    private static void multigraphKeepsParallelTypes() {
        section("multigraph");
        Map<String, EdgeRecord> edges = fold();
        List<EdgeRecord> cavePropositon = edges.values().stream()
                .filter(e -> e.fromQid().equals(Fixture.CAVE) && e.toQid().equals(Fixture.PROPOSITION))
                .toList();
        cavePropositon.forEach(e -> System.out.println("      " + e.key()));
        check("Cave relates to The Proposition in two distinct ways", cavePropositon.size() == 2);
    }

    // --- the reference fold, mirroring what each adapter must do -----------

    private static Map<String, EdgeRecord> fold() {
        Map<String, EdgeRecord> byKey = new LinkedHashMap<>();
        for (AssertionRecord a : Fixture.assertions()) {
            EdgeRecord existing = byKey.get(a.edgeKey());
            if (existing == null) {
                byKey.put(a.edgeKey(), new EdgeRecord(a.fromQid(), a.toQid(), a.typeCode(),
                        a.validFrom(), a.validTo(), List.of(a.provenance())));
            } else {
                List<Provenance> merged = new ArrayList<>(existing.sources());
                merged.add(a.provenance());
                byKey.put(a.edgeKey(), new EdgeRecord(a.fromQid(), a.toQid(), a.typeCode(),
                        existing.validFrom() != null ? existing.validFrom() : a.validFrom(),
                        existing.validTo() != null ? existing.validTo() : a.validTo(),
                        merged));
            }
        }
        return byKey;
    }

    // --- tiny harness ------------------------------------------------------

    private static boolean throwsIae(Runnable r) {
        try {
            r.run();
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    private static void section(String name) {
        System.out.println();
        System.out.println("-- " + name);
    }

    private static void check(String what, boolean ok) {
        System.out.printf("   [%s] %s%n", ok ? "PASS" : "FAIL", what);
        if (!ok) failed++;
    }
}
