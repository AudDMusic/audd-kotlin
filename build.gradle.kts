plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    `java-library`
    id("com.vanniktech.maven.publish") version "0.36.0"
}

group = "io.audd"
version = "1.6.0"
description = "Official Kotlin SDK for the AudD music recognition API."

repositories {
    mavenCentral()
}

val ktorVersion = "3.3.3"
val coroutinesVersion = "1.10.2"
val serializationVersion = "1.9.0"
val slf4jVersion = "2.0.13"
val junitVersion = "5.10.2"

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
    api("io.ktor:ktor-client-core:$ktorVersion")
    api("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("org.slf4j:slf4j-api:$slf4jVersion")

    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.slf4j:slf4j-simple:$slf4jVersion")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
        languageVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_9
        apiVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_9
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// `-PktorTestVersion=<version>` runs the tests against a different Ktor
// release than the one the SDK is compiled against, the way an app that
// depends on a newer Ktor 3 uses the published artifact.
providers.gradleProperty("ktorTestVersion").orNull?.let { ktorTestVersion ->
    configurations.named("testRuntimeClasspath") {
        resolutionStrategy.eachDependency {
            if (requested.group == "io.ktor") useVersion(ktorTestVersion)
        }
    }
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    coordinates("io.audd", "audd-kotlin", project.version.toString())

    pom {
        name = "AudD Kotlin SDK"
        description = project.description
        url = "https://audd.io"
        licenses {
            license {
                name = "MIT License"
                url = "https://opensource.org/licenses/MIT"
            }
        }
        developers {
            developer {
                name = "AudD"
                email = "api@audd.io"
                organization = "AudD"
                organizationUrl = "https://audd.io"
            }
        }
        scm {
            connection = "scm:git:https://github.com/AudDMusic/audd-kotlin.git"
            developerConnection = "scm:git:git@github.com:AudDMusic/audd-kotlin.git"
            url = "https://github.com/AudDMusic/audd-kotlin"
        }
    }
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/AudDMusic/audd-kotlin")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
