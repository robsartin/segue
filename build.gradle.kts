plugins {
    java
    jacoco
    alias(libs.plugins.spotless)
    alias(libs.plugins.spring.boot)
}

group = "com.robsartin"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    // Chosen engine. See docs/adr/0018-graph-engine-gremlin.md.
    implementation(libs.tinkergraph)
    // Reference implementation, kept working as a cross-check.
    implementation(libs.jena.arq)
    // Assertion-log persistence. See docs/adr/0024-sqlite-assertion-log.md.
    implementation(libs.sqlite.jdbc)
    // Wikidata responses, and the JSON on the MCP tool surface. Jackson 3 (tools.jackson),
    // not Jackson 2 — the MCP SDK already speaks it via mcp-json-jackson3, and it handles
    // java.time natively, so there is no second major on the classpath and no JSR-310 module
    // to remember (ADR 35). The version comes from the managed BOM, per the rule that versions
    // are never named in this file.
    implementation(libs.jackson.databind)
    implementation(libs.jspecify)

    // MCP server. See docs/adr/0032-layering-and-archunit.md — fenced to app and mcp.
    // spring-boot-starter brings Logback as the SLF4J binding (ADR 30: structured JSON to
    // stderr, no additional dependency), which supersedes the slf4j-nop placeholder that
    // stood in for logging before Spring existed. Keeping both on the classpath makes
    // Boot's LoggingApplicationListener refuse to start: "LoggerFactory is not a Logback
    // LoggerContext but Logback is on the classpath."
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.spring.ai.bom))
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.ai.starter.mcp.server)
    // The Streamable HTTP transport (ADR 28 ships both). The servlet flavour, not WebFlux: the
    // rest of this application is blocking — SQLite over JDBC, an in-process Gremlin traversal —
    // and a reactive stack would buy nothing but a second concurrency model to reason about.
    // This starter also drags in spring-boot-starter-web, which is what makes the `stdio` profile's
    // web-application-type: none load-bearing rather than decorative.
    implementation(libs.spring.ai.starter.mcp.server.webmvc)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.archunit.junit6)
    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    // Compiles on the toolchain JDK while staying runnable on 21.
    options.release.set(21)
    options.compilerArgs.add("-Xlint:unchecked")
}

tasks.test {
    // sqlite-jdbc loads a native library; grant it so the JDK's restricted-method
    // warning does not become a failure on a future release.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // StreamableHttpTransportTest sends a forged Host header to prove the DNS-rebinding defence
    // (ADR 28) answers 421. java.net.http.HttpClient refuses to set Host at all without this flag,
    // so without it the security control could only be configured, never tested — and a security
    // control nothing exercises is a comment.
    jvmArgs("-Djdk.httpclient.allowRestrictedHeaders=host")
    // StdioPurityTest launches the real application as a subprocess and needs its runtime
    // classpath. Passed as a system property rather than relying on a packaged jar, so the
    // test runs against exactly what this build just compiled — no separate bootJar step.
    systemProperty("segue.mainRuntimeClasspath", sourceSets["main"].runtimeClasspath.asPath)
    // Keep every @SpringBootTest off the developer's real graph. Without this they inherit
    // src/main/resources/application.yaml's default of ${user.home}/.segue/segue.db, so `./gradlew
    // check` would create ~/.segue/, open the real assertion log and replay whatever is in it.
    //
    // A system property, not a src/test/resources/application.yaml. That file used to hold this
    // override and looked like it overrode one key; it did not. Spring Boot resolves
    // classpath:/application.yaml to the FIRST match on the classpath, and test resources come
    // first, so the whole of main's application.yaml — transport protocol, endpoint, server bind
    // address, MCP server name — was invisible to every test in the suite. That is the opposite of
    // what an integration test is for, and it is why the Streamable HTTP transport could not be
    // tested against its own configuration until the file was deleted. A system property sits above
    // config data in Spring's precedence order, so it overrides exactly the one key it names and
    // shadows nothing. @DynamicPropertySource still wins over it, which is what lets individual
    // tests point at their own @TempDir.
    //
    // build/, not a TempDir: `./gradlew clean` removes it, and nothing in the suite reads a graph
    // seeded by a previous run through this default.
    // DeckBehaviourTest runs the real deck page in a real headless Chrome (issue #103) and skips
    // itself where none is installed, so `./gradlew check` stays green on a machine without one.
    // CI sets SEGUE_REQUIRE_BROWSER, which turns that skip into a failure — #93 installed Graphviz
    // for exactly this reason: the one check standing between deck.html and a silent regression
    // must not be able to report success by never having run.
    systemProperty(
        "segue.chrome",
        providers.systemProperty("segue.chrome").getOrElse(""),
    )
    systemProperty(
        "segue.requireBrowser",
        providers.environmentVariable("SEGUE_REQUIRE_BROWSER").getOrElse("false"),
    )
    // The same rule for the same reason, one dependency over (issue #164). WhatAHoverShowsTest and
    // ImagemapRecipeTest render through the real `dot` — the <title> a browser shows and the
    // imagemap recipe the guide ships are both written by Graphviz, so nothing here can assert them
    // — and they skipped themselves where it was absent. #93 installed Graphviz in CI precisely so
    // they run; without this flag a degraded install left the suite green having rendered nothing.
    systemProperty(
        "segue.requireGraphviz",
        providers.environmentVariable("SEGUE_REQUIRE_GRAPHVIZ").getOrElse("false"),
    )
    systemProperty(
        "segue.database",
        layout.buildDirectory.file("test-data/segue-test.db").get().asFile.absolutePath,
    )
    // HeadlessChromeNetworkTest keeps the NetLog it measured at build/reports/netlog/<test>.json,
    // inside the tree the CI workflow uploads as the `reports` artifact. Its allowlist is
    // per-platform — CI run 33655745937 reddened on redirector.gvt1.com, which Linux Chrome asks
    // for and macOS Chrome does not — and without the log in the artifact the CI host set could be
    // read only out of an assertion message. Passed rather than assumed relative to the working
    // directory, so the copy follows a relocated build directory.
    systemProperty(
        "segue.reports",
        layout.buildDirectory.dir("reports").get().asFile.absolutePath,
    )
    // Documents this suite reads and checks. Gradle's up-to-date check knows about compiled
    // classes, not about Markdown — without these two lines, falsifying a document and running
    // `./gradlew check` reports BUILD SUCCESSFUL because `test` is skipped entirely. That has now
    // been measured on a real commit shape for each guard that reads a document:
    //   - DeveloperGuideEnumerationsTest re-derives the guide's enumerations from the tree and
    //     compares them to docs/developer-guide.md (issue #145) — falsifying the guide passed.
    //   - AdrIndexTest reads docs/adr/README.md and the ADR files beside it (issue #170) — an
    //     index-only commit, exactly the commit that guard exists to check, printed
    //     `Task :test UP-TO-DATE`.
    //   - DocumentationLinksTest follows every link in README.md and docs/**/*.md (issue #168) —
    //     breaking the README's anchor into docs/user-guide.md printed `Task :test UP-TO-DATE`
    //     and BUILD SUCCESSFUL, because the two narrower declarations this replaces — the guide
    //     file and the docs/adr directory — covered neither README.md nor docs/user-guide.md.
    // So the declaration is the whole docs tree plus README.md, not a list of the documents that
    // happen to be read today: a narrower list is this same bug waiting for the next test that
    // reads a document nobody listed. `docs` subsumes the developer-guide and docs/adr
    // declarations this replaces, so they are folded in rather than left to overlap.
    inputs.dir("docs").withPropertyName("docs").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs
        .file("README.md")
        .withPropertyName("readme")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // PackageListsTest derives the dev-tool packages from this file's own JavaExec registrations
    // (issue #165), and `test` is blind to it the same way: measured — with a mainClass the
    // parser must refuse planted in a registration below, `./gradlew test` printed
    // `Task :test UP-TO-DATE` and BUILD SUCCESSFUL before this was added. The cost is that any
    // edit to this file re-runs the suite, which is the same price the two lines above pay.
    inputs
        .file("build.gradle.kts")
        .withPropertyName("buildScript")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    useJUnitPlatform {
        // Excluded from the normal gate: it needs the network and can fail for reasons
        // that have nothing to do with this code. Run it deliberately, via ./gradlew liveTest.
        excludeTags("live")
    }
    testLogging {
        // stderr as well as failures: HeadlessChrome prints one line there when a launch's wait for
        // Chrome's startup cert-verifier flush ended on its fallback bound instead of on the marker
        // (issue #186). That is the case where the deck tests are back to surviving the flush by
        // luck, it is not a failure and must not become one, and a green run that only says so in
        // build/test-results/**.xml says it to nobody.
        events("failed", "standardError")
    }
}

tasks.register<Test>("liveTest") {
    group = "verification"
    description =
        "Runs the tagged live smoke tests against the real Wikidata and MusicBrainz APIs. Needs " +
            "network. The `probe` tag is excluded: see mbProbe, which is the task for it."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    // Every `live` test EXCEPT the probes. A smoke test needs the network and nothing else, so
    // this task can be run on any machine at any time; a probe is an instrument handed a copy of
    // the assertion log through -Dsegue.probe.db, and it refuses to run without one rather than
    // skipping. This task forwards no such property, so a probe reached by the `live` tag alone
    // would make `./gradlew liveTest` unconditionally red — which it was between the commit that
    // added MusicBrainzProbeLiveTest and this line.
    useJUnitPlatform {
        includeTags("live")
        excludeTags("probe")
    }
    // Never up-to-date: the point is to re-check the real endpoint.
    outputs.upToDateWhen { false }
}

tasks.register<Test>("mbProbe") {
    group = "verification"
    description =
        "Runs the MusicBrainz probe behind ADR 55's magnitudes against the real ws/2 and Query " +
            "Service, printing its five blocks and asserting their structure rather than their " +
            "values. Needs network, and needs a COPY: it refuses the owner's own log and " +
            "anything under \$HOME/.segue, because a probe that opens the real database is the " +
            "one thing ADR 55 and issue #167 both forbid. Copy it first — cp " +
            "\$HOME/.segue/segue.db /tmp/segue-probe.db — and pass " +
            "-Dsegue.probe.db=/tmp/segue-probe.db; -Dsegue.probe.seeds shortens the run from its " +
            "default. Write \$HOME and not ~ — a tilde does not expand inside double quotes. " +
            "Example: ./gradlew mbProbe -Dsegue.probe.db=/tmp/segue-probe.db"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    // The other side of liveTest's exclusion, stated in the same vocabulary: that task is `live`
    // minus `probe`, this one is `probe`. A class-name filter would put one fully-qualified name
    // in two tasks and leave the next probe class free to redden liveTest all over again; a tag
    // enrols it in this task instead, which is the property `live` itself already has.
    useJUnitPlatform { includeTags("probe") }
    // sqlite-jdbc loads a native library, the same grant tasks.test makes. liveTest does not make
    // it — nothing tagged `live` opened SQLite until this probe did — so without this line the
    // probe would meet the JDK's restricted-method warning on the copy it was handed.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // The copy is named per invocation and never defaulted; an absent property arrives blank and
    // ProbeDatabase refuses it with the copy step above.
    systemProperty(
        "segue.probe.db",
        providers.systemProperty("segue.probe.db").getOrElse(""),
    )
    systemProperty(
        "segue.probe.seeds",
        providers.systemProperty("segue.probe.seeds").getOrElse(""),
    )
    testLogging {
        // The table is the whole output, and it is written to stdout: without this the run says
        // BUILD SUCCESSFUL and shows nobody what it measured.
        showStandardStreams = true
        events("failed", "standardError")
    }
    // Never up-to-date: the point is to measure the graph and the endpoints as they are now.
    outputs.upToDateWhen { false }
}

tasks.register<JavaExec>("resolveNames") {
    group = "application"
    description =
        "Resolves a name list to Wikidata QIDs, for bulk seeding. Needs network. See ADR 40. " +
            "Example: ./gradlew resolveNames --args=\"--list \$HOME/names.csv\""
    mainClass.set("com.robsartin.segue.seed.SeedCli")
    classpath = sourceSets["main"].runtimeClasspath
    // Never up-to-date: the list, and Wikidata, both change under it. The tool is resumable, so
    // re-running it is cheap — it skips every name an earlier run already answered.
    outputs.upToDateWhen { false }
}

tasks.register<JavaExec>("exportGraph") {
    group = "application"
    description =
        "Exports a bounded view of the graph to DOT or GraphML. Reads only; needs no network. " +
            "The format comes from --format, or from the --out extension when that is absent " +
            "(.dot/.gv, .graphml/.xml; anything else means DOT); a --format that contradicts " +
            "the extension is refused. See ADR 41. Example: ./gradlew exportGraph " +
            "--args=\"--view neighbourhood --qid Q42 --out \$HOME/one.graphml\""
    mainClass.set("com.robsartin.segue.export.ExportCli")
    classpath = sourceSets["main"].runtimeClasspath
    // sqlite-jdbc loads a native library, the same grant tasks.test makes.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // Never up-to-date: the graph changes under it, and the point is to look at it now.
    outputs.upToDateWhen { false }
}

tasks.register<JavaExec>("hoverableSvg") {
    group = "application"
    description =
        "Rewrites a Graphviz-rendered SVG so a browser shows its tooltips. Reads one file and " +
            "writes another; no store, no network. See ADR 41's issue-#99 amendment. Example: " +
            "./gradlew hoverableSvg --args=\"--in \$HOME/one.svg --out \$HOME/one-hoverable.svg\""
    mainClass.set("com.robsartin.segue.export.HoverableSvg")
    classpath = sourceSets["main"].runtimeClasspath
    // Never up-to-date: the render changes under it, and Gradle has no way to know.
    outputs.upToDateWhen { false }
}

tasks.register<JavaExec>("listRatings") {
    group = "application"
    description =
        "Lists every rating with its label, note and when it last changed. Reads only; needs no " +
            "network. The output is personal data (ADR 33) — write it outside the working tree. " +
            "See ADR 43. Example: ./gradlew listRatings " +
            "--args=\"--sort recent --out \$HOME/ratings.txt\""
    mainClass.set("com.robsartin.segue.ratings.RatingsCli")
    classpath = sourceSets["main"].runtimeClasspath
    // sqlite-jdbc loads a native library, the same grant tasks.test makes.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // Never up-to-date: the ratings change under it, and the point is to see them now.
    outputs.upToDateWhen { false }
}

tasks.register<JavaExec>("recommend") {
    group = "application"
    description =
        "Ranks entities you do NOT already have by how much more of your list reaches them than " +
            "their size predicts, and explains each one with real routes. Reads only; needs no " +
            "network. The output is personal data (ADR 33) — write it outside the working tree. " +
            "See ADR 45. Example: ./gradlew recommend " +
            "--args=\"--known \$HOME/known.csv --out \$HOME/next.txt --scorer lift\""
    mainClass.set("com.robsartin.segue.recommend.RecommendCli")
    classpath = sourceSets["main"].runtimeClasspath
    // sqlite-jdbc loads a native library, the same grant tasks.test makes.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // The whole graph is replayed into memory, and a real one is six figures of assertions.
    maxHeapSize = "4g"
    // Never up-to-date: the graph changes under it, and the point is to ask it now.
    outputs.upToDateWhen { false }
}

tasks.register<JavaExec>("rate") {
    group = "application"
    description =
        "Serves a local page that deals your entities one at a time and records a 1-5 rating " +
            "per keystroke, filling the affinity table the recommender weights by. --revise <n> " +
            "deals already-rated entities holding exactly that rating instead, for reconsidering " +
            "them. Loopback only. Writes the taste layer and nothing else. See ADR 46. Example: " +
            "./gradlew rate --args=\"--known \$HOME/setlist-scout/filtered-qids.csv\""
    mainClass.set("com.robsartin.segue.rate.RateCli")
    classpath = sourceSets["main"].runtimeClasspath
    // sqlite-jdbc loads a native library, the same grant tasks.test makes.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // The whole graph is replayed into memory, and a real one is six figures of assertions.
    maxHeapSize = "4g"
    // A long-running server: Gradle must not hold the console.
    standardInput = System.`in`
    // Never up-to-date: the ratings change under it, and the point is to add to them now.
    outputs.upToDateWhen { false }
}

tasks.register<JavaExec>("retractEntity") {
    group = "application"
    description =
        "Retracts one entity: appends a retraction claim so the projection stops showing that " +
            "entity and its edges. The log is never edited — every original claim stays in it. " +
            "Needs no network. See ADR 44 and ADR 60. --db is required, and SEGUE_DB does not " +
            "satisfy it: this tool has no default database, because the one it used to have was " +
            "the hole in issue #179. Write \$HOME and not ~ — a tilde does not expand inside " +
            "double quotes. Example: ./gradlew retractEntity --args=\"--db " +
            "\$HOME/.segue/segue.db --qid Q12345 --reason 'resolved to the wrong entity' " +
            "--dry-run\""
    mainClass.set("com.robsartin.segue.retract.RetractCli")
    classpath = sourceSets["main"].runtimeClasspath
    // sqlite-jdbc loads a native library, the same grant tasks.test makes.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // Never up-to-date: the log changes under it, and re-running it is a deliberate act.
    outputs.upToDateWhen { false }
}

tasks.register<JavaExec>("ownClaim") {
    group = "application"
    description =
        "Records one claim of your own: mint a local entity Wikidata does not model, assert an " +
            "edge between two ids, or merge a local id into the QID it turned out to be. " +
            "Appends to the log and writes nothing else — the graph is rebuilt from it at the " +
            "next boot. Deliberately not an MCP tool: an owner claim skips the corroboration " +
            "count, so a model must not be able to make one. Needs no network. See ADR 60. " +
            "--db is required, and SEGUE_DB does not satisfy it; there is no default database " +
            "here. Type the whole task name: Gradle matches abbreviations by camel-case hump, " +
            "so ./gradlew own resolves to :ownClaim and runs rather than reporting an unknown " +
            "task (issue #179). Write \$HOME and not ~ — a tilde does not expand inside double " +
            "quotes. Example: ./gradlew ownClaim --args=\"mint --db \$HOME/.segue/segue.db " +
            "--kind WORK --label 'A Self-Pressed Record'\""
    mainClass.set("com.robsartin.segue.own.OwnCli")
    classpath = sourceSets["main"].runtimeClasspath
    // sqlite-jdbc loads a native library, the same grant tasks.test makes.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // Never up-to-date: the log changes under it, and re-running it is a deliberate act.
    outputs.upToDateWhen { false }
}

tasks.register<JavaExec>("graphCensus") {
    group = "application"
    description =
        "Counts the graph and prints the counts: nodes by kind, edges by type, source and " +
            "corroboration, the claim rows and what retraction and merge did to them, the taste " +
            "layer by score, degree quantiles against ADR 57's floor, and what MusicBrainz " +
            "reached. Aggregates only — no labels, no ids, no notes — so the output is safe to " +
            "paste. Reads only; needs no network. See ADR 63. --db is required, and SEGUE_DB " +
            "does not satisfy it. Write \$HOME and not ~ — a tilde does not expand inside " +
            "double quotes. Example: ./gradlew graphCensus --args=\"--db \$HOME/.segue/segue.db\""
    mainClass.set("com.robsartin.segue.census.CensusCli")
    classpath = sourceSets["main"].runtimeClasspath
    // sqlite-jdbc loads a native library, the same grant tasks.test makes.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // The whole graph is folded in memory, and a real log is six figures of assertions.
    maxHeapSize = "4g"
    // Never up-to-date: the graph changes under it, and the point is to count it now.
    outputs.upToDateWhen { false }
}

spotless {
    java {
        googleJavaFormat(libs.versions.googleJavaFormat.get())
        target("src/**/*.java")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.80".toBigDecimal()
            }
        }
        rule {
            limit {
                counter = "INSTRUCTION"
                value = "COVEREDRATIO"
                minimum = "0.80".toBigDecimal()
            }
        }
        rule {
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.65".toBigDecimal()
            }
        }
    }
}

tasks.javadoc {
    // Javadoc is a gate, not a report nobody runs (issue #195). Before this it was outside `check`,
    // and it had been failing for some time on two `invalid use of @param` errors that nothing on
    // the way to a merge would have shown anybody. Every doclint group is on — a `{@link}` naming a
    // class that no longer exists, malformed HTML, a tag in a place the tool rejects — and `-Werror`
    // makes a warning stop the build, because a gate that prints a warning and exits 0 is a report.
    //
    // `missing` is off, and that is a decision rather than an oversight: as measured on 2026-09-02
    // the whole of the rest of the output was `no @param for <record component>` on this project's
    // records plus one `no main description`. Requiring a doc comment on every record component is
    // a defensible choice, but it is a different piece of work — with `missing` on, this task could
    // not join `check` until all of it was done, and none of the errors above would have been
    // caught in the meantime. Turning it on later is a matter of deleting `-missing` here once the
    // components are documented.
    //
    // The two options are added separately because `addStringOption` writes `-name value` and
    // `-Werror` takes no value; passing it as a string option puts the next argument where javadoc
    // expects a flag. `-quiet` is the value carried on the doclint option for the same reason: it
    // is javadoc's own no-argument flag, and this is the one-argument slot it fits in.
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:all,-missing", "-quiet")
    (options as StandardJavadocDocletOptions).addBooleanOption("Werror", true)
}

tasks.check {
    dependsOn(tasks.jacocoTestReport, tasks.jacocoTestCoverageVerification, tasks.javadoc)
}
