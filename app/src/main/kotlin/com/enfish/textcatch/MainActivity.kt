package com.enfish.textcatch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.enfish.textcatch.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 설정 UI — API URL 저장, 권한 요청, 테스트 전송, 앱 버전 / 전송 대기열 표시.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

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

        binding.btnLogs.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }

        // 6시간마다 정기 상태 확인(heartbeat) API 호출 예약 (01/07/13/19시대, 기기 번호 끝자리 분)
        DailyPing.schedule(this)

        requestAllPermissions()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
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
        val granted = requiredPermissions().count {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        val total = requiredPermissions().size
        val phone = Utils.getDevicePhoneNumber(this).ifEmpty { "(알 수 없음)" }
        binding.textStatus.text = buildString {
            append("권한: $granted / $total 허용\n")
            append("기기 번호(수신자): $phone\n")
            append("API URL: ${Utils.getApiUrl(this@MainActivity)}")
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
     * 이 기능 이전에 쌓인 작업도 함께 조회된다.
     */
    private fun observeQueue() {
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
        WorkManager.getInstance(applicationContext)
            .getWorkInfosByTagLiveData(ForwardWorker::class.java.name)
            .observe(this) { infos ->
                val enqueued = infos.filter { it.state == WorkInfo.State.ENQUEUED }
                val waiting = enqueued.count { it.runAttemptCount == 0 }  // 첫 실행 전(네트워크 대기 등)
                val retrying = enqueued.filter { it.runAttemptCount > 0 } // 실패 후 백오프 중
                val running = infos.count { it.state == WorkInfo.State.RUNNING }
                val blocked = infos.count { it.state == WorkInfo.State.BLOCKED } // MMS 체인 순서 대기
                binding.textQueue.text = buildString {
                    append("전송 대기열: ${waiting + retrying.size + running + blocked}건\n")
                    append("발송 대기 $waiting · 재시도 대기 ${retrying.size} · 전송 중 $running · 순서 대기 $blocked")
                    retrying.minOfOrNull { it.nextScheduleTimeMillis }?.let { next ->
                        val maxTry = retrying.maxOf { it.runAttemptCount }
                        append("\n다음 재시도: ${fmt.format(Date(next))} (최다 ${maxTry}회 실패)")
                    }
                }
            }
    }

    /** 실제 수신 없이 전송 경로를 점검하는 테스트 버튼. */
    private fun sendTestPayload() {
        // 실제 SMS 수신과 같은 경로: 직접 전송 → 실패 시 WorkManager
        val ctx = applicationContext
        val receiver = Utils.getDevicePhoneNumber(this)
        Thread {
            Forwarder.forwardSms(
                context = ctx,
                sender = "01012345678",
                receiver = receiver,
                body = "테스트 메시지 from MainActivity",
                timestamp = System.currentTimeMillis()
            )
        }.start()
        toast("테스트 전송 시작 (로그 보기 / logcat 확인)")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
