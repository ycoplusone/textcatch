package com.enfish.textcatch

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * REST API 전송 공통부. 수신 즉시 직접 전송(Forwarder.forwardSms)과
 * WorkManager 재시도 전송(ForwardWorker)이 같은 판정 규칙을 쓰도록 한 곳에 둔다.
 */
object Api {

    private const val TAG = "Api"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    sealed class PostOutcome {
        object Success : PostOutcome()
        /** 다시 보내도 같은 결과일 응답 (400/401/403/404/422 등). */
        class Permanent(val reason: String) : PostOutcome()
        /** 일시적 실패: 네트워크 예외, 408/429/5xx 등. */
        class Retryable(val reason: String) : PostOutcome()
    }

    /** WorkManager 워커용: 느린 서버도 기다린다. */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 수신 즉시 직접 전송용: BroadcastReceiver(goAsync) 허용 시간(~10초) 안에 끝나야 하므로
     * 전체 호출을 FAST_CALL_TIMEOUT 으로 제한. 초과하면 Retryable → 워커로 넘긴다.
     */
    private const val FAST_CALL_TIMEOUT_SEC = 8L
    private val fastClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .writeTimeout(4, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(FAST_CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()
    }

    fun post(context: Context, payload: JSONObject, fast: Boolean = false): PostOutcome {
        val url = Utils.getApiUrl(context)
        Log.d(TAG, "API 전송 → $url : ${payload.optString("type")} path=${payload.optString("path")}" +
                " attempt=${payload.optInt("attempt")} device_id=${payload.optString("device_id")}")
        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(JSON))
            .build()
        return try {
            (if (fast) fastClient else client).newCall(request).execute().use { resp ->
                val code = resp.code
                Log.d(TAG, "API 응답 code=$code")
                when {
                    resp.isSuccessful -> PostOutcome.Success
                    code == 408 || code == 429 || code >= 500 -> PostOutcome.Retryable(code.toString())
                    else -> PostOutcome.Permanent(code.toString())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "API 전송 예외 (재시도 대상)", e)
            PostOutcome.Retryable("예외")
        }
    }

    /**
     * SMS / HEARTBEAT 공통 payload.
     * - msg_id: 메시지 고유값. 직접 전송 응답 유실 → 워커 재전송 시에도 같은 값이므로 서버 중복 제거용
     * - received_at: 폰이 메시지를 받은 시각 (timestamp 는 통신사 SMSC 접수 시각)
     * - sent_at / attempt / path: 이번 전송 시각, 시도 횟수(1부터), 경로("direct" | "worker")
     */
    fun buildPayload(
        context: Context,
        type: String,
        sender: String,
        receiver: String,
        body: String,
        timestamp: Long,
        msgId: String,
        receivedAt: Long,
        attempt: Int,
        path: String
    ): JSONObject = JSONObject().apply {
        put("type", type)
        put("sender", sender)
        put("receiver", receiver)
        put("body", body)
        put("timestamp", timestamp)
        stamp(this, context, msgId, receivedAt, attempt, path)
    }

    /** 공통 메타 필드(device_id, msg_id, received_at, sent_at, attempt, path) 추가. */
    fun stamp(
        payload: JSONObject,
        context: Context,
        msgId: String,
        receivedAt: Long,
        attempt: Int,
        path: String
    ) {
        // 기기 고유값: 한 기기의 여러 번호를 서버에서 같은 기기로 묶는 용도
        payload.put("device_id", Utils.getDeviceId(context))
        payload.put("msg_id", msgId)
        payload.put("received_at", receivedAt)
        payload.put("sent_at", System.currentTimeMillis())
        payload.put("attempt", attempt)
        payload.put("path", path)
    }
}
