import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.connor.pendant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.connor.pendant"
        minSdk = 31  // Pixel 8 Pro is Android 14+; we don't care about older
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-m1"

        buildConfigField(
            "String",
            "AGENT_URL",
            "\"${localProps.getProperty("agent.url", "http://agent:8773/raw")}\""
        )
        buildConfigField(
            "String",
            "PENDANT_SECRET",
            "\"${localProps.getProperty("pendant.secret", "changeme")}\""
        )
        // Phase 1 audio-out: defaults to agent:8774/ws but local.properties wins.
        buildConfigField(
            "String",
            "AUDIO_OUT_WS_URL",
            "\"${localProps.getProperty("audio.out.url", "ws://agent:8774/ws")}\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.websockets)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.material)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.play.services.location)
    implementation(libs.androidx.health.connect.client)
}
