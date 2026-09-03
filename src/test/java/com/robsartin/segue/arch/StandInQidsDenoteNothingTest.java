package com.robsartin.segue.arch;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every identifier a test invents is one Wikibase's grammar refuses, so it denotes nothing and can
 * never denote anything — ADR 58, issue #171.
 *
 * <p><b>Why this class exists.</b> {@code FixtureQidsDenoteNothingTest} holds the same rule over
 * fifteen reflected constants, and that is its whole scope: the same invented id planted in a test
 * outside {@code Fixture} was measured green on the tree this class was written against. Every
 * other file in {@code src/test} was unguarded, and a contributor adding {@code Q900016} in good
 * faith was making the exact mistake ADR 58 was written about.
 *
 * <p><b>What is scanned.</b> Every file under {@code src/test}, walked and sorted so the failure
 * order is stable. In a {@code .java} file only the text inside a string literal or a text block is
 * read; in every other file the whole text is. Both halves of that rule are load-bearing:
 *
 * <ul>
 *   <li><b>A qid in prose is not an invented identifier.</b> Comments in this tree name real
 *       entities to explain something — what makes a search hit disambiguable, an upstream P31
 *       mistake, the exemplar in {@code qid must look like …} — and this class's own comment names
 *       an invented one to say what it catches. A checker that reds on the documentation of its own
 *       rule invites somebody to weaken the checker. This is the twin of the inline-code span
 *       {@link DocumentationLinksTest} removes before it matches a link.
 *   <li><b>A qid in a resource is an identifier.</b> One hand-inserted row in {@code
 *       src/test/resources/wikidata} carries an invented id, so the recordings are read whole.
 * </ul>
 *
 * <p><b>Why a lexer rather than two regular expressions.</b> Stripping {@code //…} to end of line
 * and then matching quoted runs is shorter, and it reads a Java file it cannot parse: {@code //}
 * inside {@code "http://www.wikidata.org/entity/Q…"} is not a comment, and a text block is a
 * literal whose quotes do not pair the way that matcher assumes. Measured on this tree, the short
 * version silently loses nine sightings across two blind spots — four of them invented ids, one
 * whole file — and a scan that cannot read a construct must not report it as absent. So the source
 * is walked character by character, in the four states Java has.
 *
 * <p><b>What this class cannot see, and nothing like it could.</b> Six sites build a qid at runtime
 * from a bare {@code "Q"} and an integer — {@code "Q" + i} and {@code "Q" + (i + 1)} in {@code
 * domain/PathRankingTest}, {@code "Q" + (700 + i)} and {@code "Q" + (100 + i)} twice in {@code
 * mcp/SegueServiceTest}, and {@code "Q" + next++} in {@code
 * musicbrainz/MusicBrainzSourceAdapterTest} — and they mint allocatable ids no scan over source
 * text can reach. They are fixed by hand under issue #171. A second limit is smaller and worth
 * saying: this file's own allowlist is a literal in {@code src/test}, so the sweep reads it like
 * any other file's, though it matches by id alone — see {@code ALLOWED} — and it covers itself. A
 * third: a site key names the file and whether a sighting sat inside an annotation, and cannot tell
 * a node id from a class id inside a file that already declares the id in code — {@code new
 * NodeRecord("Q5", …)} inside {@code wikidata/KindMapperTest} stays green because {@code Q5} is
 * legitimately in that file already. Only a parser could. A fourth, in the annotation classifier
 * itself: {@code opensAnnotation} walks the raw source backwards from a {@code (} looking for a
 * preceding {@code @Ident}, so a {@code //} comment ending in {@code @Ident} immediately before a
 * code {@code (} would misclassify that paren as opening an annotation — zero instances of that
 * shape exist in this tree today. Every one of these is a limit of the mechanism; a limit stated in
 * the test is a limit, and a limit nobody wrote down is a hole.
 *
 * <p><b>There is one list and no escape hatch.</b> A companion set carried the ids awaiting
 * migration while issue #171 emptied it band by band; it is gone, so an allocatable-form id in
 * {@code src/test} is either a deliberately real entity, allowed at the file and context it appears
 * in with the reason it is real, in {@code ALLOWED} — or a failure. A new test that invents an
 * entity takes the leading-zero form — {@code Q0900100} — which Wikibase's grammar refuses
 * outright.
 */
class StandInQidsDenoteNothingTest {

  private static final Path ROOT = RepositoryTree.root();

  /** The tree this class sweeps. */
  private static final Path TEST_TREE = ROOT.resolve("src/test");

  /** This class's own source, which is a list of identifiers rather than a use of them. */
  private static final Path SELF =
      ROOT.resolve("src/test/java/com/robsartin/segue/arch/StandInQidsDenoteNothingTest.java");

  /** Anything qid-shaped at all, allocatable or not, so the allocatable ones can be picked out. */
  private static final Pattern TOKEN = Pattern.compile("\\bQ\\d+\\b");

  /**
   * Wikibase's item-id grammar, read from the one place this repository already writes it down:
   * {@code FixtureQidsDenoteNothingTest}'s own constant, which quotes WikibaseDataModel {@code
   * src/Entity/ItemId.php}. Read reflectively rather than copied, and rather than widened — the
   * plan for issue #171 leaves that class untouched, and the second copy of a rule is the one a
   * future editor misses.
   */
  private static final Pattern WIKIBASE_ITEM_ID = wikibaseItemIdGrammar();

  /** One place the tree may carry an allowed id. */
  private record Site(String file, Context context) {
    @Override
    public String toString() {
      return context == Context.ANNOTATION ? file + " (in an annotation)" : file + " (in code)";
    }
  }

  /** One deliberately real id: why it is real, and every site allowed to carry it. */
  private record Allowance(String reason, Set<Site> sites) {}

  private static Allowance real(String reason, Site... sites) {
    return new Allowance(reason, Set.of(sites));
  }

  private static Site code(String file) {
    return new Site(file, Context.CODE);
  }

  private static Site annotation(String file) {
    return new Site(file, Context.ANNOTATION);
  }

  private static String relative(Path file) {
    return ROOT.relativize(file).toString();
  }

  /** This file declares ids; every other file has to name the site. */
  private static boolean isAllowed(Sighting sighting) {
    Allowance allowance = ALLOWED.get(sighting.id());
    return allowance != null
        && (sighting.file().equals(SELF)
            || allowance.sites().contains(new Site(relative(sighting.file()), sighting.context())));
  }

  private static String report(Sighting sighting) {
    Allowance allowance = ALLOWED.get(sighting.id());
    return allowance == null
        ? sighting.describe()
        : sighting.describe()
            + "  — allowed, but only at "
            + (allowance.sites().isEmpty()
                ? "(no site at all)"
                : allowance.sites().stream()
                    .map(Site::toString)
                    .sorted()
                    .collect(Collectors.joining(", ")));
  }

  /**
   * Ids that are deliberately real, each with the reason it is. Four kinds, and every entry says
   * which it is: a class id production code maps, an entity a recorded response or a live test is
   * genuinely about, a deliberately allocatable negative control, and a token that is not an
   * identifier at all.
   *
   * <p>A reason, not a bare set: an allowlist without reasons is a list of numbers nobody can
   * review, and naming a real Wikidata entity in a test is meant to be a deliberate act.
   *
   * <p><b>An entry names its sites, and says why.</b> {@code GraphStoreContract} numbers its
   * questions {@code Q1} to {@code Q4} in {@code @DisplayName} <b>and</b> mints node ids in the
   * same file's method bodies — issue #216, measured green on {@code 07d8e2f}: the same id, same
   * file, same shape of use, allowed for one reason and not the other. A site names the file and
   * whether the sighting sat inside an annotation's arguments, so the reason stays checkable at
   * every place it is claimed. Two consequences follow: moving a test file reds twice — once on the
   * new path as an undeclared site, once on the old path as a dead one — and the failure message
   * names both paths; and a new file's first genuine use of a real class id has to be declared here
   * before it is green.
   *
   * <p><b>This file is a declaration site.</b> A sighting inside {@code
   * StandInQidsDenoteNothingTest} itself is allowed once its id is a key at all — no entry names
   * this file. An allocatable id typed into this class that is not an entry still reds, so the
   * class still covers itself, one scope wider than every other file.
   */
  static final Map<String, Allowance> ALLOWED =
      Map.ofEntries(
          entry(
              "Q1",
              real(
                  "not an identifier — the question number in GraphStoreContract's @DisplayName",
                  annotation("src/test/java/com/robsartin/segue/port/GraphStoreContract.java"))),
          entry(
              "Q2",
              real(
                  "not an identifier — the question number in GraphStoreContract's @DisplayName",
                  annotation("src/test/java/com/robsartin/segue/port/GraphStoreContract.java"))),
          entry(
              "Q3",
              real(
                  "not an identifier — the question number in GraphStoreContract's @DisplayName",
                  annotation("src/test/java/com/robsartin/segue/port/GraphStoreContract.java"))),
          entry(
              "Q4",
              real(
                  "not an identifier — the question number in GraphStoreContract's @DisplayName",
                  annotation("src/test/java/com/robsartin/segue/port/GraphStoreContract.java"))),
          entry(
              "Q5",
              real(
                  "class id — mapped by ClassLabels and KindMapper",
                  code(
                      "src/test/java/com/robsartin/segue/app/WikidataMusicBrainzIdentityTest.java"),
                  code("src/test/java/com/robsartin/segue/domain/LoggedAssertionTest.java"),
                  code("src/test/java/com/robsartin/segue/domain/RecordInvariantsTest.java"),
                  code("src/test/java/com/robsartin/segue/export/DotWriterTest.java"),
                  code("src/test/java/com/robsartin/segue/export/ImagemapRecipeTest.java"),
                  code(
                      "src/test/java/com/robsartin/segue/export/StandInAgreesInEveryHomeTest.java"),
                  code("src/test/java/com/robsartin/segue/export/WhatAHoverShowsTest.java"),
                  code("src/test/java/com/robsartin/segue/musicbrainz/BridgedIdentityTest.java"),
                  code(
                      "src/test/java/com/robsartin/segue/musicbrainz/CorroborationAcrossSourcesTest.java"),
                  code(
                      "src/test/java/com/robsartin/segue/musicbrainz/MusicBrainzNeighbourIdentityTest.java"),
                  code(
                      "src/test/java/com/robsartin/segue/musicbrainz/NeighbourFetchCountTest.java"),
                  code("src/test/java/com/robsartin/segue/seed/WikidataFactsTest.java"),
                  code("src/test/java/com/robsartin/segue/sqlite/SqliteAssertionLogTest.java"),
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"),
                  code(
                      "src/test/java/com/robsartin/segue/wikidata/WikidataEntityResolverTest.java"),
                  code("src/test/resources/musicbrainz/probe-fixture.json"),
                  code("src/test/resources/wikidata/bad-seeds-reverse.json"),
                  code("src/test/resources/wikidata/cave-claims.json"),
                  code("src/test/resources/wikidata/gibson-claims.json"),
                  code("src/test/resources/wikidata/hofstetter-claims.json"),
                  code("src/test/resources/wikidata/scalzi-claims.json"))),
          entry(
              "Q42",
              real(
                  "negative control, deliberately allocatable — OwnerClaimTest asserts LocalEntity"
                      + " refuses it",
                  code("src/test/java/com/robsartin/segue/domain/OwnerClaimTest.java"),
                  code("src/test/java/com/robsartin/segue/sqlite/SqliteAssertionLogTest.java"))),
          entry(
              "Q328",
              real(
                  "entity — a real value in the recorded proposition-claims.json",
                  code("src/test/resources/wikidata/proposition-claims.json"))),
          entry(
              "Q515",
              real(
                  "class id — mapped by KindMapper",
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"))),
          entry(
              "Q1064",
              real(
                  "entity — a real value in the recorded proposition-claims.json",
                  code("src/test/resources/wikidata/proposition-claims.json"))),
          entry(
              "Q1299",
              real(
                  "entity — a real value in the recorded proposition-claims.json",
                  code("src/test/java/com/robsartin/segue/wikidata/ClaimMapperTest.java"),
                  code("src/test/resources/wikidata/proposition-claims.json"))),
          entry(
              "Q1860",
              real(
                  "entity — a real value in the recorded proposition-claims.json",
                  code("src/test/resources/wikidata/proposition-claims.json"))),
          entry(
              "Q5593",
              real(
                  "entity — named by LoggedAssertion",
                  code("src/test/java/com/robsartin/segue/domain/CandidateTest.java"),
                  code("src/test/java/com/robsartin/segue/domain/LoggedAssertionTest.java"),
                  code("src/test/java/com/robsartin/segue/domain/RecordInvariantsTest.java"),
                  code("src/test/java/com/robsartin/segue/ingest/IngestServiceTest.java"),
                  code("src/test/java/com/robsartin/segue/sqlite/SqliteAssertionLogTest.java"),
                  code("src/test/java/com/robsartin/segue/wikidata/WikidataClientTest.java"))),
          entry(
              "Q6256",
              real(
                  "class id — mapped by KindMapper",
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"))),
          entry(
              "Q7366",
              real(
                  "class id — mapped by ClassLabels and KindMapper",
                  code("src/test/java/com/robsartin/segue/port/GraphStoreContract.java"),
                  code("src/test/java/com/robsartin/segue/wikidata/ReverseClaimsTest.java"),
                  code("src/test/resources/wikidata/cave-reverse.json"))),
          entry(
              "Q7791",
              real(
                  "entity — a real value in the recorded proposition-claims.json",
                  code("src/test/resources/wikidata/proposition-claims.json"))),
          entry(
              "Q11424",
              real(
                  "class id — mapped by ClassLabels and KindMapper",
                  code("src/test/java/com/robsartin/segue/export/DotWriterTest.java"),
                  code("src/test/java/com/robsartin/segue/export/PaletteSeparationTest.java"),
                  code("src/test/java/com/robsartin/segue/ingest/WikidataIngestEndToEndTest.java"),
                  code("src/test/java/com/robsartin/segue/seed/SeedResolverTest.java"),
                  code("src/test/java/com/robsartin/segue/wikidata/ClaimMapperTest.java"),
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"),
                  code("src/test/java/com/robsartin/segue/wikidata/ReverseClaimsTest.java"),
                  code(
                      "src/test/java/com/robsartin/segue/wikidata/WikidataEntityResolverTest.java"),
                  code("src/test/resources/wikidata/cave-reverse.json"),
                  code("src/test/resources/wikidata/proposition-claims.json"))),
          entry(
              "Q12345",
              real(
                  "not an identifier — the exemplar in Qid.check's \"qid must look like"
                      + " Q12345\" message, quoted back by BridgedIdentityTest's hasMessage",
                  code("src/test/java/com/robsartin/segue/musicbrainz/BridgedIdentityTest.java"))),
          entry(
              "Q15416",
              real(
                  "class id — mapped by ClassLabels and KindMapper",
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"))),
          entry(
              "Q16473",
              real(
                  "entity — named by DotWriter and HoverableSvg",
                  code("src/test/java/com/robsartin/segue/domain/RecordInvariantsTest.java"),
                  code("src/test/java/com/robsartin/segue/sqlite/SqliteAssertionLogTest.java"))),
          entry(
              "Q23444",
              real(
                  "entity — a real value in the recorded proposition-claims.json",
                  code("src/test/resources/wikidata/proposition-claims.json"))),
          entry(
              "Q24862",
              real(
                  "class id — mapped by ClassLabels and KindMapper",
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"))),
          entry(
              "Q36180",
              real(
                  "class id — mapped by Expectations",
                  code("src/test/java/com/robsartin/segue/seed/ExpectationsTest.java"))),
          entry(
              "Q42998",
              real(
                  "class id — mapped by KindMapper",
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"))),
          entry(
              "Q43229",
              real(
                  "class id — mapped by KindMapper and RecognitionInstitutions",
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"),
                  code(
                      "src/test/java/com/robsartin/segue/wikidata/RecognitionInstitutionsTest.java"))),
          entry(
              "Q118066",
              real(
                  "entity — a real value in the recorded bad-seeds-claims.json",
                  code("src/test/resources/wikidata/bad-seeds-claims.json"))),
          entry(
              "Q131186",
              real(
                  "class id — mapped by KindMapper",
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"))),
          entry(
              "Q132241",
              real(
                  "class id — mapped by KindMapper",
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"))),
          entry(
              "Q134556",
              real(
                  "class id — mapped by ClassLabels and KindMapper",
                  code("src/test/java/com/robsartin/segue/export/DotWriterTest.java"),
                  code("src/test/java/com/robsartin/segue/export/PaletteSeparationTest.java"),
                  code("src/test/java/com/robsartin/segue/port/GraphStoreContract.java"),
                  code("src/test/java/com/robsartin/segue/wikidata/ReverseClaimsTest.java"),
                  code("src/test/resources/wikidata/bad-seeds-reverse.json"),
                  code("src/test/resources/wikidata/cave-reverse.json"))),
          entry(
              "Q163740",
              real(
                  "class id — mapped by KindMapper and RecognitionInstitutions",
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"),
                  code(
                      "src/test/java/com/robsartin/segue/wikidata/RecognitionInstitutionsTest.java"))),
          entry(
              "Q166565",
              real(
                  "entity — a real value in the recorded bad-seeds-reverse.json",
                  code("src/test/java/com/robsartin/segue/wikidata/WikidataSourceAdapterTest.java"),
                  code("src/test/resources/wikidata/bad-seeds-reverse.json"))),
          entry(
              "Q177220",
              real(
                  "class id — mapped by Expectations",
                  code("src/test/java/com/robsartin/segue/domain/RecordInvariantsTest.java"),
                  code("src/test/java/com/robsartin/segue/sqlite/SqliteAssertionLogTest.java"))),
          entry(
              "Q178790",
              real(
                  "class id — mapped by KindMapper and RecognitionInstitutions",
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"),
                  code(
                      "src/test/java/com/robsartin/segue/wikidata/RecognitionInstitutionsTest.java"))),
          entry(
              "Q180337",
              real(
                  "entity — a real value in two recorded Wikidata responses",
                  code("src/test/java/com/robsartin/segue/ingest/WikidataIngestEndToEndTest.java"),
                  code("src/test/java/com/robsartin/segue/mcp/ToolResultsTest.java"),
                  code("src/test/java/com/robsartin/segue/wikidata/ClaimMapperTest.java"),
                  code("src/test/java/com/robsartin/segue/wikidata/ReverseClaimsTest.java"),
                  code(
                      "src/test/java/com/robsartin/segue/wikidata/WikidataEntityResolverTest.java"),
                  code("src/test/java/com/robsartin/segue/wikidata/WikidataLiveSmokeTest.java"),
                  code("src/test/java/com/robsartin/segue/wikidata/WikidataSourceAdapterTest.java"),
                  code("src/test/resources/wikidata/cave-reverse.json"),
                  code("src/test/resources/wikidata/proposition-claims.json"))),
          entry(
              "Q182832",
              real(
                  "class id — mapped by ClassLabels and KindMapper",
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"))),
          entry(
              "Q188987",
              real(
                  "entity — a real value in the recorded gibson-claims.json",
                  code("src/test/java/com/robsartin/segue/mcp/SharedAwardRouteLiveTest.java"),
                  code("src/test/java/com/robsartin/segue/mcp/SharedAwardRouteTest.java"),
                  code("src/test/resources/wikidata/gibson-claims.json"))),
          entry(
              "Q192668",
              real(
                  "entity — named by EntityTools",
                  code("src/test/java/com/robsartin/segue/app/SegueConfigurationTest.java"),
                  code("src/test/java/com/robsartin/segue/mcp/PersonSeededRouteLiveTest.java"),
                  code("src/test/java/com/robsartin/segue/mcp/ToolResultsTest.java"),
                  code("src/test/java/com/robsartin/segue/wikidata/ReverseClaimsTest.java"),
                  code(
                      "src/test/java/com/robsartin/segue/wikidata/WikidataEntityResolverTest.java"),
                  code("src/test/java/com/robsartin/segue/wikidata/WikidataLiveSmokeTest.java"),
                  code("src/test/java/com/robsartin/segue/wikidata/WikidataSourceAdapterTest.java"),
                  code("src/test/resources/wikidata/bad-seeds-claims.json"),
                  code("src/test/resources/wikidata/bad-seeds-reverse.json"),
                  code("src/test/resources/wikidata/cave-claims.json"),
                  code("src/test/resources/wikidata/proposition-claims.json"),
                  code("src/test/resources/wikidata/search-cave.json"))),
          entry(
              "Q193977",
              real(
                  "class id — mapped by ClassLabels and KindMapper",
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"))),
          entry(
              "Q202866",
              real(
                  "class id — mapped by ClassLabels and KindMapper",
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"))),
          entry(
              "Q207338",
              real(
                  "class id — mapped by KindMapper",
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"))),
          entry(
              "Q215380",
              real(
                  "class id — mapped by ClassLabels and KindMapper",
                  code(
                      "src/test/java/com/robsartin/segue/app/WikidataMusicBrainzIdentityTest.java"),
                  code("src/test/java/com/robsartin/segue/export/ImagemapRecipeTest.java"),
                  code("src/test/java/com/robsartin/segue/export/WhatAHoverShowsTest.java"),
                  code("src/test/java/com/robsartin/segue/musicbrainz/BridgedIdentityTest.java"),
                  code("src/test/java/com/robsartin/segue/seed/SeedResolverTest.java"),
                  code("src/test/java/com/robsartin/segue/seed/SeedRunTest.java"),
                  code("src/test/java/com/robsartin/segue/seed/WikidataFactsTest.java"),
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"),
                  code(
                      "src/test/java/com/robsartin/segue/wikidata/RecognitionInstitutionsTest.java"),
                  code("src/test/resources/musicbrainz/probe-fixture.json"),
                  code("src/test/resources/wikidata/bad-seeds-claims.json"),
                  code("src/test/resources/wikidata/cave-reverse.json"))),
          entry(
              "Q233046",
              real(
                  "entity — a real value in the recorded proposition-claims.json",
                  code("src/test/resources/wikidata/proposition-claims.json"))),
          entry(
              "Q245068",
              real(
                  "class id — mapped by Expectations",
                  code("src/test/java/com/robsartin/segue/seed/ExpectationsTest.java"))),
          entry(
              "Q255032",
              real(
                  "entity — a real value in three recorded Wikidata responses",
                  code("src/test/java/com/robsartin/segue/mcp/SharedAwardRouteTest.java"),
                  code("src/test/resources/wikidata/gibson-claims.json"),
                  code("src/test/resources/wikidata/hugo-best-novel.json"),
                  code("src/test/resources/wikidata/scalzi-claims.json"))),
          entry(
              "Q277308",
              real(
                  "entity — a real value in the recorded scalzi-claims.json",
                  code("src/test/java/com/robsartin/segue/mcp/SharedAwardRouteLiveTest.java"),
                  code("src/test/java/com/robsartin/segue/mcp/SharedAwardRouteTest.java"),
                  code("src/test/resources/wikidata/scalzi-claims.json"))),
          entry(
              "Q316528",
              real(
                  "entity — a real value in the recorded bad-seeds-reverse.json",
                  code("src/test/java/com/robsartin/segue/wikidata/WikidataSourceAdapterTest.java"),
                  code("src/test/resources/wikidata/bad-seeds-reverse.json"))),
          entry(
              "Q378427",
              real(
                  "class id — mapped by ClassLabels",
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"),
                  code("src/test/resources/wikidata/hugo-best-novel.json"))),
          entry(
              "Q383784",
              real(
                  "entity — a real value in the two recorded bad-seeds responses",
                  code("src/test/java/com/robsartin/segue/wikidata/WikidataSourceAdapterTest.java"),
                  code("src/test/resources/wikidata/bad-seeds-claims.json"),
                  code("src/test/resources/wikidata/bad-seeds-reverse.json"))),
          entry(
              "Q414147",
              real(
                  "class id — mapped by KindMapper and RecognitionInstitutions",
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"),
                  code(
                      "src/test/java/com/robsartin/segue/wikidata/RecognitionInstitutionsTest.java"))),
          entry(
              "Q482994",
              real(
                  "class id — mapped by ClassLabels and KindMapper",
                  code("src/test/java/com/robsartin/segue/export/DotWriterTest.java"),
                  code("src/test/java/com/robsartin/segue/export/GraphMlWriterTest.java"),
                  code("src/test/java/com/robsartin/segue/export/PaletteSeparationTest.java"),
                  code("src/test/java/com/robsartin/segue/export/ViewSelectorTest.java"),
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"))),
          entry(
              "Q486688",
              real(
                  "entity — WikidataLiveSmokeTest asks the real API about it",
                  code("src/test/java/com/robsartin/segue/wikidata/WikidataLiveSmokeTest.java"))),
          entry(
              "Q506240",
              real(
                  "class id — mapped by ClassLabels and KindMapper",
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"))),
          entry(
              "Q552814",
              real(
                  "entity — a real value in the recorded proposition-claims.json",
                  code("src/test/java/com/robsartin/segue/mcp/PersonSeededRouteLiveTest.java"),
                  code("src/test/java/com/robsartin/segue/wikidata/ClaimMapperTest.java"),
                  code("src/test/resources/wikidata/proposition-claims.json"))),
          entry(
              "Q618779",
              real(
                  "class id — mapped by ClassLabels and RecognitionInstitutions",
                  code("src/test/java/com/robsartin/segue/export/DotWriterTest.java"),
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"),
                  code(
                      "src/test/java/com/robsartin/segue/wikidata/RecognitionInstitutionsTest.java"))),
          entry(
              "Q639669",
              real(
                  "class id — mapped by Expectations",
                  code("src/test/java/com/robsartin/segue/seed/AdjudicatorTest.java"),
                  code("src/test/java/com/robsartin/segue/seed/ExpectationsTest.java"),
                  code("src/test/java/com/robsartin/segue/seed/WikidataFactsTest.java"))),
          entry(
              "Q748019",
              real(
                  "class id — mapped by RecognitionInstitutions",
                  code(
                      "src/test/java/com/robsartin/segue/wikidata/RecognitionInstitutionsTest.java"))),
          entry(
              "Q809003",
              real(
                  "entity — a real value in the two recorded bad-seeds responses",
                  code("src/test/java/com/robsartin/segue/wikidata/WikidataSourceAdapterTest.java"),
                  code("src/test/resources/wikidata/bad-seeds-claims.json"),
                  code("src/test/resources/wikidata/bad-seeds-reverse.json"))),
          entry(
              "Q829080",
              real(
                  "class id — mapped by RecognitionInstitutions",
                  code(
                      "src/test/java/com/robsartin/segue/wikidata/RecognitionInstitutionsTest.java"))),
          entry(
              "Q855091",
              real(
                  "class id — mapped by Expectations",
                  code("src/test/java/com/robsartin/segue/seed/WikidataFactsTest.java"))),
          entry(
              "Q937857",
              real(
                  "class id — AdjudicatorTest's and ExpectationsTest's FOOTBALLER, the occupation the"
                      + " musician expectation must reject",
                  code("src/test/java/com/robsartin/segue/seed/AdjudicatorTest.java"),
                  code("src/test/java/com/robsartin/segue/seed/ExpectationsTest.java"))),
          entry(
              "Q955824",
              real(
                  "class id — mapped by RecognitionInstitutions",
                  code("src/test/java/com/robsartin/segue/export/ViewSelectorTest.java"),
                  code("src/test/java/com/robsartin/segue/mcp/SegueServiceTest.java"),
                  code(
                      "src/test/java/com/robsartin/segue/wikidata/RecognitionInstitutionsTest.java"))),
          entry(
              "Q1046088",
              real(
                  "class id — mapped by RecognitionInstitutions",
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"),
                  code(
                      "src/test/java/com/robsartin/segue/wikidata/RecognitionInstitutionsTest.java"))),
          entry(
              "Q1051182",
              real(
                  "entity — named by EdgeTypes",
                  code("src/test/java/com/robsartin/segue/wikidata/ReverseClaimsTest.java"),
                  code("src/test/java/com/robsartin/segue/wikidata/WikidataLiveSmokeTest.java"),
                  code("src/test/java/com/robsartin/segue/wikidata/WikidataSourceAdapterTest.java"),
                  code("src/test/resources/wikidata/bad-seeds-claims.json"),
                  code("src/test/resources/wikidata/cave-claims.json"),
                  code("src/test/resources/wikidata/cave-reverse.json"))),
          entry(
              "Q1147045",
              real(
                  "entity — a real value in the recorded cave-claims.json",
                  code("src/test/resources/wikidata/cave-claims.json"))),
          entry(
              "Q1259759",
              real(
                  "class id — mapped by ClassLabels and KindMapper",
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"))),
          entry(
              "Q1261214",
              real(
                  "class id — mapped by ClassLabels and KindMapper",
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"))),
          entry(
              "Q1421784",
              real(
                  "entity — a real value in the recorded artist-with-url-relations.json",
                  code("src/test/resources/musicbrainz/artist-with-url-relations.json"))),
          entry(
              "Q1535279",
              real(
                  "entity — a real value in the recorded cave-claims.json",
                  code("src/test/resources/wikidata/cave-claims.json"))),
          entry(
              "Q1538570",
              real(
                  "class id — mapped by KindMapper",
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"))),
          entry(
              "Q1656682",
              real(
                  "class id — mapped by KindMapper",
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"))),
          entry(
              "Q1738793",
              real(
                  "entity — WikidataLiveSmokeTest asks the real API about it",
                  code("src/test/java/com/robsartin/segue/wikidata/WikidataLiveSmokeTest.java"))),
          entry(
              "Q2085381",
              real(
                  "class id — mapped by RecognitionInstitutions",
                  code(
                      "src/test/java/com/robsartin/segue/wikidata/RecognitionInstitutionsTest.java"))),
          entry(
              "Q2268818",
              real(
                  "entity — a real value in the recorded bad-seeds-claims.json",
                  code("src/test/resources/wikidata/bad-seeds-claims.json"))),
          entry(
              "Q2526255",
              real(
                  "class id — mapped by Expectations",
                  code("src/test/java/com/robsartin/segue/wikidata/ReverseClaimsTest.java"))),
          entry(
              "Q2715462",
              real(
                  "entity — a real value in the recorded cave-reverse.json",
                  code("src/test/java/com/robsartin/segue/wikidata/ReverseClaimsTest.java"),
                  code("src/test/resources/wikidata/cave-reverse.json"))),
          entry(
              "Q2996499",
              real(
                  "entity — a real value in the recorded bad-seeds-claims.json",
                  code("src/test/resources/wikidata/bad-seeds-claims.json"))),
          entry(
              "Q3129816",
              real(
                  "entity — a real value in the recorded bad-seeds-claims.json",
                  code("src/test/resources/wikidata/bad-seeds-claims.json"))),
          entry(
              "Q3331189",
              real(
                  "class id — mapped by ClassLabels",
                  code("src/test/resources/wikidata/cave-reverse.json"))),
          entry(
              "Q4649799",
              real(
                  "entity — a real value in the recorded proposition-claims.json",
                  code("src/test/java/com/robsartin/segue/wikidata/ClaimMapperTest.java"),
                  code("src/test/resources/wikidata/proposition-claims.json"))),
          entry(
              "Q5398426",
              real(
                  "class id — mapped by ClassLabels and KindMapper",
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"))),
          entry(
              "Q5741069",
              real(
                  "class id — mapped by ClassLabels and KindMapper",
                  code("src/test/java/com/robsartin/segue/export/LogProjectionTest.java"),
                  code("src/test/java/com/robsartin/segue/export/ViewSelectorTest.java"),
                  code("src/test/java/com/robsartin/segue/mcp/SegueServiceTest.java"),
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"),
                  code(
                      "src/test/java/com/robsartin/segue/wikidata/RecognitionInstitutionsTest.java"))),
          entry(
              "Q6013406",
              real(
                  "entity — a real value in the recorded search-cave.json",
                  code("src/test/resources/wikidata/search-cave.json"))),
          entry(
              "Q6301911",
              real(
                  "entity — a real value in the recorded cave-reverse.json",
                  code("src/test/java/com/robsartin/segue/wikidata/ReverseClaimsTest.java"),
                  code("src/test/resources/wikidata/cave-reverse.json"))),
          entry(
              "Q6650163",
              real(
                  "entity — WikidataLiveSmokeTest asks the real API about it",
                  code("src/test/java/com/robsartin/segue/wikidata/WikidataLiveSmokeTest.java"))),
          entry(
              "Q6774606",
              real(
                  "entity — SharedAwardRouteLiveTest asks the real API about it",
                  code("src/test/java/com/robsartin/segue/mcp/SharedAwardRouteLiveTest.java"))),
          entry(
              "Q7558495",
              real(
                  "class id — a solo-act class leavesTheBandsAlone asserts is never a recognition"
                      + " institution",
                  code(
                      "src/test/java/com/robsartin/segue/wikidata/RecognitionInstitutionsTest.java"))),
          entry(
              "Q7612859",
              real(
                  "entity — a real value in the recorded hofstetter-claims.json",
                  code("src/test/java/com/robsartin/segue/mcp/SharedAwardRouteLiveTest.java"),
                  code("src/test/java/com/robsartin/segue/mcp/SharedAwardRouteTest.java"),
                  code("src/test/resources/wikidata/hofstetter-claims.json"))),
          entry(
              "Q7725634",
              real(
                  "class id — mapped by ClassLabels and KindMapper",
                  code("src/test/java/com/robsartin/segue/export/DotWriterTest.java"),
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"),
                  code("src/test/java/com/robsartin/segue/wikidata/ReverseClaimsTest.java"),
                  code("src/test/resources/wikidata/cave-reverse.json"))),
          entry(
              "Q9212979",
              real(
                  "class id — mapped by KindMapper",
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"))),
          entry(
              "Q9357859",
              real(
                  "entity — a real value in the recorded cave-claims.json",
                  code("src/test/resources/wikidata/cave-claims.json"))),
          entry(
              "Q10590726",
              real(
                  "class id — mapped by ClassLabels and KindMapper",
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"))),
          entry(
              "Q10800557",
              real(
                  "class id — mapped by Expectations",
                  code("src/test/java/com/robsartin/segue/seed/ExpectationsTest.java"))),
          entry(
              "Q11448906",
              real(
                  "class id — mapped by ClassLabels and RecognitionInstitutions",
                  code(
                      "src/test/java/com/robsartin/segue/wikidata/RecognitionInstitutionsTest.java"))),
          entry(
              "Q12057459",
              real(
                  "class id — mapped by RecognitionInstitutions",
                  code(
                      "src/test/java/com/robsartin/segue/wikidata/RecognitionInstitutionsTest.java"))),
          entry(
              "Q13473501",
              real(
                  "class id — mapped by KindMapper",
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"))),
          entry(
              "Q16334295",
              real(
                  "class id — mapped by KindMapper",
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"))),
          entry(
              "Q18510489",
              real(
                  "class id — mapped by KindMapper",
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"),
                  code(
                      "src/test/java/com/robsartin/segue/wikidata/RecognitionInstitutionsTest.java"))),
          entry(
              "Q19314966",
              real(
                  "class id — a comedy class leavesTheBandsAlone asserts is never a recognition"
                      + " institution",
                  code(
                      "src/test/java/com/robsartin/segue/wikidata/RecognitionInstitutionsTest.java"))),
          entry(
              "Q19351429",
              real(
                  "class id — mapped by KindMapper",
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"))),
          entry(
              "Q19863965",
              real(
                  "entity — a real value in the recorded search-cave.json",
                  code("src/test/resources/wikidata/search-cave.json"))),
          entry(
              "Q21191270",
              real(
                  "class id — mapped by ClassLabels and KindMapper",
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"))),
          entry(
              "Q23418635",
              real(
                  "entity — a real value in the recorded bad-seeds-claims.json",
                  code("src/test/resources/wikidata/bad-seeds-claims.json"))),
          entry(
              "Q45400320",
              real(
                  "class id — mapped by RecognitionInstitutions",
                  code(
                      "src/test/java/com/robsartin/segue/wikidata/RecognitionInstitutionsTest.java"))),
          entry(
              "Q55850593",
              real(
                  "class id — mapped by ClassLabels and KindMapper",
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"))),
          entry(
              "Q56816954",
              real(
                  "class id — mapped by KindMapper",
                  code("src/test/java/com/robsartin/segue/ingest/GraphProjectorTest.java"),
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"),
                  code(
                      "src/test/java/com/robsartin/segue/wikidata/RecognitionInstitutionsTest.java"))),
          entry(
              "Q58483083",
              real(
                  "class id — mapped by ClassLabels and KindMapper",
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"))),
          entry(
              "Q96888669",
              real(
                  "class id — mapped by RecognitionInstitutions",
                  code(
                      "src/test/java/com/robsartin/segue/wikidata/RecognitionInstitutionsTest.java"))),
          entry(
              "Q97798779",
              real(
                  "entity — a real value in the recorded cave-reverse.json",
                  code("src/test/java/com/robsartin/segue/wikidata/ReverseClaimsTest.java"),
                  code("src/test/resources/wikidata/cave-reverse.json"))),
          entry(
              "Q105543609",
              real(
                  "class id — mapped by ClassLabels and KindMapper",
                  code("src/test/java/com/robsartin/segue/export/DotWriterTest.java"),
                  code("src/test/java/com/robsartin/segue/export/GraphMlWriterTest.java"),
                  code("src/test/java/com/robsartin/segue/export/PaletteSeparationTest.java"),
                  code("src/test/java/com/robsartin/segue/export/ViewSelectorTest.java"),
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"))),
          entry(
              "Q110039749",
              real(
                  "class id — mapped by ClassLabels and KindMapper",
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"))),
          entry(
              "Q121998451",
              real(
                  "entity — named by ReverseClaims",
                  code("src/test/java/com/robsartin/segue/wikidata/ReverseClaimsTest.java"),
                  code("src/test/resources/wikidata/cave-reverse.json"))),
          entry(
              "Q127334927",
              real(
                  "class id — a band class leavesTheBandsAlone asserts is never a recognition"
                      + " institution",
                  code(
                      "src/test/java/com/robsartin/segue/wikidata/RecognitionInstitutionsTest.java"))),
          entry(
              "Q131806449",
              real(
                  "entity — WikidataLiveSmokeTest asks the real API about it",
                  code("src/test/java/com/robsartin/segue/wikidata/WikidataLiveSmokeTest.java"))),
          entry(
              "Q1000000000",
              real(
                  "negative control, deliberately allocatable — QidTest asserts Wikibase's grammar"
                      + " still admits ten digits, which is the upper bound that test pins",
                  code("src/test/java/com/robsartin/segue/domain/QidTest.java"))));

  /** One allocatable-form id, and where it was found. */
  private record Sighting(Path file, int line, String id, Context context) {
    String describe() {
      return context == Context.ANNOTATION
          ? "%s:%d  %s (in an annotation)".formatted(relative(file), line, id)
          : "%s:%d  %s".formatted(relative(file), line, id);
    }
  }

  /** One pass over the tree: the files it read, and every allocatable-form id in them. */
  private record Sweep(List<Path> files, List<Sighting> sightings) {}

  private static final Sweep SWEEP = sweep(TEST_TREE);

  @Test
  @DisplayName("every identifier a test invents is one Wikidata can never allocate")
  void shouldUseAnIdWikidataCannotAllocateWhenATestNamesAnEntityItInvented() {
    List<Sighting> offending = SWEEP.sightings().stream().filter(s -> !isAllowed(s)).toList();
    String report =
        offending.stream()
            .map(StandInQidsDenoteNothingTest::report)
            .collect(Collectors.joining("\n"));

    assertThat(report)
        .as(
            "%d identifiers in src/test that Wikidata could allocate, out of %d allocatable-form"
                + " sightings in %d files. Every one of them denotes a real entity, or will as"
                + " soon as the counter reaches it. Give the entity an id the grammar refuses,"
                + " by prepending one zero to the id the test uses today - the Q0900100 shape,"
                + " which LocalEntity's javadoc already predicts. A merge's canonical side is the"
                + " one place that shape is refused, and it takes ADR 62's eleven-digit shape"
                + " instead; see Qid.checkCanonicalSide. Or, if the test is genuinely about a real"
                + " entity, add it to ALLOWED with the reason it is real - or, if the id is already"
                + " allowed elsewhere, add this file to its sites - an id is allowed at the sites"
                + " its entry names and nowhere else",
            offending.size(), SWEEP.sightings().size(), SWEEP.files().size())
        .isEmpty();
  }

  @Test
  @DisplayName("the sweep actually read the test tree, so a green is not an empty sweep")
  void shouldHaveReadTheTreeWhenTheSweepReportsNoOffendingId() {
    assertThat(SWEEP.files())
        .as("files read under %s - zero means the walk stopped working", ROOT.relativize(TEST_TREE))
        .isNotEmpty();
    assertThat(SWEEP.sightings())
        .as(
            "allocatable-form ids seen anywhere under %s - zero means the matcher stopped working,"
                + " and the deliberately real ids in ALLOWED guarantee this stays non-empty",
            ROOT.relativize(TEST_TREE))
        .isNotEmpty();
    assertThat(SWEEP.files())
        .as(
            "this class's own source among the files the sweep read. It is named by an absolute"
                + " path, and isAllowed's file().equals(SELF) check is what keeps this file's own"
                + " ALLOWED literals from being reported as offending sightings. A moved or renamed"
                + " SELF reds two tests: this one, directly, because the swept files would no longer"
                + " contain the stale path - and the offending-id test, because that check stops"
                + " firing for this file's own literals. Read this one first")
        .contains(SELF);
  }

  /** One id claimed at one site — never at all, when {@link #site} is {@code null}. */
  private record Claim(String id, Site site) {
    @Override
    public String toString() {
      return site == null ? id + " @ (no site at all)" : id + " @ " + site;
    }
  }

  @Test
  @DisplayName("the allowlist names no site the tree no longer carries the id at")
  void shouldCarryNoDeadSiteWhenTheAllowlistIsCheckedAgainstTheTree() {
    Set<Claim> live =
        SWEEP.sightings().stream()
            .filter(s -> !s.file().equals(SELF))
            .map(s -> new Claim(s.id(), new Site(relative(s.file()), s.context())))
            .collect(Collectors.toCollection(LinkedHashSet::new));

    List<String> dead =
        ALLOWED.entrySet().stream()
            .flatMap(
                e ->
                    e.getValue().sites().isEmpty()
                        ? Stream.of(new Claim(e.getKey(), null))
                        : e.getValue().sites().stream().map(s -> new Claim(e.getKey(), s)))
            .filter(claim -> !live.contains(claim))
            .map(Claim::toString)
            .sorted()
            .toList();

    assertThat(dead)
        .as(
            "sites named by ALLOWED that no longer carry the id they were written about, in the"
                + " context they were written for. A reason that outlives its site is a reason"
                + " nobody can check; delete the site, or the entry with its last one")
        .isEmpty();
  }

  @Test
  @DisplayName("a literal inside an annotation is read apart from one in code")
  void shouldReadALiteralAsAnAnnotationWhenItSitsInsideAnAnnotationsArguments() {
    String source =
        """
        class Probe {
          @DisplayName("Q1: a question number")
          void answers() {
            store.upsertNode(new NodeRecord("Q1", PERSON, "a node id"));
          }
        }
        """;

    assertThat(
            literals(source).stream().filter(l -> l.text().startsWith("Q1")).map(Literal::context))
        .as("the question number is an annotation's argument; the node id is code")
        .containsExactly(Context.ANNOTATION, Context.CODE);
  }

  // --- the sweep ------------------------------------------------------------------------------

  private static Sweep sweep(Path root) {
    List<Path> files;
    try (Stream<Path> tree = Files.walk(root)) {
      files = tree.filter(Files::isRegularFile).sorted().toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    List<Sighting> sightings = new ArrayList<>();
    for (Path file : files) {
      String text = RepositoryTree.read(file);
      if (file.getFileName().toString().endsWith(".java")) {
        for (Literal literal : literals(text)) {
          collect(text, literal.start(), literal.text(), literal.context(), file, sightings);
        }
      } else {
        collect(text, 0, text, Context.CODE, file, sightings);
      }
    }
    return new Sweep(files, List.copyOf(sightings));
  }

  /**
   * Every allocatable-form token in {@code scanned}, which begins at {@code offset} in {@code
   * text}.
   */
  private static void collect(
      String text, int offset, String scanned, Context context, Path file, List<Sighting> into) {
    Matcher token = TOKEN.matcher(scanned);
    while (token.find()) {
      String id = token.group();
      if (WIKIBASE_ITEM_ID.matcher(id).matches()) {
        into.add(new Sighting(file, lineOf(text, offset + token.start()), id, context));
      }
    }
  }

  /** Where a sighting sat. An allowlist entry names this, so one reason cannot cover both. */
  private enum Context {
    /**
     * A string literal or text block in Java code — and, in a non-Java file, the file's own text.
     */
    CODE,
    /** A string literal inside an annotation's arguments, where a {@code @DisplayName} lives. */
    ANNOTATION
  }

  /** One string literal or text block, and where its text begins in the file. */
  private record Literal(int start, String text, Context context) {}

  /**
   * Every string literal and text block in a Java source, with comments, character literals and
   * escapes handled where they change the answer. Four states and no regular expression, for the
   * reason the class comment gives.
   */
  private static List<Literal> literals(String source) {
    List<Literal> literals = new ArrayList<>();
    Deque<Boolean> parens = new ArrayDeque<>();
    int end = source.length();
    int at = 0;
    while (at < end) {
      char c = source.charAt(at);
      if (c == '/' && at + 1 < end && source.charAt(at + 1) == '/') {
        int newline = source.indexOf('\n', at);
        at = newline < 0 ? end : newline;
      } else if (c == '/' && at + 1 < end && source.charAt(at + 1) == '*') {
        int close = source.indexOf("*/", at + 2);
        at = close < 0 ? end : close + 2;
      } else if (source.startsWith("\"\"\"", at)) {
        int body = at + 3;
        int close = body;
        while (close < end && !source.startsWith("\"\"\"", close)) {
          close += source.charAt(close) == '\\' ? 2 : 1;
        }
        literals.add(
            new Literal(body, source.substring(body, Math.min(close, end)), contextOf(parens)));
        at = Math.min(close + 3, end);
      } else if (c == '"' || c == '\'') {
        int body = at + 1;
        int close = body;
        while (close < end && source.charAt(close) != c && source.charAt(close) != '\n') {
          close += source.charAt(close) == '\\' ? 2 : 1;
        }
        if (c == '"') {
          literals.add(
              new Literal(body, source.substring(body, Math.min(close, end)), contextOf(parens)));
        }
        at = Math.min(close + 1, end);
      } else {
        if (c == '(') {
          parens.push(opensAnnotation(source, at));
        } else if (c == ')' && !parens.isEmpty()) {
          parens.pop();
        }
        at++;
      }
    }
    return literals;
  }

  /** A literal sits inside an annotation's arguments exactly when the open-paren stack says so. */
  private static Context contextOf(Deque<Boolean> parens) {
    return parens.contains(Boolean.TRUE) ? Context.ANNOTATION : Context.CODE;
  }

  /** Whether this {@code (} closes an {@code @Ident}, which is what starts an annotation's args. */
  private static boolean opensAnnotation(String source, int paren) {
    int at = paren - 1;
    while (at >= 0 && Character.isWhitespace(source.charAt(at))) {
      at--;
    }
    while (at >= 0
        && (Character.isJavaIdentifierPart(source.charAt(at)) || source.charAt(at) == '.')) {
      at--;
    }
    return at >= 0 && source.charAt(at) == '@';
  }

  private static int lineOf(String text, int index) {
    int line = 1;
    for (int at = 0; at < index; at++) {
      if (text.charAt(at) == '\n') {
        line++;
      }
    }
    return line;
  }

  private static Pattern wikibaseItemIdGrammar() {
    String owner = "com.robsartin.segue.fixture.FixtureQidsDenoteNothingTest";
    try {
      Field field = Class.forName(owner).getDeclaredField("WIKIBASE_ITEM_ID");
      field.setAccessible(true);
      Object grammar = field.get(null);
      if (!(grammar instanceof Pattern pattern)) {
        throw new AssertionError(
            owner
                + ".WIKIBASE_ITEM_ID is no longer a java.util.regex.Pattern but a "
                + (grammar == null ? "null" : grammar.getClass().getName())
                + ". This class reads Wikibase's item-id grammar from that field and matches"
                + " tokens against it, so it must stay a compiled Pattern");
      }
      return pattern;
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(
          owner
              + " no longer declares WIKIBASE_ITEM_ID. This class reads Wikibase's item-id grammar"
              + " from there so that the repository spells it exactly once - restore the constant,"
              + " or move it somewhere both classes can name",
          e);
    }
  }
}
