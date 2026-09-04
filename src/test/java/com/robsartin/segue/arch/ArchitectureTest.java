package com.robsartin.segue.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.robsartin.segue.app.SegueApplication;
import com.robsartin.segue.domain.AffinityRecord;
import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.EdgeRecord;
import com.robsartin.segue.domain.LocalEntity;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.OwnerEdge;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.domain.SameAs;
import com.robsartin.segue.ingest.IngestService;
import com.robsartin.segue.musicbrainz.BridgedIdentity;
import com.robsartin.segue.port.AffinityStore;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.support.DefaultDatabase;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.properties.HasName;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.conditions.ArchConditions;
import com.tngtech.archunit.library.dependencies.SliceAssignment;
import com.tngtech.archunit.library.dependencies.SliceIdentifier;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Each rule names the decision it defends. See docs/adr/0032-layering-and-archunit.md.
 *
 * <p>Rules for packages that do not exist yet (ingest, mcp, app, sqlite, wikidata) arrive with
 * those packages in later increments. ArchUnit rules over empty package sets pass vacuously and
 * teach nothing, so they are not written in advance.
 */
@AnalyzeClasses(
    packages = "com.robsartin.segue",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  /** ADR 18: the domain layer carries zero third-party dependencies. */
  @ArchTest
  static final ArchRule domainHasNoThirdPartyDependencies =
      classes()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .onlyDependOnClassesThat()
          .resideInAnyPackage("..domain..", "java..", "javax..")
          .because("ADR 18 keeps the domain runnable with nothing but a JDK");

  /** ADR 18: the port layer is the seam, so it depends only on the domain. */
  @ArchTest
  static final ArchRule portDependsOnlyOnDomain =
      classes()
          .that()
          .resideInAPackage("..port..")
          .should()
          .onlyDependOnClassesThat()
          .resideInAnyPackage("..domain..", "..port..", "java..", "javax..")
          .because("the port exists to make the engine choice reversible");

  /**
   * ADR 11: domain value types are records, enums or sealed. Static registries are not value types.
   */
  @ArchTest
  static final ArchRule domainValueTypesAreRecordsOrEnums =
      classes()
          .that()
          .resideInAPackage("..domain..")
          .and()
          .areNotInterfaces()
          .should()
          .bePackagePrivate()
          .orShould()
          .beRecords()
          .orShould()
          .beEnums()
          .orShould()
          .haveOnlyPrivateConstructors()
          .because(
              "ADR 11 requires records for value types; a class with only private constructors is a"
                  + " static registry, not a value type");

  /**
   * ADR 32's adapters, in one place — the readable list, not the source of truth.
   *
   * <p><b>The tree is the source: the packages holding a class that implements a {@code port}
   * interface.</b> {@code PackageListsTest} derives that set through ArchUnit and asserts this list
   * equals it, so an adapter arriving without an entry here reds the build instead of shipping
   * unfenced (issue #165). Adding a source is still one entry here, but forgetting it is no longer
   * silent.
   *
   * <p>Read by {@link #adaptersDoNotDependOnEachOther}, by {@link #adaptersDoNotDependUpward}, and
   * by {@code DeveloperGuideEnumerationsTest}, which holds the guide's adapter sentence to it.
   */
  static final List<String> ADAPTER_PACKAGES =
      List.of("jena", "musicbrainz", "sqlite", "tinker", "wikidata");

  /** {@code ..jena..}-style patterns over {@link #ADAPTER_PACKAGES}, for the package predicates. */
  private static String[] adapterPackagePatterns() {
    return ADAPTER_PACKAGES.stream().map(p -> ".." + p + "..").toArray(String[]::new);
  }

  /**
   * ADR 32's dev-side tools, in one place — the readable list, not the source of truth.
   *
   * <p><b>The tree is the source: the {@code mainClass} packages of the {@code JavaExec} tasks, and
   * the packages holding a {@code *Cli} with a {@code main}.</b> {@code PackageListsTest} derives
   * that set both ways and asserts this list equals each, so a new tool arriving without an entry
   * here reds the build. Until issue #165 it did not: a planted tool reaching {@code export},
   * {@code recommend} and {@code IngestService} left every rule below green, because a package this
   * list does not name is fenced by nothing. Adding a tool is still one entry here, but forgetting
   * it is no longer silent — which is how {@code own} arrived unfenced in #92.
   *
   * <p><b>This list is why the sibling fences cannot go stale one at a time.</b> Each tool's fence
   * used to spell its siblings out by hand, and five of the six spellings were incomplete: {@code
   * recommend} — the fifth tool, and the newest when this was written — was missing from {@link
   * #seedNeverOpensAStore}, {@link #theRatingsToolOpensNothingElse}, {@link
   * #theRetractionToolOpensNothingElse} and {@link #theExporterOnlyReads}, and {@code export}
   * fenced no sibling but the two writers. Each omission was invisible in the rule that had it,
   * because a rule that lists four packages looks exactly like a rule that lists five. Deriving the
   * list is the same move <a href="https://github.com/robsartin/segue/issues/140">issue #140</a>
   * made for {@link #ADAPTER_PACKAGES}, for the same reason: the gap was only ever visible by
   * enumerating the ordered pairs, and now there is nothing to enumerate by hand.
   *
   * <p>{@link #noPackageCycles} is not a backstop here, for #140's reason exactly — where a sibling
   * fence exists it forbids the return edge, so no cycle can form.
   */
  static final List<String> DEV_TOOL_PACKAGES =
      List.of("census", "export", "own", "rate", "ratings", "recommend", "retract", "seed");

  /**
   * Every dev-tool package except the ones named, as {@code ..x..} patterns, then {@code
   * alsoFenced} unchanged.
   *
   * <p>{@code permitted} always contains the tool's own package, and contains a second entry only
   * where a decision allows one sibling — today {@code rate → recommend} (ADR 46) and {@code census
   * → export} (ADR 63). Written as an allowlist rather than a denylist so that the exception is the
   * thing a reader has to justify, and so a new tool is fenced from every one of its siblings the
   * moment it joins {@link #DEV_TOOL_PACKAGES}.
   *
   * @throws IllegalArgumentException if {@code permitted} names something that is not a dev tool —
   *     a typo would otherwise silently widen or invert the fence it was meant to describe
   */
  private static String[] otherDevToolsAnd(List<String> permitted, String... alsoFenced) {
    if (!DEV_TOOL_PACKAGES.containsAll(permitted)) {
      throw new IllegalArgumentException(
          "not dev-tool packages: " + permitted + " — known tools are " + DEV_TOOL_PACKAGES);
    }
    return Stream.concat(
            DEV_TOOL_PACKAGES.stream()
                .filter(t -> !permitted.contains(t))
                .map(t -> ".." + t + ".."),
            Stream.of(alsoFenced))
        .toArray(String[]::new);
  }

  /** One slice per adapter package; everything else is ignored, so it is never compared. */
  private static final SliceAssignment ADAPTERS =
      new SliceAssignment() {
        @Override
        public SliceIdentifier getIdentifierOf(JavaClass javaClass) {
          String inPackage = javaClass.getPackageName();
          for (String adapter : ADAPTER_PACKAGES) {
            String root = "com.robsartin.segue." + adapter;
            if (inPackage.equals(root) || inPackage.startsWith(root + ".")) {
              return SliceIdentifier.of(adapter);
            }
          }
          return SliceIdentifier.ignore();
        }

        @Override
        public String getDescription() {
          return "adapter packages " + ADAPTER_PACKAGES;
        }
      };

  /**
   * ADR 32: adapters are siblings, not collaborators — in both directions, over one list.
   *
   * <p><b>This replaced five pairwise rules that covered 16 of the 20 ordered pairs five adapters
   * make.</b> {@code tinker → sqlite}, {@code tinker → wikidata}, {@code jena → sqlite} and {@code
   * jena → wikidata} were unforbidden, and had been since before a second source existed (<a
   * href="https://github.com/robsartin/segue/issues/140">issue #140</a>). Each of the five rules
   * was individually correct; the gap was only visible by enumerating the ordered pairs rather than
   * the rules. {@link #noPackageCycles} could not catch it either — the sibling rules forbade the
   * return edge, so no cycle could ever form, and the rule that looked like a backstop was
   * structurally unable to be one.
   *
   * <p><b>A {@code DescribedPredicate} cannot express this.</b> {@code dependOnClassesThat} takes a
   * predicate over the target class only, so nothing on the object side can see which package the
   * origin is in and say "a <em>different</em> adapter". The naive {@code
   * resideInAnyPackage(adapters) → resideInAnyPackage(adapters)} is worse than useless: it fails on
   * every dependency inside one adapter. Slices are only compared across slices, so the
   * intra-package problem dissolves.
   *
   * <p>What the five deleted rules said, kept because it is the argument and not the mechanism:
   * MusicBrainz identifies an artist by MBID, {@code NodeRecord} identifies one by QID (ADR 22
   * clause 1), and Wikidata holds the mapping in P434 — so one import of {@code WikidataClient} and
   * one SPARQL query would bridge them in an afternoon. It would also mean the third source's cost
   * depends on which of the first two it happens to need, and the question ADR 54 exists to answer
   * could never be asked again. {@code musicbrainz} declares {@code MusicBrainzIdentity} and
   * something outside supplies it; {@code app} is the only package ADR 32 lets see two adapters at
   * once.
   */
  @ArchTest
  static final ArchRule adaptersDoNotDependOnEachOther =
      SlicesRuleDefinition.slices()
          .assignedFrom(ADAPTERS)
          .should()
          .notDependOnEachOther()
          .because(
              "ADR 32 and ADR 25: adapters are siblings, not collaborators — a second source is"
                  + " only evidence that adding a source is cheap if it was added without the"
                  + " first, and an import between two adapters would make every later source's"
                  + " cost depend on which sibling it needed");

  /** ADR 32: adapters depend downward only — never on ingest, mcp or app. */
  @ArchTest
  static final ArchRule adaptersDoNotDependUpward =
      noClasses()
          .that()
          .resideInAnyPackage(adapterPackagePatterns())
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..ingest..", "..mcp..", "..app..", "..seed..")
          .because("ADR 32: adapters are the bottom of the dependency graph");

  /**
   * ADR 40: the bulk seeding tool resolves names and reports; it never opens a store.
   *
   * <p>This is the whole safety argument for a committed tool that reads a private list. It cannot
   * write a claim to the graph, because it never sees a {@code GraphStore} or an {@code
   * AssertionLog} — {@link #onlyIngestAppliesClaimsToTheGraph} already forbids the calls, and this
   * forbids reaching the objects at all, so it cannot open {@code ~/.segue/segue.db} even to read
   * it. It also cannot become a seventh MCP tool by accident: {@code mcp} is on the same list.
   *
   * <p>Every sibling tool is on that list too, from {@link #DEV_TOOL_PACKAGES}. Three of them —
   * {@code export}, {@code ratings} and {@code recommend} — were missing until issue #105, so this
   * tool could have reached {@code ExportRun} and opened the database through it, which is the one
   * thing this rule's whole safety argument says it cannot do.
   */
  @ArchTest
  static final ArchRule seedNeverOpensAStore =
      noClasses()
          .that()
          .resideInAPackage("..seed..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              otherDevToolsAnd(
                  List.of("seed"),
                  "..sqlite..",
                  "..tinker..",
                  "..jena..",
                  "..ingest..",
                  "..mcp..",
                  "..app.."))
          .because(
              "ADR 40: the seeding tool resolves and reports — it never writes the graph, never"
                  + " opens the database, and is deliberately not an MCP tool (ADR 26)");

  /**
   * ADR 28: stdout belongs to the MCP protocol and nothing else — with one named exception.
   *
   * <p>{@link SegueApplication} reads {@code System.out} exactly once, in {@code main}, before
   * Spring runs, in order to capture the real stdout and redirect {@code System.out} itself to
   * stderr (FIX 5 of the increment-4a final review — see that class's Javadoc for why: a
   * dependency's own accidental write, not this project's, is what the redirection defends
   * against). That is the one legitimate read this rule needs to exempt, named by class rather than
   * relaxed for the package or the project, so a second class reaching for {@code System.out} still
   * fails this rule.
   */
  @ArchTest
  static final ArchRule nothingWritesToStandardOut =
      noClasses()
          .that()
          .doNotHaveFullyQualifiedName(SegueApplication.class.getName())
          .should()
          .accessField(System.class, "out")
          .because(
              "ADR 28: on the stdio transport stdout carries the protocol; a stray"
                  + " println corrupts the JSON-RPC stream. SegueApplication is the sole, named"
                  + " exception (FIX 5, final review) — see its Javadoc.");

  /**
   * ADR 30: SLF4J is the only logging API, and stderr is written through it — with the same one
   * named exception as {@link #nothingWritesToStandardOut}. {@link SegueApplication} reads {@code
   * System.err} once, in {@code main}, to build the {@code PrintStream} that {@code System.out}
   * gets redirected to (FIX 5, final review): this is not logging, it is the redirection target,
   * and it is the same class and the same justification as the stdout exemption above.
   */
  @ArchTest
  static final ArchRule nothingWritesToStandardError =
      noClasses()
          .that()
          .doNotHaveFullyQualifiedName(SegueApplication.class.getName())
          .should()
          .accessField(System.class, "err")
          .because(
              "ADR 30: logging goes through SLF4J, which is configured to target stderr."
                  + " SegueApplication is the sole, named exception (FIX 5, final review) — see"
                  + " its Javadoc.");

  /**
   * A call or a method reference is both an access, and neither is a subtype of the other.
   *
   * <p>ArchUnit models {@code r.note()} as a {@code JavaMethodCall} under {@code JavaCall}, and
   * {@code AffinityRecord::note} as a {@code JavaMethodReference} under {@code
   * JavaCodeUnitReference}. Those two are <em>siblings</em>: they meet only at {@link
   * JavaCodeUnitAccess}, and the nearest condition that accepts a predicate over both is {@code
   * accessTargetWhere}, which takes {@code JavaAccess<?>}. This predicate is typed to fit that, and
   * every rule below is written with {@code accessTargetWhere} rather than {@code callMethodWhere}
   * for exactly this reason — see {@link #callTo}.
   */
  private static final DescribedPredicate<JavaAccess<?>> A_CALL_OR_A_METHOD_REFERENCE =
      DescribedPredicate.describe(
          "a call or a method reference", JavaCodeUnitAccess.class::isInstance);

  /**
   * A call to {@code name}, <b>or a method reference to it</b>, on an owner assignable to {@code
   * owner}.
   *
   * <p>Assignability, not the exact owner, for the same reason {@link #noPrintStackTrace} needs it:
   * javac encodes the call-site owner as the <em>static type of the receiver expression</em>. A
   * field declared {@code SqliteAssertionLog} rather than {@code AssertionLog} compiles an owner of
   * the implementation, and the exact-owner form would miss it — which is the shape a bypass would
   * most plausibly take, since a class reaching around {@code IngestService} is a class that has
   * already helped itself to a concrete store.
   *
   * <p><b>The reference form, since issue #104.</b> This predicate was typed {@code
   * DescribedPredicate<JavaCall<?>>} and fed to {@code callMethodWhere}, which meant every rule
   * built on it was <em>structurally incapable</em> of seeing {@code
   * affinity.find(qid).map(AffinityRecord::note)} — a leak in the one form that reads as tidier
   * than the one the fences caught. Nothing was using it: the gap was dormant, and found by reading
   * the class hierarchy rather than by a failure.
   *
   * <p><b>Every rule below was verified against the reference form, because a rule that has only
   * seen the direct call has never been tested against the form this widening is about.</b>
   * Thirteen method references planted across {@code mcp}, {@code rate}, {@code recommend}, {@code
   * export}, {@code ratings} and {@code retract} left all twelve rules built on this predicate
   * <em>green</em> beforehand and failed every one of them afterwards. That is the only evidence
   * that this predicate, rather than some neighbouring clause, is what holds those lines — and the
   * one rule that did catch a planted reference beforehand, {@link #theRatingDeckLogsNoRating},
   * caught it as a <em>type</em> dependency and would not have seen a reference to a method on a
   * type the package may name.
   *
   * <p><b>Bound references match too, which is why neither string above says "unbound".</b> The
   * planted violations were all unbound ({@code AffinityRecord::note}), because that is the form
   * issue #104 was written about; the issue-#104 review then checked {@code t::note} on a parameter
   * and {@code held::note} on a field, and both are caught. The wording matters more here than it
   * looks: these two strings are what a developer reads <em>at the moment a fence fires</em>, and
   * reporting a real bound-reference leak under text that says only unbound ones are covered would
   * send them looking for a second hole that is not there.
   *
   * <p><b>{@link #A_CALL_OR_A_METHOD_REFERENCE} is not decoration.</b> {@code JavaAccess} also
   * covers field accesses, and a record's component field carries the accessor's name — so without
   * that clause {@code callTo("note", AffinityRecord.class)} would match {@code AffinityRecord}'s
   * own generated {@code note()} reading {@code this.note}, and {@link
   * #onlyTheRatingsToolReadsANote} would fail against correct production code in {@code domain}. A
   * fence that has to be loosened to let the guarded class compile is a fence nobody keeps.
   */
  private static DescribedPredicate<JavaAccess<?>> callTo(String name, Class<?> owner) {
    return A_CALL_OR_A_METHOD_REFERENCE
        .and(JavaAccess.Predicates.target(HasName.Predicates.name(name)))
        .and(JavaAccess.Predicates.targetOwner(JavaClass.Predicates.assignableTo(owner)))
        .as("a call or a method reference to %s.%s", owner.getSimpleName(), name);
  }

  /**
   * ADR 30: printStackTrace writes to stderr without touching System.err.
   *
   * <p>Matched by {@link #callTo}, on target name and owner-assignability rather than {@code
   * callMethod(Throwable.class, "printStackTrace")}, because javac encodes the call-site owner as
   * the caught variable's static type — {@code catch (RuntimeException e) { e.printStackTrace(); }}
   * compiles an owner of {@code RuntimeException}, not {@code Throwable}, so the exact-owner form
   * silently misses the single most common shape of this bug.
   */
  @ArchTest
  static final ArchRule noPrintStackTrace =
      noClasses()
          .should()
          .accessTargetWhere(callTo("printStackTrace", Throwable.class))
          .because("ADR 30: SLF4J is the only logging API, and stack traces belong in a logger");

  /** ADR 30: no competing logging API. */
  @ArchTest
  static final ArchRule noJavaUtilLogging =
      noClasses()
          .should()
          .dependOnClassesThat()
          .resideInAPackage("java.util.logging..")
          .because("ADR 30: SLF4J is the only logging API");

  /**
   * The three writes that put a claim somewhere durable: both halves of {@code IngestService.apply}
   * and the log append that must precede them.
   */
  private static final DescribedPredicate<JavaAccess<?>> APPLIES_A_CLAIM =
      callTo("record", GraphStore.class)
          .or(callTo("upsertNode", GraphStore.class))
          .or(callTo("append", AssertionLog.class))
          .as("applies a claim: GraphStore.record, GraphStore.upsertNode or AssertionLog.append");

  /**
   * ADR 19: the graph is a derived projection, so only {@code ingest} writes claims — appending to
   * the log first and applying to the graph second. Everything else hands the claim to {@link
   * IngestService} and touches no store itself.
   *
   * <p><b>All three writes, not just one.</b> This rule matched only {@code GraphStore.record}
   * until issue #44. That left {@code AssertionLog.append} and {@code GraphStore.upsertNode}
   * unguarded while ADR 32's table, {@code IngestService}'s Javadoc and {@code SegueService}'s
   * Javadoc all said otherwise — an invariant documented, believed and unenforced, which is the
   * worst of the three states. Nothing was violating it; nothing would have stopped the next one.
   *
   * <p>The gap mattered most at {@code append}. A claim that reached the graph without passing
   * through the log would vanish at the next boot, because {@link
   * com.robsartin.segue.ingest.GraphProjector} rebuilds the graph from the log and from nothing
   * else — silent data loss that no test would have noticed and that ADR 19 exists to make
   * impossible.
   *
   * <p><b>No exception is needed for replay.</b> {@code GraphProjector} does apply logged claims to
   * the graph, and it does so from inside {@code ingest}, so the package clause already covers it —
   * unlike {@link SegueApplication}'s stdout exemption, which had to name a class because the class
   * sat outside the package the rule fences. If a legitimate applier ever appears elsewhere, name
   * it the way that one is named; do not widen the package.
   */
  @ArchTest
  static final ArchRule onlyIngestAppliesClaimsToTheGraph =
      noClasses()
          .that()
          .resideOutsideOfPackage("..ingest..")
          .should()
          .accessTargetWhere(APPLIES_A_CLAIM)
          .because(
              "ADR 19: the log is the source of truth and only ingest projects it — a graph write"
                  + " that skipped the log would be gone at the next boot");

  /**
   * A call to a constructor of {@code owner}, <b>or a reference to it</b>.
   *
   * <p>Shaped like {@link #callTo} and for the same reason (issue #104): ArchUnit models {@code new
   * OwnerEdge(...)} as a {@code JavaConstructorCall} and {@code OwnerEdge::new} as a {@code
   * JavaConstructorReference}, and those two meet only at {@link JavaCodeUnitAccess}. A rule
   * written with {@code callConstructorWhere} would be structurally incapable of seeing the
   * reference form, which is exactly the dormant gap #104 found in the method-call fences.
   *
   * <p>{@code equivalentTo}, not assignability: records are final, there is no subclass to reach
   * the constructor through, and a target owner is the exact declaring type here rather than the
   * static type of a receiver expression.
   */
  private static DescribedPredicate<JavaAccess<?>> constructionOf(Class<?> owner) {
    return DescribedPredicate.<JavaAccess<?>>describe(
            "a constructor call or a constructor reference", JavaCodeUnitAccess.class::isInstance)
        .and(
            JavaAccess.Predicates.target(HasName.Predicates.name(JavaConstructor.CONSTRUCTOR_NAME)))
        .and(JavaAccess.Predicates.targetOwner(JavaClass.Predicates.equivalentTo(owner)))
        .as("a construction of %s", owner.getSimpleName());
  }

  /**
   * #92: the owner's three claims are made through their factories, never their constructors.
   *
   * <p><b>This rule is the structural half of a split the domain records make deliberately.</b>
   * {@link LocalEntity}, {@link OwnerEdge} and {@link SameAs} validate two different things in two
   * different places. Their canonical constructors enforce only what Wikidata's own grammar fixes
   * and this project can never re-tighten — {@code Q\d+}, and ADR 58's fact that a leading zero is
   * never allocatable. Their factories {@code minted()}, {@code claimed()} and {@code declared()}
   * enforce this project's <em>conventions</em>: two leading zeros for a local id (issue #141), and
   * ADR 22 clause 3's controlled relation vocabulary.
   *
   * <p>The split exists because the constructor is also the path {@code SqliteAssertionLog.readRow}
   * rebuilds a logged row through, and the log is append-only (ADR 19): a row written under last
   * month's convention has to stay decodable after the convention moves, which it already has once
   * ({@code c837265}). Re-running today's convention against yesterday's row would make one old row
   * take out boot replay, {@code rate}, {@code recommend}, {@code exportGraph}, {@code
   * retractEntity} and {@code listRatings} at once, on a row nothing may delete.
   *
   * <p><b>What it costs, and what this rule buys back.</b> The cost is that {@code new
   * OwnerEdge(from, to, "NOT_A_TYPE", now)} compiles, and is appendable. The obvious guard — a
   * second copy of the vocabulary check at the write boundary — was deliberately refused, on the
   * grounds that an unpinned duplicate of a rule is the copy a future writer forgets to move. This
   * is the fix that adds no second copy: every <em>maker</em> of a claim is required to go through
   * the one place the convention lives, and only the two packages that legitimately reconstruct a
   * claim from storage may reach past it.
   *
   * <p><b>Two packages, and only two.</b> {@code domain}, because a factory's whole job is to
   * delegate to the constructor it guards; {@code sqlite}, because {@code readRow} is
   * reconstruction rather than claiming — the row was validated when it was made, and re-validating
   * it on the way out is the re-litigation the split exists to prevent. Anything else that wants
   * one of these claims is making one.
   */
  @ArchTest
  static final ArchRule ownerClaimsAreMadeThroughTheirFactories =
      noClasses()
          .that()
          .resideOutsideOfPackages("..domain..", "..sqlite..")
          .should(
              ArchConditions.accessTargetWhere(
                  constructionOf(LocalEntity.class)
                      .or(constructionOf(OwnerEdge.class))
                      .or(constructionOf(SameAs.class))))
          .because(
              "#92: the conventions live in LocalEntity.minted, OwnerEdge.claimed and"
                  + " SameAs.declared, so everything that MAKES an owner claim goes through them —"
                  + " only domain and sqlite, which reconstruct logged rows, may reach the"
                  + " constructors");

  /**
   * #163: a bridged neighbour is built through {@link BridgedIdentity#describing}, never through
   * the record's constructor.
   *
   * <p>{@link #ownerClaimsAreMadeThroughTheirFactories}'s shape, aimed at one record and for a
   * sharper reason than convention. The constructor <b>throws</b> on a class id that is not a QID;
   * the factory <b>drops</b>, answering {@link BridgedIdentity#undescribed}. Both are correct and
   * they are not interchangeable, because of where a producer sits: {@code
   * MusicBrainzSourceAdapter} catches {@code MusicBrainzIdentityUnavailableException} and nothing
   * else, and {@code SegueService.expandEntity} wraps {@code adapter.expand} in no {@code try} at
   * all. So an {@code IllegalArgumentException} out of the constructor, inside a real {@code
   * identitiesFor}, aborts a whole expansion across every adapter on one contributor-entered value
   * — which is exactly the failure GAP 9 and issue #147 exist to prevent, and exactly what issue
   * #163's fix round 1 found in the log: {@code NodeRecord} refuses such a class id from inside
   * {@code IngestService.apply}, which runs after the claim has been appended, so the append-only
   * log (ADR 19) was left holding a row {@code GraphProjector} re-throws on at every boot.
   *
   * <p><b>Why a rule rather than a comment.</b> The constructor is public because the record's
   * fixtures and its own factories call it, and {@code new BridgedIdentity(qid, kind, label,
   * classes)} reads like the obvious thing to write in a bridge. Nothing about the call site says
   * which of the two behaviours it gets, and the difference only shows up in a poisoned log at the
   * next boot. The one place the constructor's throw is the right answer is {@link BridgedIdentity}
   * itself, which is why that is the only class exempt.
   *
   * <p><b>What this rule does not reach.</b> {@code @AnalyzeClasses} imports {@code src/main} only
   * ({@code ImportOption.DoNotIncludeTests}), so the test doubles that legitimately build rows —
   * {@code BridgedIdentityTest}'s fixtures, and the described neighbours {@code
   * MusicBrainzNeighbourIdentityTest} hands its stub bridge — are outside the import rather than
   * exempted by a clause here. A test asserting what the constructor refuses has to call it.
   */
  @ArchTest
  static final ArchRule bridgedIdentitiesAreBuiltThroughTheirFactory =
      noClasses()
          .that()
          .doNotBelongToAnyOf(BridgedIdentity.class)
          .should(ArchConditions.accessTargetWhere(constructionOf(BridgedIdentity.class)))
          .because(
              "#163: BridgedIdentity.describing drops a row whose class id cannot be read, where"
                  + " the constructor throws — and a throw out of a producer aborts the whole"
                  + " expansion, because MusicBrainzSourceAdapter catches only"
                  + " MusicBrainzIdentityUnavailableException and SegueService.expandEntity wraps"
                  + " nothing");

  /**
   * ADR 41: the graph exporter reads. It has no way to write, and that is the point.
   *
   * <p>The mirror of {@link #onlyIngestAppliesClaimsToTheGraph}, aimed at one package instead of at
   * everything outside another, and it forbids one thing more. Half of it — the three durable
   * writes — the rule above already covers from the other direction; the additional half is that
   * {@code export} may not depend on {@link IngestService} <b>at all</b>, which nothing else says.
   * That half is what completes the guarantee: without it a class here could reach the one
   * legitimate writer and route a claim through it, breaking no other rule in this file.
   *
   * <p><b>Why the exporter is not in {@code seed}.</b> The other dev-side tool is fenced by {@link
   * #seedNeverOpensAStore}, which forbids {@code seed} from depending on {@code sqlite}, {@code
   * tinker}, {@code jena}, {@code ingest}, {@code mcp} or {@code app} — it must not open a store
   * even to read one. This tool's entire job is reading a store. Two tools with opposite
   * relationships to the database cannot share a package and keep either fence meaningful, so they
   * are siblings with a rule each.
   *
   * <p><b>{@code GraphProjector} is deliberately not forbidden.</b> The bounded views traverse, so
   * they need a projection, and the exporter builds one by replaying the log into a throwaway
   * in-memory {@code TinkerGraphStore} — exactly what the application does at boot. That replay
   * writes to an object that never reaches a disk and is discarded when the process exits, and
   * reusing it is what keeps an exported route identical to the one {@code find_paths} returns
   * rather than a second traversal that can drift. What this rule guarantees is that nothing
   * durable changes: no claim reaches the log, the database or a store the exporter did not create
   * itself.
   *
   * <p><b>There are two dev-side tools that write, not one.</b> This Javadoc said "the one dev-side
   * tool that [writes]" while ADR 46 was adding a second — {@code rate}, which writes a rating
   * through {@code AffinityStore.put} — so the sentence was literally false and the rule below
   * covered only half of what it claimed. They write to different layers: {@code retract} appends a
   * world-fact claim through {@code IngestService}, {@code rate} writes the taste layer and never
   * touches {@code ingest} at all (ADR 33).
   *
   * <p><b>Every sibling is banned now, not only the two that write</b> (<a
   * href="https://github.com/robsartin/segue/issues/105">issue #105</a>). Naming the writers was
   * the narrowest reading of the fence: the reason a sibling is forbidden is that reaching it lets
   * this package inherit that sibling's fence instead of its own, and a read-only sibling has a
   * fence too. {@code ratings} may sweep the whole affinity table and {@code export} may not;
   * {@code recommend} and {@code seed} each carry clauses this rule does not. The list comes from
   * {@link #DEV_TOOL_PACKAGES} rather than from this Javadoc, so a new tool is covered without
   * anyone remembering to come back here.
   */
  @ArchTest
  static final ArchRule theExporterOnlyReads =
      noClasses()
          .that()
          .resideInAPackage("..export..")
          .should(
              ArchConditions.accessTargetWhere(APPLIES_A_CLAIM)
                  .or(
                      ArchConditions.dependOnClassesThat(
                          JavaClass.Predicates.equivalentTo(IngestService.class)))
                  // Without this clause the exporter could reach RetractRun and append a
                  // retraction through it, or RateServer and write a rating through that, or
                  // RatingsRun and sweep the table it may not sweep — inheriting a looser fence
                  // than its own, the exact shape theRatingsToolOpensNothingElse refuses. Issue
                  // #105: this listed only the two writers, which left the three read-only
                  // siblings reachable; it is derived from DEV_TOOL_PACKAGES now.
                  .or(
                      ArchConditions.dependOnClassesThat(
                          JavaClass.Predicates.resideInAnyPackage(
                              otherDevToolsAnd(List.of("export"))))))
          .because(
              "ADR 41: the exporter is a read-only tool — it never appends to the log, never"
                  + " writes the graph, cannot reach the one class that is allowed to, and cannot"
                  + " reach a sibling tool to borrow its fence (issue #105)");

  /** The JDK's networking APIs — the thing an offline tool must not be able to reach. */
  private static final DescribedPredicate<JavaClass> ON_A_NETWORK_API =
      JavaClass.Predicates.resideInAnyPackage("java.net..", "javax.net..");

  /** This project's own classes — the only ones the walk below steps through. */
  private static final DescribedPredicate<JavaClass> OWN_CODE =
      JavaClass.Predicates.resideInAPackage("com.robsartin.segue..");

  /**
   * A class of this project's that reaches a network API, itself or through a chain of this
   * project's own classes.
   *
   * <p>The object side of {@link #theExporterNeverSpeaksToANetwork}, and the reason that rule names
   * no HTTP client.
   *
   * <p><b>The walk steps only through {@code com.robsartin.segue}, and that is not a shortcut.</b>
   * {@code JavaClass.getTransitiveDependenciesFromSelf} was tried first and is unusable here:
   * ArchUnit resolves missing dependencies off the classpath, {@code java.lang.Class} declares
   * {@code getResource} returning a {@code java.net.URL}, and every class extends {@code Object} —
   * so the closure reaches {@code java.net} from literally everything. Measured, not guessed: that
   * form reported 830 violations against an unmodified {@code export}. Restricting the hops to this
   * project's classes asks the question actually worth asking — can the exporter get to a network
   * through code this repository controls — and leaves the JDK and the libraries to the direct
   * {@link #ON_A_NETWORK_API} clause.
   */
  private static final DescribedPredicate<JavaClass> REACHES_A_NETWORK =
      new DescribedPredicate<>(
          "reach java.net or javax.net, directly or through another class in this project") {
        @Override
        public boolean test(JavaClass javaClass) {
          if (!OWN_CODE.test(javaClass)) {
            return false;
          }
          Set<String> seen = new HashSet<>();
          Deque<JavaClass> pending = new ArrayDeque<>(Set.of(javaClass));
          while (!pending.isEmpty()) {
            JavaClass current = pending.poll();
            if (!seen.add(current.getFullName())) {
              continue;
            }
            for (Dependency dependency : current.getDirectDependenciesFromSelf()) {
              JavaClass target = dependency.getTargetClass();
              if (ON_A_NETWORK_API.test(target)) {
                return true;
              }
              if (OWN_CODE.test(target)) {
                pending.add(target);
              }
            }
          }
          return false;
        }
      };

  /**
   * ADR 41: the exporter is offline as well as read-only.
   *
   * <p>An export is a pure function of one database file, which is what makes it reproducible, fast
   * and safe to run against a graph nobody is watching. The pressure on that came with issue #63: a
   * DOT tooltip names the Wikidata class a node is an instance of, the graph stores only the class
   * QID (ADR 42), and the label is one HTTP call away. {@code ClassLabels} is an offline table for
   * exactly that reason, and this rule is what stops the next person reaching for the network
   * instead — a lookup per node would turn a one-second export of a 132-node neighbourhood into 132
   * round trips, and a `full` export into tens of thousands.
   *
   * <p>It names {@code java.net} rather than the project's own HTTP client because the temptation
   * is to write a fresh one here. {@code wikidata} as a whole is deliberately NOT banned: {@code
   * LogProjection} calls {@code KindMapper.rederive}, which is a static table and no more a network
   * call than this one is.
   *
   * <p><b>{@code musicbrainz} as a whole IS banned, because nothing in {@code export} wants
   * anything from it.</b> Wikidata's exemption was bought by two offline tables the exporter
   * genuinely needs; MusicBrainz offers the exporter nothing but a second HTTP client, so the fence
   * can be the package rather than a carve-out — and a package is what {@code resideInAnyPackage}
   * matches.
   *
   * <p><b>{@link #REACHES_A_NETWORK} is the clause that names no client.</b> This rule used to list
   * {@code ..wikidata.WikidataClient} among the packages, which is a class name passed to a package
   * predicate: it matched nothing, and {@code export} could hold a {@code WikidataClient} with the
   * build green (<a href="https://github.com/robsartin/segue/issues/139">issue #139</a>, measured
   * that way before this was changed). Naming the class instead would have fixed that one case and
   * left the next source's client to be remembered by hand — the shape #139 says came within one
   * step of propagating. So the object side asks what a class DOES: does it reach {@code java.net}
   * or {@code javax.net}, itself or through a chain of this project's own classes? That covers
   * {@code WikidataClient} and {@code MusicBrainzClient} today, {@code rate.RateServer}, and any
   * client a third source brings, with nothing to remember.
   *
   * <p>Transitive rather than direct, and the difference is load-bearing: {@code
   * WikidataEntityResolver} holds a {@code WikidataClient} and touches {@code java.net} nowhere
   * itself, so a direct-only test would let {@code export} reach the network through one
   * indirection. Both cases were watched go red before this was trusted.
   */
  @ArchTest
  static final ArchRule theExporterNeverSpeaksToANetwork =
      noClasses()
          .that()
          .resideInAPackage("..export..")
          .should()
          .dependOnClassesThat(
              ON_A_NETWORK_API
                  .or(JavaClass.Predicates.resideInAnyPackage("..musicbrainz.."))
                  .or(REACHES_A_NETWORK))
          .because(
              "ADR 41: an export is a pure function of the database file — a class label fetched at"
                  + " export time would make a picture depend on the internet being up");

  /**
   * ADR 63: the census reads, and it cannot write either layer.
   *
   * <p>A dev-side tool, and its fence is the exporter's with one clause moved: {@code
   * AffinityStore.put} and {@code updateRating} are named here, as they are for {@code ratings} and
   * {@code recommend}, because this tool holds the whole score map and affinity is the one part of
   * segue that cannot be regenerated from a source.
   *
   * <p><b>{@code export} is the one sibling this tool may reach, and that is a decision rather than
   * an oversight.</b> The fold is what the sections count: {@code Census}'s components are the list
   * and say which reads what — most take a {@code LogProjection} and nothing else, {@code
   * ClaimCensus} takes the raw log rows beside it, and {@code TasteCensus} takes the score map read
   * through {@code AffinityStore.readRatings} as well as both. There are two ways to have a fold:
   * read {@code LogProjection}, or write a third one. {@code BothFoldsAgreeTest} exists because two
   * folds of one log drifted, and {@code Equivalences.foldEndpoints} and {@code
   * Retractions.survives} were both moved into {@code domain} to stop it recurring — so a census
   * that disagreed with the export about how many nodes there are would be exactly the defect this
   * repository has spent three issues preventing. The borrowed fence is bounded the way {@code rate
   * → recommend} is bounded (ADR 46): {@link #theExporterOnlyReads} makes {@code export} read-only,
   * so nothing reachable through it can write.
   */
  @ArchTest
  static final ArchRule theCensusOnlyReads =
      noClasses()
          .that()
          .resideInAPackage("..census..")
          .should(
              ArchConditions.accessTargetWhere(
                      APPLIES_A_CLAIM
                          .or(callTo("put", AffinityStore.class))
                          .or(callTo("updateRating", AffinityStore.class)))
                  .or(
                      ArchConditions.dependOnClassesThat(
                          JavaClass.Predicates.equivalentTo(IngestService.class)))
                  .or(
                      ArchConditions.dependOnClassesThat(
                          JavaClass.Predicates.resideInAnyPackage(
                              otherDevToolsAnd(List.of("census", "export"))))))
          .because(
              "ADR 63: counting is a read — the census never appends to the log, never writes the"
                  + " graph, never writes a rating, and reaches exactly one sibling, export, so"
                  + " that there is one fold of the log rather than two");

  /**
   * ADR 63: the census opens the two stores in one file, folds the log, and reaches nothing else.
   *
   * <p>No traversal, so no {@code tinker} and no {@code jena}; no replay, so no {@code ingest};
   * nothing to serve, so no {@code mcp} and no {@code app}. {@code wikidata} is deliberately NOT
   * banned, for the reason {@link #theExporterNeverSpeaksToANetwork} gives: {@code
   * KindMapper.rederive} is a static table and no more a network call than {@code ClassLabels} is,
   * and both {@code LogProjection} and {@code Equivalences.standIns} are driven by it. {@code
   * musicbrainz} IS banned as a package, exactly as it is for the exporter — the census reads the
   * source id {@code "musicbrainz"} as text off the log, which is the only thing the log holds, and
   * importing the adapter would buy it nothing but a second HTTP client.
   *
   * <p>{@link #REACHES_A_NETWORK} is the clause that names no client, and it is here for issue
   * #139's reason: a census is a pure function of one local file, and the entity a count is short
   * of is exactly the row that makes fetching one look like an improvement.
   */
  @ArchTest
  static final ArchRule theCensusOpensNothingElse =
      noClasses()
          .that()
          .resideInAPackage("..census..")
          .should()
          .dependOnClassesThat(
              JavaClass.Predicates.resideInAnyPackage(
                      "..tinker..",
                      "..jena..",
                      "..ingest..",
                      "..mcp..",
                      "..app..",
                      "..musicbrainz..")
                  .or(ON_A_NETWORK_API)
                  .or(REACHES_A_NETWORK))
          .because(
              "ADR 63: the census folds the log and counts what comes out — it needs no engine, no"
                  + " replay and no network, and cannot become an MCP tool by accident");

  /**
   * ADR 43: the ratings tool reads, and it cannot write either layer.
   *
   * <p>The third dev-side tool, and the one whose fence needs a clause no other rule in this file
   * has: <b>{@code AffinityStore.put}</b>. {@link #onlyIngestAppliesClaimsToTheGraph} guards the
   * three world-fact writes from everywhere, and {@link #theExporterOnlyReads} repeats them at
   * {@code export} - but nothing anywhere forbids writing a <em>rating</em>, because until now the
   * only class outside {@code mcp} holding an {@code AffinityStore} looked up one qid at a time.
   * This tool holds the whole table, and affinity is the one part of segue that cannot be
   * regenerated from a source: a world fact deleted by accident comes back from Wikidata, and a
   * rating deleted by accident is gone. The tool that reads all of it must be unable to touch any
   * of it.
   */
  @ArchTest
  static final ArchRule theRatingsToolOnlyReads =
      noClasses()
          .that()
          .resideInAPackage("..ratings..")
          .should(
              ArchConditions.accessTargetWhere(
                  APPLIES_A_CLAIM
                      .or(callTo("put", AffinityStore.class))
                      .or(callTo("updateRating", AffinityStore.class))))
          .because(
              "ADR 43: listing your ratings is a read — the tool never appends to the log, never"
                  + " writes the graph, and never writes the taste layer it exists to display");

  /**
   * ADR 43: the ratings tool opens two stores in one file and nothing else.
   *
   * <p>The tightest of the three dev-tool fences, and it can be, because this tool needs the least:
   * a bulk read of the {@code affinity} table and the node claims in the log, both through {@code
   * sqlite}. No traversal, so no {@code tinker}; no projection, so no {@code ingest}; no picture,
   * so no {@code export}. Every sibling tool is banned as well as the application packages, because
   * a dependency on a sibling would quietly let this one inherit the sibling's looser fence -
   * {@code export} may use {@code GraphProjector}, and this may not. The sibling half of that list
   * is derived from {@link #DEV_TOOL_PACKAGES}: it was written out by hand until issue #105, and
   * {@code recommend} — the fifth tool — had never been added to it.
   *
   * <p>{@code java.net} for the same reason {@link #theExporterNeverSpeaksToANetwork} names it:
   * this tool joins qids to labels, a label is one HTTP call away, and a rating whose entity has
   * left the graph is exactly the row that makes fetching one look like an improvement. It is not.
   * A listing of personal data must be a pure function of one local file, with nothing leaving the
   * machine.
   */
  @ArchTest
  static final ArchRule theRatingsToolOpensNothingElse =
      noClasses()
          .that()
          .resideInAPackage("..ratings..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              otherDevToolsAnd(
                  List.of("ratings"),
                  "..tinker..",
                  "..jena..",
                  "..ingest..",
                  "..mcp..",
                  "..app..",
                  "java.net..",
                  "javax.net.."))
          .because(
              "ADR 43: the ratings tool reads the affinity table and the log's node claims, offline"
                  + " — it needs no engine, no projection and no network, and cannot become an MCP"
                  + " tool by accident");

  /**
   * ADR 43, and the reason ADR 39's refusal survives: nothing but the dev tool may read every
   * rating at once.
   *
   * <p>ADR 39 declined a bulk {@code list_affinity} because it is the single call that would put
   * the whole taste layer in front of a model, and ADR 43 did not overturn that - it separated the
   * audiences. The port now has a {@code readAll}, so the refusal is one method call away from
   * being undone by a well-meaning addition to {@code SegueService}, and {@code ToolSurfaceTest}
   * would not notice: a tool can grow a field without growing a row.
   *
   * <p>So the distinction is enforced where it actually lives, at the call. {@code find} stays
   * available everywhere, which is what {@code get_entity} and {@code AffinityOverlay} use; the
   * sweep is reserved to one package. If a future ADR gives the bulk read to somebody else, this
   * rule is what it has to change - which is the point.
   */
  @ArchTest
  static final ArchRule onlyTheRatingsToolReadsEveryRating =
      noClasses()
          .that()
          .resideOutsideOfPackage("..ratings..")
          .should()
          .accessTargetWhere(callTo("readAll", AffinityStore.class))
          .because(
              "ADR 16, ADR 39 and ADR 43: the owner may enumerate their taste layer from a dev-side"
                  + " tool; nothing on the MCP surface may enumerate it at all");

  /**
   * ADR 33 as amended by issue #85: the score is ordinary data, the note is not, and this rule is
   * where the new line is drawn.
   *
   * <p>The line used to run around the whole taste layer. Issue #85 moved it to run between the two
   * fields, and the argument is short: a rating is the known-list at higher resolution — 815
   * entities chosen because the owner likes them are already handed to a model — while a note is
   * free text no schema constrains. "Reminds me of Dad's funeral" is a categorically different fact
   * from "4/5", and an MCP tool result enters a model's context, which leaves the machine.
   *
   * <p>So the note is confined at the one place it can be read back from storage: {@code
   * AffinityRecord.note()}. Two packages may call it. {@code ratings} is the owner's own listing
   * tool (ADR 43), where reading both fields is the entire purpose. {@code sqlite} is not a reader
   * at all — it is the table, and it calls the accessor to bind a prepared-statement parameter on
   * the way in. Everything else may hold an {@code AffinityRecord} and read its rating, and cannot
   * see the words: {@code mcp} above all, but also {@code export}, {@code recommend} and every
   * package nobody has written yet.
   *
   * <p><b>This is what makes the leak fixed in issue #85 stay fixed.</b> {@code get_entity}
   * returned the note from the day ADR 39 shipped, through a single call to this accessor in {@code
   * ViewMapper}, and a wire type with no note field would be re-grown by exactly that call. {@code
   * NoteNeverLeavesThroughAToolTest} proves the surface as it stands; this proves the field nobody
   * has thought of yet.
   */
  @ArchTest
  static final ArchRule onlyTheRatingsToolReadsANote =
      noClasses()
          .that()
          .resideOutsideOfPackages("..ratings..", "..sqlite..")
          .should()
          .accessTargetWhere(callTo("note", AffinityRecord.class))
          .because(
              "ADR 33 as amended by issue #85: the rating is ordinary data and may be read"
                  + " anywhere; the note is personal free text, read by the owner's own listing"
                  + " tool and by nothing that answers an MCP call");

  /**
   * ADR 45: the recommender reads, and it cannot write either layer.
   *
   * <p>The fifth dev-side tool, and the second one whose fence has to name {@code
   * AffinityStore.put} - not because it holds ratings, but because it is the tool that would most
   * plausibly be extended to write one. A recommender that could record "I liked this suggestion"
   * would be a taste-layer writer wearing a world-fact tool's fence, and ADR 33 keeps {@code
   * note_affinity} the only writer there is.
   *
   * <p>{@code IngestService} is banned as a type for the reason {@link #theExporterOnlyReads} bans
   * it: without that clause a class here could route a claim through the one legitimate writer and
   * break no other rule. {@code GraphProjector} is deliberately allowed, exactly as it is for the
   * exporter - the sweep and the routes need a real traversal, so the tool replays the log into a
   * throwaway in-memory graph and reuses the engine, which is what keeps a recommendation's routes
   * identical to the ones {@code find_paths} returns.
   */
  @ArchTest
  static final ArchRule theRecommenderOnlyReads =
      noClasses()
          .that()
          .resideInAPackage("..recommend..")
          .should(
              ArchConditions.accessTargetWhere(
                      APPLIES_A_CLAIM
                          .or(callTo("put", AffinityStore.class))
                          .or(callTo("updateRating", AffinityStore.class)))
                  .or(
                      ArchConditions.dependOnClassesThat(
                          JavaClass.Predicates.equivalentTo(IngestService.class))))
          .because(
              "ADR 45: recommending is a read — the tool never appends to the log, never writes"
                  + " the graph, never writes a rating, and cannot reach the one class that is"
                  + " allowed to");

  /**
   * ADR 45 as amended by issue #85: the recommender reads ratings, and it still cannot read a note.
   *
   * <p>The rule with the most to say about this tool, because ADR 33's stated payoff is
   * "recommendations are derived by traversing the world graph and filtering through affinity" and
   * this is the tool that derives them. <b>The filtering half is now built</b> — {@code
   * Recommendations.regardFor} turns the ratings into the weight per known entity that {@code
   * CandidateSweep} was always multiplying by.
   *
   * <p><b>The rule is narrowed rather than deleted, and the narrowing is the whole decision.</b> It
   * used to ban {@code AffinityStore} as a <em>type</em>, on the reasoning that {@code find} is
   * available everywhere and eight hundred single-qid lookups are a bulk read spelled slowly. That
   * instinct was right and it survives here in the only form that still has work to do: the
   * recommender may hold the store and call {@code readRatings}, whose {@code Map<String, Integer>}
   * has nowhere to put a note, and it may not call {@code find} or {@code readAll} or so much as
   * name {@code AffinityRecord} — the three ways a note could reach this package.
   *
   * <p>Three fences, and they are answering different questions. This one says the recommender
   * cannot see the words. {@link #onlyTheRatingsToolReadsANote} says the same of everything else in
   * the project, at the accessor. {@link #onlyTheRecommenderReadsEveryRating} keeps the note-free
   * bulk read off the MCP surface, so widening the taste layer's readership stays an ADR-level
   * decision even though the score is now ordinary data. That sentence used to end "is this tool's
   * alone"; issue #101 (ADR 46) took the decision it asks for and widened the rule to {@code
   * resideOutsideOfPackages("..recommend..", "..rate..")}, so the rating deck may call {@code
   * readRatings} as well — it needs the same map to know which entities it has already dealt.
   *
   * <p><b>The deck reading scores does not let it reach a note — but it is held off one differently
   * from this package, and the difference is worth reading rather than assuming.</b> Two rules
   * cover both equally: {@link #onlyTheRatingsToolReadsANote} shuts {@code AffinityRecord.note()}
   * out of everything but {@code ratings} and {@code sqlite}, and {@link
   * #onlyTheRatingsToolReadsEveryRating} keeps {@code readAll} — the read that carries a note — as
   * the listing tool's. Past that they diverge. This rule also bans {@code AffinityStore.find} in
   * {@code recommend} and forbids that package to name {@code AffinityRecord} at all; {@code rate}
   * has no {@code find} ban anywhere. Since the issue-#109 review the two packages agree on the
   * record itself — {@link #theRatingDeckLogsNoRating} bans it across {@code rate} with no
   * exception, the deck's one write having moved to {@code AffinityStore.updateRating} — and what
   * holds the deck off the words in its own right is {@link #theRatingDeckNeverReadsANote}, which
   * bans the accessor for every class in {@code rate}, {@code RateServer} included.
   */
  @ArchTest
  static final ArchRule theRecommenderReadsRatingsAndNeverNotes =
      noClasses()
          .that()
          .resideInAPackage("..recommend..")
          .should(
              ArchConditions.dependOnClassesThat(
                      JavaClass.Predicates.equivalentTo(AffinityRecord.class))
                  .or(
                      ArchConditions.accessTargetWhere(
                          callTo("find", AffinityStore.class)
                              .or(callTo("readAll", AffinityStore.class)))))
          .because(
              "ADR 45 as amended by issue #85: the recommender weights by the score and cannot"
                  + " reach the note — readRatings returns a map of qid to rating, and the two"
                  + " reads that carry free text stay out of this package");

  /**
   * Issue #85: the note-free bulk read belongs to the recommender and the rating deck, and to
   * nothing on the MCP surface.
   *
   * <p>The sibling of {@link #onlyTheRatingsToolReadsEveryRating}, one field narrower and for a
   * different reason. That rule protects a note; this one protects nothing personal at all now that
   * the score is ordinary data — what it protects is <b>ADR 26's six tools</b>. A bulk read
   * appearing on the surface would arrive as a field on an existing tool rather than as a new tool,
   * and {@code ToolSurfaceTest} counts tools, so it would not notice. ADR 45 recorded a re-open
   * condition for a conversational recommendation and issue #85 deliberately did not exercise it;
   * until an ADR does, {@code get_entity} answers one qid at a time.
   *
   * <p>Widened by issue #101 (ADR 46): the deck needs the same note-free map to know which entities
   * are already rated and must not be dealt again, which is the resume mechanism {@code Deck}'s
   * class comment describes. Both readers are dev-side tools off the MCP surface, so the thing this
   * rule actually protects is unchanged.
   *
   * <p>Widened again by issue #227 (ADR 63): the census reports how many ratings sit at each score,
   * and needs the same note-free map to do it. <b>The note-carrying reads are untouched</b> —
   * {@link #onlyTheRatingsToolReadsEveryRating} keeps {@code readAll} to the listing tool and
   * {@link #onlyTheRatingsToolReadsANote} keeps the accessor there — so what this widening admits
   * is a {@code Map<String, Integer>} with nowhere to put a note, which is the same fence the
   * recommender's own rule turns on. All three readers are dev-side tools off the MCP surface, so
   * the thing this rule actually protects is unchanged.
   */
  @ArchTest
  static final ArchRule onlyTheRecommenderReadsEveryRating =
      noClasses()
          .that()
          .resideOutsideOfPackages("..recommend..", "..rate..", "..census..")
          .should()
          .accessTargetWhere(callTo("readRatings", AffinityStore.class))
          .because(
              "ADR 26 and issues #85, #101 and #227: the score is ordinary data, and reading every"
                  + " score at once is a dev-side tool's job — the recommender, the rating deck or"
                  + " the census — rather than a field on an MCP tool");

  /**
   * Issue #101: the deck writes the taste layer and nothing else.
   *
   * <p>The mirror image of {@code theRatingsToolOnlyReads}. That tool may read every rating and
   * write none; this one may write a rating and must not touch the graph or the log. Between them
   * the two dev tools that meet the affinity table can each do exactly one thing to it.
   *
   * <p><b>{@code IngestService} is banned as a type, and that clause is issue #105's</b> — the same
   * clause {@link #theExporterOnlyReads} and {@link #theRecommenderOnlyReads} already carried, and
   * for the reason the exporter's Javadoc gives: without it a class here could route a claim
   * through the one legitimate writer and break no other rule, because the three write calls this
   * rule forbids would all be made by {@code ingest} rather than by {@code rate}. The deck's
   * Javadoc had asserted that {@code rate} never touches {@code ingest} since ADR 46; nothing held
   * it, and the assertion was measured green with a violation in place before this was added.
   *
   * <p>The {@code ingest} <em>package</em> stays reachable, exactly as it does for the exporter and
   * the recommender: {@code RateCli} needs {@code GraphProjector} to replay the log into the
   * throwaway in-memory graph a card's routes are traversed on. {@code IngestService} is the one
   * class in it that writes, so the fence is the type and not the package.
   */
  @ArchTest
  static final ArchRule theRatingDeckWritesOnlyAffinity =
      noClasses()
          .that()
          .resideInAPackage("..rate..")
          .should(
              ArchConditions.accessTargetWhere(APPLIES_A_CLAIM)
                  .or(
                      ArchConditions.dependOnClassesThat(
                          JavaClass.Predicates.equivalentTo(IngestService.class))))
          .because(
              "ADR 46: the deck records what the owner thinks and never what the world says — it"
                  + " appends no claim, records no edge, upserts no node, and cannot reach the one"
                  + " class that is allowed to");

  /**
   * Issue #85, held by construction and then by rule.
   *
   * <p>{@code Card} has no note field, so there is nothing for the page to render even by accident;
   * this stops the field being reintroduced by someone who thinks it would be handy.
   *
   * <p><b>Held twice over since the issue-#109 review</b>, and deliberately kept anyway. {@link
   * #theRatingDeckLogsNoRating} now bans the whole package from naming {@code AffinityRecord}, so
   * this accessor is already out of reach; but that rule defends a different thing (a rating in a
   * log line) and could be relaxed by a future decision that has no view on notes. This rule is the
   * one that says <em>why</em> the deck must not see the words, and it is the reason {@code
   * AffinityStore.updateRating} exists rather than a deck that reads a note and carries it back.
   */
  @ArchTest
  static final ArchRule theRatingDeckNeverReadsANote =
      noClasses()
          .that()
          .resideInAPackage("..rate..")
          .should(ArchConditions.accessTargetWhere(callTo("note", AffinityRecord.class)))
          .because(
              "issue #85: a rating is ordinary data and a note is not — the deck writes the first"
                  + " and must never be able to display the second");

  /**
   * ADR 33: no rating reaches a log line, and no class in {@code rate} may name the type that
   * carries one.
   *
   * <p><b>The named exception is withdrawn (issue #109 review), and the withdrawal is the
   * decision.</b> This rule used to exclude {@code RateServer} by simple name, because that class
   * built the record it wrote: {@code affinity.put(new AffinityRecord(qid, rating, null, now))}.
   * That {@code null} was the bug {@code --revise} made reachable — the upsert took {@code
   * excluded.note} and erased a note the owner could never restore — so {@code RateServer} now
   * calls {@code AffinityStore.updateRating}, a write with nowhere to put a note, and constructs no
   * record at all. With the last legitimate use gone the exception could only shelter a new one.
   *
   * <p><b>Two classes were reaching the record for reasons that had nothing to do with holding a
   * rating</b>, and both now read the scale from {@code RatingScale} instead. {@code RateCli} is
   * the instructive one: it named {@code AffinityRecord.MIN_RATING}/{@code MAX_RATING} in its usage
   * string and its {@code --revise} check, and this rule <em>passed anyway</em>, because javac
   * inlines a compile-time {@code int} constant and the reference is simply not in the bytecode
   * ArchUnit reads. A fence that holds only until somebody touches a non-constant member is a fence
   * the next reader will mistake for decoration. Moving the bounds to a class that carries no
   * rating fixes the source and the bytecode at once.
   *
   * <p>What the rule protects is unchanged: the deck logs a port, a count and a path, and a qid
   * paired with a score is the personal part — the easiest way to leak it being a debug line added
   * while chasing something else. Now no class in {@code rate} can hold one to log.
   */
  @ArchTest
  static final ArchRule theRatingDeckLogsNoRating =
      noClasses()
          .that()
          .resideInAPackage("..rate..")
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName("com.robsartin.segue.domain.AffinityRecord")
          .because(
              "ADR 33 keeps affinity out of every log line. The deck writes through"
                  + " AffinityStore.updateRating, which needs no record and has nowhere to put a"
                  + " note, so nothing in this package may hold a rating at all");

  /**
   * ADR 46: the deck needs a log, an engine, the recommender's sweep and nothing else.
   *
   * <p>The deck's half of the fence every dev tool carries — {@link #seedNeverOpensAStore}, {@link
   * #theRatingsToolOpensNothingElse}, {@link #theRecommenderOpensNothingElse}, {@link
   * #theRetractionToolOpensNothingElse} — and for the same reason: a dependency on a sibling lets
   * this tool inherit that sibling's fence instead of its own. It writes a rating and nothing else,
   * so reaching {@code retract} (which appends a world-fact claim) or {@code ratings} (which reads
   * every note) would each be a way around a rule this package is otherwise held to.
   *
   * <p><b>{@code recommend} is deliberately NOT banned, and it is one of the two dependencies
   * between dev tools that are left open — the other is {@code census → export} ({@link
   * #theCensusOnlyReads}, ADR 63).</b> No arithmetic over the pairs is given here on purpose: the
   * number of them changes with every tool the build registers, and it was already stale once.
   * {@link #DEV_TOOL_PACKAGES} and {@link #otherDevToolsAnd} are the authority — each rule's {@code
   * permitted} list is the whole of its exception, and {@code otherDevToolsAnd} throws on a name
   * that is not a dev tool, so the two open pairs cannot quietly become three. It is expressed as
   * the second entry in this rule's {@code permitted} list rather than as an omission from a
   * hand-written denylist, which is what makes it reviewable: the exception is the thing a reader
   * has to justify. The candidate half of the deck is the recommender's own {@code CandidateSweep},
   * {@code Routes} and {@code Sweep}, so that a card's routes are the routes that tool would give
   * for the same pair rather than a second implementation that can drift. ADR 46 argues that
   * dependency and ADR 45 moved {@code QidList} into {@code support} rather than let a shared
   * reader create it by accident. It runs one way only: {@link #theRecommenderOpensNothingElse}
   * bans the return trip.
   *
   * <p><b>{@code java.net} is deliberately NOT banned either</b>, and this is the one dev tool that
   * could not carry that clause. Its whole shape is an HTTP server: {@code RateServer} binds an
   * {@code InetSocketAddress} on {@link java.net.InetAddress#getLoopbackAddress()} and parses the
   * {@code Origin} header with {@link java.net.URI}. What the siblings' {@code java.net} ban buys
   * them — nothing leaves the machine — is bought here by the bind address and the Origin allowlist
   * instead, which is ADR 46's own argument and is tested over a real socket in {@code
   * RateServerTest} rather than asserted here.
   */
  @ArchTest
  static final ArchRule theRatingDeckOpensNothingElse =
      noClasses()
          .that()
          .resideInAPackage("..rate..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              otherDevToolsAnd(List.of("rate", "recommend"), "..jena..", "..mcp..", "..app.."))
          .because(
              "ADR 46: the deck replays one local log into one in-memory graph and serves it on"
                  + " loopback — it needs no second engine and no sibling tool but the recommender,"
                  + " whose sweep it reuses on purpose, and cannot become an MCP tool by accident");

  /**
   * ADR 45: the recommender needs a log, an engine and nothing else.
   *
   * <p>The same fence its siblings carry, with the same reasoning. Every other dev tool is banned
   * as a package - the list comes from {@link #DEV_TOOL_PACKAGES} - because a dependency on a
   * sibling would let this tool inherit the sibling's different fence: {@code retract} may write,
   * and this may not. {@code java.net} because a recommendation is a pure function of one local
   * file: the list of what somebody already knows never leaves the machine, which is ADR 40's
   * argument for why the seeding list lives outside this repository, applied to the tool that reads
   * it.
   *
   * <p>{@code jena} is banned as the reference adapter nothing outside the bake-off should reach;
   * {@code tinker} is not, because the throwaway projection is a {@code TinkerGraphStore} the same
   * way the exporter's is.
   */
  @ArchTest
  static final ArchRule theRecommenderOpensNothingElse =
      noClasses()
          .that()
          .resideInAPackage("..recommend..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              otherDevToolsAnd(
                  List.of("recommend"),
                  "..jena..",
                  "..mcp..",
                  "..app..",
                  "java.net..",
                  "javax.net.."))
          .because(
              "ADR 45: the recommender replays one local log into one in-memory graph, offline —"
                  + " it needs no sibling tool, no second engine and no network, and cannot become"
                  + " an MCP tool by accident");

  /**
   * ADR 44: the retraction tool writes exactly one thing, and cannot write anything else.
   *
   * <p>The fourth dev-side tool, and the first one that <b>writes</b> - which is why its fence is
   * shaped differently from the other three. {@code seed} may not open a store at all, {@code
   * export} and {@code ratings} may read one; this appends. What it may append is one retraction,
   * through {@link IngestService#retract}, which is the only reason it is allowed to depend on
   * {@code ingest} at all.
   *
   * <p>So every durable write is forbidden <em>here</em> as well as from wherever else it is
   * already forbidden: {@code AssertionLog.append} directly (it must go through {@code ingest}, so
   * that {@link #onlyIngestAppliesClaimsToTheGraph} keeps meaning what it says), both halves of the
   * graph write, and <b>both</b> taste-layer writes — {@code AffinityStore.put} and, since the
   * issue-#109 review, {@code AffinityStore.updateRating}. The taste-layer clauses matter most: a
   * retraction is about the world-fact layer, and ADR 33 keeps the taste layer out of it entirely.
   * A rating is the one thing in segue that cannot be regenerated, and the tool whose whole purpose
   * is removing things must be unable to touch it.
   *
   * <p><b>The count is deliberately not stated as a number any more.</b> This javadoc said "the
   * four durable writes" and went on listing four while the port had grown a fifth; the rule kept
   * its teeth only because {@link #theRetractionToolOpensNothingElse} bans {@code AffinityStore} as
   * a type in this package. A rule whose prose promises coverage it does not have reads as
   * decorative to the next person, whether or not a second rule happens to be holding the line.
   */
  @ArchTest
  static final ArchRule theRetractionToolWritesOnlyRetractions =
      noClasses()
          .that()
          .resideInAPackage("..retract..")
          .should(
              ArchConditions.accessTargetWhere(
                  APPLIES_A_CLAIM
                      .or(callTo("put", AffinityStore.class))
                      .or(callTo("updateRating", AffinityStore.class))
                      .or(callTo("readAll", AffinityStore.class))))
          .because(
              "ADR 44: retraction appends one claim through IngestService and writes nothing"
                  + " else — not the graph, not the log directly, and never the taste layer");

  /**
   * ADR 44: the retraction tool sees a log and nothing else.
   *
   * <p>{@code GraphStore} is named as a <em>type</em>, not just as two forbidden calls, and that is
   * the clause worth reading twice. A retraction has no graph half: the port cannot remove
   * anything, and widening the port that exists to keep the engine choice reversible (ADR 18) so a
   * dev tool could is what ADR 41 already refused. The graph catches up by being rebuilt from the
   * log (ADR 24). So this tool has no business holding a graph at all, and {@link
   * IngestService#retract} is static precisely so that satisfying a constructor could not become
   * the reason it held one.
   *
   * <p>The sibling tools are banned for the reason ADR 43 gives: a dependency on one would let this
   * one inherit the sibling's different fence. They come from {@link #DEV_TOOL_PACKAGES}, which is
   * how {@code recommend} joined them — it was absent from the hand-written list until issue #105.
   * {@code java.net} because a decision about your own graph is a pure function of one local file,
   * and nothing about it leaves the machine.
   */
  @ArchTest
  static final ArchRule theRetractionToolOpensNothingElse =
      noClasses()
          .that()
          .resideInAPackage("..retract..")
          .should()
          .dependOnClassesThat(
              JavaClass.Predicates.equivalentTo(GraphStore.class)
                  .or(JavaClass.Predicates.equivalentTo(AffinityStore.class))
                  .or(
                      JavaClass.Predicates.resideInAnyPackage(
                          otherDevToolsAnd(
                              List.of("retract"),
                              "..tinker..",
                              "..jena..",
                              "..mcp..",
                              "..app..",
                              "java.net..",
                              "javax.net.."))))
          .because(
              "ADR 44: retraction is a decision about the log, made offline, from a tool that"
                  + " cannot hold a graph, a rating, an engine or a network connection");

  /**
   * #92: the owner-claim tool appends through {@code ingest} and opens nothing else.
   *
   * <p><b>The seventh dev-side tool, and the second that writes a world-fact claim.</b> Its fence
   * is deliberately the same shape as {@link #theRetractionToolOpensNothingElse}'s, and the reason
   * is not that the two tools are alike — it is that they are unalike in the one way that would
   * have argued for a graph, and still do not get one. A retraction genuinely <em>has</em> no graph
   * half. An owner claim does: {@code IngestService.apply} has a case for each of the three, and a
   * minted entity becomes a node the moment the log is replayed. What both tools lack is a
   * <em>running</em> graph to apply it to, so the projection catches up at the next boot (ADR 24)
   * and neither tool has any business holding a {@link GraphStore}. Naming the type rather than the
   * two write calls is what makes that unarguable: {@link #onlyIngestAppliesClaimsToTheGraph}
   * already forbids the calls from here, and this forbids reaching the object they are made on.
   *
   * <p><b>{@link AffinityStore} as a type, and it is not decoration.</b> A merge carries the
   * owner's ratings across — that is half of what {@code SameAs} is for — but it carries them
   * through {@link com.robsartin.segue.port.IdentityMerge} at read time, on the machine that holds
   * the graph, and never from this tool. A rating is the one thing in segue that cannot be
   * regenerated from a source, and the tool whose merge subcommand is the most plausible reason
   * anyone would reach for the affinity table must be unable to reach it at all. That single clause
   * covers both taste-layer writes and both taste-layer reads at once, which is why this package
   * needs no second rule in the shape of {@link #theRetractionToolWritesOnlyRetractions}.
   *
   * <p>The sibling half comes from {@link #DEV_TOOL_PACKAGES}, which {@code own} now joins — the
   * first real exercise of the derived list since issue #105 built it, and the reason this rule
   * only had to be written once rather than seven times. A dependency on a sibling would let this
   * tool inherit that sibling's fence instead of its own: {@code rate} may write a rating and this
   * may not, {@code export} may build a projection and this has no graph to project onto. {@code
   * java.net} because a claim about the owner's own shelf is a pure function of one local file and
   * nothing about it leaves the machine — the same clause every sibling but {@code rate} carries,
   * and {@code rate} is exempt only because it <em>is</em> an HTTP server.
   */
  @ArchTest
  static final ArchRule theOwnerClaimToolOpensNothingElse =
      noClasses()
          .that()
          .resideInAPackage("..own..")
          .should()
          .dependOnClassesThat(
              JavaClass.Predicates.equivalentTo(GraphStore.class)
                  .or(JavaClass.Predicates.equivalentTo(AffinityStore.class))
                  .or(
                      JavaClass.Predicates.resideInAnyPackage(
                          otherDevToolsAnd(
                              List.of("own"),
                              "..tinker..",
                              "..jena..",
                              "..mcp..",
                              "..app..",
                              "java.net..",
                              "javax.net.."))))
          .because(
              "#92: an owner claim is appended through IngestService.claim and applied at the next"
                  + " boot — the tool holds no graph, never reaches the taste layer a merge"
                  + " carries, borrows no sibling's fence, and cannot become an MCP tool by"
                  + " accident");

  /**
   * #179: the two claim tools have no default database, and cannot quietly grow one back.
   *
   * <p>The dev tools that resolve {@code --db}, then {@code SEGUE_DB}, then {@code
   * ${user.home}/.segue/segue.db} do it through the one copy of that rule in {@link
   * DefaultDatabase} — so they are exactly its callers, which is a grep rather than a list anybody
   * has to keep correct here. {@code retract} and {@code own} require {@code --db} outright,
   * because they append a first-person claim to a log ADR 19 forbids editing, and because an
   * agent's shell is initialised from the owner's profile and inherits {@code SEGUE_DB} — a
   * variable cannot tell the owner apart from an agent running as the owner.
   *
   * <p><b>This rule is the second line of defence, not the first, and an earlier draft of this
   * javadoc had that backwards.</b> It claimed the refusal tests would still pass if a later edit
   * wired the default in behind the refusal. They do not. Measured against three separate plants —
   * a call to {@link DefaultDatabase#resolve}, the env-or-home rule re-implemented inline, and the
   * same rule in a class outside {@code support} — each one reds <b>three</b> tests in {@code
   * RetractCliTest} or {@code OwnCliTest}: the refusal, the {@code --dry-run} refusal and the
   * {@code SEGUE_DB}-only refusal, each of which also asserts that no database was created under
   * the test's own home. Those tests are what catches a default coming back.
   *
   * <p>What this rule adds is what a behaviour test cannot survive: it still holds when the tests
   * are <em>edited to match</em>, which is the ordinary way a guard dies — someone wires the
   * default in, three tests go red, and the cheapest way back to green is to change what they
   * expect. This rule has to be deleted deliberately instead, and it states the decision at the
   * boundary rather than inside a test's expectations.
   *
   * <p>{@code dependOnClassesThat} rather than {@code callMethodWhere}, deliberately. A dependency
   * is any constant-pool reference — a call, a method reference, a field of that type, a signature
   * — so the rule does not have to anticipate the shape the reintroduction takes. {@code
   * callConstructorWhere} would have missed a {@code DefaultDatabase::resolve} method reference
   * entirely, which is the trap issue #105 already recorded in {@link #callTo}.
   *
   * <p><b>Prose does not count, and that is why this rule needs a control that plants real
   * code.</b> Both CLIs name {@code DefaultDatabase} inside {@code &#123;@code …&#125;} javadoc to
   * say they do not use it, and javadoc leaves no bytecode edge: {@code javap -v} finds zero
   * references to it in {@code RetractCli}, {@code OwnCli} and all their nested classes, against
   * four in each class that really does call {@code resolve}. A control that planted only a comment
   * would pass while testing nothing.
   */
  @ArchTest
  static final ArchRule theClaimToolsHaveNoDefaultDatabase =
      noClasses()
          .that()
          .resideInAnyPackage("..retract..", "..own..")
          .should()
          .dependOnClassesThat(JavaClass.Predicates.equivalentTo(DefaultDatabase.class))
          .because(
              "#179: retraction and owner-claim append a permanent first-person claim, so the"
                  + " database is named per invocation and there is no default here to fall back"
                  + " to — SEGUE_DB is inherited by any shell started from the owner's profile");

  /**
   * A {@link Path} handed out by anything in {@code support} — a method's return, a field's type.
   *
   * <p>Both forms, because a capability does not care how it is packaged: {@code
   * RequiredDatabase.resolved(env, home)} and {@code public static final Path DEFAULT} give a
   * caller the same thing. {@code CodeUnitAccessTarget} covers a call <b>and</b> a method reference
   * (they share that supertype), which is the distinction {@link #callTo} exists to make and the
   * one a {@code callMethodWhere} predicate would have lost.
   */
  private static final DescribedPredicate<JavaAccess<?>> A_PATH_TAKEN_OUT_OF_SUPPORT =
      new DescribedPredicate<>("a Path taken out of support") {
        @Override
        public boolean test(JavaAccess<?> access) {
          AccessTarget target = access.getTarget();
          if (!target.getOwner().getPackageName().startsWith("com.robsartin.segue.support")) {
            return false;
          }
          JavaClass handedBack =
              switch (target) {
                case AccessTarget.CodeUnitAccessTarget codeUnit -> codeUnit.getRawReturnType();
                case AccessTarget.FieldAccessTarget field -> field.getRawType();
                default -> null;
              };
          return handedBack != null && handedBack.isEquivalentTo(Path.class);
        }
      };

  /**
   * #179: the claim tools' database comes from the flag they were typed with, and from nowhere
   * else.
   *
   * <p>{@link #theClaimToolsHaveNoDefaultDatabase} forbids a <em>name</em>. This forbids the
   * <em>capability</em>, and the difference is not academic — it was measured. Both tools depend on
   * {@code support.RequiredDatabase}, which calls {@link DefaultDatabase#resolve} itself for the
   * path it quotes back in its refusal. Give that class one more public method returning a {@link
   * Path} and wire it into either tool, and the default is back with the same reach it had before
   * #179 — while the class the other rule names is never mentioned. Planted exactly that way, the
   * other rule stayed <b>green</b>.
   *
   * <p><b>Why {@code Path} is the line, and why a {@code String} is not.</b> The refusal has to
   * quote the database the tool would once have used or the owner's next command is a lookup, so
   * {@code support} genuinely does hand these two packages that path — inside a sentence. What
   * separates the sentence from the capability is the type: a {@code String} has to be parsed back
   * into a {@code Path} by a line somebody has to write and a reviewer can see, and a {@code Path}
   * does not. That is the whole reason {@code RequiredDatabase.refusal} returns one and not the
   * other, and this rule is what makes that a property of the build rather than of the javadoc that
   * asserts it.
   *
   * <p><b>Over {@code support} as a package, not over {@code RequiredDatabase} as a class.</b> A
   * rule naming the one class that bridges these packages today would be the same shape as the
   * mistake it guards — the next helper to carry a path across would inherit nothing, exactly as
   * {@code ArchitectureTest}'s hand-written sibling lists kept missing the newest tool until issue
   * #105 derived them. The claim tools call nothing in {@code support} but {@code refusal} today,
   * so the whole surface can be fenced at no cost to anything that exists.
   *
   * <p><b>Three routes leave every rule in this class green</b>, all three measured rather than
   * reasoned about, and all three caught by the refusal tests instead: the env-or-home rule
   * re-implemented inline in either CLI (the mistake this branch already made once); a {@code
   * support} helper returning the default as a {@code String} for the caller to parse; and the same
   * helper returning a {@code Path} from <b>any package that is not {@code support}</b> — a new
   * {@code com.robsartin.segue.dbpath.DbPath} wired into {@code OwnCli} passed both #179 rules and
   * {@link #theOwnerClaimToolOpensNothingElse} together. The fence is scoped to one package and one
   * class name, so a path handed out from elsewhere is outside it by construction. {@code Path.of}
   * on the flag's own value is of course what both tools do and must keep doing.
   */
  @ArchTest
  static final ArchRule theClaimToolsTakeTheirDatabaseFromTheFlagAlone =
      noClasses()
          .that()
          .resideInAnyPackage("..retract..", "..own..")
          .should(ArchConditions.accessTargetWhere(A_PATH_TAKEN_OUT_OF_SUPPORT))
          .because(
              "#179: retraction and owner-claim take the database from the --db they were typed"
                  + " with — support may hand them the default path inside a refusal sentence, and"
                  + " may not hand either of them a Path they could open");

  /**
   * ADR 63: the census has no default database either, and it is fenced separately from ADR 60's
   * two claim tools.
   *
   * <p><b>Why a third rule rather than a wider one.</b> {@link #theClaimToolsHaveNoDefaultDatabase}
   * and {@link #theClaimToolsTakeTheirDatabaseFromTheFlagAlone} are named for the tools that append
   * a first-person claim, ADR 60 names both rules in its text and is immutable, and its
   * consequences say in as many words that a third tool would have to be added by hand. Widening
   * them would make two rule names describe something that is not a claim tool.
   *
   * <p><b>Why the census requires the flag at all, when nothing here writes.</b> ADR 60's central
   * clause rather than its consequence: an agent's shell is initialised from the owner's profile
   * and inherits {@code SEGUE_DB}, and this tool's output is the shape of the owner's whole graph
   * and taste layer. Aggregates are publishable (ADR 51); whether to publish them is the owner's
   * decision per invocation.
   */
  @ArchTest
  static final ArchRule theCensusHasNoDefaultDatabase =
      noClasses()
          .that()
          .resideInAPackage("..census..")
          .should()
          .dependOnClassesThat(JavaClass.Predicates.equivalentTo(DefaultDatabase.class))
          .because(
              "ADR 63: the census names its database on the command line — SEGUE_DB is inherited by"
                  + " any shell started from the owner's profile, so it cannot stand in for a flag"
                  + " typed per invocation");

  /**
   * The sibling of {@link #theCensusHasNoDefaultDatabase}, and the reason ADR 60 gives for having
   * two: the first forbids a <em>name</em> and the second forbids the <em>capability</em>. {@code
   * census} depends on {@code support.RequiredDatabase} for the refusal sentence, and that class
   * calls {@code DefaultDatabase} itself — so a {@code Path}-returning method added there and wired
   * in restores the default while the rule above stays green. Planted exactly that way for ADR 60,
   * measured green; the same hole is the same hole here.
   */
  @ArchTest
  static final ArchRule theCensusTakesItsDatabaseFromTheFlagAlone =
      noClasses()
          .that()
          .resideInAPackage("..census..")
          .should(ArchConditions.accessTargetWhere(A_PATH_TAKEN_OUT_OF_SUPPORT))
          .because(
              "ADR 63, on ADR 60's measurement: a fence that forbids a class name stops only the"
                  + " lazy version — what has to be unavailable is any route from support to a"
                  + " java.nio.file.Path");

  /**
   * The taste layer, by type rather than by package.
   *
   * <p>Every other rule in this class names a package, because every other boundary in this project
   * IS a package. ADR 33's boundary is not: the affinity port sits in {@code port} beside {@code
   * AssertionLog}, its record in {@code domain} beside {@code AssertionRecord}, its store in {@code
   * sqlite} beside {@code SqliteAssertionLog} — each one where its layer's conventions put it. A
   * fifth package for four classes would have made the rule easier to write and the codebase harder
   * to read, so the predicate does the work the package name would have done.
   */
  private static final DescribedPredicate<JavaClass> AFFINITY_TYPES =
      new DescribedPredicate<>("part of the taste layer (ADR 33)") {
        @Override
        public boolean test(JavaClass type) {
          return type.getPackageName().startsWith("com.robsartin.segue")
              && (type.getSimpleName().contains("Affinity")
                  || type.getSimpleName().equals("TasteTools"));
        }
      };

  /** The world-fact layer's own vocabulary: the log, the graph, and the claim types they carry. */
  private static final DescribedPredicate<JavaClass> WORLD_FACT_TYPES =
      new DescribedPredicate<>("the world-fact layer's stores and claim types (ADR 19)") {
        private final Set<String> names =
            Set.of(
                GraphStore.class.getName(),
                AssertionLog.class.getName(),
                IngestService.class.getName(),
                LoggedAssertion.class.getName(),
                AssertionRecord.class.getName(),
                NodeAssertion.class.getName(),
                EdgeRecord.class.getName(),
                Provenance.class.getName());

        @Override
        public boolean test(JavaClass type) {
          return names.contains(type.getFullName());
        }
      };

  /**
   * ADR 33: affinity is not an assertion, and the taste layer never writes to the graph or the log.
   *
   * <p>This is the invariant the whole ADR rests on, and the tempting violation is small: give
   * {@code AffinityRecord} a {@link Provenance} so it looks like everything else, or let the
   * affinity store append a "user rated this" row to the log so the history is all in one place.
   * Either would compile and pass every other test in the suite.
   */
  @ArchTest
  static final ArchRule affinityNeverTouchesTheWorldFactLayer =
      noClasses()
          .that(AFFINITY_TYPES)
          .should()
          .dependOnClassesThat(WORLD_FACT_TYPES)
          .because(
              "ADR 33: affinity carries no provenance and no corroboration, and note_affinity is"
                  + " the only writer of the taste layer — it never writes to the graph or the log");

  /**
   * ADR 33, from the other side: ingest and the graph adapters must not learn that taste exists.
   *
   * <p>{@code IngestService} never sees a rating, and a source adapter cannot be tempted to emit
   * one. This is what keeps "the world graph can be shared, exported or made public without
   * carrying personal data" true by construction rather than by care — which matters more here than
   * usual, because this repository IS public (issue #37).
   */
  @ArchTest
  static final ArchRule theWorldFactLayerNeverTouchesAffinity =
      noClasses()
          .that()
          .resideInAnyPackage(
              "..ingest..", "..tinker..", "..jena..", "..wikidata..", "..musicbrainz..")
          .should()
          .dependOnClassesThat(AFFINITY_TYPES)
          .because(
              "ADR 33: IngestService never sees a rating, and the world graph stays free of"
                  + " personal data so it can be exported or shared without one");

  /** ADR 32's layering is unidirectional by construction, so any slice cycle is a violation. */
  @ArchTest
  static final ArchRule noPackageCycles =
      SlicesRuleDefinition.slices().matching("com.robsartin.segue.(*)..").should().beFreeOfCycles();

  /** ADR 32: the framework lives at the edges. Everything else stays plain Java. */
  @ArchTest
  static final ArchRule springOnlyInAppAndMcp =
      noClasses()
          .that()
          .resideOutsideOfPackages("..app..", "..mcp..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("org.springframework..")
          .because(
              "ADR 25 and ADR 32: adapters must be testable without an application context,"
                  + " and adding a source must not require a framework");

  /**
   * One JSON library, one major version. Jackson 3 lives under {@code tools.jackson}; Jackson 2's
   * {@code com.fasterxml.jackson.core}/{@code .databind}/{@code .datatype} packages are what this
   * rule keeps out. {@code com.fasterxml.jackson.annotation} is deliberately NOT listed: Jackson 3
   * kept its annotations on the old coordinates, so {@code ToolResult}'s {@code @JsonValue} is a
   * Jackson 3 import despite how it reads.
   *
   * <p>The two-major split was never a decision anyone made, and it is what let issue #18 happen —
   * the tool surface was serialised by the one Jackson that cannot write a {@code
   * java.time.Instant} without an extra module, while the MCP SDK next to it used Jackson 3, which
   * can.
   */
  @ArchTest
  static final ArchRule onlyJackson3 =
      noClasses()
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.fasterxml.jackson.core..",
              "com.fasterxml.jackson.databind..",
              "com.fasterxml.jackson.datatype..")
          .because(
              "ADR 35: Jackson 3 is the one JSON library — the MCP SDK already speaks it and"
                  + " it handles java.time natively, so a second major on the classpath buys"
                  + " nothing and costs a serialisation bug (issue #18)");
}
