import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// every commit here starts with the same 7 characters
val commit = providers.exec { commandLine("git", "rev-parse", "--short=12", "HEAD") }.standardOutput.asText

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "us.z1x.fidont"
    compileSdk = 37

    defaultConfig {
        applicationId = "us.z1x.fidont"
        minSdk = 34
        targetSdk = 37
        versionCode = 19
        versionName = "0.1.18"
        buildConfigField("String", "COMMIT", "\"${commit.get().trim()}\"")
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = file(keystoreProperties.getProperty("storeFile", "../keystore.jks"))
                storePassword = keystoreProperties.getProperty("storePassword", "")
                keyAlias = keystoreProperties.getProperty("keyAlias", "")
                keyPassword = keystoreProperties.getProperty("keyPassword", "")
            } else {
                storeFile = file(System.getenv("KEYSTORE_FILE") ?: "../keystore.jks")
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: ""
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.credentials)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.compose)
    implementation(libs.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.zxing)
    implementation(libs.sqldelight.android)
}
