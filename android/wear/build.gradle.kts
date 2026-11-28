import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val versionFile = rootProject.file("../VERSION")
val versionStr = if (versionFile.exists()) versionFile.readText().trim() else "0.0.0"
val vParts = versionStr.split('.').map { it.toIntOrNull() ?: 0 }
val parsedVersionCode =
    (vParts.getOrElse(0) { 0 } * 1_000_000) +
        (vParts.getOrElse(1) { 0 } * 10_000) +
        (vParts.getOrElse(2) { 0 } * 100)

android {
    namespace = "com.nw5w.graywolf.wear"
    compileSdk = 36

    defaultConfig {
        // Must match the phone app's applicationId for Play Services Wearable Data Layer RPCs.
        applicationId = "com.nw5w.graywolf"
        minSdk = 30
        targetSdk = 36
        versionCode = parsedVersionCode
        versionName = versionStr
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    val keystorePath = System.getenv("GRAYWOLF_KEYSTORE_PATH")
    val keystoreBase64 = System.getenv("GRAYWOLF_KEYSTORE_BASE64")
    val keystorePassword = System.getenv("GRAYWOLF_KEYSTORE_PASSWORD")
    val keyAlias = System.getenv("GRAYWOLF_KEY_ALIAS") ?: "graywolf-upload"
    val keyPassword = System.getenv("GRAYWOLF_KEY_PASSWORD") ?: keystorePassword

    val resolvedKeystoreFile: java.io.File? = when {
        keystorePath != null -> file(keystorePath)
        keystoreBase64 != null -> {
            val tmp = layout.buildDirectory.file("wear-upload.keystore").get().asFile
            tmp.parentFile.mkdirs()
            tmp.writeBytes(Base64.getDecoder().decode(keystoreBase64))
            tmp
        }
        else -> null
    }

    signingConfigs {
        if (resolvedKeystoreFile != null && keystorePassword != null) {
            create("release") {
                storeFile = resolvedKeystoreFile
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword!!
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui:1.6.8")
    implementation("androidx.compose.foundation:foundation:1.6.8")
    implementation("androidx.wear.compose:compose-material:1.3.1")
    implementation("androidx.wear.compose:compose-foundation:1.3.1")
    implementation("androidx.wear.compose:compose-navigation:1.3.1")
    implementation("androidx.wear:wear:1.3.0")
    implementation("androidx.wear:wear-input:1.2.0")
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
