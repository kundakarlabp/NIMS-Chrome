plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.kundakarlab.nimsfastsummarymobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.kundakarlab.nimsfastsummarymobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets {
        getByName("main").assets.srcDirs("src/main/assets", "../../../shared/nims-web")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
