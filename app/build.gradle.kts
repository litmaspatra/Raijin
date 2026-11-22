plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    // Render test stack is property-gated (see renderTests below): kept on the
    // build classpath but NOT applied here so lightweight mode can skip it.
    alias(libs.plugins.paparazzi) apply false
}

/**
 * Verification tooling: property-gated render test stack.
 * Default TRUE: CI and normal development keep full render coverage
 * (Paparazzi goldens + snapshot tests).
 * `-Praijin.renderTests=false` is for memory-constrained sandboxes only:
 * it skips applying the Paparazzi plugin (so layoutlib never lands on the
 * unit-test compile classpath) and excludes the 6 render test files:
 *   LearnedSkillsPaparazziTest, PreferencesPaparazziTest,
 *   SkillInstallApprovalPaparazziTest, WhatIKnowPaparazziTest,
 *   ui/island/LongAnswerSnapshotTest, ui/island/NumberRegionSnapshotTest
 * A pass in lightweight mode NEVER implies render or full-suite verification.
 */
val renderTests = providers.gradleProperty("raijin.renderTests")
    .map(String::toBooleanStrict)
    .getOrElse(true)

if (renderTests) {
    apply(plugin = libs.plugins.paparazzi.get().pluginId)
}

android {
    namespace = "com.raijin.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.raijin.app"
        minSdk = 29          // Android 10+ — required for overlay + audio routing APIs
        targetSdk = 35
        // Monotonic, identifiable builds: every CI run gets a distinct,
        // increasing versionCode (workflow run number never decreases) and a
        // versionName that names the run + commit, so device test drops are
        // never indistinguishable. Local builds stay above any shipped code.
        val ciRun = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        val ciSha = System.getenv("GITHUB_SHA")?.take(7)
        versionCode = 1000 + (ciRun ?: 0)
        versionName = if (ciRun != null) "0.4.0-ci$ciRun+$ciSha" else "0.4.0-local"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // No applicationIdSuffix — keep com.raijin.app so overlay permission
            // granted in Settings applies to this debug build.
            isDebuggable = true
            // CI pins a stable debug signing identity via RAIJIN_DEBUG_KEYSTORE_PATH
            // instead of relying on AGP's implicit user-home lookup, which is not
            // guaranteed to read the restored keystore on hosted runners.
            // Unset locally: AGP default ~/.android/debug.keystore is used.
            System.getenv("RAIJIN_DEBUG_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }?.let { path ->
                signingConfig = signingConfigs.create("ciStableDebug").apply {
                    storeFile = file(path)
                    storePassword = "android"
                    keyAlias = "androiddebugkey"
                    keyPassword = "android"
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    ksp {
        // PRD ME-1: export Room schemas so migrations are reviewable/testable
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    // app/legacy/ is outside the standard source set (src/main/java, src/main/kotlin) and
    // is already excluded from compilation by default. No sourceSets override needed.

    testOptions { unitTests.isIncludeAndroidResources = true }

    if (!renderTests) {
        // Lightweight mode: omit the 6 render test files (see renderTests doc).
        // Excluded at the Kotlin compile task: the AndroidSourceSet kotlin
        // accessor is not reliably in scope here, and Kotlin compiles from
        // the java source dirs, so source-set exclusion would not apply.
        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
            if (name == "compileDebugUnitTestKotlin" || name == "compileReleaseUnitTestKotlin") {
                exclude("**/*PaparazziTest.kt", "**/*SnapshotTest.kt")
            }
        }
    }

    lint {
        // F0: permissive; tightened in F4 after device validation on iQOO Z9s.
        abortOnError = false
        // KNOWN UPSTREAM ISSUE: lint analysis intermittently crashes on
        // Kotlin 2.1.0 bytecode with "Provided Metadata instance has version
        // 2.1.0, while maximum supported version is 2.0.0" (kotlinx-metadata
        // bundled with lint caps at 2.0.0; crashes in lint's metadata
        // reading, e.g. ComposableCoroutineCreationDetector). Also crashed
        // at lint 8.8.0 via android.experimental.lint.version (probe
        // reverted); intermittent on bundled lint (run 45048052106 passed).
        // Upstream: https://issuetracker.google.com/issues/386304679
        // Accepted: when it crashes, analysis is incomplete for that run;
        // no detector suppression.
    }
}

dependencies {
    // Brain contract & core — pure Kotlin assistant brain
    implementation(project(":brain-contract"))
    implementation(project(":brain-core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.animation)
    // Coroutines: aligned with :brain-contract api dep (1.9.0)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.moshi.kotlin)

    // MU-1: in-app audio player (Tier 1 local library)
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-session:1.6.1")
    implementation("androidx.media3:media3-common-ktx:1.6.1")

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Moshi codegen
    ksp(libs.moshi.codegen)

    debugImplementation(libs.androidx.ui.tooling)

    // OkHttp — Provider-neutral OpenAI-compatible API client & network transport
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // SavedState — required for SavedStateRegistryOwner used in HudOverlay's lifecycle shim
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")

    // Tests
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.kotlinx.coroutines.android)
    // org.json is an Android SDK class; provide a JVM-compatible shim for unit tests
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.room.runtime)
}
