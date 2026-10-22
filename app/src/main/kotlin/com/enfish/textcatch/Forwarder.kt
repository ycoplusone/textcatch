package com.enfish.textcatch

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.UUID
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
    const val KEY_RECEIVED_AT = "received_at"
    const val KEY_MSG_ID = "msg_id"
    /** MMS 정리 작업: 특정 WAP Push 가 아니라 밀린 미전송 MMS 를 찾아 보낸다 (kickMms). */
    const val KEY_SWEEP = "sweep"

    private const val TAG = "Forwarder"

    /** MMS 작업은 같은 content provider 를 보므로 직렬 실행(동시 실행 시 같은 건 중복 전송 방지). */
    private const val MMS_CHAIN = "mms_forward"

    /** 체인 작업의 다음 실행이 이보다 더 뒤면 멈춘 것으로 본다 (현재 백오프 간격 상한 600초 + 여유). */
    private const val MMS_STALL_MS = 15 * 60 * 1000L

    private fun enqueue(context: Context, data: Data, uniqueChain: String? = null) {
        // 네트워크가 있을 때 실행 (없으면 연결될 때까지 대기 → 유실 방지)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<ForwardWorker>()
            .setInputData(data)
            .setConstraints(constraints)
            // MMS 콘텐츠 미도착 재시도: 20초 선형 백오프 (20, 40, … 600초)
            // 전송 실패 재시도는 RetryQueue 가 담당 (1→2→4…최대 600초 일괄 재발송)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 20, TimeUnit.SECONDS)
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

    /**
     * SMS 빠른 경로: 수신 즉시 직접 POST 를 1회 시도하고, 일시적 실패일 때만 WorkManager 로 넘긴다.
     * (WorkManager 스케줄링/expedited quota/Doze 대기를 거치지 않아 평소 지연이 가장 작다)
     * 네트워크를 쓰므로 메인 스레드가 아닌 곳(goAsync 백그라운드 스레드 등)에서 호출해야 한다.
     * 최대 소요 ≈ Api 의 fast callTimeout(8초).
     */
    fun forwardSms(
        context: Context,
        sender: String,
        receiver: String,
        body: String,
        timestamp: Long,
        receivedAt: Long = System.currentTimeMillis()
    ) {
        val msgId = UUID.randomUUID().toString()
        var payload: org.json.JSONObject? = null
        val outcome = try {
            payload = Api.buildPayload(
                context, "SMS", sender, receiver, body, timestamp,
                msgId, receivedAt, attempt = 1, path = "direct"
            )
            Api.post(context, payload, fast = true).also {
                when (it) {
                    is Api.PostOutcome.Success -> {
                        LogStore.add(
                            context, body, delayMs = payload.getLong("sent_at") - receivedAt,
                            attempt = 1, via = LogStore.VIA_DIRECT
                        )
                        RetryQueue.onServerReachable(context)
                    }
                    is Api.PostOutcome.Permanent -> LogStore.add(
                        context, body, failReason = it.reason,
                        delayMs = payload.getLong("sent_at") - receivedAt,
                        attempt = 1, via = LogStore.VIA_DIRECT
                    )
                    is Api.PostOutcome.Retryable -> Unit
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "직접 전송 중 예외 → 워커로 넘김", e)
            Api.PostOutcome.Retryable("예외")
        }
        if (outcome is Api.PostOutcome.Retryable) {
            val p = payload
            if (p != null) {
                // 일시 실패 → 지연 대기열 (RetryWorker 가 다른 대기 건과 함께 일괄 재발송)
                Log.d(TAG, "직접 전송 실패(${outcome.reason}) → 지연 대기열로 넘김 msg_id=$msgId")
                RetryQueue.add(context, p)
            } else {
                Log.d(TAG, "직접 전송 실패(${outcome.reason}) → WorkManager 로 넘김 msg_id=$msgId")
                enqueueSms(context, sender, receiver, body, timestamp, receivedAt, msgId)
            }
        }
    }

    fun enqueueSms(
        context: Context,
        sender: String,
        receiver: String,
        body: String,
        timestamp: Long,
        receivedAt: Long = System.currentTimeMillis(),
        msgId: String = UUID.randomUUID().toString()
    ) {
        enqueue(
            context,
            Data.Builder()
                .putString(KEY_TYPE, "SMS")
                .putString(KEY_SENDER, sender)
                .putString(KEY_RECEIVER, receiver)
                .putString(KEY_BODY, body)
                .putLong(KEY_TIMESTAMP, timestamp)
                .putLong(KEY_RECEIVED_AT, receivedAt)
                .putString(KEY_MSG_ID, msgId)
                .build()
        )
    }

    /** 6시간마다 정기 상태 확인용 heartbeat. SMS 와 동일 경로로 즉시 payload 구성해 전송된다. */
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
                .putLong(KEY_RECEIVED_AT, System.currentTimeMillis())
                .putString(KEY_MSG_ID, UUID.randomUUID().toString())
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
                .putLong(KEY_RECEIVED_AT, timestamp)
                .putString(KEY_MSG_ID, UUID.randomUUID().toString())
                .build(),
            uniqueChain = MMS_CHAIN
        )
    }

    /** 밀린 미전송 MMS 를 (최대 24시간 전까지) 한 건씩 찾아 보내는 정리 작업을 MMS 체인에 붙인다. */
    fun enqueueMmsSweep(context: Context, receiver: String) {
        val now = System.currentTimeMillis()
        enqueue(
            context,
            Data.Builder()
                .putString(KEY_TYPE, "MMS")
                .putString(KEY_RECEIVER, receiver)
                .putLong(KEY_TIMESTAMP, now)
                .putLong(KEY_RECEIVED_AT, now)
                .putString(KEY_MSG_ID, UUID.randomUUID().toString())
                .putBoolean(KEY_SWEEP, true)
                .build(),
            uniqueChain = MMS_CHAIN
        )
    }

    /**
     * 백오프로 멈춘 MMS 체인 정리. 체인은 직렬이라 맨 앞 작업이 재시도 대기(구버전 작업은 최대 5시간)에
     * 걸리면 뒤의 MMS 가 전부 밀린다 → 실행 중인 작업이 없으면 체인을 비우고 정리 작업으로 즉시 다시 찾는다.
     * - onlyIfStalled: 앱 실행 시 자동 점검용. 다음 실행이 MMS_STALL_MS 이상 뒤로 밀린 작업이 있을 때만 정리.
     * WorkManager DB 조회를 기다리므로 메인 스레드가 아닌 곳에서 호출. 반환값 = 정리한 대기 작업 수.
     */
    fun kickMms(context: Context, onlyIfStalled: Boolean = false): Int {
        val wm = WorkManager.getInstance(context.applicationContext)
        val infos = wm.getWorkInfosForUniqueWork(MMS_CHAIN).get()
        if (infos.any { it.state == WorkInfo.State.RUNNING }) return 0
        val stuck = infos.count { !it.state.isFinished }
        if (stuck == 0) return 0
        if (onlyIfStalled) {
            val limit = System.currentTimeMillis() + MMS_STALL_MS
            if (infos.none { it.state == WorkInfo.State.ENQUEUED && it.nextScheduleTimeMillis > limit }) return 0
        }
        Log.d(TAG, "MMS 체인 정리: 대기 작업 ${stuck}건 취소 → 정리 작업으로 재탐색")
        wm.cancelUniqueWork(MMS_CHAIN).result.get()
        enqueueMmsSweep(context, Utils.getDevicePhoneNumber(context))
        return stuck
    }
}
