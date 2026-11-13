import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.beamcast.sender"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.beamcast.sender"
        minSdk = 23
        targetSdk = 35
        versionCode = (project.property("beamcastVersionCode") as String).toInt()
        versionName = "1.0"
    }
    signingConfigs {
        // Fixed key kept in the repository so every build can update an installed one in place.
        getByName("debug") { storeFile = rootProject.file("keystore/debug.keystore") }
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { viewBinding = true }
    androidResources { noCompress += "apk" }
    lint { abortOnError = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}
// The TV app travels inside the phone app, which can push it to the receiver (see TvUpdater).
val tvApkDir = layout.buildDirectory.dir("generated/tvApk")
val bundleTvApk by tasks.registering(Copy::class) {
    dependsOn(":tv:assembleDebug")
    from(project(":tv").layout.buildDirectory.file("outputs/apk/debug/tv-debug.apk"))
    into(tvApkDir)
    rename { "beamcast-tv.apk" }
}
android.sourceSets["main"].assets.srcDir(tvApkDir)
tasks.matching { it.name.endsWith("Assets") || it.name.startsWith("lint") || it.name.contains("Lint") }.configureEach { dependsOn(bundleTvApk) }

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":protocol"))
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime)
    implementation(libs.webkit)
    implementation(libs.coroutines.android)
    implementation(libs.nanohttpd)
    implementation(libs.serialization.json)
    implementation("com.google.android.gms:play-services-cast-framework:22.0.0")
    implementation("androidx.mediarouter:mediarouter:1.7.0")
    testImplementation(libs.junit)
}
