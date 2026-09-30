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
import com.enfish.textcatch.databinding.ActivityMainBinding

/**
 * 설정 UI — API URL 저장, 권한 요청, 테스트 전송.
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

    /** 실제 수신 없이 전송 경로를 점검하는 테스트 버튼. */
    private fun sendTestPayload() {
        Forwarder.enqueueSms(
            context = this,
            sender = "01012345678",
            receiver = Utils.getDevicePhoneNumber(this),
            body = "테스트 메시지 from MainActivity",
            timestamp = System.currentTimeMillis()
        )
        toast("테스트 전송을 큐에 넣었습니다 (로그 보기 / logcat 확인)")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
