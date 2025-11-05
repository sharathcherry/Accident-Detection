plugins {
    alias(libs.plugins.android.application)
}


android {
    namespace = "com.example.accidentdetection"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.accidentdetection"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Force ONNX Runtime to be included
        ndk {
            abiFilters.add("armeabi-v7a")
            abiFilters.add("arm64-v8a")
            abiFilters.add("x86")
            abiFilters.add("x86_64")
        }
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

    // Ensure .data files and other assets are included without compression
    // This is critical for ONNX models with external data files
    androidResources {
        noCompress += "onnx"
        noCompress += "data"
        noCompress += ""  // Don't compress ANY assets
    }

    // Explicitly configure source sets to include assets
    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.onnxruntime.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}

configurations.all {
    resolutionStrategy {
        cacheDynamicVersionsFor(0, "seconds")
        cacheChangingModulesFor(0, "seconds")
    }
}

