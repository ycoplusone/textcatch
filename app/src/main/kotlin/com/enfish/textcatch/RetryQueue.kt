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
 * 지연(일시 실패) 메시지 대기열 + 전송 중/보류/손상 보관소. 메시지는 디스크에 쓴 뒤에만 보내고, 삭제는 전송 성공 시에만 한다.
 *
 * - inflight/   : 직접 전송 직전에 먼저 저장(선저장). 성공하면 삭제, 실패하면 retry_queue/ 로 이동.
 *                 전송 도중 프로세스가 죽어 남은 파일은 recoverInflight 가 retry_queue/ 로 옮긴다.
 * - retry_queue/: 일시 실패 + 4xx payload. RetryWorker 하나가 대기 중인 모든 건을 한 번에(오래된 순) 보낸다.
 *   (MMS 이미지 Base64 가 커서 SharedPreferences 대신 파일 사용)
 * - hold/       : 4xx 가 HOLD_PERMANENT_AFTER_MS, 일시 실패가 GIVE_UP_AFTER_MS 넘게 계속된 건. 삭제하지 않고 보관,
 *                 heartbeat 로 서버에 보고, "보류메세지재발송" 버튼(releaseHold)으로 다시 대기열에 넣는다.
 * - corrupt/    : 읽을 수 없는 파일 (삭제하지 않고 격리, heartbeat 로 보고).
 * - 파일 쓰기는 임시 파일 → rename 으로 원자적 (쓰는 도중 죽어도 반쪽 파일이 남지 않음).
 * - 재발송 간격: 1 → 2 → 4 → 8 → … → 최대 600초 (전체 대기열 공통 단계, 대기열이 비면 초기화)
 * - flushNow(): "지연메세지발송" 버튼 — 대기 중인 간격을 무시하고 즉시 일괄 발송.
 * - payload 의 "_" 로 시작하는 키는 내부 관리용 (Api.post 가 전송 전에 뺀다).
 */
object RetryQueue {

    private const val TAG = "RetryQueue"
    const val WORK_NAME = "retry_flush"

    /** 재발송 간격 상한 (10분). */
    const val MAX_DELAY_SEC = 600L
    /** 대기열에 들어온 뒤 이 시간이 지나도 일시 실패면 보류로 옮긴다. */
    const val GIVE_UP_AFTER_MS = 24 * 60 * 60 * 1000L
    /** 4xx 는 서버 배포 실수 등 잠깐의 문제일 수 있어 이 시간 동안은 계속 재시도한 뒤 보류. */
    const val HOLD_PERMANENT_AFTER_MS = 6 * 60 * 60 * 1000L
    /** 보류 보관 기간. 넘으면 삭제하고 삭제 건수를 heartbeat 로 보고. */
    private const val HOLD_KEEP_MS = 30 * 24 * 60 * 60 * 1000L
    /** inflight 파일이 이보다 오래 남아 있으면 전송 도중 프로세스가 죽은 것 (직접 전송은 최대 ~8초). */
    private const val INFLIGHT_STALE_MS = 60 * 1000L

    /** 내부 관리 키: 대기열에 (다시) 들어온 시각 — 보류 판단 기준. */
    const val KEY_SINCE = "_since"
    private const val KEY_HOLD_REASON = "_hold_reason"
    private const val KEY_HOLD_AT = "_hold_at"

    private const val DIR = "retry_queue"
    private const val DIR_INFLIGHT = "inflight"
    private const val DIR_HOLD = "hold"
    private const val DIR_CORRUPT = "corrupt"
    private const val PREFS = "textcatch_retry"
    private const val KEY_STEP = "step"        // 다음 간격 = 2^step 초 (상한 600)
    private const val KEY_NEXT_AT = "next_at"  // 다음 일괄 재발송 예정 시각 (화면 표시용)
    private const val KEY_HOLD_DROPPED = "hold_dropped" // 보관 기간 초과로 삭제한 보류 건수 (누적)

    /** RetryWorker 실행 중 여부 (실행 중이면 새 건은 워커가 다음 라운드에서 가져간다). */
    @Volatile
    var flushing = false

    private fun dir(context: Context, name: String = DIR) = File(context.filesDir, name).apply { mkdirs() }

    /** 파일명 = 수신시각(0 패딩) + msg_id → 이름순 정렬이 곧 오래된 순 */
    private fun fileName(payload: JSONObject): String {
        val receivedAt = payload.optLong("received_at", System.currentTimeMillis())
        val msgId = payload.optString("msg_id").ifEmpty { System.nanoTime().toString() }
        return "%015d_%s.json".format(receivedAt, msgId)
    }

    /** 임시 파일에 다 쓴 뒤 rename → 대상 파일은 항상 완전한 내용만 보인다. */
    private fun writeAtomic(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw java.io.IOException("rename 실패 ${file.name}")
        }
    }

    private fun jsonFiles(d: File): List<File> =
        d.listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name } ?: emptyList()

    /** 일시 실패한 payload(msg_id/received_at/attempt 포함)를 대기열에 넣고 재발송을 예약한다. */
    fun add(context: Context, payload: JSONObject) = synchronized(this) {
        if (!payload.has(KEY_SINCE)) payload.put(KEY_SINCE, System.currentTimeMillis())
        writeAtomic(File(dir(context), fileName(payload)), payload.toString())
        Log.d(TAG, "대기열 추가 msg_id=${payload.optString("msg_id")} type=${payload.optString("type")} 대기 ${size(context)}건")
        if (!flushing) schedule(context, delaySec = currentDelaySec(context), ExistingWorkPolicy.KEEP)
    }

    /** 대기 중인 파일 목록 (오래된 순). */
    fun list(context: Context): List<File> = synchronized(this) { jsonFiles(dir(context)) }

    fun size(context: Context): Int = list(context).size

    fun read(context: Context, file: File): JSONObject? = try {
        JSONObject(file.readText())
    } catch (e: Exception) {
        Log.e(TAG, "대기열 파일 손상 → 격리 ${file.name}", e)
        synchronized(this) { file.renameTo(File(dir(context, DIR_CORRUPT), file.name)) }
        null
    }

    fun update(file: File, payload: JSONObject) = synchronized(this) {
        if (file.exists()) writeAtomic(file, payload.toString())
    }

    fun remove(file: File) = synchronized(this) { file.delete() }

    // ---------- 선저장 (직접 전송) ----------

    /** 직접 전송 직전에 payload 를 디스크에 먼저 기록. 반환된 파일은 전송 결과에 따라 remove / promote. */
    fun beginInflight(context: Context, payload: JSONObject): File = synchronized(this) {
        File(dir(context, DIR_INFLIGHT), fileName(payload)).also { writeAtomic(it, payload.toString()) }
    }

    /** 직접 전송 실패 → 선저장 파일을 지연 대기열로 넘긴다. */
    fun promote(context: Context, inflight: File, payload: JSONObject) = synchronized(this) {
        add(context, payload)
        inflight.delete()
    }

    /**
     * 전송 도중 프로세스가 죽어 남은 inflight 파일을 대기열로 옮기고(서버가 이미 받았을 수 있음 → msg_id 로 중복 제거),
     * 쓰다 만 임시 파일을 정리한다. 반환값 = 복구한 건수.
     */
    fun recoverInflight(context: Context): Int = synchronized(this) {
        val now = System.currentTimeMillis()
        listOf(DIR, DIR_INFLIGHT, DIR_HOLD).forEach { name ->
            dir(context, name).listFiles { f -> f.name.endsWith(".tmp") && now - f.lastModified() > INFLIGHT_STALE_MS }
                ?.forEach { it.delete() }
        }
        var n = 0
        for (f in jsonFiles(dir(context, DIR_INFLIGHT))) {
            if (now - f.lastModified() < INFLIGHT_STALE_MS) continue // 지금 전송 중일 수 있음
            if (f.renameTo(File(dir(context), f.name))) n++
        }
        if (n > 0) Log.w(TAG, "전송 중 중단된 메시지 ${n}건 → 대기열로 복구")
        n
    }

    // ---------- 보류 ----------

    /** 대기열 파일을 보류로 옮긴다 (삭제하지 않음). */
    fun hold(context: Context, file: File, payload: JSONObject, reason: String) = synchronized(this) {
        payload.put(KEY_HOLD_REASON, reason)
        payload.put(KEY_HOLD_AT, System.currentTimeMillis())
        writeAtomic(File(dir(context, DIR_HOLD), file.name), payload.toString())
        file.delete()
        Log.w(TAG, "보류 이동 ($reason) msg_id=${payload.optString("msg_id")}")
    }

    fun holdSize(context: Context): Int = synchronized(this) { jsonFiles(dir(context, DIR_HOLD)).size }

    /** "보류메세지재발송" 버튼: 보류 건을 모두 대기열로 되돌리고(보류 기한 새로 시작) 즉시 일괄 발송. 반환값 = 건수. */
    fun releaseHold(context: Context): Int {
        val n = synchronized(this) {
            val files = jsonFiles(dir(context, DIR_HOLD))
            for (f in files) {
                val p = read(context, f) ?: continue
                p.remove(KEY_HOLD_REASON)
                p.remove(KEY_HOLD_AT)
                p.put(KEY_SINCE, System.currentTimeMillis())
                writeAtomic(File(dir(context), f.name), p.toString())
                f.delete()
            }
            files.size
        }
        if (n > 0) {
            Log.d(TAG, "보류 ${n}건 → 대기열로 되돌려 즉시 발송")
            flushNow(context)
        }
        return n
    }

    /** 보관 기간이 지난 보류 건 삭제 (삭제 건수는 누적 기록해 보고). */
    private fun purgeHold(context: Context) = synchronized(this) {
        val now = System.currentTimeMillis()
        var n = 0
        for (f in jsonFiles(dir(context, DIR_HOLD))) {
            if (now - f.lastModified() > HOLD_KEEP_MS && f.delete()) n++
        }
        if (n > 0) {
            val p = prefs(context)
            p.edit().putInt(KEY_HOLD_DROPPED, p.getInt(KEY_HOLD_DROPPED, 0) + n).apply()
            Log.w(TAG, "보관 기간 초과 보류 ${n}건 삭제")
        }
    }

    /** heartbeat 에 실을 상태: 대기/전송 중/보류/손상 건수 + 보류 사유별 건수 + 가장 오래된 보류 수신 시각. */
    fun status(context: Context): JSONObject {
        purgeHold(context)
        return synchronized(this) {
            val held = jsonFiles(dir(context, DIR_HOLD))
            val reasons = JSONObject()
            var oldest = 0L
            for (f in held) {
                val p = try { JSONObject(f.readText()) } catch (e: Exception) { null } ?: continue
                val r = p.optString(KEY_HOLD_REASON, "?")
                reasons.put(r, reasons.optInt(r, 0) + 1)
                val at = p.optLong("received_at", 0L)
                if (at > 0 && (oldest == 0L || at < oldest)) oldest = at
            }
            JSONObject().apply {
                put("retry_count", jsonFiles(dir(context)).size)
                put("inflight_count", jsonFiles(dir(context, DIR_INFLIGHT)).size)
                put("hold_count", held.size)
                put("hold_oldest_received_at", oldest)
                put("hold_reasons", reasons)
                put("hold_dropped", prefs(context).getInt(KEY_HOLD_DROPPED, 0))
                put("corrupt_count", jsonFiles(dir(context, DIR_CORRUPT)).size)
            }
        }
    }

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

    /**
     * 중단된 전송 복구 + 대기열에 건이 남아 있으면 재발송이 예약돼 있도록 보장.
     * (앱 화면 진입 / SMS 수신 / heartbeat 알람 / 재부팅 시 호출하는 안전장치)
     */
    fun ensureScheduled(context: Context) {
        recoverInflight(context)
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
