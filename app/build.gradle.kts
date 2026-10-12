import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.protobuf")
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().also { props: Properties ->
    if (keystorePropsFile.exists()) FileInputStream(keystorePropsFile).use(props::load)
}
val releaseStorePath: String? =
    System.getenv("ANDROID_KEYSTORE_FILE") ?: keystoreProps.getProperty("storeFile")
val hasReleaseSigning = !releaseStorePath.isNullOrBlank()

android {
    namespace = "com.gdincu.bandmoot"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gdincu.bandmoot"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // Only English strings exist; strip other locales pulled in by libraries.
        resourceConfigurations += listOf("en")
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = rootProject.file(releaseStorePath!!)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                    ?: keystoreProps.getProperty("storePassword")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                    ?: keystoreProps.getProperty("keyAlias")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
                    ?: keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildFeatures {
        // No BuildConfig usage in code; skip generating it.
        buildConfig = false
    }

    buildTypes {
        release {
            // R8: strips unused code (incl. most of BouncyCastle/Kotlin/protobuf)
            // + shrinkResources removes unused resources.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            // Without keys this builds app-release-unsigned.apk; sign it before deploying.
        }
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
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

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.protobuf:protobuf-javalite:4.29.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.29.1"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins.maybeCreate("java").option("lite")
        }
    }
}
