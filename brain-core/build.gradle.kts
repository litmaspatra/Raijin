plugins {
    `java-library`
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":brain-contract"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    // AP-3: E.164 phone normalization/validation with region metadata.
    implementation("com.googlecode.libphonenumber:libphonenumber:8.13.47")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        // Full assertion messages (expected vs actual) in CI logs.
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
