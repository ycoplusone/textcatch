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
            } catch (e: Exception) {
                Log.e(TAG, "직접 전송 처리 실패 → WorkManager 로 큐잉", e)
                Forwarder.enqueueSms(appContext, sender, receiver, text, timestamp, receivedAt)
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
        Forwarder.enqueueMms(context, receiver, System.currentTimeMillis())

        val extras: Bundle? = intent.extras
        val pdu = extras?.getByteArray("data")
        Log.d(TAG, "MMS pdu size=${pdu?.size ?: 0}")
    }
}
