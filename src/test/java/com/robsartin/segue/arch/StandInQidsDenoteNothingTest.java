package com.robsartin.segue.arch;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * saying: this file's own two lists are literals in {@code src/test}, so the sweep reads them like
 * any other file's and they cover themselves. Both are limits of the mechanism; a limit stated in
 * the test is a limit, and a limit nobody wrote down is a hole.
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

  /**
   * Ids that are deliberately real, each with the reason it is. Four kinds, and every entry says
   * which it is: a class id production code maps, an entity a recorded response or a live test is
   * genuinely about, a deliberately allocatable negative control, and a token that is not an
   * identifier at all.
   *
   * <p>A reason, not a bare set: an allowlist without reasons is a list of numbers nobody can
   * review, and naming a real Wikidata entity in a test is meant to be a deliberate act.
   *
   * <p><b>An id is allowed or excluded whole, and one group straddles that.</b> {@code
   * GraphStoreContract} numbers its questions {@code Q1} to {@code Q4} in {@code @DisplayName}.
   * Every node-id use of {@code Q1}, {@code Q2} and {@code Q3} elsewhere in the tree has taken the
   * leading-zero form — band H, issue #171 — so all four question numbers are here now, sharing one
   * reason: the question numbers stay.
   */
  static final Map<String, String> ALLOWED =
      Map.ofEntries(
          entry(
              "Q1", "not an identifier — the question number in GraphStoreContract's @DisplayName"),
          entry(
              "Q2", "not an identifier — the question number in GraphStoreContract's @DisplayName"),
          entry(
              "Q3", "not an identifier — the question number in GraphStoreContract's @DisplayName"),
          entry(
              "Q4", "not an identifier — the question number in GraphStoreContract's @DisplayName"),
          entry("Q5", "class id — mapped by ClassLabels and KindMapper"),
          entry(
              "Q42",
              "negative control, deliberately allocatable — OwnerClaimTest asserts LocalEntity"
                  + " refuses it, and it stands as a merge's canonical side"),
          entry("Q328", "entity — a real value in the recorded proposition-claims.json"),
          entry("Q515", "class id — mapped by KindMapper"),
          entry("Q1064", "entity — a real value in the recorded proposition-claims.json"),
          entry("Q1299", "entity — a real value in the recorded proposition-claims.json"),
          entry("Q1860", "entity — a real value in the recorded proposition-claims.json"),
          entry("Q5593", "entity — named by LoggedAssertion"),
          entry("Q6256", "class id — mapped by KindMapper"),
          entry("Q7366", "class id — mapped by ClassLabels and KindMapper"),
          entry("Q7791", "entity — a real value in the recorded proposition-claims.json"),
          entry("Q11424", "class id — mapped by ClassLabels and KindMapper"),
          entry("Q15416", "class id — mapped by ClassLabels and KindMapper"),
          entry("Q16473", "entity — named by DotWriter and HoverableSvg"),
          entry("Q23444", "entity — a real value in the recorded proposition-claims.json"),
          entry("Q24862", "class id — mapped by ClassLabels and KindMapper"),
          entry("Q36180", "class id — mapped by Expectations"),
          entry("Q42998", "class id — mapped by KindMapper"),
          entry("Q43229", "class id — mapped by KindMapper and RecognitionInstitutions"),
          entry("Q118066", "entity — a real value in the recorded bad-seeds-claims.json"),
          entry("Q131186", "class id — mapped by KindMapper"),
          entry("Q132241", "class id — mapped by KindMapper"),
          entry("Q134556", "class id — mapped by ClassLabels and KindMapper"),
          entry("Q163740", "class id — mapped by KindMapper and RecognitionInstitutions"),
          entry("Q166565", "entity — a real value in the recorded bad-seeds-reverse.json"),
          entry("Q177220", "class id — mapped by Expectations"),
          entry("Q178790", "class id — mapped by KindMapper and RecognitionInstitutions"),
          entry("Q180337", "entity — a real value in two recorded Wikidata responses"),
          entry("Q182832", "class id — mapped by ClassLabels and KindMapper"),
          entry("Q188987", "entity — a real value in the recorded gibson-claims.json"),
          entry("Q192668", "entity — named by EntityTools"),
          entry("Q193977", "class id — mapped by ClassLabels and KindMapper"),
          entry("Q202866", "class id — mapped by ClassLabels and KindMapper"),
          entry("Q207338", "class id — mapped by KindMapper"),
          entry("Q215380", "class id — mapped by ClassLabels and KindMapper"),
          entry("Q233046", "entity — a real value in the recorded proposition-claims.json"),
          entry("Q245068", "class id — mapped by Expectations"),
          entry("Q255032", "entity — a real value in three recorded Wikidata responses"),
          entry("Q277308", "entity — a real value in the recorded scalzi-claims.json"),
          entry("Q316528", "entity — a real value in the recorded bad-seeds-reverse.json"),
          entry("Q378427", "class id — mapped by ClassLabels"),
          entry("Q383784", "entity — a real value in the two recorded bad-seeds responses"),
          entry("Q414147", "class id — mapped by KindMapper and RecognitionInstitutions"),
          entry("Q482994", "class id — mapped by ClassLabels and KindMapper"),
          entry("Q486688", "entity — WikidataLiveSmokeTest asks the real API about it"),
          entry("Q506240", "class id — mapped by ClassLabels and KindMapper"),
          entry("Q552814", "entity — a real value in the recorded proposition-claims.json"),
          entry("Q618779", "class id — mapped by ClassLabels and RecognitionInstitutions"),
          entry("Q639669", "class id — mapped by Expectations"),
          entry("Q748019", "class id — mapped by RecognitionInstitutions"),
          entry("Q809003", "entity — a real value in the two recorded bad-seeds responses"),
          entry("Q829080", "class id — mapped by RecognitionInstitutions"),
          entry("Q855091", "class id — mapped by Expectations"),
          entry(
              "Q937857",
              "class id — AdjudicatorTest's and ExpectationsTest's FOOTBALLER, the occupation the"
                  + " musician expectation must reject"),
          entry("Q955824", "class id — mapped by RecognitionInstitutions"),
          entry("Q1046088", "class id — mapped by RecognitionInstitutions"),
          entry("Q1051182", "entity — named by EdgeTypes"),
          entry("Q1147045", "entity — a real value in the recorded cave-claims.json"),
          entry("Q1259759", "class id — mapped by ClassLabels and KindMapper"),
          entry("Q1261214", "class id — mapped by ClassLabels and KindMapper"),
          entry("Q1421784", "entity — a real value in the recorded artist-with-url-relations.json"),
          entry("Q1535279", "entity — a real value in the recorded cave-claims.json"),
          entry("Q1538570", "class id — mapped by KindMapper"),
          entry("Q1656682", "class id — mapped by KindMapper"),
          entry("Q1738793", "entity — WikidataLiveSmokeTest asks the real API about it"),
          entry("Q2085381", "class id — mapped by RecognitionInstitutions"),
          entry("Q2268818", "entity — a real value in the recorded bad-seeds-claims.json"),
          entry("Q2526255", "class id — mapped by Expectations"),
          entry("Q2715462", "entity — a real value in the recorded cave-reverse.json"),
          entry("Q2996499", "entity — a real value in the recorded bad-seeds-claims.json"),
          entry("Q3129816", "entity — a real value in the recorded bad-seeds-claims.json"),
          entry("Q3331189", "class id — mapped by ClassLabels"),
          entry("Q4649799", "entity — a real value in the recorded proposition-claims.json"),
          entry("Q5398426", "class id — mapped by ClassLabels and KindMapper"),
          entry("Q5741069", "class id — mapped by ClassLabels and KindMapper"),
          entry("Q6013406", "entity — a real value in the recorded search-cave.json"),
          entry("Q6301911", "entity — a real value in the recorded cave-reverse.json"),
          entry("Q6650163", "entity — WikidataLiveSmokeTest asks the real API about it"),
          entry("Q6774606", "entity — SharedAwardRouteLiveTest asks the real API about it"),
          entry(
              "Q7558495",
              "class id — a solo-act class leavesTheBandsAlone asserts is never a recognition"
                  + " institution"),
          entry("Q7612859", "entity — a real value in the recorded hofstetter-claims.json"),
          entry("Q7725634", "class id — mapped by ClassLabels and KindMapper"),
          entry("Q9212979", "class id — mapped by KindMapper"),
          entry("Q9357859", "entity — a real value in the recorded cave-claims.json"),
          entry("Q10590726", "class id — mapped by ClassLabels and KindMapper"),
          entry("Q10800557", "class id — mapped by Expectations"),
          entry("Q11448906", "class id — mapped by ClassLabels and RecognitionInstitutions"),
          entry("Q12057459", "class id — mapped by RecognitionInstitutions"),
          entry("Q13473501", "class id — mapped by KindMapper"),
          entry("Q16334295", "class id — mapped by KindMapper"),
          entry("Q18510489", "class id — mapped by KindMapper"),
          entry(
              "Q19314966",
              "class id — a comedy class leavesTheBandsAlone asserts is never a recognition"
                  + " institution"),
          entry("Q19351429", "class id — mapped by KindMapper"),
          entry("Q19863965", "entity — a real value in the recorded search-cave.json"),
          entry("Q21191270", "class id — mapped by ClassLabels and KindMapper"),
          entry("Q23418635", "entity — a real value in the recorded bad-seeds-claims.json"),
          entry("Q45400320", "class id — mapped by RecognitionInstitutions"),
          entry("Q55850593", "class id — mapped by ClassLabels and KindMapper"),
          entry("Q56816954", "class id — mapped by KindMapper"),
          entry("Q58483083", "class id — mapped by ClassLabels and KindMapper"),
          entry("Q96888669", "class id — mapped by RecognitionInstitutions"),
          entry("Q97798779", "entity — a real value in the recorded cave-reverse.json"),
          entry("Q105543609", "class id — mapped by ClassLabels and KindMapper"),
          entry("Q110039749", "class id — mapped by ClassLabels and KindMapper"),
          entry("Q121998451", "entity — named by ReverseClaims"),
          entry(
              "Q127334927",
              "class id — a band class leavesTheBandsAlone asserts is never a recognition"
                  + " institution"),
          entry("Q131806449", "entity — WikidataLiveSmokeTest asks the real API about it"),
          entry(
              "Q1000000000",
              "negative control, deliberately allocatable — QidTest asserts Wikibase's grammar"
                  + " still admits ten digits, which is the upper bound that test pins"));

  /**
   * Invented ids still in the allocatable form, carried so the rest of the suite can go green while
   * they are migrated band by band.
   *
   * <p><b>This set shrinks to empty and is then deleted.</b> It is emptied band by band under issue
   * #171, whose last task deletes it and this paragraph with it. Adding an id to it is not a fix;
   * it is a record that a fix is owed. A new test that needs an id it invents takes the
   * leading-zero form — {@code Q0900100} — which Wikibase's grammar refuses outright.
   */
  static final Set<String> NOT_YET_MIGRATED = Set.of("Q999999");

  /** One allocatable-form id, and where it was found. */
  private record Sighting(Path file, int line, String id) {
    String describe() {
      return "%s:%d  %s".formatted(ROOT.relativize(file), line, id);
    }
  }

  /** One pass over the tree: the files it read, and every allocatable-form id in them. */
  private record Sweep(List<Path> files, List<Sighting> sightings) {}

  private static final Sweep SWEEP = sweep(TEST_TREE);

  @Test
  @DisplayName("every identifier a test invents is one Wikidata can never allocate")
  void shouldUseAnIdWikidataCannotAllocateWhenATestNamesAnEntityItInvented() {
    List<Sighting> offending =
        SWEEP.sightings().stream()
            .filter(s -> !ALLOWED.containsKey(s.id()) && !NOT_YET_MIGRATED.contains(s.id()))
            .toList();
    String report = offending.stream().map(Sighting::describe).collect(Collectors.joining("\n"));

    assertThat(report)
        .as(
            "%d identifiers in src/test that Wikidata could allocate, out of %d allocatable-form"
                + " sightings in %d files. Every one of them denotes a real entity, or will as"
                + " soon as the counter reaches it. Give the entity an id the grammar refuses,"
                + " by prepending one zero to the id the test uses today - the Q0900100 shape,"
                + " which LocalEntity's javadoc already predicts. A merge's canonical side is the"
                + " one place that shape is refused, and it takes ADR 62's eleven-digit shape"
                + " instead; see Qid.checkCanonicalSide. Or, if the test is genuinely about a real"
                + " entity, add it to ALLOWED with the reason it is real",
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
                + " path, and the dead-entry test discounts it - so a rename or a move would leave"
                + " that test comparing the lists against a tree that still contains every id they"
                + " name, silently vacuous rather than red")
        .contains(SELF);
  }

  @Test
  @DisplayName("neither list carries an id the tree no longer contains")
  void shouldCarryNoDeadEntryWhenTheListsAreCheckedAgainstTheTree() {
    Set<String> seen =
        SWEEP.sightings().stream()
            .filter(s -> !s.file().equals(SELF))
            .map(Sighting::id)
            .collect(Collectors.toCollection(LinkedHashSet::new));

    List<String> dead = new ArrayList<>();
    ALLOWED.keySet().stream().filter(id -> !seen.contains(id)).sorted().forEach(dead::add);
    NOT_YET_MIGRATED.stream().filter(id -> !seen.contains(id)).sorted().forEach(dead::add);

    assertThat(dead)
        .as(
            "ids named by ALLOWED or NOT_YET_MIGRATED that no longer appear anywhere in src/test"
                + " outside this file. An exclusion that outlives the id it excused is how a list"
                + " that is meant to shrink to empty stops shrinking; delete the entry")
        .isEmpty();
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
          collect(text, literal.start(), literal.text(), file, sightings);
        }
      } else {
        collect(text, 0, text, file, sightings);
      }
    }
    return new Sweep(files, List.copyOf(sightings));
  }

  /**
   * Every allocatable-form token in {@code scanned}, which begins at {@code offset} in {@code
   * text}.
   */
  private static void collect(
      String text, int offset, String scanned, Path file, List<Sighting> into) {
    Matcher token = TOKEN.matcher(scanned);
    while (token.find()) {
      String id = token.group();
      if (WIKIBASE_ITEM_ID.matcher(id).matches()) {
        into.add(new Sighting(file, lineOf(text, offset + token.start()), id));
      }
    }
  }

  /** One string literal or text block, and where its text begins in the file. */
  private record Literal(int start, String text) {}

  /**
   * Every string literal and text block in a Java source, with comments, character literals and
   * escapes handled where they change the answer. Four states and no regular expression, for the
   * reason the class comment gives.
   */
  private static List<Literal> literals(String source) {
    List<Literal> literals = new ArrayList<>();
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
        literals.add(new Literal(body, source.substring(body, Math.min(close, end))));
        at = Math.min(close + 3, end);
      } else if (c == '"' || c == '\'') {
        int body = at + 1;
        int close = body;
        while (close < end && source.charAt(close) != c && source.charAt(close) != '\n') {
          close += source.charAt(close) == '\\' ? 2 : 1;
        }
        if (c == '"') {
          literals.add(new Literal(body, source.substring(body, Math.min(close, end))));
        }
        at = Math.min(close + 1, end);
      } else {
        at++;
      }
    }
    return literals;
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
