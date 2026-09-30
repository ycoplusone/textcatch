// 루트 빌드 스크립트 — 플러그인을 버전과 함께 선언만 하고 적용은 각 모듈에서 한다.
// (중요) AGP 9는 Kotlin 지원이 내장이므로 org.jetbrains.kotlin.android 플러그인을 추가하지 않는다.
plugins {
    id("com.android.application") version "9.3.1" apply false
}
