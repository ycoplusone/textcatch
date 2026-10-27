package com.enfish.textcatch

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 지연 메시지 일괄 재발송 워커 (RetryQueue 의 모든 건을 오래된 순으로 한 번에 전송).
 *
 * - 라운드 후에도 남은 건이 있으면 간격을 2배로 (1, 2, 4, … 최대 600초) 다음 라운드 진행.
 *   짧은 간격(INLINE_MAX_SEC 이하)은 워커 안에서 바로 기다려 정확히 지키고,
 *   긴 간격은 WorkManager 에 지연 작업으로 예약한다 (JobScheduler 최소 지연 오차 회피).
 * - 성공은 대기열에서 제거. 4xx 가 6시간 / 일시 실패가 24시간 넘게 계속되면 삭제하지 않고 보류(hold)로 옮긴다.
 * - 네트워크 예외면 서버/망 장애로 보고 이번 라운드의 나머지는 건너뛴다 (다음 라운드에 함께 발송).
 */
class RetryWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "RetryWorker"
        /** 이 간격 이하는 워커 안에서 대기 (1+2+4+8+16 = 31초). */
        private const val INLINE_MAX_SEC = 16L
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        RetryQueue.flushing = true
        try {
            while (!isStopped) {
                flushOnce()
                if (isStopped) break
                synchronized(RetryQueue) {
                    if (RetryQueue.size(ctx) == 0) {
                        Log.d(TAG, "대기열 비움 → 간격 초기화")
                        RetryQueue.resetDelay(ctx)
                        RetryQueue.flushing = false
                        return@withContext Result.success()
                    }
                }
                val delaySec = RetryQueue.nextDelaySec(ctx)
                Log.d(TAG, "남은 ${RetryQueue.size(ctx)}건 → ${delaySec}초 후 일괄 재발송")
                if (delaySec <= INLINE_MAX_SEC) {
                    RetryQueue.setNextAt(ctx, System.currentTimeMillis() + delaySec * 1000L)
                    delay(delaySec * 1000L)
                } else {
                    RetryQueue.schedule(ctx, delaySec, ExistingWorkPolicy.APPEND_OR_REPLACE)
                    return@withContext Result.success()
                }
            }
            // 네트워크 끊김 등으로 중단 → WorkManager 가 조건 충족 시 다시 실행
            Result.retry()
        } finally {
            RetryQueue.flushing = false
        }
    }

    /** 대기 중인 모든 건을 한 번씩 전송. */
    private fun flushOnce() {
        val ctx = applicationContext
        val files = RetryQueue.list(ctx)
        if (files.isEmpty()) return
        Log.d(TAG, "일괄 재발송 시작 ${files.size}건")
        for (file in files) {
            if (isStopped) return
            val payload = RetryQueue.read(ctx, file) ?: continue
            val msgId = payload.optString("msg_id")
            val receivedAt = payload.optLong("received_at", System.currentTimeMillis())
            // 보류 판단 기준: 대기열에 (다시) 들어온 시각 (이 기능 이전 파일은 수신 시각)
            val waited = System.currentTimeMillis() - payload.optLong(RetryQueue.KEY_SINCE, receivedAt)
            val attempt = payload.optInt("attempt", 1) + 1
            Api.stamp(payload, ctx, msgId, receivedAt, attempt, "worker")
            val delayMs = payload.getLong("sent_at") - receivedAt

            when (val outcome = Api.post(ctx, payload)) {
                is Api.PostOutcome.Success -> {
                    RetryQueue.remove(file)
                    LogStore.add(ctx, payload.optString("body"), delayMs = delayMs,
                        attempt = attempt, via = LogStore.VIA_WORKER)
                }
                is Api.PostOutcome.Permanent -> {
                    // 4xx 도 서버 쪽 일시 문제일 수 있어 바로 버리지 않고 일정 시간 재시도 후 보류
                    if (waited > RetryQueue.HOLD_PERMANENT_AFTER_MS) {
                        RetryQueue.hold(ctx, file, payload, outcome.reason)
                        LogStore.add(ctx, payload.optString("body"), failReason = "${outcome.reason} 보류",
                            delayMs = delayMs, attempt = attempt, via = LogStore.VIA_WORKER)
                    } else {
                        RetryQueue.update(file, payload) // attempt 반영
                        Log.w(TAG, "4xx 재시도 예정 (${outcome.reason}) attempt=$attempt msg_id=$msgId")
                    }
                }
                is Api.PostOutcome.Retryable -> {
                    if (waited > RetryQueue.GIVE_UP_AFTER_MS) {
                        RetryQueue.hold(ctx, file, payload, outcome.reason)
                        LogStore.add(ctx, payload.optString("body"),
                            failReason = "${outcome.reason} 보류",
                            delayMs = delayMs, attempt = attempt, via = LogStore.VIA_WORKER)
                    } else {
                        RetryQueue.update(file, payload) // attempt 반영
                        Log.d(TAG, "재시도 예정 (${outcome.reason}) attempt=$attempt msg_id=$msgId")
                        // 연결 자체가 안 되면 나머지도 실패할 것 → 이번 라운드 중단
                        if (outcome.reason == "예외") return
                    }
                }
            }
        }
    }
}
