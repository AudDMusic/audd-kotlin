plugins {
    kotlin("jvm") version "2.0.21"
    application
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("io.audd:audd-kotlin:1.4.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    // jaudiotagger is LGPL-2.1 — see README for licensing implications.
    implementation("net.jthink:jaudiotagger:3.0.1")
    implementation("org.slf4j:slf4j-simple:2.0.13")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "ScanAndRenameKt"
}
