package com.enfish.textcatch

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * 15분마다 도는 정기 점검 (WorkManager 주기 작업 — 재부팅 후에도 WorkManager 가 유지).
 * 브로드캐스트 없이 조용히 남는 건을 찾아 전송 경로에 다시 올린다.
 * - 전송 도중 중단된 SMS(inflight) 복구 + 지연 대기열 재발송 예약 확인 (RetryQueue.ensureScheduled)
 * - 저장소에 있는데 안 보낸 MMS 전송 (Forwarder.sweepMms: 늦게 다운로드된 MMS 등)
 * Doze 중에는 OS 가 실행을 미룰 수 있다 (깨어나는 정비 시간대에 실행).
 */
class WatchdogWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    companion object {
        private const val TAG = "WatchdogWorker"
        const val WORK_NAME = "watchdog"
    }

    override fun doWork(): Result {
        try {
            RetryQueue.ensureScheduled(applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "대기열 점검 실패", e)
        }
        try {
            Forwarder.sweepMms(applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "MMS 점검 실패", e)
        }
        return Result.success()
    }
}
