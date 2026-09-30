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
    fun getDevicePhoneNumber(context: Context): String {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                ?: return ""
            val number = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                        as? SubscriptionManager
                val subId = SubscriptionManager.getDefaultSubscriptionId()
                sm?.getPhoneNumber(subId).takeUnless { it.isNullOrBlank() } ?: tm.line1Number
            } else {
                @Suppress("DEPRECATION")
                tm.line1Number
            }
            number ?: ""
        } catch (e: SecurityException) {
            ""
        } catch (e: Exception) {
            ""
        }
    }
}
