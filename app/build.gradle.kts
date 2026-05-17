plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.apollo)
}

android {
    namespace = "net.unraidcontrol.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.unraidcontrol.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 69
        versionName = "0.1.30-beta6"

        vectorDrawables { useSupportLibrary = true }
    }

    val releaseKeystore = rootProject.file("app/release.keystore")

    signingConfigs {
        create("release") {
            if (releaseKeystore.exists()) {
                storeFile     = releaseKeystore
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                    ?: providers.gradleProperty("KEYSTORE_PASSWORD").orNull
                keyAlias      = System.getenv("KEY_ALIAS")
                    ?: providers.gradleProperty("KEY_ALIAS").orNull
                keyPassword   = System.getenv("KEY_PASSWORD")
                    ?: providers.gradleProperty("KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            signingConfig = if (releaseKeystore.exists())
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    // App bytecode stays Java 17 (Android-appropriate; D8/R8 desugars).
    // JDK 21 is the *build/toolchain* JVM, not the emitted class level —
    // see ADR-0023. javac 21 emits 17 bytecode fine.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].kotlin.srcDir("src/main/kotlin")

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "/META-INF/DEPENDENCIES",
            )
        }
    }
}

// AGP 9 built-in Kotlin: the kotlin {} extension is top-level (no longer
// nested in android {}). Toolchain 21 = the JDK that runs the compiler
// (CI provisions JDK 21); Kotlin jvmTarget defaults to compileOptions
// targetCompatibility (Java 17). See ADR-0023.
kotlin {
    jvmToolchain(21)
}

apollo {
    service("unraid") {
        packageName.set("net.unraidcontrol.app.graphql")
        generateOptionalOperationVariables.set(false)
        // Custom scalars used by the Unraid 7 schema. PrefixedID and DateTime
        // are opaque strings to us; BigInt is a Long; URL/Port are their
        // natural Kotlin counterparts. JSON is mapped to kotlin.Any? via
        // our JsonAnyAdapter — the server returns inline JSON values
        // (objects/arrays/primitives), NOT pre-stringified text, so a
        // String mapping crashes the polled snapshot.
        mapScalar("PrefixedID", "kotlin.String")
        mapScalar("DateTime",   "kotlin.String")
        mapScalar("BigInt",     "kotlin.Long")
        mapScalar("JSON",       "kotlin.Any",  "com.apollographql.apollo.api.AnyAdapter")
        mapScalar("URL",        "kotlin.String")
        mapScalar("Port",       "kotlin.Int")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.nav.compose)
    implementation(libs.hilt.viewmodel.compose)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.tink.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.apollo.runtime)
    implementation(libs.apollo.api)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.markdown.renderer)
    implementation(libs.markdown.renderer.m3)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
}
