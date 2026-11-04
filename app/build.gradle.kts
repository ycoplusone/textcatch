import java.util.Properties

plugins {
    // (중요) AGP 9는 Kotlin 지원이 내장이므로 com.android.application 만 적용한다.
    // org.jetbrains.kotlin.android / kotlin("android") 를 추가하면 빌드 실패.
    id("com.android.application")
}

// release 서명 설정 — keystore.properties(gitignore 됨)에서 읽는다.
// 파일이 없으면(다른 PC 등) release 서명 없이 빌드 (assembleDebug 는 영향 없음).
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.enfish.textcatch"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.enfish.textcatch"
        minSdk = 24
        targetSdk = 36
        versionCode = 23
        versionName = "3.2"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
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
