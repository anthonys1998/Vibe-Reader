plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val appVersionCode = providers.gradleProperty("appVersionCode").orNull?.let { raw ->
    raw.toIntOrNull() ?: throw GradleException("appVersionCode must be an integer.")
} ?: 41
if (appVersionCode !in 1..2_100_000_000) throw GradleException("appVersionCode must be between 1 and 2,100,000,000.")

android {
    namespace = "com.paperlight.reader"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    defaultConfig {
        applicationId = "com.paperlight.reader"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = "2.0"
    }

    signingConfigs {
        create("release") {
            val keystorePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    implementation(libs.jsoup)
    implementation(libs.epub4j.core) {
        exclude(group = "xmlpull", module = "xmlpull")
    }
    implementation("com.github.junrar:junrar:8.1.2")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.work.runtime)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")


    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.androidx.work.testing)
    testImplementation("org.robolectric:robolectric:4.14.1")
}
