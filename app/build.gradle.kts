plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "com.audipp"
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.audipp"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild.cmake.arguments += "-DANDROID_STL=none"
    }

    buildTypes {
        debug {
            ndk.abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        release {
            ndk.abiFilters += setOf("arm64-v8a", "armeabi-v7a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
    }

    sourceSets["androidTest"].assets.srcDir("$projectDir/schemas")

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

kotlin {
    jvmToolchain(17)
}

dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.okhttp)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.robolectric)
    implementation(libs.androidx.room.runtime)
    implementation(libs.smbj)
    ksp(libs.androidx.room.compiler)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
