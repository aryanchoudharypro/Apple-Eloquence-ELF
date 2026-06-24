// SPDX-License-Identifier: MIT
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.eloquence.tts"
    compileSdk = 34
    ndkVersion = "27.2.12479018"   // NDK r27c — matches the converter's libc++ __ndk1 rewrite

    defaultConfig {
        applicationId = "com.eloquence.tts"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // arm64-v8a only by default — it's the validated target (engine
            // synthesizes correctly under Bionic). The converter can also emit
            // x86_64 (`--os android` on an x86_64 slice; ARCH_CONFIG has an
            // "android-x86_64" entry) for emulator testing, but that build has
            // an unresolved eciNew init crash — see android/README.md. Add
            // "x86_64" here once that's fixed.
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake { arguments += "-DELOQUENCE_CJK_ATEXIT=ON" }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // The converted engine .so files (eci.so + lib<lang>.so + libc++_shared.so)
    // are dropped into src/main/jniLibs/arm64-v8a/ — see android/README.md.
    // They install into the read-only nativeLibraryDir, where dlopen of
    // executable code is permitted (writable app storage is not).
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.google.android.material:material:1.12.0")
}
