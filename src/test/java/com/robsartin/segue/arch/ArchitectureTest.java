package com.robsartin.segue.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.robsartin.segue.app.SegueApplication;
import com.robsartin.segue.domain.AffinityRecord;
import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.EdgeRecord;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.ingest.IngestService;
import com.robsartin.segue.port.AffinityStore;
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
import com.tngtech.archunit.lang.conditions.ArchConditions;
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
              "..sqlite..",
              "..tinker..",
              "..jena..",
              "..ingest..",
              "..mcp..",
              "..app..",
              "..retract..",
              "..rate..")
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
   * covered only half of what it claimed. Both packages are banned now, and they write to different
   * layers: {@code retract} appends a world-fact claim through {@code IngestService}, {@code rate}
   * writes the taste layer and never touches {@code ingest} at all (ADR 33).
   */
  @ArchTest
  static final ArchRule theExporterOnlyReads =
      noClasses()
          .that()
          .resideInAPackage("..export..")
          .should(
              ArchConditions.callMethodWhere(APPLIES_A_CLAIM)
                  .or(
                      ArchConditions.dependOnClassesThat(
                          JavaClass.Predicates.equivalentTo(IngestService.class)))
                  // ADR 44 added a fourth dev-side tool, and it was the first one that writes;
                  // ADR 46's rating deck is the second. Without these two clauses the exporter
                  // could reach RetractRun and append a retraction through it, or RateServer and
                  // write a rating through that, inheriting a looser fence than its own — the
                  // exact shape theRatingsToolOpensNothingElse already refuses for its siblings.
                  // The two writers write to different layers and neither is the exporter's.
                  .or(
                      ArchConditions.dependOnClassesThat(
                          JavaClass.Predicates.resideInAnyPackage("..retract..", "..rate.."))))
          .because(
              "ADR 41: the exporter is a read-only tool — it never appends to the log, never"
                  + " writes the graph, and cannot reach the one class that is allowed to, nor"
                  + " either of the two dev-side tools that write (ADR 44, ADR 46)");

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
   */
  @ArchTest
  static final ArchRule theExporterNeverSpeaksToANetwork =
      noClasses()
          .that()
          .resideInAPackage("..export..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("java.net..", "javax.net..", "..wikidata.WikidataClient")
          .because(
              "ADR 41: an export is a pure function of the database file — a class label fetched at"
                  + " export time would make a picture depend on the internet being up");

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
              ArchConditions.callMethodWhere(
                  APPLIES_A_CLAIM.or(callTo("put", AffinityStore.class))))
          .because(
              "ADR 43: listing your ratings is a read — the tool never appends to the log, never"
                  + " writes the graph, and never writes the taste layer it exists to display");

  /**
   * ADR 43: the ratings tool opens two stores in one file and nothing else.
   *
   * <p>The tightest of the three dev-tool fences, and it can be, because this tool needs the least:
   * a bulk read of the {@code affinity} table and the node claims in the log, both through {@code
   * sqlite}. No traversal, so no {@code tinker}; no projection, so no {@code ingest}; no picture,
   * so no {@code export}. {@code seed} and {@code export} are banned as well as the application
   * packages, because a dependency on a sibling tool would quietly let this one inherit the
   * sibling's looser fence - {@code export} may use {@code GraphProjector}, and this may not.
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
              "..tinker..",
              "..jena..",
              "..ingest..",
              "..mcp..",
              "..app..",
              "..seed..",
              "..export..",
              "..retract..",
              "..rate..",
              "java.net..",
              "javax.net..")
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
          .callMethodWhere(callTo("readAll", AffinityStore.class))
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
          .callMethodWhere(callTo("note", AffinityRecord.class))
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
              ArchConditions.callMethodWhere(APPLIES_A_CLAIM.or(callTo("put", AffinityStore.class)))
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
   * has no {@code find} ban anywhere, and {@link #theRatingDeckLogsNoRating} lets exactly one class
   * name the record — {@code RateServer}, which has to construct the one it writes. What holds the
   * deck off the words is {@link #theRatingDeckNeverReadsANote}, which bans the accessor for every
   * class in {@code rate}, {@code RateServer} included and with no exception.
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
                      ArchConditions.callMethodWhere(
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
   * appearing on the surface would arrive as a field on an existing tool rather than as a seventh
   * tool, and {@code ToolSurfaceTest} counts tools, so it would not notice. ADR 45 recorded a
   * re-open condition for a conversational recommendation and issue #85 deliberately did not
   * exercise it; until an ADR does, {@code get_entity} answers one qid at a time.
   *
   * <p>Widened by issue #101 (ADR 46): the deck needs the same note-free map to know which entities
   * are already rated and must not be dealt again, which is the resume mechanism {@code Deck}'s
   * class comment describes. Both readers are dev-side tools off the MCP surface, so the thing this
   * rule actually protects is unchanged.
   */
  @ArchTest
  static final ArchRule onlyTheRecommenderReadsEveryRating =
      noClasses()
          .that()
          .resideOutsideOfPackages("..recommend..", "..rate..")
          .should()
          .callMethodWhere(callTo("readRatings", AffinityStore.class))
          .because(
              "ADR 26 and issues #85 and #101: the score is ordinary data, and reading every score"
                  + " at once is a dev-side tool's job — the recommender or the rating deck — rather"
                  + " than a field on an MCP tool");

  /**
   * Issue #101: the deck writes the taste layer and nothing else.
   *
   * <p>The mirror image of {@code theRatingsToolOnlyReads}. That tool may read every rating and
   * write none; this one may write a rating and must not touch the graph or the log. Between them
   * the two dev tools that meet the affinity table can each do exactly one thing to it.
   */
  @ArchTest
  static final ArchRule theRatingDeckWritesOnlyAffinity =
      noClasses()
          .that()
          .resideInAPackage("..rate..")
          .should(ArchConditions.callMethodWhere(APPLIES_A_CLAIM))
          .because(
              "ADR 46: the deck records what the owner thinks and never what the world says — it"
                  + " appends no claim, records no edge and upserts no node");

  /**
   * Issue #85, held by construction and then by rule.
   *
   * <p>{@code Card} has no note field, so there is nothing for the page to render even by accident;
   * this stops the field being reintroduced by someone who thinks it would be handy.
   */
  @ArchTest
  static final ArchRule theRatingDeckNeverReadsANote =
      noClasses()
          .that()
          .resideInAPackage("..rate..")
          .should(ArchConditions.callMethodWhere(callTo("note", AffinityRecord.class)))
          .because(
              "issue #85: a rating is ordinary data and a note is not — the deck writes the first"
                  + " and must never be able to display the second");

  /**
   * ADR 33: no rating reaches a log line. RateServer holds one just long enough to write it.
   *
   * <p>Narrower than it looks, and the narrowing is the whole decision. Written blanket first —
   * {@code noClasses().that().resideInAPackage("..rate..")} against {@code AffinityRecord} as a
   * type — it failed naming {@link com.robsartin.segue.rate.RateServer}, because that class must
   * construct the record it writes: {@code affinity.put(new AffinityRecord(...))} in {@code rate}
   * is not a bug, it is the one write this package exists to make. The deck logs a port, a count
   * and a path; a qid paired with a score is the personal part, and the easiest way to leak it is a
   * debug line added while chasing something else. Excluding {@code RateServer} by name states that
   * exception where it can be read rather than designing around it silently, and every other class
   * in {@code rate} — {@code Card}, {@code Deck}, {@code RateRun}, {@code RateCli} — still cannot
   * hold a rating at all.
   */
  @ArchTest
  static final ArchRule theRatingDeckLogsNoRating =
      noClasses()
          .that()
          .resideInAPackage("..rate..")
          .and()
          .haveSimpleNameNotEndingWith("RateServer")
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName("com.robsartin.segue.domain.AffinityRecord")
          .because(
              "ADR 33 keeps affinity out of every log line. RateServer is the single exception,"
                  + " because it must build the record it writes; nothing else in the deck may"
                  + " hold a rating at all, and RateServer owns no logger that prints one");

  /**
   * ADR 46: the deck needs a log, an engine, the recommender's sweep and nothing else.
   *
   * <p>The sixth tool's half of the fence every dev tool carries — {@link #seedNeverOpensAStore},
   * {@link #theRatingsToolOpensNothingElse}, {@link #theRecommenderOpensNothingElse}, {@link
   * #theRetractionToolOpensNothingElse} — and for the same reason: a dependency on a sibling lets
   * this tool inherit that sibling's fence instead of its own. It writes a rating and nothing else,
   * so reaching {@code retract} (which appends a world-fact claim) or {@code ratings} (which reads
   * every note) would each be a way around a rule this package is otherwise held to.
   *
   * <p><b>{@code recommend} is deliberately NOT banned, and it is the only sibling pair in the
   * project that may depend on each other.</b> The candidate half of the deck is the recommender's
   * own {@code CandidateSweep}, {@code Routes} and {@code Sweep}, so that a card's routes are the
   * routes that tool would give for the same pair rather than a second implementation that can
   * drift. ADR 46 argues that dependency and ADR 45 moved {@code QidList} into {@code support}
   * rather than let a shared reader create it by accident. It runs one way only: {@link
   * #theRecommenderOpensNothingElse} bans the return trip.
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
              "..jena..",
              "..mcp..",
              "..app..",
              "..seed..",
              "..export..",
              "..ratings..",
              "..retract..")
          .because(
              "ADR 46: the deck replays one local log into one in-memory graph and serves it on"
                  + " loopback — it needs no second engine and no sibling tool but the recommender,"
                  + " whose sweep it reuses on purpose, and cannot become an MCP tool by accident");

  /**
   * ADR 45: the recommender needs a log, an engine and nothing else.
   *
   * <p>The same fence its siblings carry, with the same reasoning. {@code seed}, {@code export},
   * {@code ratings} and {@code retract} are banned as packages because a dependency on a sibling
   * would let this tool inherit the sibling's different fence - {@code retract} may write, and this
   * may not. {@code java.net} because a recommendation is a pure function of one local file: the
   * list of what somebody already knows never leaves the machine, which is ADR 40's argument for
   * why the seeding list lives outside this repository, applied to the tool that reads it.
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
              "..jena..",
              "..mcp..",
              "..app..",
              "..seed..",
              "..export..",
              "..ratings..",
              "..retract..",
              "..rate..",
              "java.net..",
              "javax.net..")
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
   * <p>So the four durable writes are all forbidden <em>here</em> as well as from wherever else
   * they are already forbidden: {@code AssertionLog.append} directly (it must go through {@code
   * ingest}, so that {@link #onlyIngestAppliesClaimsToTheGraph} keeps meaning what it says), both
   * halves of the graph write, and {@code AffinityStore.put}. That last one matters most: a
   * retraction is about the world-fact layer, and ADR 33 keeps the taste layer out of it entirely.
   * A rating is the one thing in segue that cannot be regenerated, and the tool whose whole purpose
   * is removing things must be unable to touch it.
   */
  @ArchTest
  static final ArchRule theRetractionToolWritesOnlyRetractions =
      noClasses()
          .that()
          .resideInAPackage("..retract..")
          .should(
              ArchConditions.callMethodWhere(
                  APPLIES_A_CLAIM
                      .or(callTo("put", AffinityStore.class))
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
   * one inherit the sibling's different fence. {@code java.net} because a decision about your own
   * graph is a pure function of one local file, and nothing about it leaves the machine.
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
                          "..tinker..",
                          "..jena..",
                          "..mcp..",
                          "..app..",
                          "..seed..",
                          "..export..",
                          "..ratings..",
                          "..rate..",
                          "java.net..",
                          "javax.net..")))
          .because(
              "ADR 44: retraction is a decision about the log, made offline, from a tool that"
                  + " cannot hold a graph, a rating, an engine or a network connection");

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
