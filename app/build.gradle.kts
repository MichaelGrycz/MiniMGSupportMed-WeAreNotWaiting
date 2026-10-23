plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.spotless)
}

android {
    namespace = "pl.mgsupport.mglycemia"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "pl.mgsupport.mglycemia"
        minSdk = 24
        targetSdk = 36
        versionCode = 65
        val appVersionName = "1.0-beta64"
        versionName = appVersionName

        // Installer and launcher show the version, so testers can tell builds apart at a glance.
        manifestPlaceholders["appLabel"] = "MGlikemia $appVersionName"

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
        // java.time is used throughout the BLE/history layer; without desugaring it would crash on
        // Android 7.x (minSdk 24). This also makes lint treat those APIs as available.
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        baseline = file("lint-baseline.xml")
        warningsAsErrors = false
    }
}

spotless {
    java {
        target("src/**/*.java")
        googleJavaFormat(libs.versions.googleJavaFormat.get()).aosp()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

dependencies {
    // Placeholder coordinates for the JavaSake :lib subproject. Gradle substitutes the local
    // composite build (declared in settings.gradle.kts) at configuration time, so this never
    // actually resolves against a Maven repository.
    implementation("org.openminimed:lib:0.1.0-SNAPSHOT")

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)
    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
