package com.robsartin.segue.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.robsartin.segue.port.GraphStore;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.properties.HasName;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;

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
          .resideInAnyPackage("..ingest..", "..mcp..", "..app..")
          .because("ADR 32: adapters are the bottom of the dependency graph");

  /** ADR 28: stdout belongs to the MCP protocol and nothing else. */
  @ArchTest
  static final ArchRule nothingWritesToStandardOut =
      noClasses()
          .should()
          .accessField(System.class, "out")
          .because(
              "ADR 28: on the stdio transport stdout carries the protocol; a stray"
                  + " println corrupts the JSON-RPC stream");

  /** ADR 30: SLF4J is the only logging API, and stderr is written through it. */
  @ArchTest
  static final ArchRule nothingWritesToStandardError =
      noClasses()
          .should()
          .accessField(System.class, "err")
          .because("ADR 30: logging goes through SLF4J, which is configured to target stderr");

  /**
   * ADR 30: printStackTrace writes to stderr without touching System.err.
   *
   * <p>Matched by target name and owner-assignability rather than {@code
   * callMethod(Throwable.class, "printStackTrace")}, because javac encodes the call-site owner as
   * the caught variable's static type — {@code catch (RuntimeException e) { e.printStackTrace(); }}
   * compiles an owner of {@code RuntimeException}, not {@code Throwable}, so the exact-owner form
   * silently misses the single most common shape of this bug.
   */
  @ArchTest
  static final ArchRule noPrintStackTrace =
      noClasses()
          .should()
          .callMethodWhere(
              JavaCall.Predicates.target(HasName.Predicates.name("printStackTrace"))
                  .and(
                      JavaAccess.Predicates.targetOwner(
                          JavaClass.Predicates.assignableTo(Throwable.class))))
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
   * ADR 19: the graph is a derived projection, so only {@code ingest} replays claims into it via
   * {@code GraphStore.record}. Everything else appends to the log; nothing edits the graph
   * directly.
   */
  @ArchTest
  static final ArchRule onlyIngestAppliesClaimsToTheGraph =
      noClasses()
          .that()
          .resideOutsideOfPackage("..ingest..")
          .should()
          .callMethodWhere(
              JavaCall.Predicates.target(HasName.Predicates.name("record"))
                  .and(
                      JavaAccess.Predicates.targetOwner(
                          JavaClass.Predicates.assignableTo(GraphStore.class))))
          .because("ADR 19: the log is the source of truth and only ingest projects it");

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
}
