import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Credenciales de firma release (fuera del repo; ver README)
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.agustin.videoboostao"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.agustin.videoboostao"
        minSdk = 33
        targetSdk = 36
        versionCode = 12
        versionName = "2.10"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        aidl = true
    }

    lint {
        // El chequeo lint-vital escribe un .dat que Google Drive suele bloquear
        // (falla al hashear MD5). No es crítico para esta app; se desactiva.
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    // Material 3 estable (1.4.0 via BOM). El look Expressive se logra con
    // color dinamico (Material You), formas extra-grandes y espaciado
    // generoso; todo API publica aqui. MaterialExpressiveTheme (el envoltorio
    // formal) solo es publico en 1.5.0-alpha, que exige AGP 9.1+.
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")

    // Shizuku: re-activar el servicio de accesibilidad sin root (shell UID)
    // para el modo full-auto. Solo se usa si el usuario tiene Shizuku; la app
    // funciona sin él.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // Cliente ADB embebido: alternativa in-app a Shizuku (empareja con el
    // "Wireless debugging" del propio dispositivo y corre los mismos comandos
    // shell). libadb-android arrastra spake2-android (LGPL-3.0, para el pairing
    // SPAKE2) y BouncyCastle (MIT). sun-security-android genera el certificado
    // X.509; conscrypt provee el TLS del daemon ADB; hiddenapibypass abre las
    // APIs ocultas que conscrypt necesita en Android 9+.
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("com.github.MuntashirAkon:sun-security-android:1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")

    // Corrutinas: el pairing/connect/exec de ADB son I/O de red bloqueante y
    // deben correr fuera del hilo principal.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
