package com.enfish.textcatch

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.Calendar
import java.util.Date

/**
 * 매일 01시대에 "앱이 정상 동작 중"임을 알리는 정기 API 호출(heartbeat)을 예약한다.
 * - 실제 SMS 발송이 아니라, 기존 전송 경로(ForwardWorker)로 heartbeat payload 만 보낸다.
 * - 발송 분 = 기기 번호 끝자리 (예: ...7 → 01:07).
 * - AlarmManager 로 매일 1회, 발송 후 다음 날로 다시 예약(재부팅 시 DailyPingReceiver 가 재예약).
 */
object DailyPing {
    const val TAG = "DailyPing"
    const val ACTION_FIRE = "com.enfish.textcatch.action.DAILY_PING"
    const val BODY = "TextCatch 테스트"
    const val HOUR = 1
    private const val REQUEST_CODE = 7001

    /** 기기 번호 끝자리(0~9) → 분. 숫자가 없으면 0. */
    fun minuteFromNumber(number: String): Int =
        number.lastOrNull { it.isDigit() }?.minus('0') ?: 0

    /** 오늘 01:[분] 이 지났으면 내일, 아니면 오늘 그 시각의 epoch millis. */
    fun nextTriggerMillis(number: String, now: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, HOUR)
            set(Calendar.MINUTE, minuteFromNumber(number))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    fun schedule(context: Context) {
        val ctx = context.applicationContext
        val number = Utils.getDevicePhoneNumber(ctx)
        val triggerAt = nextTriggerMillis(number)
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            ctx,
            REQUEST_CODE,
            Intent(ctx, DailyPingReceiver::class.java).setAction(ACTION_FIRE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            // Doze 중에도 깨어나 정확히 실행
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } catch (e: SecurityException) {
            // 정확 알람 권한이 없는 기기 → 비정확 알람으로 대체(약간 늦을 수 있음)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            Log.w(TAG, "정확 알람 불가 → 비정확 예약", e)
        }
        Log.d(TAG, "다음 정기 호출 예약: ${Date(triggerAt)} (번호=$number)")
    }
}
