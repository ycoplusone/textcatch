package com.enfish.textcatch

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * 메시지 데이터를 추출해 REST API 로 POST 하는 WorkManager 워커.
 *
 * - ① MMS: WAP Push 직후에는 본문/이미지가 아직 안 들어와 있으므로,
 *   content provider 를 짧게 폴링해서 준비될 때까지 기다린 뒤 전송한다.
 *   그래도 안 오면 Result.retry() 로 WorkManager 가 나중에 다시 시도한다.
 * - ② 실패 시 Result.retry() → 지수 백오프 재시도.
 */
class ForwardWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "ForwardWorker"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        // MMS 폴링: 1.5초 간격 x 6회 (약 9초) 동안 콘텐츠 도착 대기
        private const val MMS_POLL_TRIES = 6
        private const val MMS_POLL_INTERVAL_MS = 1500L
        private const val MMS_RECENT_WINDOW_MS = 5 * 60 * 1000L

        private const val PREFS = "textcatch_state"
        private const val KEY_LAST_MMS_ID = "last_mms_id"
        private const val KEY_SENT_WORK_IDS = "sent_work_ids"
        private const val SENT_WORK_IDS_MAX = 100
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** 이번에 전송한 MMS id (성공 시 중복 방지용으로 저장). */
    private var pendingMmsId: Long? = null

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // 전송은 됐는데 작업이 중단돼(네트워크 전환 등) 재실행된 경우 → 다시 보내지 않는다
        if (isAlreadySent()) {
            Log.d(TAG, "이미 전송된 작업 재실행 → 건너뜀 id=$id")
            return@withContext Result.success()
        }
        val type = inputData.getString(Forwarder.KEY_TYPE) ?: "SMS"
        val receiver = inputData.getString(Forwarder.KEY_RECEIVER) ?: ""
        val timestamp = inputData.getLong(Forwarder.KEY_TIMESTAMP, System.currentTimeMillis())

        val payload: JSONObject = if (type == "MMS") {
            buildMmsPayload(receiver, timestamp)
                ?: run {
                    // 아직 MMS 콘텐츠가 안 옴 → 나중에 다시 시도
                    Log.d(TAG, "MMS 콘텐츠 미도착 → 재시도 예약")
                    return@withContext Result.retry()
                }
        } else {
            buildSmsPayload(
                sender = inputData.getString(Forwarder.KEY_SENDER) ?: "",
                receiver = receiver,
                body = inputData.getString(Forwarder.KEY_BODY) ?: "",
                timestamp = timestamp
            )
        }

        if (post(payload)) {
            // 성공: 재실행 중복 방지 표시를 먼저 즉시 저장(commit) → 이력 저장
            markSent()
            LogStore.add(applicationContext, payload.optString("body"))
            Result.success()
        } else {
            Result.retry()
        }
    }

    // ---------- SMS ----------

    private fun buildSmsPayload(
        sender: String,
        receiver: String,
        body: String,
        timestamp: Long
    ): JSONObject = JSONObject().apply {
        put("type", "SMS")
        put("sender", sender)
        put("receiver", receiver)
        put("body", body)
        put("timestamp", timestamp)
    }

    // ---------- MMS ----------

    /**
     * content provider 를 폴링해 최신 MMS(본문/이미지)를 읽는다.
     * 준비되면 payload 반환, 아직이면 null(→ 재시도).
     */
    private suspend fun buildMmsPayload(receiver: String, timestamp: Long): JSONObject? {
        val lastSent = getLastMmsId()
        repeat(MMS_POLL_TRIES) { attempt ->
            // 아직 안 보낸 수신 MMS 중 가장 오래된 것부터 (연속 수신 시 누락 방지)
            for (id in queryUnsentMmsIds(lastSent)) {
                val dateMs = queryMmsDateMillis(id)
                val recent = dateMs <= 0L || dateMs >= timestamp - MMS_RECENT_WINDOW_MS
                if (!recent) continue
                val body = StringBuilder()
                val images = JSONArray()
                readMmsParts(id, body, images)
                // 본문 없이 제목에만 내용이 있는 LMS 대응
                if (body.isEmpty()) body.append(queryMmsSubject(id))
                if (body.isNotEmpty() || images.length() > 0) {
                    pendingMmsId = id
                    Log.d(TAG, "MMS 파싱 id=$id bodyLen=${body.length} images=${images.length()}")
                    return JSONObject().apply {
                        put("type", "MMS")
                        put("sender", queryMmsSender(id))
                        put("receiver", queryMmsReceiver(id) ?: receiver)
                        put("body", body.toString())
                        put("images", images)
                        put("timestamp", timestamp)
                    }
                }
            }
            Log.d(TAG, "MMS 대기중... attempt=${attempt + 1}/$MMS_POLL_TRIES")
            delay(MMS_POLL_INTERVAL_MS)
        }
        return null
    }

    /** 다운로드 완료된(m_type=132) 수신함(msg_box=1) MMS 중 lastSent 이후 id, 오래된 순. */
    private fun queryUnsentMmsIds(lastSent: Long): List<Long> {
        val ids = mutableListOf<Long>()
        applicationContext.contentResolver.query(
            Uri.parse("content://mms"), arrayOf("_id"),
            "_id>? AND msg_box=1 AND m_type=132", arrayOf(lastSent.toString()), "_id ASC"
        )?.use { c ->
            while (c.moveToNext()) ids.add(c.getLong(0))
        }
        return ids
    }

    /** MMS 제목. 프로바이더에 ISO-8859-1 로 저장되는 경우가 있어 sub_cs 로 재디코딩. */
    private fun queryMmsSubject(mmsId: Long): String {
        applicationContext.contentResolver
            .query(Uri.parse("content://mms/$mmsId"), arrayOf("sub", "sub_cs"), null, null, null)
            ?.use { c ->
                if (!c.moveToFirst()) return ""
                val raw = c.getString(0) ?: return ""
                val cs = charsetOf(if (c.isNull(1)) 0 else c.getInt(1)) ?: return raw
                return try {
                    // ISO-8859-1 범위 밖 문자가 있으면 이미 디코딩된 문자열
                    if (raw.any { it.code > 0xFF }) raw
                    else String(raw.toByteArray(Charsets.ISO_8859_1), cs)
                } catch (e: Exception) {
                    raw
                }
            }
        return ""
    }

    /** MMS charset(MIBenum) → Charset. 알 수 없으면 null. */
    private fun charsetOf(mib: Int): java.nio.charset.Charset? = when (mib) {
        106 -> Charsets.UTF_8
        3 -> Charsets.US_ASCII
        4 -> Charsets.ISO_8859_1
        36, 38 -> java.nio.charset.Charset.forName("EUC-KR")
        1015 -> Charsets.UTF_16
        else -> null
    }

    /** MMS date 컬럼은 초 단위인 경우가 많아 밀리초로 정규화. */
    private fun queryMmsDateMillis(mmsId: Long): Long {
        val uri = Uri.parse("content://mms/$mmsId")
        applicationContext.contentResolver
            .query(uri, arrayOf("date"), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val raw = c.getLong(0)
                    return if (raw < 1_000_000_000_000L) raw * 1000L else raw
                }
            }
        return 0L
    }

    /** 듀얼 SIM: MMS 를 받은 SIM(sub_id 컬럼)의 번호. 알 수 없으면 null. */
    private fun queryMmsReceiver(mmsId: Long): String? {
        val subId = try {
            applicationContext.contentResolver
                .query(Uri.parse("content://mms/$mmsId"), arrayOf("sub_id"), null, null, null)
                ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else null }
        } catch (e: Exception) {
            null // sub_id 컬럼이 없는 기기
        } ?: return null
        return Utils.getDevicePhoneNumber(applicationContext, subId).ifBlank { null }
    }

    private fun queryMmsSender(mmsId: Long): String {
        val uri = Uri.parse("content://mms/$mmsId/addr")
        applicationContext.contentResolver
            .query(uri, arrayOf("address", "type"), null, null, null)?.use { c ->
                val addrIdx = c.getColumnIndex("address")
                val typeIdx = c.getColumnIndex("type")
                while (c.moveToNext()) {
                    val t = if (typeIdx >= 0) c.getInt(typeIdx) else -1
                    if (t == 137) return c.getString(addrIdx) ?: "" // PduHeaders.FROM
                }
            }
        return ""
    }

    private fun readMmsParts(mmsId: Long, bodyOut: StringBuilder, imagesOut: JSONArray) {
        val uri = Uri.parse("content://mms/part")
        applicationContext.contentResolver
            .query(uri, null, "mid=?", arrayOf(mmsId.toString()), null)?.use { c ->
                val idIdx = c.getColumnIndex("_id")
                val ctIdx = c.getColumnIndex("ct")
                val textIdx = c.getColumnIndex("text")
                val dataIdx = c.getColumnIndex("_data")
                val chsetIdx = c.getColumnIndex("chset")
                while (c.moveToNext()) {
                    val contentType = c.getString(ctIdx) ?: continue
                    when {
                        contentType == "text/plain" -> {
                            var t = if (textIdx >= 0) c.getString(textIdx) else null
                            // 아주 긴 텍스트는 text 컬럼 대신 파일(_data)에 저장됨
                            if (t.isNullOrEmpty() && dataIdx >= 0 && !c.isNull(dataIdx)) {
                                val cs = if (chsetIdx >= 0 && !c.isNull(chsetIdx))
                                    charsetOf(c.getInt(chsetIdx)) else null
                                t = readPartAsText(c.getLong(idIdx), cs ?: Charsets.UTF_8)
                            }
                            if (!t.isNullOrEmpty()) bodyOut.append(t)
                        }
                        contentType.startsWith("image/") -> {
                            val base64 = readPartAsBase64(c.getLong(idIdx))
                            if (base64 != null) {
                                imagesOut.put(JSONObject().apply {
                                    put("mime_type", contentType)
                                    put("data", base64)
                                })
                            }
                        }
                    }
                }
            }
    }

    private fun readPartAsText(partId: Long, charset: java.nio.charset.Charset): String? {
        val partUri = ContentUris.withAppendedId(Uri.parse("content://mms/part"), partId)
        return try {
            applicationContext.contentResolver.openInputStream(partUri)?.use { input ->
                String(input.readBytes(), charset)
            }
        } catch (e: Exception) {
            Log.e(TAG, "텍스트 파트 읽기 실패 id=$partId", e)
            null
        }
    }

    private fun readPartAsBase64(partId: Long): String? {
        val partUri = ContentUris.withAppendedId(Uri.parse("content://mms/part"), partId)
        return try {
            applicationContext.contentResolver.openInputStream(partUri)?.use { input ->
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                var read: Int
                while (input.read(chunk).also { read = it } != -1) {
                    buffer.write(chunk, 0, read)
                }
                Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            Log.e(TAG, "이미지 파트 읽기 실패 id=$partId", e)
            null
        }
    }

    // ---------- 전송 ----------

    private fun post(payload: JSONObject): Boolean {
        val url = Utils.getApiUrl(applicationContext)
        Log.d(TAG, "API 전송 → $url : ${payload.optString("type")}")
        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(JSON))
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                Log.d(TAG, "API 응답 code=${resp.code}")
                resp.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "API 전송 예외 (재시도 예정)", e)
            false
        }
    }

    // ---------- 상태 저장 ----------

    private fun getLastMmsId(): Long =
        applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_MMS_ID, -1L)

    /** 이 작업(WorkRequest id)이 이미 전송 완료됐는지. 재실행돼도 id 는 같다. */
    private fun isAlreadySent(): Boolean =
        applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SENT_WORK_IDS, "")!!.split(',').contains(id.toString())

    /** 전송 완료 표시 + MMS 마지막 id 를 디스크에 즉시 기록 (최근 SENT_WORK_IDS_MAX 건 유지). */
    private fun markSent() {
        val p = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ids = p.getString(KEY_SENT_WORK_IDS, "")!!.split(',').filter { it.isNotEmpty() }
        val next = (ids + id.toString()).takeLast(SENT_WORK_IDS_MAX).joinToString(",")
        p.edit().apply {
            putString(KEY_SENT_WORK_IDS, next)
            pendingMmsId?.let { putLong(KEY_LAST_MMS_ID, it) }
        }.commit()
    }
}
