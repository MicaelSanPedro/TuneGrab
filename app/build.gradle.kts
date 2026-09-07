plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tunegrab.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tunegrab.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 4
        versionName = "0.2.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file("keystore/tunegrab.keystore")
            storePassword = "tunegrab2025"
            keyAlias = "tunegrab"
            keyPassword = "tunegrab2025"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("io.coil-kt:coil:2.6.0")

    // Extração do YouTube — mesma engine usada pelo app NewPipe.
    // Fork próprio (https://github.com/MicaelSanPedro/NewPipeExtractor, tag v0.26.5-android1)
    // com patch de compatibilidade Android < 13: a v0.26.5 oficial usa
    // URLDecoder/URLEncoder com Charset (Java 10) e String.isBlank() (Java 11),
    // que só existem no Android API 33+ e causam NoSuchMethodError em aparelhos antigos.
    implementation("com.github.MicaelSanPedro:NewPipeExtractor:v0.26.5-android1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
