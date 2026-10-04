plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "us.z1x.fidont"
    compileSdk = 37

    defaultConfig {
        applicationId = "us.z1x.fidont"
        minSdk = 34
        targetSdk = 37
        versionCode = 3
        versionName = "0.1.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    buildFeatures {
        compose = true
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
