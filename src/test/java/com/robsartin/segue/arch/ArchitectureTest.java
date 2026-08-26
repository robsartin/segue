package com.robsartin.segue.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.robsartin.segue.app.SegueApplication;
import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.EdgeRecord;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.ingest.IngestService;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.port.GraphStore;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.properties.HasName;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import java.util.Set;

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

  /** ADR 32: adapters never depend on each other. */
  @ArchTest
  static final ArchRule tinkerDoesNotDependOnJena =
      noClasses()
          .that()
          .resideInAPackage("..tinker..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..jena..")
          .because("ADR 32: adapters are siblings, not collaborators");

  /** ADR 32: adapters never depend on each other. */
  @ArchTest
  static final ArchRule jenaDoesNotDependOnTinker =
      noClasses()
          .that()
          .resideInAPackage("..jena..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..tinker..")
          .because("ADR 32: adapters are siblings, not collaborators");

  /** ADR 32: adapters depend downward only — never on ingest, mcp or app. */
  @ArchTest
  static final ArchRule adaptersDoNotDependUpward =
      noClasses()
          .that()
          .resideInAnyPackage("..tinker..", "..jena..", "..sqlite..", "..wikidata..")
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
   */
  @ArchTest
  static final ArchRule seedNeverOpensAStore =
      noClasses()
          .that()
          .resideInAPackage("..seed..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "..sqlite..", "..tinker..", "..jena..", "..ingest..", "..mcp..", "..app..")
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
   * A call to {@code name} on an owner assignable to {@code owner}.
   *
   * <p>Assignability, not the exact owner, for the same reason {@link #noPrintStackTrace} needs it:
   * javac encodes the call-site owner as the <em>static type of the receiver expression</em>. A
   * field declared {@code SqliteAssertionLog} rather than {@code AssertionLog} compiles an owner of
   * the implementation, and the exact-owner form would miss it — which is the shape a bypass would
   * most plausibly take, since a class reaching around {@code IngestService} is a class that has
   * already helped itself to a concrete store.
   */
  private static DescribedPredicate<JavaCall<?>> callTo(String name, Class<?> owner) {
    return JavaCall.Predicates.target(HasName.Predicates.name(name))
        .and(JavaAccess.Predicates.targetOwner(JavaClass.Predicates.assignableTo(owner)));
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
          .callMethodWhere(callTo("printStackTrace", Throwable.class))
          .because("ADR 30: SLF4J is the only logging API, and stack traces belong in a logger");

  /** ADR 30: no competing logging API. */
  @ArchTest
  static final ArchRule noJavaUtilLogging =
      noClasses()
          .should()
          .dependOnClassesThat()
          .resideInAPackage("java.util.logging..")
          .because("ADR 30: SLF4J is the only logging API");

  /** ADR 32: adapters never depend on each other. */
  @ArchTest
  static final ArchRule sqliteDoesNotDependOnOtherAdapters =
      noClasses()
          .that()
          .resideInAPackage("..sqlite..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..tinker..", "..jena..", "..wikidata..")
          .because("ADR 32: adapters are siblings, not collaborators");

  /**
   * The three writes that put a claim somewhere durable: both halves of {@code IngestService.apply}
   * and the log append that must precede them.
   */
  private static final DescribedPredicate<JavaCall<?>> APPLIES_A_CLAIM =
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
          .callMethodWhere(APPLIES_A_CLAIM)
          .because(
              "ADR 19: the log is the source of truth and only ingest projects it — a graph write"
                  + " that skipped the log would be gone at the next boot");

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
          .resideInAnyPackage("..ingest..", "..tinker..", "..jena..", "..wikidata..")
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

  /** ADR 32: wikidata is an adapter like any other. */
  @ArchTest
  static final ArchRule wikidataDoesNotDependOnOtherAdapters =
      noClasses()
          .that()
          .resideInAPackage("..wikidata..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..tinker..", "..jena..", "..sqlite..")
          .because("ADR 32: adapters are siblings, not collaborators");

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
