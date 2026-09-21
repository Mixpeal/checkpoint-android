plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ng.checkpoint"
    compileSdk = 35

    defaultConfig {
        applicationId = "ng.checkpoint"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ONNX Runtime ships four ABIs and they dominate the APK. x86 exists only for
        // emulators. Both ARM variants stay, because armeabi-v7a is still what a lot of
        // cheap Android phones in this market run.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildTypes {
        release {
            // Signed with the debug key so a release build installs for testing without a
            // keystore. Nothing here is distributed through a store.
            signingConfig = signingConfigs.getByName("debug")

            // R8 stays off until keep rules for ONNX Runtime's JNI entry points and for
            // kotlinx.serialization are written and checked on a device. A class stripped
            // by mistake fails at launch, not at build time, which is the worst place to
            // find out.
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    // packs/ is the single copy. The APK reads it as assets and the JVM tests read it as
    // resources, so a claim, its trained head and the test that greps them cannot drift
    // apart. The encoder is not here: it is large, it changes rarely, and it is fetched
    // and hash-verified at runtime. See AGENTS.md.
    sourceSets {
        getByName("main") { assets.srcDirs("src/main/assets", "../packs") }
        getByName("test") { resources.srcDirs("../packs") }
    }

    packaging {
        // ONNX Runtime's native libraries will not load straight out of the APK on a
        // device with 16 KB memory pages: the linker reports a missing DT_GNU_HASH.
        // Extracting them at install time sidesteps the in-APK alignment requirement.
        jniLibs { useLegacyPackaging = true }
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // Inference. The tokenizer is compiled into the graph via onnxruntime-extensions,
    // so the session takes raw strings and tokenizer parity stops being a class of bug.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-extensions-android:0.13.0")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
