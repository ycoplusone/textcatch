package com.enfish.textcatch

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 지연(일시 실패) 메시지 대기열.
 *
 * - 직접 전송/워커 전송이 일시 실패하면 완성된 payload 를 파일(filesDir/retry_queue)로 보관한다.
 *   (MMS 이미지 Base64 가 커서 SharedPreferences 대신 파일 사용)
 * - 재발송은 RetryWorker 하나가 대기 중인 모든 건을 한 번에(오래된 순) 보낸다.
 * - 재발송 간격: 1 → 2 → 4 → 8 → … → 최대 600초 (전체 대기열 공통 단계, 대기열이 비면 초기화)
 * - flushNow(): "지연메세지발송" 버튼 — 대기 중인 간격을 무시하고 즉시 일괄 발송.
 */
object RetryQueue {

    private const val TAG = "RetryQueue"
    const val WORK_NAME = "retry_flush"

    /** 재발송 간격 상한 (10분). */
    const val MAX_DELAY_SEC = 600L
    /** 수신 후 이 시간이 지나도 못 보내면 포기 (기존 "약 하루" 유지). */
    const val GIVE_UP_AFTER_MS = 24 * 60 * 60 * 1000L

    private const val DIR = "retry_queue"
    private const val PREFS = "textcatch_retry"
    private const val KEY_STEP = "step"        // 다음 간격 = 2^step 초 (상한 600)
    private const val KEY_NEXT_AT = "next_at"  // 다음 일괄 재발송 예정 시각 (화면 표시용)

    /** RetryWorker 실행 중 여부 (실행 중이면 새 건은 워커가 다음 라운드에서 가져간다). */
    @Volatile
    var flushing = false

    private fun dir(context: Context) = File(context.filesDir, DIR).apply { mkdirs() }

    /** 일시 실패한 payload(msg_id/received_at/attempt 포함)를 대기열에 넣고 재발송을 예약한다. */
    fun add(context: Context, payload: JSONObject) = synchronized(this) {
        val receivedAt = payload.optLong("received_at", System.currentTimeMillis())
        val msgId = payload.optString("msg_id").ifEmpty { System.nanoTime().toString() }
        // 파일명 = 수신시각(0 패딩) + msg_id → 이름순 정렬이 곧 오래된 순
        File(dir(context), "%015d_%s.json".format(receivedAt, msgId)).writeText(payload.toString())
        Log.d(TAG, "대기열 추가 msg_id=$msgId type=${payload.optString("type")} 대기 ${size(context)}건")
        if (!flushing) schedule(context, delaySec = currentDelaySec(context), ExistingWorkPolicy.KEEP)
    }

    /** 대기 중인 파일 목록 (오래된 순). */
    fun list(context: Context): List<File> = synchronized(this) {
        dir(context).listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name } ?: emptyList()
    }

    fun size(context: Context): Int = list(context).size

    fun read(file: File): JSONObject? = try {
        JSONObject(file.readText())
    } catch (e: Exception) {
        Log.e(TAG, "대기열 파일 손상 → 삭제 ${file.name}", e)
        file.delete()
        null
    }

    fun update(file: File, payload: JSONObject) = synchronized(this) {
        if (file.exists()) file.writeText(payload.toString())
    }

    fun remove(file: File) = synchronized(this) { file.delete() }

    // ---------- 재발송 간격 ----------

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 현재 단계의 간격(초): 2^step, 최대 600. */
    fun currentDelaySec(context: Context): Long {
        val step = prefs(context).getInt(KEY_STEP, 0).coerceIn(0, 20)
        return minOf(1L shl step, MAX_DELAY_SEC)
    }

    /** 이번 라운드도 실패 → 간격을 한 단계 올리고 그 간격을 반환. */
    fun nextDelaySec(context: Context): Long {
        val p = prefs(context)
        val step = p.getInt(KEY_STEP, 0)
        if ((1L shl step.coerceAtMost(20)) < MAX_DELAY_SEC) p.edit().putInt(KEY_STEP, step + 1).apply()
        return currentDelaySec(context)
    }

    fun resetDelay(context: Context) {
        prefs(context).edit().putInt(KEY_STEP, 0).remove(KEY_NEXT_AT).apply()
    }

    fun setNextAt(context: Context, at: Long) = prefs(context).edit().putLong(KEY_NEXT_AT, at).apply()

    /** 다음 일괄 재발송 예정 시각 (0 = 없음). */
    fun nextAt(context: Context): Long = prefs(context).getLong(KEY_NEXT_AT, 0L)

    // ---------- 예약 ----------

    private fun request(delaySec: Long): OneTimeWorkRequest {
        val b = OneTimeWorkRequestBuilder<RetryWorker>()
            // 오프라인이면 연결될 때까지 대기 (연결되는 즉시 일괄 발송)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        // expedited 는 지연을 줄 수 없으므로 즉시 실행일 때만 사용
        if (delaySec > 0) b.setInitialDelay(delaySec, TimeUnit.SECONDS)
        else b.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        return b.build()
    }

    /**
     * 일괄 재발송 예약.
     * - KEEP: 새 건 추가 시 — 이미 예약된 재발송이 있으면 그 시각에 함께 보낸다.
     * - APPEND_OR_REPLACE: 워커가 다음 라운드를 예약할 때 (실행 중인 자신 뒤에 붙임).
     * - REPLACE: 수동 즉시 발송.
     */
    fun schedule(context: Context, delaySec: Long, policy: ExistingWorkPolicy) {
        setNextAt(context, System.currentTimeMillis() + delaySec * 1000L)
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(WORK_NAME, policy, request(delaySec))
    }

    /** 대기열에 건이 남아 있으면 재발송이 예약돼 있도록 보장 (앱 실행 시 안전장치). */
    fun ensureScheduled(context: Context) {
        if (!flushing && size(context) > 0) {
            schedule(context, currentDelaySec(context), ExistingWorkPolicy.KEEP)
        }
    }

    /**
     * 새 메시지 전송이 성공했다 = 서버/망 복구 → 대기 중인 지연 메시지도 남은 간격을 기다리지 않고 즉시 일괄 발송.
     * 이미 재발송 중이면 그 라운드를 끊지 않도록 건드리지 않는다.
     */
    fun onServerReachable(context: Context) {
        if (flushing || size(context) == 0) return
        Log.d(TAG, "새 메시지 전송 성공 → 지연 메시지 즉시 일괄 발송")
        flushNow(context)
    }

    /** "지연메세지발송" 버튼: 간격 초기화 후 대기 중인 모든 건을 즉시 일괄 발송. 반환값 = 대기 건수. */
    fun flushNow(context: Context): Int {
        val n = size(context)
        resetDelay(context)
        if (n > 0) schedule(context, 0, ExistingWorkPolicy.REPLACE)
        return n
    }
}
