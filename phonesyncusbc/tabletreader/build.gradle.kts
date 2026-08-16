plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val syncSharedReaderSources by tasks.registering(Sync::class) {
    from("../app/src/main/java")
    into(layout.buildDirectory.dir("generated/sharedReader"))
    include(
        "com/jerrywolff/phonesyncusbc/ArtifactDataReaderView.kt",
        "com/jerrywolff/phonesyncusbc/data/ArtifactIndexDatabase.kt",
        "com/jerrywolff/phonesyncusbc/data/ArtifactIndexer.kt",
        "com/jerrywolff/phonesyncusbc/data/AuditLog.kt",
        "com/jerrywolff/phonesyncusbc/data/DataExportManager.kt",
        "com/jerrywolff/phonesyncusbc/data/FolderMetadata.kt",
        "com/jerrywolff/phonesyncusbc/data/JsonArtifactFlattener.kt",
        "com/jerrywolff/phonesyncusbc/data/RecoverySelectionPlan.kt",
        "com/jerrywolff/phonesyncusbc/domain/TransferClassifier.kt",
        "com/jerrywolff/phonesyncusbc/domain/TrustPolicy.kt",
    )
}

android {
    namespace = "com.jerrywolff.phonesynctabletreader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jerrywolff.phonesynctabletreader"
        minSdk = 29
        targetSdk = 35
        versionCode = 4
        versionName = "1.1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions.jvmTarget = "17"
    buildFeatures.compose = true

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java", layout.buildDirectory.dir("generated/sharedReader"))
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(syncSharedReaderSources)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.documentfile)
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    debugImplementation(libs.androidx.compose.ui.tooling)
}