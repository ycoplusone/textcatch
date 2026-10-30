package com.enfish.textcatch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.enfish.textcatch.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 설정 UI — API URL 저장, 권한 요청, 테스트 전송, 앱 버전 / 전송 대기열 표시.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    private var forwardInfos: List<WorkInfo> = emptyList()
    private var retryRunning = false

    /** 파일/DB 조회는 메인 스레드에서 하지 않는다 ("응답 없음" 방지) — 화면용 백그라운드 스레드 1개. */
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var delayedCount = 0
    @Volatile private var heldCount = 0

    /** 지연 대기열(파일)은 LiveData 가 없으므로 화면이 보이는 동안 1초마다 백그라운드에서 세고 화면 갱신. */
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            background {
                delayedCount = RetryQueue.size(applicationContext)
                heldCount = RetryQueue.holdSize(applicationContext)
                runOnUiThread { if (!isFinishing && !isDestroyed) renderQueue() }
            }
            ticker.postDelayed(this, 1000L)
        }
    }

    /** "사용하지 않는 앱 권한 삭제" 설정 화면에서 돌아오면 상태 다시 표시. */
    private val unusedRestrictionsSettings =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshStatus() }

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val denied = result.filterValues { !it }.keys
            if (denied.isEmpty()) {
                toast("모든 권한이 허용되었습니다")
            } else {
                toast("거부된 권한: ${denied.joinToString()}")
            }
            refreshStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.editApiUrl.setText(Utils.getApiUrl(this))
        binding.textVersion.text = versionText()
        observeQueue()

        binding.btnSave.setOnClickListener {
            val url = binding.editApiUrl.text?.toString()?.trim().orEmpty()
            if (url.isEmpty()) {
                toast("API URL 을 입력하세요")
            } else {
                Utils.setApiUrl(this, url)
                toast("API URL 저장됨")
            }
        }

        binding.btnPermissions.setOnClickListener { requestAllPermissions() }

        binding.btnTest.setOnClickListener { sendTestPayload() }

        // 1시간 주기를 기다리지 않고 heartbeat(대기/보류 현황 status 포함)를 지금 바로 전송
        binding.btnHeartbeat.setOnClickListener {
            Forwarder.enqueueHeartbeat(
                applicationContext,
                Utils.getDevicePhoneNumber(this),
                DailyPing.BODY,
                System.currentTimeMillis()
            )
            toast("heartbeat 전송 시작 (로그 보기 / logcat 확인)")
        }

        binding.btnFlush.setOnClickListener {
            val ctx = applicationContext
            // 지연 대기열(전송 실패분) + 재시도 대기 중인 MMS 체인(콘텐츠 대기분)을 함께 즉시 처리
            Thread {
                val n = try {
                    RetryQueue.flushNow(ctx)
                } catch (e: Throwable) {
                    0
                }
                val m = try {
                    Forwarder.kickMms(ctx)
                } catch (e: Throwable) {
                    0
                }
                runOnUiThread {
                    toast(
                        if (n + m > 0) "지연 메시지 ${n}건 · MMS 대기 ${m}건 즉시 발송 시작"
                        else "대기 중인 지연 메시지가 없습니다"
                    )
                    renderQueue()
                }
            }.start()
        }

        // 4xx 6시간 / 일시 실패 24시간 넘게 못 보내 보류된 건 (서버 수정 후 다시 보낼 때)
        binding.btnReleaseHold.setOnClickListener {
            val ctx = applicationContext
            Thread {
                val n = try {
                    RetryQueue.releaseHold(ctx)
                } catch (e: Throwable) {
                    -1
                }
                runOnUiThread {
                    toast(
                        when {
                            n > 0 -> "보류 메시지 ${n}건 재발송 시작"
                            n == 0 -> "보류 메시지가 없습니다"
                            else -> "보류 메시지 재발송 실패 (다시 시도하세요)"
                        }
                    )
                    renderQueue()
                }
            }.start()
        }

        binding.btnLogs.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }

        // 앱을 오래 안 열면 OS 가 SMS 권한을 회수하는 기능(권한 자동 삭제/앱 일시정지)을 끄는 설정 화면으로
        binding.btnUnusedRestrictions.setOnClickListener {
            try {
                unusedRestrictionsSettings.launch(
                    IntentCompat.createManageUnusedAppRestrictionsIntent(this, packageName)
                )
            } catch (e: Exception) {
                // 설정 화면이 없는 기기 → 앱 정보 화면
                try {
                    startActivity(
                        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(android.net.Uri.fromParts("package", packageName, null))
                    )
                } catch (e2: Exception) {
                    toast("설정 화면을 열 수 없습니다")
                }
            }
        }

        // 1시간마다 정기 상태 확인(heartbeat) API 호출 예약 (매시, 기기 번호 끝자리 분)
        DailyPing.schedule(this)
        // 15분마다 정기 점검 (중단된 전송 복구 + 미전송 MMS 전송)
        Forwarder.scheduleWatchdog(this)

        requestAllPermissions()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        // 중단된 전송 복구(파일 이동) + MMS 체인이 멈춰 있으면 정리 + 저장소에 미전송 MMS 가 있으면 전송
        val ctx = applicationContext
        Thread {
            try {
                RetryQueue.ensureScheduled(ctx)
            } catch (e: Throwable) {
                // 다음 화면 진입 / 정기 점검에서 다시 시도
            }
            try {
                Forwarder.sweepMms(ctx)
            } catch (e: Throwable) {
                // 다음 화면 진입 / 버튼으로 다시 시도
            }
        }.start()
        ticker.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(tick)
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    private fun background(block: () -> Unit) {
        try {
            io.execute {
                try {
                    block()
                } catch (e: Throwable) {
                    // 화면 표시용 조회 실패는 다음 갱신 때 다시 시도
                }
            }
        } catch (e: Exception) {
            // 화면 종료로 executor 가 닫힌 뒤 → 무시
        }
    }

    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.RECEIVE_MMS,
            // MMS(WAP_PUSH_RECEIVED) 브로드캐스트 수신에 필요 (없으면 MmsReceiver 미호출)
            Manifest.permission.RECEIVE_WAP_PUSH,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.READ_PHONE_NUMBERS)
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return list.toTypedArray()
    }

    private fun requestAllPermissions() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions.launch(missing.toTypedArray())
        }
    }

    private fun refreshStatus() {
        val ctx = applicationContext
        val granted = requiredPermissions().count {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        val total = requiredPermissions().size
        // 전화번호 / 권한 자동 삭제 상태 / 크래시 기록은 시스템 서비스·디스크 조회라 백그라운드에서
        background {
            val phone = Utils.getDevicePhoneNumber(ctx).ifEmpty { "(알 수 없음)" }
            val restrictions = when (CrashLog.unusedRestrictions(ctx)) {
                "disabled" -> "꺼짐 (정상)"
                "enabled" -> "켜짐 ⚠ 오래 안 열면 SMS 권한 회수 → 아래 버튼으로 끄기"
                "unavailable" -> "해당 없음"
                else -> "확인 불가"
            }
            val crashes = CrashLog.count(ctx)
            val text = buildString {
                append("권한: $granted / $total 허용\n")
                append("권한 자동 삭제: $restrictions\n")
                append("기기 번호(수신자): $phone\n")
                append("API URL: ${Utils.getApiUrl(ctx)}")
                if (crashes > 0) {
                    append("\n앱 비정상 종료: ${crashes}회 (최근 ${fmt.format(Date(CrashLog.lastAt(ctx)))})")
                }
            }
            runOnUiThread { if (!isFinishing && !isDestroyed) binding.textStatus.text = text }
        }
    }

    private fun versionText(): String = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        "v${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})"
    } catch (e: Exception) {
        "v?"
    }

    /**
     * 전송 대기열 실시간 표시. WorkManager 는 워커 클래스 이름을 태그로 자동 부여하므로
     * 이 기능 이전에 쌓인 작업도 함께 조회된다. 지연 대기열(RetryQueue)은 별도 줄로 표시.
     */
    private fun observeQueue() {
        val wm = WorkManager.getInstance(applicationContext)
        wm.getWorkInfosByTagLiveData(ForwardWorker::class.java.name).observe(this) { infos ->
            forwardInfos = infos
            renderQueue()
        }
        wm.getWorkInfosForUniqueWorkLiveData(RetryQueue.WORK_NAME).observe(this) { infos ->
            retryRunning = infos.any { it.state == WorkInfo.State.RUNNING }
            renderQueue()
        }
    }

    private fun renderQueue() {
        val infos = forwardInfos
        val enqueued = infos.filter { it.state == WorkInfo.State.ENQUEUED }
        val waiting = enqueued.count { it.runAttemptCount == 0 }  // 첫 실행 전(네트워크 대기 등)
        val retrying = enqueued.filter { it.runAttemptCount > 0 } // MMS 콘텐츠 대기 등 백오프 중
        val running = infos.count { it.state == WorkInfo.State.RUNNING }
        val blocked = infos.count { it.state == WorkInfo.State.BLOCKED } // MMS 체인 순서 대기
        val delayed = delayedCount
        binding.textQueue.text = buildString {
            append("전송 대기열: ${waiting + retrying.size + running + blocked + delayed}건\n")
            append("발송 대기 $waiting · 재시도 대기 ${retrying.size} · 전송 중 $running · 순서 대기 $blocked\n")
            append("지연 메시지 ${delayed}건")
            if (delayed > 0) {
                val next = RetryQueue.nextAt(applicationContext)
                val left = (next - System.currentTimeMillis()) / 1000L
                when {
                    retryRunning && left <= 0 -> append(" · 일괄 재발송 중")
                    next > 0 && left > 0 -> append(" · 다음 일괄 재발송 ${fmt.format(Date(next))} (${left}초 후)")
                    else -> append(" · 네트워크 대기 중")
                }
            }
            val held = heldCount
            if (held > 0) append("\n보류 메시지 ${held}건 (보류메세지재발송으로 다시 전송)")
            retrying.minOfOrNull { it.nextScheduleTimeMillis }?.let { next ->
                val maxTry = retrying.maxOf { it.runAttemptCount }
                append("\n다음 재시도: ${fmt.format(Date(next))} (최다 ${maxTry}회 실패)")
            }
        }
    }

    /** 실제 수신 없이 전송 경로를 점검하는 테스트 버튼. */
    private fun sendTestPayload() {
        // 실제 SMS 수신과 같은 경로: 직접 전송 → 실패 시 지연 대기열
        val ctx = applicationContext
        val receiver = Utils.getDevicePhoneNumber(this)
        Thread {
            try {
                Forwarder.forwardSms(
                    context = ctx,
                    sender = "01012345678",
                    receiver = receiver,
                    body = "테스트 메시지 from MainActivity",
                    timestamp = System.currentTimeMillis()
                )
            } catch (e: Throwable) {
                runOnUiThread { toast("테스트 전송 실패: ${e.javaClass.simpleName}") }
            }
        }.start()
        toast("테스트 전송 시작 (로그 보기 / logcat 확인)")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
