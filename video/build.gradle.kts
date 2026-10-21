import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Finesse Video: films and episodes on the car screen, only while parked. Its own app (package)
// because Android Auto lists one car app per package, and the main app is the music one. It reads
// the main app's sign-in through its SessionShareProvider, so both must be signed with the same key.
plugins {
    alias(libs.plugins.android.app)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

android {
    namespace = "com.coffeecc.finesse.video"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.coffeecc.finesse.video"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        // Where the main app shares its sign-in (its applicationId + ".session").
        buildConfigField("String", "SESSION_AUTHORITY", "\"com.coffeecc.finesse.session\"")
    }

    buildTypes {
        getByName("debug") {
            // Pairs with the main app's debug build (com.coffeecc.finesse.debug).
            applicationIdSuffix = ".debug"
            buildConfigField("String", "SESSION_AUTHORITY", "\"com.coffeecc.finesse.debug.session\"")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.car.app)
    implementation(libs.androidx.car.app.projected)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.jellyfin.sdk)
    implementation(libs.bundles.coroutines)
}
