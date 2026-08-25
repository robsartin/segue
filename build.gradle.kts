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
    // Wikidata responses. Jackson rather than a second parser because Spring Boot brings
    // it in increment 4 anyway, and one JSON library is better than two.
    implementation(libs.jackson.databind)
    // java.time handlers for the tool-surface mapper. Jackson 2 keeps JSR-310 support in a
    // separate module, and without it ProvenanceView.assertedAt cannot be written at all
    // (issue #18) — 2.22's REQUIRE_HANDLERS_FOR_JAVA8_TIMES turns the omission into a hard
    // failure rather than a silent epoch number, which is why the tool threw.
    implementation(libs.jackson.datatype.jsr310)
    // Nullability on the mcp/ view records (ADR 18 forbids this annotation in domain/, so it
    // stays out of the records the schema module actually needs it for there). Already resolved
    // transitively via Spring's dependency management; declared explicitly because code in this
    // project imports it directly.
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
    // StdioPurityTest launches the real application as a subprocess and needs its runtime
    // classpath. Passed as a system property rather than relying on a packaged jar, so the
    // test runs against exactly what this build just compiled — no separate bootJar step.
    systemProperty("segue.mainRuntimeClasspath", sourceSets["main"].runtimeClasspath.asPath)
    useJUnitPlatform {
        // Excluded from the normal gate: it needs the network and can fail for reasons
        // that have nothing to do with this code. Run it deliberately, via ./gradlew liveTest.
        excludeTags("live")
    }
    testLogging {
        events("failed")
    }
}

tasks.register<Test>("liveTest") {
    group = "verification"
    description = "Runs the tagged live tests against the real Wikidata API. Needs network."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("live") }
    // Never up-to-date: the point is to re-check the real endpoint.
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

tasks.check {
    dependsOn(tasks.jacocoTestReport, tasks.jacocoTestCoverageVerification)
}
