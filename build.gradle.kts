plugins {
    java
    jacoco
    alias(libs.plugins.spotless)
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
    runtimeOnly(libs.slf4j.nop)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.archunit.junit6)
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
