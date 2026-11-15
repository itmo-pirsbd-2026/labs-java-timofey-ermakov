plugins {
    id("java")
}

group = "org.timermakov"
version = "1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":"))
    implementation(libs.jmh.core)
    implementation(libs.gson)
    annotationProcessor(libs.jmh.generator.annprocess)
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
}

tasks.register<JavaExec>("bench") {
    group = "verification"
    description = "Runs JMH benchmarks with parameters from config/bench.settings.json."
    mainClass = "org.timermakov.geo.benchmarks.BenchMain"
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("benchSmoke") {
    group = "verification"
    description = "Single dry JMH run to verify the harness starts (HW1_BENCH_SMOKE=1)."
    mainClass = "org.timermakov.geo.benchmarks.BenchMain"
    classpath = sourceSets["main"].runtimeClasspath
    environment("HW1_BENCH_SMOKE", "1")
}
