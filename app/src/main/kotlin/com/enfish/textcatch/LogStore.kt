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

    data class Entry(val time: Long, val title: String)

    /** 송신 성공 시 호출 — body 의 앞 10글자를 제목으로 저장. */
    @Synchronized
    fun add(context: Context, rawBody: String) {
        val title = rawBody.trim().replace(Regex("\\s+"), " ").take(10)
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = JSONArray(p.getString(KEY, "[]"))

        val next = JSONArray()
        next.put(JSONObject().put("t", System.currentTimeMillis()).put("title", title))
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
            Entry(o.optLong("t"), o.optString("title"))
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    /** 화면 표시용 한 줄 포맷: "MM-dd HH:mm:ss   제목". */
    fun formatLine(e: Entry): String = "${fmt.format(Date(e.time))}   ${e.title}"
}
