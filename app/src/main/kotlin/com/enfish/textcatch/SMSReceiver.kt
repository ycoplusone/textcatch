package com.enfish.textcatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log

/**
 * SMS/MMS 수신을 즉시 감지하는 BroadcastReceiver.
 * SMS 는 수신 즉시 직접 전송(Forwarder.forwardSms)을 시도하고 실패분만 WorkManager 로,
 * MMS 는 콘텐츠 다운로드 대기가 필요해 WorkManager(ForwardWorker)로 바로 넘긴다.
 */
open class SMSReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SMSReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "SMSReceiver 트리거 action=${intent.action}")

        when (intent.action) {
            Telephony.Sms.Intents.SMS_RECEIVED_ACTION -> handleSms(context, intent)
            Telephony.Sms.Intents.WAP_PUSH_RECEIVED_ACTION -> handleMms(context, intent)
        }
    }

    /** SMS 파싱: 발신자 + 본문(멀티파트 결합) */
    private fun handleSms(context: Context, intent: Intent) {
        val receivedAt = System.currentTimeMillis()  // 폰 수신 시각 (지연 측정 기준)
        val messages: Array<SmsMessage> = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            ?: return
        if (messages.isEmpty()) return

        // 멀티파트 SMS 는 같은 발신자로 여러 조각이 오므로 본문을 이어붙인다.
        val sender = messages[0].displayOriginatingAddress ?: messages[0].originatingAddress ?: ""
        val body = StringBuilder()
        var timestamp = System.currentTimeMillis()
        for (m in messages) {
            body.append(m.messageBody ?: "")
            timestamp = m.timestampMillis
        }

        // 듀얼 SIM: 이 SMS 를 받은 SIM 의 번호를 수신자로
        val subId = Utils.subIdFromIntent(intent)
        val receiver = Utils.getDevicePhoneNumber(context, subId)
        Log.d(TAG, "SMS 수신 sender=$sender bodyLen=${body.length} subId=$subId receiver=$receiver")

        // 네트워크 호출이라 메인 스레드 불가 → goAsync 로 브로드캐스트를 연장해 백그라운드 스레드에서 전송.
        // (forwardSms 는 최대 ~8초, 실패 시 WorkManager 큐잉까지 마친 뒤 finish)
        val pending = goAsync()
        val appContext = context.applicationContext
        val text = body.toString()
        Thread {
            try {
                Forwarder.forwardSms(appContext, sender, receiver, text, timestamp, receivedAt)
            } catch (e: Throwable) {
                Log.e(TAG, "직접 전송 처리 실패 → WorkManager 로 큐잉", e)
                // 여기서 다시 예외가 나도 스레드가 죽어 앱 전체가 중지되지 않도록 잡는다
                try {
                    Forwarder.enqueueSms(appContext, sender, receiver, text, timestamp, receivedAt)
                } catch (e2: Throwable) {
                    Log.e(TAG, "WorkManager 큐잉도 실패 (선저장 파일이 남았으면 정기 점검이 복구)", e2)
                }
            }
            try {
                // 앱이 깨어난 김에 이전에 전송 도중 중단된 메시지 복구 + 대기열 재발송 예약 확인
                RetryQueue.ensureScheduled(appContext)
                Forwarder.sweepMms(appContext)
            } catch (e: Throwable) {
                Log.e(TAG, "대기열/MMS 점검 실패", e)
            } finally {
                pending.finish()
            }
        }.start()
    }

    /**
     * MMS(WAP Push) 감지.
     * WAP Push 시점에는 본문/이미지가 아직 단말에 저장되기 전이라,
     * ForwardWorker 가 content provider 를 폴링해 준비되면 전송한다.
     */
    private fun handleMms(context: Context, intent: Intent) {
        // 수신자 번호는 ForwardWorker 가 MMS 행의 sub_id 로 다시 확인한다 (여기 값은 대체값)
        val subId = Utils.subIdFromIntent(intent)
        Log.d(TAG, "MMS(WAP_PUSH) 수신 감지 subId=$subId")
        val receiver = Utils.getDevicePhoneNumber(context, subId)
        val receivedAt = System.currentTimeMillis()
        // 앞 작업이 긴 백오프로 멈춰 있으면 이 MMS 도 그 뒤에서 밀리므로 먼저 체인을 정리한 뒤 큐잉
        // (WorkManager DB 조회를 기다려야 해서 goAsync 백그라운드 스레드에서 처리)
        val pending = goAsync()
        val appContext = context.applicationContext
        Thread {
            try {
                Forwarder.kickMms(appContext, onlyIfStalled = true)
            } catch (e: Throwable) {
                Log.e(TAG, "MMS 체인 점검 실패 (큐잉은 계속)", e)
            }
            try {
                Forwarder.enqueueMms(appContext, receiver, receivedAt)
            } catch (e: Throwable) {
                // 큐잉 실패해도 MMS 는 저장소에 남아 있어 정기 점검(sweepMms)이 찾아 보낸다
                Log.e(TAG, "MMS 큐잉 실패 → 정기 점검에서 전송", e)
            } finally {
                pending.finish()
            }
        }.start()

        val extras: Bundle? = intent.extras
        val pdu = extras?.getByteArray("data")
        Log.d(TAG, "MMS pdu size=${pdu?.size ?: 0}")
    }
}
