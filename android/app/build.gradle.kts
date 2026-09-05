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
        versionCode = 21
        versionName = "3.0"
    }

    // 앱을 새로 빌드해도 서명이 같아야 "덮어쓰기 설치"가 된다.
    // 서명이 바뀌면 기존 앱을 지우고 다시 깔아야 하므로 키를 저장소에 고정해 둔다.
    // 개인 사이드로드 전용 키라 비밀 값이 아니다.
    signingConfigs {
        create("personal") {
            storeFile = file("../keystore/personal.jks")
            storePassword = "toonshortcut"
            keyAlias = "personal"
            keyPassword = "toonshortcut"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("personal")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("personal")
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
