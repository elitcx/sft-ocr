plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "id.dotcode.braille.ocr.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "id.dotcode.braille.ocr"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "0.3.0"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets["main"].kotlin.srcDir("src/main/kotlin")
    sourceSets["test"].kotlin.srcDir("src/test/kotlin")
    buildTypes {
        release { isMinifyEnabled = false }
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }

    // The bundled ML Kit model ships native libraries for four ABIs, and a universal APK
    // carries all of them - about 22 MB of that is x86/x86_64, which only an emulator ever
    // loads. Splitting per ABI gives a phone-sized artifact (arm64-v8a is every Galaxy in
    // the target range) while keeping an x86_64 APK for emulator testing.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":ocr-mlkit"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.mlkit.document.scanner)
    // Guided capture reads the preview with the same recognizer the pipeline uses.
    implementation(libs.mlkit.text.recognition)
    implementation(libs.pdfbox.android)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testImplementation(libs.org.json)
    testRuntimeOnly(libs.junit.platform.launcher)
}
