package com.enfish.textcatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 1시간마다 정기 호출(heartbeat) 알람 수신기.
 * - ACTION_FIRE: heartbeat 전송 작업을 큐잉하고 다음 회차(1시간 뒤)로 다시 예약한다.
 * - BOOT_COMPLETED: 재부팅 후 알람이 사라지므로 다시 예약한다.
 */
class DailyPingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 정기 알람/부팅 때마다 중단된 전송 복구 + 대기열 재발송 예약 확인 + 미전송 MMS 점검
        // (WorkManager DB 조회를 기다려야 해서 goAsync 백그라운드 스레드에서 처리)
        val appContext = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                RetryQueue.ensureScheduled(appContext)
                Forwarder.sweepMms(appContext)
            } catch (e: Throwable) {
                Log.e(DailyPing.TAG, "대기열/MMS 점검 실패", e)
            } finally {
                pending.finish()
            }
        }.start()
        try {
            handle(context, intent)
        } catch (e: Throwable) {
            // 리시버 예외는 앱 전체 중지로 이어지므로 잡는다. 다음 알람이 빠졌을 수 있어 재예약을 한 번 더 시도
            Log.e(DailyPing.TAG, "정기 호출 처리 실패", e)
            try {
                DailyPing.schedule(context)
            } catch (e2: Throwable) {
                Log.e(DailyPing.TAG, "정기 호출 재예약 실패 (앱 화면 진입 시 재예약)", e2)
            }
        }
    }

    private fun handle(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d(DailyPing.TAG, "부팅 완료 → 정기 호출 재예약")
                DailyPing.schedule(context)
                Forwarder.scheduleWatchdog(context)
            }
            DailyPing.ACTION_FIRE -> {
                val receiver = Utils.getDevicePhoneNumber(context)
                Forwarder.enqueueHeartbeat(
                    context,
                    receiver,
                    DailyPing.BODY,
                    System.currentTimeMillis()
                )
                Log.d(DailyPing.TAG, "정기 호출 큐잉 (receiver=$receiver)")
                DailyPing.schedule(context) // 다음 회차 예약
            }
        }
    }
}
