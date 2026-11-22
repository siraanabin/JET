plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}


val releaseKeystoreFile = file("jet-release.jks")
val releaseStorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
val releaseKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")

android {
    buildFeatures {
        buildConfig = true
        compose = true
    }

    namespace = "com.jet.downloader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jet.downloader"
        minSdk = 24
        targetSdk = 34
        versionCode = 342
        versionName = "3.4.2"

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

    kotlinOptions {
        jvmTarget = "17"
    }
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
    // Debug-only Jetpack Compose + Navigation 3. Release keeps the original WebView UI.
    debugImplementation(platform("androidx.compose:compose-bom:2025.01.00"))
    debugImplementation("androidx.activity:activity-compose:1.9.1")
    debugImplementation("androidx.compose.ui:ui")
    debugImplementation("androidx.compose.foundation:foundation")
    debugImplementation("androidx.compose.animation:animation")
    debugImplementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.navigation3:navigation3-runtime:1.0.0")
    debugImplementation("androidx.navigation3:navigation3-ui:1.0.0")


    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // Core AndroidX & WebView
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.webkit:webkit:1.11.0")

    // NewPipe Extractor (GPLv3)
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.24.2")

    // AndroidX Media3 (Apache 2.0)
    val media3Version = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")

    // AndroidX Room (Apache 2.0)
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")

    // AndroidX Navigation (Apache 2.0)
    val navVersion = "2.7.7"
    implementation("androidx.navigation:navigation-fragment-ktx:$navVersion")
    implementation("androidx.navigation:navigation-ui-ktx:$navVersion")

    // AndroidX DataStore (Apache 2.0)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Koin DI (Apache 2.0)
    implementation("io.insert-koin:koin-android:3.5.6")

    // Coil Image Loading (Apache 2.0)
    implementation("io.coil-kt:coil:2.7.0")

    // OkHttp Networking (Apache 2.0)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Kotlin Coroutines & Serialization (Apache 2.0)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Native yt-dlp + FFmpeg fallback & muxing engine
    val youtubeDlVersion = "0.17.2"
    implementation("io.github.junkfood02.youtubedl-android:library:$youtubeDlVersion")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$youtubeDlVersion")
}
