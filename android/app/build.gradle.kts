plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.toonshortcut.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.toonshortcut.app"
        // 어댑티브 아이콘만 쓰기 위해 26 이상. 안드로이드 8.0(2017) 이후 전부 해당된다.
        minSdk = 26
        targetSdk = 35
        versionCode = 31
        versionName = "3.10"
    }

    // 서명은 GitHub Actions 에서 apksigner 로 한다(.github/workflows/android.yml).
    // 예전 키에서 새 키로 넘어가는 교체 서명이라 그레이들 설정으로는 할 수 없다.
    // 그래서 release 는 서명 없이 만든다(app-release-unsigned.apk).
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
}
