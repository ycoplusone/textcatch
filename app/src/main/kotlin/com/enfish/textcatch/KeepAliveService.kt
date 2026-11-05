package com.enfish.textcatch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * 프로세스를 상주시키는 포그라운드 서비스.
 *
 * MIUI/삼성 등은 백그라운드 앱을 공격적으로 종료하는데, 앱이 죽어 있으면 SMS_RECEIVED
 * 브로드캐스트로 앱을 깨우지 못해 수신을 놓친다(heartbeat 알람은 깨우지만 SMS는 누락).
 * 상시 알림을 가진 포그라운드 서비스를 띄우면 OS 가 "사용 중인 앱"에 준하게 취급해
 * 웬만하면 프로세스를 죽이지 않으므로, SMS 브로드캐스트를 놓치지 않는다.
 *
 * - START_STICKY: 시스템이 죽여도 다시 시작한다.
 * - 알림은 IMPORTANCE_MIN 채널이라 소리/배너 없이 접힌 상태로만 상주한다(규정상 숨길 수는 없음).
 */
class KeepAliveService : Service() {

    companion object {
        private const val TAG = "KeepAliveService"
        private const val CHANNEL_ID = "keepalive"
        private const val NOTIFICATION_ID = 1002

        /**
         * 서비스 시작. 포그라운드 시작 제약(Android 12+ 백그라운드 시작 금지 등)으로 예외가 날 수 있어
         * 반드시 try/catch 로 감싼다. 실패해도 부팅/앱 진입 시 다시 시작되고, START_STICKY 로 복구된다.
         */
        fun start(context: Context) {
            try {
                val intent = Intent(context, KeepAliveService::class.java)
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Throwable) {
                Log.w(TAG, "포그라운드 서비스 시작 실패(다음 기회에 재시도)", e)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startAsForeground()
        } catch (e: Throwable) {
            // startForeground 가 제한으로 실패하면 서비스가 바로 종료될 수 있으나 앱은 죽지 않게 한다
            Log.e(TAG, "startForeground 실패", e)
            stopSelf()
        }
        // 죽어도 시스템이 다시 시작하도록
        return START_STICKY
    }

    private fun startAsForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "상시 수신 대기",
                        NotificationManager.IMPORTANCE_MIN
                    ).apply { description = "SMS/MMS 를 놓치지 않도록 앱을 상주시킵니다" }
                )
            }
        }
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("TextCatch 수신 대기 중")
            .setContentText("SMS/MMS 를 놓치지 않도록 실행 중입니다")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(pi)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Log.d(TAG, "포그라운드 서비스 시작됨")
    }
}
