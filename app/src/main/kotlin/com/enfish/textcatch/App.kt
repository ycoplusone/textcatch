package com.enfish.textcatch

import android.app.Application

/**
 * 프로세스 시작 시(화면/리시버/워커 어느 경로든) 가장 먼저 실행 — 크래시 기록기를 설치한다.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        // 프로세스가 뜨는 모든 경로(화면/리시버/워커)에서 상주 서비스 시작 시도.
        // 백그라운드 시작 제약으로 실패할 수 있으나(try/catch 내장), 부팅/앱 진입 때 다시 시작된다.
        KeepAliveService.start(this)
    }
}
