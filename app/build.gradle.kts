import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "it.tal.taldoc"
    compileSdk = 36

    defaultConfig {
        applicationId = "it.tal.taldoc"
        minSdk = 29
        targetSdk = 35
        versionCode = 10
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            // Se nei Secrets del repository c'è una chiave privata la usa, altrimenti la chiave pubblica del progetto.
            val ks = System.getenv("TALDOC_KEYSTORE")?.takeIf { it.isNotBlank() }
            storeFile = if (ks != null) file(ks) else rootProject.file("keystore/taldoc.jks")
            storePassword = System.getenv("TALDOC_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() } ?: "taldoc-public"
            keyAlias = System.getenv("TALDOC_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "taldoc"
            keyPassword = System.getenv("TALDOC_KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: "taldoc-public"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
            // telefoni Android moderni (fascia media inclusa) sono tutti arm64: APK molto più leggero
            ndk { abiFilters += listOf("arm64-v8a") }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "org/bouncycastle/pqc/**")
        }
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // OCR offline e scanner documenti
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // Testo nativo dei PDF
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // AI on-device (Gemma tramite LiteRT-LM)
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
