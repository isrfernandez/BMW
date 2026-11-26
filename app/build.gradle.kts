import java.util.Properties

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
        versionCode = 151
        versionName = "1.0.16"
        // Vosk trae su librería nativa para cada arquitectura (~9 MB cada una): la radio y la tablet
        // son ARM. La de x86_64 (emulador del PC) solo va en la de depuración (ver buildTypes).
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
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
        // Clave propia para las APK que se instalan en la radio y la tablet (desde la 0.9.84). Con la
        // de depuración de Android («CN=Android Debug») Play Protect bloqueaba la instalación.
        // No va en Git (la clave privada no debe publicarse): se copia a mano en keystore/ en el PC y
        // en la nube. Si no está, la release se firma con la de depuración, como antes.
        val releaseProps = rootProject.file("keystore/release.properties")
        if (releaseProps.exists()) create("cockpit") {
            val props = Properties().apply { releaseProps.inputStream().use { load(it) } }
            storeFile = rootProject.file("keystore/release.keystore")
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
    }

    buildTypes {
        // Versión para la radio: no depurable, compilada entera y pasada por R8 (encoge y optimiza el
        // código, quita recursos que no se usan). Lo que se usa por reflexión (clases de la radio,
        // Vosk/JNA) está protegido en proguard-rules.pro. Va firmada con la clave propia del repo
        // (keystore/release.keystore): la misma en el PC y en la nube.
        debug {
            ndk { abiFilters += "x86_64" }
        }
        release {
            signingConfig = signingConfigs.findByName("cockpit") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
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
    testOptions { unitTests.isIncludeAndroidResources = true; unitTests.all { it.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2") } }
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
    // Código QR de la ubicación del coche aparcado (solo el núcleo, sin cámara).
    implementation("com.google.zxing:core:3.5.3")
    // Reconocimiento de voz sin conexión para «Hola BMW» (el modelo en español se descarga aparte).
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    // Radio por Internet: ExoPlayer dentro de un MediaSessionService (no lo cierra el sistema con Maps delante).
    // 1.4.x es la última que compila con compileSdk 34.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    // org.json de Android no funciona en tests de JVM: usamos la implementación de referencia.
    testImplementation("org.json:json:20240303")
}
