plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.gms.google.services)
}

android {
    namespace = "com.example.zura"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.zura"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // AndroidX & Material
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)

    // Firebase (via BoM)
    implementation(platform(libs.firebase.bom))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation ("com.android.volley:volley:1.2.1")
    implementation ("com.google.maps.android:android-maps-utils:2.2.5")


    // Google Maps + Places
    implementation("com.google.android.gms:play-services-maps:18.1.0")
    implementation("com.google.android.libraries.places:places:2.7.0")

    // Coroutines (optional but useful with Firebase)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.6.4")

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}