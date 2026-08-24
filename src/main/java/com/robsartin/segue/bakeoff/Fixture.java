package com.robsartin.segue.bakeoff;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.port.GraphStore;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The Nick Cave neighbourhood - chosen because one person is a musician, a band
 * member with dates, a novelist, a screenwriter and a film composer. A model that
 * survives him survives going broad.
 *
 * <p><b>The QIDs below are PLACEHOLDERS in the Q9000xx range, not real Wikidata
 * identifiers.</b> This sandbox has no route to wikidata.org, so they could not be
 * resolved here. Slice 1 (the WikidataSourceAdapter) replaces them with real ones
 * via wbsearchentities; nothing else in the code depends on their values.
 *
 * <p>The fixture deliberately contains:
 * <ul>
 *   <li>TWO different relationship types between the same pair (Cave both wrote
 *       and scored The Proposition) - the multigraph requirement, in one case</li>
 *   <li>Edges asserted by two independent sources, so corroboration has something
 *       to count</li>
 *   <li>Overlapping but non-identical band tenures, so time travel can be wrong</li>
 *   <li>Model-generated edges that NO real source backs, including one that
 *       creates a tempting but untrustworthy shortcut between two entities</li>
 * </ul>
 */
public final class Fixture {

    // --- entities ----------------------------------------------------------
    public static final String CAVE = "Q900001";
    public static final String BAD_SEEDS = "Q900002";
    public static final String BIRTHDAY_PARTY = "Q900003";
    public static final String GRINDERMAN = "Q900004";
    public static final String ELLIS = "Q900005";
    public static final String BLIXA = "Q900006";
    public static final String NEUBAUTEN = "Q900007";
    public static final String HARVEY_MICK = "Q900008";
    public static final String PROPOSITION = "Q900009";
    public static final String HILLCOAT = "Q900010";
    public static final String ASS_SAW_ANGEL = "Q900011";
    public static final String ROAD_FILM = "Q900012";
    public static final String MCCARTHY = "Q900013";
    public static final String ROAD_NOVEL = "Q900014";
    public static final String PJ_HARVEY = "Q900015";

    // --- sources -----------------------------------------------------------
    private static final Instant STRUCTURED_PULL = Instant.parse("2026-08-01T09:00:00Z");
    private static final Instant LASTFM_PULL = Instant.parse("2026-08-20T09:00:00Z");
    private static final Instant LLM_TURN = Instant.parse("2026-08-22T14:30:00Z");

    private Fixture() {
    }

    public static void seed(GraphStore store) {
        nodes().forEach(store::upsertNode);
        assertions().forEach(store::record);
    }

    public static List<NodeRecord> nodes() {
        return List.of(
                new NodeRecord(CAVE, NodeKind.PERSON, "Nick Cave"),
                new NodeRecord(BAD_SEEDS, NodeKind.GROUP, "Nick Cave and the Bad Seeds"),
                new NodeRecord(BIRTHDAY_PARTY, NodeKind.GROUP, "The Birthday Party"),
                new NodeRecord(GRINDERMAN, NodeKind.GROUP, "Grinderman"),
                new NodeRecord(ELLIS, NodeKind.PERSON, "Warren Ellis"),
                new NodeRecord(BLIXA, NodeKind.PERSON, "Blixa Bargeld"),
                new NodeRecord(NEUBAUTEN, NodeKind.GROUP, "Einsturzende Neubauten"),
                new NodeRecord(HARVEY_MICK, NodeKind.PERSON, "Mick Harvey"),
                new NodeRecord(PROPOSITION, NodeKind.WORK, "The Proposition"),
                new NodeRecord(HILLCOAT, NodeKind.PERSON, "John Hillcoat"),
                new NodeRecord(ASS_SAW_ANGEL, NodeKind.WORK, "And the Ass Saw the Angel"),
                new NodeRecord(ROAD_FILM, NodeKind.WORK, "The Road (film)"),
                new NodeRecord(MCCARTHY, NodeKind.PERSON, "Cormac McCarthy"),
                new NodeRecord(ROAD_NOVEL, NodeKind.WORK, "The Road (novel)"),
                new NodeRecord(PJ_HARVEY, NodeKind.PERSON, "PJ Harvey"));
    }

    public static List<AssertionRecord> assertions() {
        return List.of(
                // ---- music: band membership, with tenures -------------------
                wikidata(CAVE, "MEMBER_OF", BAD_SEEDS, "1983-01-01", null, "S-cave-badseeds"),
                musicbrainz(CAVE, "MEMBER_OF", BAD_SEEDS, "1983-01-01", null, "mb-artist-rel-1"),
                wikidata(CAVE, "MEMBER_OF", BIRTHDAY_PARTY, "1978-01-01", "1983-06-30", "S-cave-bp"),
                wikidata(CAVE, "MEMBER_OF", GRINDERMAN, "2006-01-01", "2011-12-31", "S-cave-grind"),

                wikidata(BLIXA, "MEMBER_OF", BAD_SEEDS, "1983-01-01", "2003-07-31", "S-blixa-badseeds"),
                musicbrainz(BLIXA, "MEMBER_OF", BAD_SEEDS, "1983-01-01", "2003-07-31", "mb-artist-rel-2"),
                wikidata(BLIXA, "MEMBER_OF", NEUBAUTEN, "1980-01-01", null, "S-blixa-neubauten"),

                wikidata(ELLIS, "MEMBER_OF", BAD_SEEDS, "1994-01-01", null, "S-ellis-badseeds"),
                musicbrainz(ELLIS, "MEMBER_OF", GRINDERMAN, "2006-01-01", "2011-12-31", "mb-artist-rel-3"),

                wikidata(HARVEY_MICK, "MEMBER_OF", BAD_SEEDS, "1983-01-01", "2009-01-31", "S-mick-badseeds"),
                wikidata(HARVEY_MICK, "MEMBER_OF", BIRTHDAY_PARTY, "1978-01-01", "1983-06-30", "S-mick-bp"),

                // ---- film: the cross-domain edges the whole design is for ---
                // Two DIFFERENT relationship types between the same pair. A simple
                // graph would have to choose one; this is why it has to be a multigraph.
                wikidata(CAVE, "WROTE_SCREENPLAY_FOR", PROPOSITION, null, null, "S-cave-prop-writer"),
                wikidata(CAVE, "COMPOSED_FOR", PROPOSITION, null, null, "S-cave-prop-score"),
                musicbrainz(CAVE, "COMPOSED_FOR", PROPOSITION, null, null, "mb-release-score-1"),
                wikidata(ELLIS, "COMPOSED_FOR", PROPOSITION, null, null, "S-ellis-prop-score"),
                wikidata(HILLCOAT, "DIRECTED", PROPOSITION, null, null, "S-hillcoat-prop"),

                wikidata(HILLCOAT, "DIRECTED", ROAD_FILM, null, null, "S-hillcoat-road"),
                wikidata(CAVE, "COMPOSED_FOR", ROAD_FILM, null, null, "S-cave-road-score"),
                wikidata(ELLIS, "COMPOSED_FOR", ROAD_FILM, null, null, "S-ellis-road-score"),
                wikidata(ROAD_FILM, "BASED_ON", ROAD_NOVEL, null, null, "S-road-basedon"),
                wikidata(MCCARTHY, "AUTHORED", ROAD_NOVEL, null, null, "S-mccarthy-road"),

                // ---- literature --------------------------------------------
                wikidata(CAVE, "AUTHORED", ASS_SAW_ANGEL, null, null, "S-cave-novel"),

                // ---- statistical and model-generated -----------------------
                lastfm(CAVE, "SIMILAR_TO", PJ_HARVEY, "lastfm-similar-2026-08"),
                // Backed by nothing but a model. It must survive corroborated(1)
                // and disappear at corroborated(2).
                llm(CAVE, "COLLABORATED_WITH", PJ_HARVEY, "chat-2026-08-22#a1"),
                // The dangerous one: a plausible model claim that creates a ONE-HOP
                // shortcut between Cave and McCarthy, competing with the real
                // three-hop route through The Road. Shortest is not most trustworthy.
                llm(CAVE, "INFLUENCED_BY", MCCARTHY, "chat-2026-08-22#a2"));
    }

    // --- helpers -----------------------------------------------------------

    private static AssertionRecord wikidata(String from, String type, String to,
                                            String validFrom, String validTo, String ref) {
        return new AssertionRecord(from, to, type, date(validFrom), date(validTo),
                new Provenance("wikidata", ref, STRUCTURED_PULL, 1.00));
    }

    private static AssertionRecord musicbrainz(String from, String type, String to,
                                               String validFrom, String validTo, String ref) {
        return new AssertionRecord(from, to, type, date(validFrom), date(validTo),
                new Provenance("musicbrainz", ref, STRUCTURED_PULL, 0.80));
    }

    private static AssertionRecord lastfm(String from, String type, String to, String ref) {
        return new AssertionRecord(from, to, type, null, null,
                new Provenance("lastfm", ref, LASTFM_PULL, 0.50));
    }

    private static AssertionRecord llm(String from, String type, String to, String ref) {
        return new AssertionRecord(from, to, type, null, null,
                new Provenance("llm:claude", ref, LLM_TURN, 0.30));
    }

    private static LocalDate date(String iso) {
        return iso == null ? null : LocalDate.parse(iso);
    }
}
