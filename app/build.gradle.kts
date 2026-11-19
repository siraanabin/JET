plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseKeystoreFile = file("jet-release.jks")
val releaseStorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
val releaseKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")

android {
    namespace = "com.jet.downloader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jet.downloader"
        minSdk = 24
        targetSdk = 34
        versionCode = 340
        versionName = "3.4.0"
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
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
