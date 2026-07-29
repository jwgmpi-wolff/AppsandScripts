plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "net.wolffentp.stockstreamportfolio"
    compileSdk = 35

    val releaseStoreFile = (project.findProperty("STOCKSTREAM_RELEASE_STORE_FILE") as String?)
        ?: System.getenv("STOCKSTREAM_RELEASE_STORE_FILE")
    val releaseStorePassword = (project.findProperty("STOCKSTREAM_RELEASE_STORE_PASSWORD") as String?)
        ?: System.getenv("STOCKSTREAM_RELEASE_STORE_PASSWORD")
    val releaseKeyAlias = (project.findProperty("STOCKSTREAM_RELEASE_KEY_ALIAS") as String?)
        ?: System.getenv("STOCKSTREAM_RELEASE_KEY_ALIAS")
    val releaseKeyPassword = (project.findProperty("STOCKSTREAM_RELEASE_KEY_PASSWORD") as String?)
        ?: System.getenv("STOCKSTREAM_RELEASE_KEY_PASSWORD")
    val hasReleaseSigning = !releaseStoreFile.isNullOrBlank()
        && !releaseStorePassword.isNullOrBlank()
        && !releaseKeyAlias.isNullOrBlank()
        && !releaseKeyPassword.isNullOrBlank()

    defaultConfig {
        applicationId = "net.wolffentp.stockstreamportfolio"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val backendBaseUrl = (project.findProperty("STOCKSTREAM_BACKEND_BASE_URL") as String?) ?: "https://replace-with-api-host/"
        val androidClientId = (project.findProperty("STOCKSTREAM_ANDROID_CLIENT_ID") as String?) ?: "REPLACE_WITH_ANDROID_CLIENT_ID"
        val tenantId = (project.findProperty("STOCKSTREAM_TENANT_ID") as String?) ?: "REPLACE_WITH_TENANT_ID"
        val backendScope = (project.findProperty("STOCKSTREAM_BACKEND_SCOPE") as String?) ?: "api://REPLACE_WITH_BACKEND_API_CLIENT_ID/access_as_user"

        buildConfigField("String", "BACKEND_BASE_URL", "\"$backendBaseUrl\"")
        buildConfigField("String", "ANDROID_CLIENT_ID", "\"$androidClientId\"")
        buildConfigField("String", "TENANT_ID", "\"$tenantId\"")
        buildConfigField("String", "BACKEND_SCOPE", "\"$backendScope\"")
    }

    signingConfigs {
        create("release") {
            if (!releaseStoreFile.isNullOrBlank()) {
                storeFile = rootProject.file(releaseStoreFile)
            }
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.01.00")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.navigation:navigation-compose:2.8.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("com.google.android.material:material:1.12.0")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("com.microsoft.identity.client:msal:8.4.1") {
        exclude(group = "com.microsoft.device.display", module = "display-mask")
    }

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.microsoft.signalr:signalr:7.0.7")
    implementation("io.reactivex.rxjava3:rxjava:3.1.9")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.google.truth:truth:1.4.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
