package com.enfish.textcatch

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

/**
 * 공용 유틸 — 설정 저장/조회, 기기 전화번호(수신자) 추출.
 */
object Utils {

    private const val PREFS = "textcatch_prefs"
    private const val KEY_API_URL = "api_url"

    /** 기본 API URL (테스트용 하드코딩 기본값). MainActivity 에서 덮어쓸 수 있다. */
    const val DEFAULT_API_URL = "http://enfish.duckdns.org:5000/api/textcatch"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getApiUrl(context: Context): String =
        prefs(context).getString(KEY_API_URL, DEFAULT_API_URL) ?: DEFAULT_API_URL

    fun setApiUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_API_URL, url.trim()).apply()
    }

    /**
     * 기기(수신자) 전화번호를 가져온다.
     * READ_PHONE_STATE / READ_PHONE_NUMBERS 권한이 필요하며,
     * 통신사/기기에 따라 빈 값이 나올 수 있다.
     */
    @SuppressLint("MissingPermission", "HardwareIds")
    fun getDevicePhoneNumber(
        context: Context,
        subId: Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    ): String {
        return try {
            val baseTm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                ?: return ""
            // 듀얼 SIM: 메시지를 받은 SIM 기준. 모르면 기본 SIM.
            val id = if (SubscriptionManager.isValidSubscriptionId(subId)) subId
                     else SubscriptionManager.getDefaultSubscriptionId()
            val tm = if (SubscriptionManager.isValidSubscriptionId(id))
                         baseTm.createForSubscriptionId(id) else baseTm
            val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                    as? SubscriptionManager
            val number = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                sm?.getPhoneNumber(id).takeUnless { it.isNullOrBlank() }
            } else {
                @Suppress("DEPRECATION")
                sm?.getActiveSubscriptionInfo(id)?.number.takeUnless { it.isNullOrBlank() }
            } ?: @Suppress("DEPRECATION") tm.line1Number
            number ?: ""
        } catch (e: SecurityException) {
            ""
        } catch (e: Exception) {
            ""
        }
    }

    /** SMS/WAP_PUSH 브로드캐스트에 담긴 수신 SIM 의 subscription id. 없으면 INVALID. */
    fun subIdFromIntent(intent: android.content.Intent): Int =
        intent.getIntExtra(
            SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
            intent.getIntExtra("subscription", SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        )
}
