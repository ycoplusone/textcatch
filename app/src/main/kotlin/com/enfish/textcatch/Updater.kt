package com.enfish.textcatch

import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 플레이스토어 없이 서버에서 새 버전을 받아 설치한다.
 * 흐름: {API URL}/version 조회 → versionCode 비교 → 새 버전이면 APK 다운로드
 *       → 크기/sha256 검증 → 시스템 설치 화면 실행.
 *
 * 전제: 모든 기기에 "같은 release 서명"으로 최초 1회 설치되어 있어야 덮어쓰기 설치가 된다.
 */
object Updater {

    private const val TAG = "Updater"
    private const val AUTHORITY_SUFFIX = ".fileprovider"
    private const val NOTI_CHANNEL_ID = "update"
    private const val NOTI_ID = 2001

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private data class Info(
        val versionCode: Long,
        val versionName: String,
        val url: String,
        val sha256: String?,
        val size: Long,
        val notes: String
    )

    /** 버전 정보 URL — 설정된 API URL 뒤에 /version 을 붙인다. */
    private fun versionUrl(context: Context): String =
        Utils.getApiUrl(context).trimEnd('/') + "/version"

    private fun currentCode(context: Context): Long = try {
        PackageInfoCompat.getLongVersionCode(
            context.packageManager.getPackageInfo(context.packageName, 0)
        )
    } catch (e: Exception) {
        0L
    }

    /**
     * heartbeat 등 백그라운드(Activity 없음)에서 호출 — 새 버전이 있으면 알림만 띄운다.
     * 백그라운드에서는 설치 화면을 바로 열 수 없으므로(안드로이드 제한), 알림을 탭하면
     * 앱이 열리고 거기서 다운로드+설치가 진행된다. 네트워크 호출이므로 반드시 백그라운드 스레드에서 호출할 것.
     */
    fun checkInBackground(context: Context) {
        try {
            val info = fetchInfo(context) ?: return
            if (info.versionCode > currentCode(context)) {
                notifyNewVersion(context, info)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "백그라운드 업데이트 확인 실패", e)
        }
    }

    private fun notifyNewVersion(context: Context, info: Info) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            nm.getNotificationChannel(NOTI_CHANNEL_ID) == null
        ) {
            nm.createNotificationChannel(
                NotificationChannel(NOTI_CHANNEL_ID, "앱 업데이트", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        // 탭 → 앱 열림 → MainActivity 가 자동 확인하여 업데이트 다이얼로그 표시
        val pi = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val noti = NotificationCompat.Builder(context, NOTI_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("새 버전 v${info.versionName}")
            .setContentText("탭하여 업데이트를 설치하세요")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try {
            nm.notify(NOTI_ID, noti)
        } catch (e: Throwable) {
            // POST_NOTIFICATIONS 미허용(Android 13+) → 조용히 무시 (앱 열 때 자동 확인으로 대체)
            Log.w(TAG, "업데이트 알림 표시 실패", e)
        }
    }

    /**
     * 업데이트 확인. 백그라운드에서 조회 후 메인 스레드에서 안내.
     * @param manual true = 버튼으로 직접 실행(최신/실패도 알림). false = 앱 실행 시 자동(조용히).
     */
    fun check(activity: AppCompatActivity, manual: Boolean) {
        Thread {
            try {
                val info = fetchInfo(activity)
                val cur = currentCode(activity as Context)
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    when {
                        info == null ->
                            if (manual) toast(activity, "버전 정보를 가져오지 못했습니다")
                        info.versionCode <= cur ->
                            if (manual) toast(activity, "최신 버전입니다 (v${info.versionName})")
                        else -> promptUpdate(activity, info, cur)
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "업데이트 확인 실패", e)
                activity.runOnUiThread {
                    if (manual && !activity.isFinishing) toast(activity, "업데이트 확인 실패")
                }
            }
        }.start()
    }

    private fun fetchInfo(context: Context): Info? {
        val req = Request.Builder().url(versionUrl(context)).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "version 응답 code=${resp.code}")
                return null
            }
            val json = JSONObject(resp.body?.string().orEmpty())
            val url = json.optString("url")
            if (url.isBlank()) return null
            return Info(
                versionCode = json.optLong("versionCode", 0L),
                versionName = json.optString("versionName", "?"),
                url = url,
                sha256 = json.optString("sha256").takeIf { it.isNotBlank() },
                size = json.optLong("size", 0L),
                notes = json.optString("notes")
            )
        }
    }

    private fun promptUpdate(activity: AppCompatActivity, info: Info, cur: Long) {
        val msg = buildString {
            append("새 버전 v${info.versionName} (${info.versionCode})\n현재 버전 ($cur)\n")
            if (info.notes.isNotBlank()) append("\n${info.notes}")
        }
        AlertDialog.Builder(activity)
            .setTitle("업데이트 있음")
            .setMessage(msg)
            .setNegativeButton("나중에", null)
            .setPositiveButton("업데이트") { _, _ -> ensureInstallPermissionThenDownload(activity, info) }
            .show()
    }

    /** Android 8+ 는 "출처를 알 수 없는 앱 설치" 허용이 필요하다. 없으면 설정으로 안내하고 중단. */
    private fun ensureInstallPermissionThenDownload(activity: AppCompatActivity, info: Info) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            AlertDialog.Builder(activity)
                .setTitle("설치 권한 필요")
                .setMessage("이 앱이 업데이트를 설치하려면 '이 출처 허용'을 켜야 합니다.\n설정에서 허용한 뒤 다시 '업데이트 확인'을 눌러주세요.")
                .setNegativeButton("취소", null)
                .setPositiveButton("설정 열기") { _, _ ->
                    try {
                        activity.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${activity.packageName}")
                            )
                        )
                    } catch (e: Exception) {
                        toast(activity, "설정 화면을 열 수 없습니다")
                    }
                }
                .show()
            return
        }
        download(activity, info)
    }

    private fun download(activity: AppCompatActivity, info: Info) {
        val tv = TextView(activity).apply { setPadding(56, 40, 56, 16); text = "다운로드 준비 중..." }
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv)
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = 56; marginEnd = 56; bottomMargin = 32
            })
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("업데이트 다운로드")
            .setView(box)
            .setCancelable(false)
            .create()
        dialog.show()

        Thread {
            val apk = File(activity.getExternalFilesDir(null), "update/textcatch.apk")
            try {
                apk.parentFile?.mkdirs()
                if (apk.exists()) apk.delete()

                val req = Request.Builder().url(info.url).get().build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("다운로드 응답 code=${resp.code}")
                    val bodyBytes = resp.body ?: throw IllegalStateException("빈 응답")
                    val total = if (info.size > 0) info.size else bodyBytes.contentLength()
                    val digest = MessageDigest.getInstance("SHA-256")
                    bodyBytes.byteStream().use { input ->
                        apk.outputStream().use { output ->
                            val buf = ByteArray(1 shl 16)
                            var read: Int
                            var done = 0L
                            var lastPct = -1
                            while (input.read(buf).also { read = it } != -1) {
                                output.write(buf, 0, read)
                                digest.update(buf, 0, read)
                                done += read
                                if (total > 0) {
                                    val pct = (done * 100 / total).toInt()
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        activity.runOnUiThread {
                                            bar.progress = pct
                                            tv.text = "다운로드 중... $pct%  (${done / 1024}KB)"
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 크기 검증
                    if (info.size > 0 && apk.length() != info.size) {
                        throw IllegalStateException("크기 불일치 (받음=${apk.length()} / 기대=${info.size})")
                    }
                    // sha256 검증 (있을 때만)
                    if (info.sha256 != null) {
                        val got = digest.digest().joinToString("") { "%02x".format(it) }
                        if (!got.equals(info.sha256, ignoreCase = true)) {
                            throw IllegalStateException("무결성 검증 실패 (파일이 손상/변조되었을 수 있음)")
                        }
                    }
                }

                activity.runOnUiThread {
                    safeDismiss(dialog)
                    if (!activity.isFinishing && !activity.isDestroyed) launchInstall(activity, apk)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "다운로드/검증 실패", e)
                try {
                    if (apk.exists()) apk.delete()
                } catch (ignore: Throwable) {
                }
                activity.runOnUiThread {
                    safeDismiss(dialog)
                    if (!activity.isFinishing) toast(activity, "업데이트 실패: ${e.message}")
                }
            }
        }.start()
    }

    private fun launchInstall(activity: AppCompatActivity, apk: File) {
        try {
            val uri = FileProvider.getUriForFile(activity, activity.packageName + AUTHORITY_SUFFIX, apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "설치 실행 실패", e)
            toast(activity, "설치 화면을 열 수 없습니다: ${e.message}")
        }
    }

    private fun safeDismiss(dialog: AlertDialog) {
        try {
            if (dialog.isShowing) dialog.dismiss()
        } catch (ignore: Throwable) {
        }
    }

    private fun toast(activity: AppCompatActivity, msg: String) =
        android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_SHORT).show()
}
