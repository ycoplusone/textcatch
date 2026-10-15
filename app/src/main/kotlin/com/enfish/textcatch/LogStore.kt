package com.enfish.textcatch

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 송신 이력 저장소.
 * 요구사항대로 각 항목은 "시간 + 제목 10글자" 만 남긴다.
 * SharedPreferences 에 최신순으로 최대 MAX 개까지 보관한다.
 */
object LogStore {

    private const val PREFS = "textcatch_logs"
    private const val KEY = "entries"
    private const val MAX = 300

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    const val VIA_DIRECT = "direct"  // 수신 즉시 직접 전송
    const val VIA_WORKER = "worker"  // WorkManager 전송 (직접 전송 실패분, MMS, heartbeat)

    /** delayMs: 폰 수신 → 전송 시각 (-1 = 기록 없음), attempt: 시도 횟수 (0 = 기록 없음). */
    data class Entry(
        val time: Long,
        val title: String,
        val delayMs: Long = -1,
        val attempt: Int = 0,
        val via: String = ""
    )

    /**
     * 송신 결과 기록 — body 의 앞 10글자를 제목으로 저장.
     * failReason 이 있으면 전송 포기 건으로 "[실패 400] 제목" 형태로 남긴다.
     * delayMs / attempt / via 는 지연 분석용 (화면에 "+1.2s 즉시" 형태로 표시).
     */
    @Synchronized
    fun add(
        context: Context,
        rawBody: String,
        failReason: String? = null,
        delayMs: Long = -1,
        attempt: Int = 0,
        via: String = ""
    ) {
        val head = rawBody.trim().replace(Regex("\\s+"), " ").take(10)
        val title = if (failReason != null) "[실패 $failReason] $head" else head
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = JSONArray(p.getString(KEY, "[]"))

        val next = JSONArray()
        next.put(
            JSONObject().put("t", System.currentTimeMillis()).put("title", title)
                .put("d", delayMs).put("a", attempt).put("v", via)
        )
        val keep = minOf(old.length(), MAX - 1)
        for (i in 0 until keep) next.put(old.getJSONObject(i))

        p.edit().putString(KEY, next.toString()).apply()
    }

    /** 최신순 이력 목록. */
    fun list(context: Context): List<Entry> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray(p.getString(KEY, "[]"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(o.optLong("t"), o.optString("title"), o.optLong("d", -1), o.optInt("a"), o.optString("v"))
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    /**
     * 화면 표시용 한 줄 포맷: "MM-dd HH:mm:ss  +1.2s 즉시  제목" / "… +3분10초 워커3회  제목".
     * 지연 기록이 없는 예전 항목은 "MM-dd HH:mm:ss   제목".
     */
    fun formatLine(e: Entry): String {
        val time = fmt.format(Date(e.time))
        if (e.delayMs < 0) return "$time   ${e.title}"
        val how = when (e.via) {
            VIA_DIRECT -> "즉시"
            VIA_WORKER -> "워커${e.attempt}회"
            else -> ""
        }
        return "$time  +${formatDelay(e.delayMs)} $how  ${e.title}"
    }

    /** 지연 표시: 1분 미만 "4.2s", 1시간 미만 "3분10초", 이상 "2시간5분". */
    fun formatDelay(ms: Long): String {
        val sec = ms.coerceAtLeast(0) / 1000
        return when {
            sec < 60 -> String.format(Locale.US, "%.1fs", ms.coerceAtLeast(0) / 1000.0)
            sec < 3600 -> "${sec / 60}분${sec % 60}초"
            else -> "${sec / 3600}시간${sec % 3600 / 60}분"
        }
    }
}
