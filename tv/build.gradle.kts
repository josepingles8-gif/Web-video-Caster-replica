plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.beamcast.receiver"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.beamcast.receiver"
        minSdk = 23
        targetSdk = 35
        versionCode = (project.property("beamcastVersionCode") as String).toInt()
        versionName = "1.0"
    }
    signingConfigs {
        // Fixed key kept in the repository so every build can update an installed one in place.
        getByName("debug") { storeFile = rootProject.file("keystore/debug.keystore") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    lint {
        abortOnError = true
        // media3 @UnstableApi is satisfied by Kotlin @OptIn; lint only understands the Java form.
        disable += "UnsafeOptInUsageError"
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":protocol"))
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.hls)
    implementation(libs.media3.dash)
    implementation(libs.media3.ui)
    implementation(libs.nanohttpd)
    implementation(libs.appcompat)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
}
