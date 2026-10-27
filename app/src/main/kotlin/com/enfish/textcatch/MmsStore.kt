package com.enfish.textcatch

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONObject

/**
 * 폰의 MMS 저장소(content://mms) 조회 + 마지막 전송 MMS id 관리.
 * WAP Push 신호가 아니라 저장소를 기준으로 "저장돼 있는데 아직 안 보낸 MMS" 가 남지 않게 하는 데 쓴다.
 * (ForwardWorker 전송, Forwarder.sweepMms 정기 점검, heartbeat 상태 보고가 공용)
 */
object MmsStore {

    private const val TAG = "MmsStore"

    const val PREFS = "textcatch_state"
    const val KEY_LAST_MMS_ID = "last_mms_id"

    /** 다운로드 대기 MMS 알림을 보고 대상으로 볼 기간 (이보다 오래된 알림은 만료된 것으로 보고 제외). */
    private const val PENDING_REPORT_WINDOW_MS = 7 * 24 * 60 * 60 * 1000L

    private val MMS_URI: Uri = Uri.parse("content://mms")

    /** 마지막으로 전송(또는 대기열 이관)한 MMS id. -1 = 첫 설치(전송 기록 없음). */
    fun getLastMmsId(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_MMS_ID, -1L)

    /** 다운로드 완료된(m_type=132) 수신함(msg_box=1) MMS 중 lastSent 이후 id, 오래된 순. */
    fun unsentIds(context: Context, lastSent: Long = getLastMmsId(context)): List<Long> {
        val ids = mutableListOf<Long>()
        try {
            context.contentResolver.query(
                MMS_URI, arrayOf("_id"),
                "_id>? AND msg_box=1 AND m_type=132", arrayOf(lastSent.toString()), "_id ASC"
            )?.use { c ->
                while (c.moveToNext()) ids.add(c.getLong(0))
            }
        } catch (e: Exception) {
            Log.e(TAG, "MMS 조회 실패 (READ_SMS 권한 확인)", e)
        }
        return ids
    }

    /** 다운로드 대기 중인 MMS 알림(m_type=130, 수신함)이 lastSent 이후에 있는지. */
    fun hasPendingDownload(context: Context, lastSent: Long = getLastMmsId(context)): Boolean = try {
        context.contentResolver.query(
            MMS_URI, arrayOf("_id"),
            "_id>? AND msg_box=1 AND m_type=130", arrayOf(lastSent.toString()), null
        )?.use { it.count > 0 } ?: false
    } catch (e: Exception) {
        false
    }

    /** MMS date 컬럼은 초 단위인 경우가 많아 밀리초로 정규화. 알 수 없으면 0. */
    fun dateMillis(context: Context, mmsId: Long): Long = try {
        context.contentResolver.query(
            Uri.parse("content://mms/$mmsId"), arrayOf("date"), null, null, null
        )?.use { c ->
            if (c.moveToFirst()) normalizeDate(c.getLong(0)) else 0L
        } ?: 0L
    } catch (e: Exception) {
        0L
    }

    private fun normalizeDate(raw: Long): Long = if (raw < 1_000_000_000_000L) raw * 1000L else raw

    /**
     * MMS 의 msg_id: 기기 + 저장소 id + 수신 시각으로 고정.
     * 어느 작업(WAP Push 작업 / 정리 작업)이 보내든, 재실행되든 같은 MMS 는 같은 값 → 서버 중복 제거 가능.
     */
    fun msgId(context: Context, mmsId: Long, dateMs: Long): String =
        "mms-${Utils.getDeviceId(context)}-$mmsId-$dateMs"

    /**
     * heartbeat status 에 MMS 현황 추가.
     * - mms_unsent: 저장소엔 있는데 아직 안 보낸 건수 (계속 0 이 아니면 전송이 막힌 것)
     * - mms_pending_download / mms_pending_oldest: 최근 7일 내 다운로드 안 된 MMS 알림 건수와 가장 오래된 시각
     *   (자동 다운로드 꺼짐/실패 → 내용이 폰에 없어 보낼 수 없는 MMS)
     */
    fun putStatus(context: Context, status: JSONObject) {
        status.put("mms_unsent", unsentIds(context).size)
        val since = System.currentTimeMillis() - PENDING_REPORT_WINDOW_MS
        var count = 0
        var oldest = 0L
        try {
            context.contentResolver.query(
                MMS_URI, arrayOf("date"), "msg_box=1 AND m_type=130", null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val d = normalizeDate(c.getLong(0))
                    if (d < since) continue
                    count++
                    if (oldest == 0L || d < oldest) oldest = d
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "MMS 다운로드 대기 조회 실패", e)
        }
        status.put("mms_pending_download", count)
        status.put("mms_pending_oldest", oldest)
    }
}
