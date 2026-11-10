plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.cockpitf30"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cockpitf30"
        // Las unidades TopWay/ZQ suelen venir con Android 10–13.
        minSdk = 26
        targetSdk = 34
        versionCode = 65
        versionName = "0.9.55"
        // Vosk trae su librería nativa para cada arquitectura (~9 MB cada una): la radio es ARM y el
        // emulador del PC x86_64; la de x86 de 32 bits no hace falta.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    // Misma clave de depuración en el PC y en la nube: así cualquier APK de depuración se
    // instala encima del anterior sin "las firmas no coinciden". Es una clave solo para pruebas.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        // Versión para la radio: no depurable, así Android la compila entera y Compose va bastante
        // más fluido que en la de depuración. Va firmada con la misma clave compartida para que se
        // instale encima de cualquier APK anterior. Sin R8 (minify) a propósito: la app usa mucha
        // reflexión con las clases de la radio y Vosk/JNA, y no se puede probar aquí.
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // Las librerías nativas de Vosk van comprimidas en el APK (unos 18 MB menos); Android las
    // descomprime al instalar.
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Reconocimiento de voz sin conexión para «Hola BMW» (el modelo en español se descarga aparte).
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // org.json de Android no funciona en tests de JVM: usamos la implementación de referencia.
    testImplementation("org.json:json:20240303")
}
