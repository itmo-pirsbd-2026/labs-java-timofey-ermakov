plugins {
    id("java")
    id("application")
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
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
}

application {
    mainClass = "org.timermakov.geo.profile.ProfileRunner"
}
