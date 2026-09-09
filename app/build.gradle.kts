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
        versionCode = 29
        versionName = "0.12.0"

        // ABIs do motor yt-dlp embutido (python + ffmpeg são nativos).
        // x86/x86_64 ficam de fora para o APK não dobrar de tamanho —
        // aparelhos físicos são arm32/arm64.
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
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

    packaging {
        // Exigido pelo youtubedl-android: os binários do python/ffmpeg precisam
        // ficar extraídos no disco (não dá para executar direto do APK).
        jniLibs {
            useLegacyPackaging = true
        }
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
    implementation("androidx.media:media:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("io.coil-kt:coil:2.6.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Extração do YouTube — mesma engine usada pelo app NewPipe.
    // Fork próprio (https://github.com/MicaelSanPedro/NewPipeExtractor, tag v0.26.5-android4):
    //  1) compatibilidade Android < 13 (a v0.26.5 oficial usa URLDecoder/URLEncoder com
    //     Charset — Java 10 — e String.isBlank() — Java 11 —, que causam NoSuchMethodError
    //     em aparelhos antigos);
    //  2) clients extras de streams (TVHTML5 + visionOS + iOS) e fallback em cascata;
    //  3) suporte completo a PoTokenProvider: player request WEB com PoToken (destrava o
    //     bot-check "Sign in to confirm you're not a bot"), client iOS com PoToken e
    //     WEB+PoToken/iOS/visionOS/TV como fallback primário quando os clients anônimos falham.
    implementation("com.github.MicaelSanPedro:NewPipeExtractor:v0.26.5-android4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Motor de download yt-dlp EMBUTIDO no APK (python 3.12 + yt-dlp + ffmpeg),
    // a mesma engine usada pelo app Seal — o “plano A” do download na v0.4.0.
    // O yt-dlp é mantido semanalmente contra as mudanças do YouTube (rotação de
    // clients, desafios de JS via QuickJS embutido) e ainda é auto-atualizado
    // na primeira execução de cada versão do app (UpdateChannel.STABLE).
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
}
