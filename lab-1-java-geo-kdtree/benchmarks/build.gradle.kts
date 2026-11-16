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
    implementation(libs.smile.core)
    annotationProcessor(libs.jmh.generator.annprocess)
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
}

tasks.register<JavaExec>("bench") {
    group = "verification"
    description = "Runs JMH benchmarks with parameters from config/bench.settings.json (or -PbenchConfig=<path>)."
    mainClass = "org.timermakov.geo.benchmarks.BenchMain"
    classpath = sourceSets["main"].runtimeClasspath
    val benchConfig = providers.gradleProperty("benchConfig").orNull
    if (benchConfig != null) {
        environment("HW1_BENCH_CONFIG", rootProject.file(benchConfig).absolutePath)
    }
}

tasks.register<JavaExec>("benchSmoke") {
    group = "verification"
    description = "Single dry JMH run to verify the harness starts (HW1_BENCH_SMOKE=1)."
    mainClass = "org.timermakov.geo.benchmarks.BenchMain"
    classpath = sourceSets["main"].runtimeClasspath
    environment("HW1_BENCH_SMOKE", "1")
}

tasks.register<JavaExec>("benchPublic") {
    group = "verification"
    description = "Runs the public-library (Smile ECEF) JMH benchmarks with parameters from config/bench.settings.json (or -PbenchConfig=<path>)."
    mainClass = "org.timermakov.geo.benchmarks.PublicBenchMain"
    classpath = sourceSets["main"].runtimeClasspath
    val benchConfig = providers.gradleProperty("benchConfig").orNull
    if (benchConfig != null) {
        environment("HW1_BENCH_CONFIG", rootProject.file(benchConfig).absolutePath)
    }
}
