plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mcal.dtmf"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mcal.dtmf"
        minSdk = 23
        targetSdk = 34
        versionCode = 240404
        versionName = "3.0"
        vectorDrawables {
            useSupportLibrary = true
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,versions/9/previous-compilation-data.bin}"
        }
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.material3.android)
    implementation(libs.androidx.material.android)

    implementation(libs.androidx.preference.ktx)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.work.runtime)

    implementation(libs.voyager.koin)
    implementation(libs.voyager.navigator)
    implementation(libs.voyager.transitions)

    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")
}
