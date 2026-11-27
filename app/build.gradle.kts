import java.util.Properties

plugins {
    // AGP 9 compiles Kotlin itself; the root build pins the Kotlin Gradle plugin version.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.sonora.player"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.sonora.player"
        minSdk = 26
        targetSdk = 36
        versionCode = 13
        versionName = "1.5.2"
        // Service keys built into the app, read from secrets.properties (kept out of Git). Users never enter them.
        val secrets = Properties().apply {
            val f = rootProject.file("secrets.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        buildConfigField("String", "TOMTOM_KEY", "\"${secrets.getProperty("tomtom.key", "").trim()}\"")
        // The map engine is native code: ship phones' ARM builds only (keeps the APK small).
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // Compress the map engine's native code inside the APK: a much smaller download from GitHub.
    packaging { jniLibs { useLegacyPackaging = true } }

    // Release signing: a private key kept out of Git (keystore.properties + sonora-release.jks in the project root).
    // Without them (e.g. a fresh clone) release builds fall back to the debug key so they still install.
    val keyProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (keyProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keyProps.getProperty("storeFile"))
                storePassword = keyProps.getProperty("storePassword")
                keyAlias = keyProps.getProperty("keyAlias")
                keyPassword = keyProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipe Extractor uses newer java.* APIs.
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // Media3 marks much of its advanced audio API as @UnstableApi; we opt in deliberately.
        disable += "UnsafeOptInUsageError"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.ExperimentalFoundationApi",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.lucide)
    implementation(libs.coil.compose)
    implementation(libs.androidx.palette)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.cast)
    implementation(libs.androidx.ui.text.google.fonts)
    implementation(libs.maplibre.android)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)

    // YouTube Music
    implementation(libs.newpipe.extractor)
    implementation(libs.okhttp)
    implementation(libs.androidx.media3.okhttp)
    implementation(libs.coil.network.okhttp)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // Frosted-glass bars
    implementation(libs.haze)
    implementation(libs.haze.blur)

    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
}
