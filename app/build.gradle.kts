plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Every CI build gets a higher version code than the previous one, so it installs as an update.
val buildNumber = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull() ?: 1

android {
    namespace = "io.github.davydgky.launcher"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.davydgky.launcher"
        // Same platform floor as Velo: Android 16, targeting the newest platform.
        minSdk = 36
        targetSdk = 37
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
        androidResources.localeFilters += listOf("en", "de")
        // GitHub repository whose releases the in-app updater follows.
        buildConfigField("String", "UPDATE_REPOSITORY", "\"davydgky/WEWEWE-Launcher\"")
    }

    signingConfigs {
        getByName("debug") {
            // A fixed key in the repository: CI runners start fresh and would otherwise sign every build
            // with a new key, which Android refuses to install as an update.
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Left unsigned here: CI signs it with the secret release key and a rotation lineage from the
            // public debug key (scripts/sign_release.sh), which the Gradle signing config cannot express.
            signingConfig = null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += listOf("DebugProbesKt.bin", "kotlin-tooling-metadata.json", "META-INF/*.version")
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(project(":core"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    // Liquid Glass, the same as in Velo.
    implementation(libs.kyant.backdrop)
    implementation(libs.kyant.shapes)

    // On-device text recognition with the model bundled in the APK (no Play services, no download).
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.image.labeling)
    // Gemini Nano on the device (AICore), for short answers in the search.
    implementation(libs.mlkit.genai.prompt)

    testImplementation(libs.junit)
    // Real org.json for JVM unit tests (android.jar only contains stubs).
    testImplementation(libs.org.json)
}
