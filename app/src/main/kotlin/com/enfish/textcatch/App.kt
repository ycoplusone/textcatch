package com.enfish.textcatch

import android.app.Application

/**
 * 프로세스 시작 시(화면/리시버/워커 어느 경로든) 가장 먼저 실행 — 크래시 기록기를 설치한다.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
