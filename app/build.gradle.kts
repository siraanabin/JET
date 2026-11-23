import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val requestedReleaseTasks = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }
if (requestedReleaseTasks) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}


val releaseKeystoreFile = file("jet-release.jks")
val releaseStorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
val releaseKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")


android {
    buildFeatures { buildConfig = true }
    namespace = "com.jet.downloader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jet.downloader"
        minSdk = 24
        targetSdk = 34
        versionCode = 376
        versionName = "3.7.6"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = releaseKeystoreFile
            storePassword = releaseStorePassword ?: ""
            keyAlias = releaseKeyAlias ?: ""
            keyPassword = releaseKeyPassword ?: ""
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("release")
            resValue("string", "app_name", "JET Debug")
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += listOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Keep annotation processors aligned with Kotlin 2.4 metadata. Room KAPT otherwise
// resolves an older kotlin-metadata-jvm that rejects metadata version 2.4.0.
configurations.configureEach {
    resolutionStrategy.force("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.21")
}


val validateReleaseSigning = tasks.register("validateReleaseSigning") {
    doLast {
        val missing = buildList {
            if (!releaseKeystoreFile.isFile) add("app/jet-release.jks")
            if (releaseStorePassword.isNullOrBlank()) add("ANDROID_KEYSTORE_PASSWORD")
            if (releaseKeyAlias.isNullOrBlank()) add("ANDROID_KEY_ALIAS")
            if (releaseKeyPassword.isNullOrBlank()) add("ANDROID_KEY_PASSWORD")
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Release signing is not configured. Missing: ${missing.joinToString()}"
            )
        }
    }
}

tasks.configureEach {
    if (name == "preReleaseBuild") {
        dependsOn(validateReleaseSigning)
    }
}

dependencies {
    // Firebase BoM 35.0.0 aligns the protobuf/gRPC dependency graph.
    implementation(platform("com.google.firebase:firebase-bom:35.0.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-crashlytics")
    implementation("com.google.firebase:firebase-firestore")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // Core AndroidX & WebView
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.palette:palette-ktx:1.0.0")

    // NewPipe Extractor (GPLv3)
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.27.1")

    // AndroidX Media3 (Apache 2.0)
    val media3Version = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")

    // AndroidX Room (Apache 2.0) - updated processor supports newer Kotlin metadata.
    val roomVersion = "2.8.5"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")

    // AndroidX Navigation
    val navVersion = "2.7.7"
    implementation("androidx.navigation:navigation-fragment-ktx:$navVersion")
    implementation("androidx.navigation:navigation-ui-ktx:$navVersion")

    // AndroidX DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Koin DI
    implementation("io.insert-koin:koin-android:3.5.6")

    // Coil Image Loading
    implementation("io.coil-kt:coil:2.7.0")

    // OkHttp Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Kotlin Coroutines & Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Native yt-dlp + FFmpeg fallback & muxing engine
    val youtubeDlVersion = "0.18.1"
    implementation("io.github.junkfood02.youtubedl-android:library:$youtubeDlVersion")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$youtubeDlVersion")
}
