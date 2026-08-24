plugins {
    application
}

group = "com.robsartin"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // Chosen engine. See CLAUDE.md for why.
    implementation("org.apache.tinkerpop:tinkergraph-gremlin:3.7.3")
    // Reference implementation, kept working as a cross-check.
    implementation("org.apache.jena:jena-arq:5.3.0")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.16")
}

tasks.withType<JavaCompile>().configureEach {
    // Compiles on JDK 25 while staying runnable on 21. Bump to 25 whenever
    // you stop caring about the older runtime.
    options.release.set(21)
    options.compilerArgs.add("-Xlint:unchecked")
}

application {
    mainClass.set("com.robsartin.segue.bakeoff.BakeOff")
}

tasks.register<JavaExec>("selfTest") {
    group = "verification"
    description = "Zero-dependency domain and fixture checks."
    mainClass.set("com.robsartin.segue.bakeoff.DomainSelfTest")
    classpath = sourceSets["main"].runtimeClasspath
}
