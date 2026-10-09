plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.protobuf")
}

android {
    namespace = "com.example.bandmoot"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.bandmoot"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // Xiaomi wire protocol (vendored Gadgetbridge xiaomi.proto -> XiaomiProto).
    implementation("com.google.protobuf:protobuf-javalite:4.29.1")
    // AES-CCM for the Xiaomi encrypted handshake (lightweight API only,
    // no JCE provider registration -> no conflict with Android's built-in BC).
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
