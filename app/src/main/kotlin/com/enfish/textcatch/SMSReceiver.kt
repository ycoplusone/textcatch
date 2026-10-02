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
 * 수신 데이터를 파싱해 SMSForwardService 로 넘겨 REST API 전송을 맡긴다.
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

        Forwarder.enqueueSms(context, sender, receiver, body.toString(), timestamp)
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
