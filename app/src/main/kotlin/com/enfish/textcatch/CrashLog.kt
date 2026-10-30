package com.enfish.textcatch

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.PackageManagerCompat
import androidx.core.content.UnusedAppRestrictionsConstants
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 현장 폰의 비정상 종료를 원격으로 파악하기 위한 기록.
 * - 처리되지 않은 예외(크래시)를 SharedPreferences 에 남긴다 (App.onCreate 에서 install).
 * - Android 11+ 는 OS 가 기록한 종료 사유(ANR / 메모리 부족 / 강제 종료 등)도 함께 읽는다.
 * - heartbeat status 에 실어 서버로 보낸다 (putStatus). 누적값이며 앱 데이터 삭제/재설치 시 초기화.
 */
object CrashLog {

    private const val TAG = "CrashLog"
    private const val PREFS = "crash_log"
    private const val KEY_COUNT = "crash_count"
    private const val KEY_AT = "last_crash_at"
    private const val KEY_TRACE = "last_crash"
    private const val TRACE_MAX = 1500
    private const val EXIT_MAX = 5

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 기본 예외 처리기 앞에 기록 단계를 끼운다. 기록 후 원래 처리기(시스템 "앱 중지" 처리)로 넘긴다. */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val p = prefs(appContext)
                // 프로세스가 곧 죽으므로 apply 가 아니라 commit(동기 기록)
                p.edit()
                    .putInt(KEY_COUNT, p.getInt(KEY_COUNT, 0) + 1)
                    .putLong(KEY_AT, System.currentTimeMillis())
                    .putString(KEY_TRACE, "[${thread.name}] ${Log.getStackTraceString(e)}".take(TRACE_MAX))
                    .commit()
            } catch (ignored: Throwable) {
            }
            previous?.uncaughtException(thread, e)
        }
    }

    fun count(context: Context): Int = prefs(context).getInt(KEY_COUNT, 0)

    fun lastAt(context: Context): Long = prefs(context).getLong(KEY_AT, 0L)

    /**
     * "사용하지 않는 앱의 권한 삭제 / 앱 일시정지" 상태. 켜져 있으면 앱을 오래 안 열 때
     * SMS 권한이 회수되어 수신이 조용히 멈춘다. 반환: "disabled"(꺼짐, 정상) | "enabled" | "unavailable" | "error".
     * 결과를 기다리므로 메인 스레드가 아닌 곳에서 호출.
     */
    fun unusedRestrictions(context: Context): String = try {
        when (PackageManagerCompat.getUnusedAppRestrictionsStatus(context).get(3, TimeUnit.SECONDS)) {
            UnusedAppRestrictionsConstants.DISABLED -> "disabled"
            UnusedAppRestrictionsConstants.FEATURE_NOT_AVAILABLE -> "unavailable"
            UnusedAppRestrictionsConstants.API_30_BACKPORT,
            UnusedAppRestrictionsConstants.API_30,
            UnusedAppRestrictionsConstants.API_31 -> "enabled"
            else -> "error"
        }
    } catch (e: Exception) {
        "error"
    }

    /** OS 가 기록한 최근 종료 사유 (Android 11+). 오래된 것은 OS 가 알아서 정리한다. */
    private fun exitReasons(context: Context): JSONArray {
        val out = JSONArray()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return out
        try {
            val am = context.getSystemService(ActivityManager::class.java) ?: return out
            for (info in am.getHistoricalProcessExitReasons(context.packageName, 0, EXIT_MAX)) {
                out.put(JSONObject().apply {
                    put("reason", reasonName(info.reason))
                    put("at", info.timestamp)
                    put("description", info.description ?: "")
                })
            }
        } catch (e: Exception) {
            Log.w(TAG, "종료 사유 조회 실패", e)
        }
        return out
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "UNKNOWN($reason)"
    }

    /** heartbeat status 에 크래시/종료 사유/권한 자동 삭제 상태를 추가한다. */
    fun putStatus(context: Context, status: JSONObject) {
        val p = prefs(context)
        status.put("crash_count", p.getInt(KEY_COUNT, 0))
        status.put("last_crash_at", p.getLong(KEY_AT, 0L))
        status.put("last_crash", p.getString(KEY_TRACE, "") ?: "")
        status.put("exit_reasons", exitReasons(context))
        status.put("unused_restrictions", unusedRestrictions(context))
    }
}
