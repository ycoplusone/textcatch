plugins {
    // (중요) AGP 9는 Kotlin 지원이 내장이므로 com.android.application 만 적용한다.
    // org.jetbrains.kotlin.android / kotlin("android") 를 추가하면 빌드 실패.
    id("com.android.application")
}

android {
    namespace = "com.enfish.textcatch"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.enfish.textcatch"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
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

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // OkHttp (REST API 호출)
    implementation("com.squareup.okhttp3:okhttp:4.11.0")

    // WorkManager (백그라운드 보장 전송 + 재시도/백오프 + 네트워크 복구 대기)
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
