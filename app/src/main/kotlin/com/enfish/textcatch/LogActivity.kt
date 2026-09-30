package com.enfish.textcatch

import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.enfish.textcatch.databinding.ActivityLogBinding

/**
 * 송신 이력 화면 — 시간 + 제목(10글자) 목록을 최신순으로 보여준다.
 */
class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.btnRefresh.setOnClickListener { reload() }
        binding.btnClear.setOnClickListener {
            LogStore.clear(this)
            reload()
        }

        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun reload() {
        val entries = LogStore.list(this)
        val lines = entries.map { LogStore.formatLine(it) }

        binding.listLogs.adapter =
            ArrayAdapter(this, android.R.layout.simple_list_item_1, lines)
        binding.textCount.text = "총 ${entries.size}건"

        val empty = entries.isEmpty()
        binding.textEmpty.visibility = if (empty) android.view.View.VISIBLE else android.view.View.GONE
        binding.listLogs.visibility = if (empty) android.view.View.GONE else android.view.View.VISIBLE
    }
}
