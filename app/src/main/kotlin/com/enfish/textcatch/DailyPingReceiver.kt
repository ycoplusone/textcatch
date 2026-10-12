package com.enfish.textcatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 6시간마다 정기 호출(heartbeat) 알람 수신기.
 * - ACTION_FIRE: heartbeat 전송 작업을 큐잉하고 다음 회차(6시간 뒤)로 다시 예약한다.
 * - BOOT_COMPLETED: 재부팅 후 알람이 사라지므로 다시 예약한다.
 */
class DailyPingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d(DailyPing.TAG, "부팅 완료 → 정기 호출 재예약")
                DailyPing.schedule(context)
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
