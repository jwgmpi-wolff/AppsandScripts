plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

import com.android.build.gradle.internal.api.BaseVariantOutputImpl

android {
    namespace = "com.example.numberorigindesk"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.numberorigindesk"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
    }

    applicationVariants.all {
        outputs.all {
            (this as BaseVariantOutputImpl).outputFileName = "NumberOriginDesk.apk"
        }
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.ui:ui:1.7.8")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.8")
    implementation("io.michaelrocks:libphonenumber-android:8.13.55")
    implementation("org.jsoup:jsoup:1.18.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    debugImplementation("androidx.compose.ui:ui-tooling:1.7.8")
}

tasks.register<Copy>("stageReleaseApk") {
    dependsOn("assembleDebug")
    from(layout.buildDirectory.file("outputs/apk/debug/NumberOriginDesk.apk"))
    into(rootProject.projectDir.parentFile.resolve("releases"))
}