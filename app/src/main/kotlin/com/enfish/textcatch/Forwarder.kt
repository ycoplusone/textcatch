package com.enfish.textcatch

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 수신된 메시지를 WorkManager 작업으로 큐잉하는 진입점.
 * WorkManager 가 백그라운드 실행 보장 + 실패 시 지수 백오프 재시도 +
 * 네트워크 연결 시점까지 대기를 담당한다.
 */
object Forwarder {

    const val KEY_TYPE = "type"          // "SMS" | "MMS"
    const val KEY_SENDER = "sender"
    const val KEY_RECEIVER = "receiver"
    const val KEY_BODY = "body"
    const val KEY_TIMESTAMP = "timestamp"

    /** MMS 작업은 같은 content provider 를 보므로 직렬 실행(동시 실행 시 같은 건 중복 전송 방지). */
    private const val MMS_CHAIN = "mms_forward"

    private fun enqueue(context: Context, data: Data, uniqueChain: String? = null) {
        // 네트워크가 있을 때 실행 (없으면 연결될 때까지 대기 → 유실 방지)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<ForwardWorker>()
            .setInputData(data)
            .setConstraints(constraints)
            // ② 재시도: 실패 시 10초부터 지수 백오프
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            // 가능하면 즉시 실행(quota 초과 시 일반 작업으로)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        val wm = WorkManager.getInstance(context.applicationContext)
        if (uniqueChain != null) {
            wm.enqueueUniqueWork(uniqueChain, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        } else {
            wm.enqueue(request)
        }
    }

    fun enqueueSms(
        context: Context,
        sender: String,
        receiver: String,
        body: String,
        timestamp: Long
    ) {
        enqueue(
            context,
            Data.Builder()
                .putString(KEY_TYPE, "SMS")
                .putString(KEY_SENDER, sender)
                .putString(KEY_RECEIVER, receiver)
                .putString(KEY_BODY, body)
                .putLong(KEY_TIMESTAMP, timestamp)
                .build()
        )
    }

    /** 매일 정기 상태 확인용 heartbeat. SMS 와 동일 경로로 즉시 payload 구성해 전송된다. */
    fun enqueueHeartbeat(
        context: Context,
        receiver: String,
        body: String,
        timestamp: Long
    ) {
        enqueue(
            context,
            Data.Builder()
                .putString(KEY_TYPE, "HEARTBEAT")
                .putString(KEY_SENDER, "")
                .putString(KEY_RECEIVER, receiver)
                .putString(KEY_BODY, body)
                .putLong(KEY_TIMESTAMP, timestamp)
                .build()
        )
    }

    fun enqueueMms(context: Context, receiver: String, timestamp: Long) {
        enqueue(
            context,
            Data.Builder()
                .putString(KEY_TYPE, "MMS")
                .putString(KEY_RECEIVER, receiver)
                .putLong(KEY_TIMESTAMP, timestamp)
                .build(),
            uniqueChain = MMS_CHAIN
        )
    }
}
