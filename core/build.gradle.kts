import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The search core has no Android dependency, so it is unit tested on the plain JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
}

tasks.test {
    testLogging {
        events("failed")
        showStandardStreams = true
    }
}
