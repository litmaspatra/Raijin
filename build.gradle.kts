plugins {
    // ORDER MATTERS: AGP must be declared before Kotlin Android to ensure AGP's variant
    // API classes are on the build classpath when KotlinAndroidTarget's decorated proxy
    // class is generated. Without this ordering, class generation fails with:
    //   "Could not generate a decorated class for type KotlinAndroidTarget.
    //    > com/android/build/gradle/api/BaseVariant"
    // (BaseVariant was removed in AGP 8.0; KotlinAndroidTarget's proxy generation requires
    //  AGP classes in the classloader before it can handle their absence correctly.)
    id("com.android.application") version "8.6.1" apply false

    // All three Kotlin variants with apply false so the correct version is registered
    // for each plugin ID. :app uses alias(libs.plugins.kotlin.android/.compose) which
    // carry version 2.1.0 — they must match the version registered here.
    // :brain-contract and :brain-core inherit kotlin("jvm") version from here.
    kotlin("jvm")            version "2.1.0" apply false
    kotlin("android")        version "2.1.0" apply false
    kotlin("plugin.compose") version "2.1.0" apply false
}
