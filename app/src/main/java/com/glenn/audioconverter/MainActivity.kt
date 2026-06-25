package com.glenn.audioconverter

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.glenn.audioconverter.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var logAdapter: LogAdapter
    private val selectedFiles = mutableListOf<Pair<android.net.Uri, String>>()
    private var sampleRateChoice = "原始"

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            logAdapter.clear()
            selectedFiles.clear()
            uris.forEach { uri ->
                val name = getFileName(uri) ?: uri.lastPathSegment ?: "unknown"
                selectedFiles.add(Pair(uri, name))
            }
            Toast.makeText(this, "已选择 ${selectedFiles.size} 个文件", Toast.LENGTH_SHORT).show()
            binding.btnStart.text = "开始转换 (${selectedFiles.size})"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSpinner()
        setupRecyclerView()
        requestPermissions()
        setupClickListeners()
    }

    private fun setupSpinner() {
        val rates = listOf("原始", "44100", "48000", "32000", "22050", "16000")
        binding.spinnerSampleRate.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, rates)
        binding.spinnerSampleRate.setSelection(0)
        binding.spinnerSampleRate.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                sampleRateChoice = rates[pos]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupRecyclerView() {
        logAdapter = LogAdapter(onLongClick = { pos ->
            val item = logAdapter.getAllLogs().lines().getOrElse(pos) { "" }
            if (item.isNotBlank()) {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("log", item))
                Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
            }
        })
        binding.recyclerLogs.layoutManager = LinearLayoutManager(this)
        binding.recyclerLogs.adapter = logAdapter
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            for (perm in listOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)) {
                if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(perm), 0)
                }
            }
        } else if (Build.VERSION.SDK_INT <= 32) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 0)
            }
        }
    }

    private fun setupClickListeners() {
        binding.btnSelectFiles.setOnClickListener {
            if (selectedFiles.isNotEmpty()) {
                logAdapter.clear()
                selectedFiles.clear()
            }
            filePicker.launch(arrayOf("video/mp4", "audio/mpeg"))
        }

        binding.btnStart.setOnClickListener {
            if (selectedFiles.isEmpty()) {
                Toast.makeText(this, "请先选择文件", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startConversion()
        }

        binding.btnClearLogs.setOnClickListener {
            logAdapter.clear()
        }

        binding.btnCopyLogs.setOnClickListener {
            val text = logAdapter.getAllLogs()
            if (text.isBlank()) {
                Toast.makeText(this, "无日志可复制", Toast.LENGTH_SHORT).show()
            } else {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("logs", text))
                Toast.makeText(this, "已复制 ${logAdapter.itemCount} 条日志", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startConversion() {
        binding.btnStart.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = true
        binding.tvProgress.visibility = View.VISIBLE

        val files = selectedFiles.toList()
        val total = files.size

        lifecycleScope.launch {
            BatchConverter.convert(this@MainActivity, files, sampleRateChoice) { done, _, result ->
                logAdapter.addItem(result)
                binding.tvProgress.text = "进度: $done / $total"
            }

            // Done
            binding.progressBar.isIndeterminate = false
            binding.progressBar.progress = 100
            binding.tvProgress.text = "完成: ${logAdapter.itemCount} 个文件"
            binding.btnStart.isEnabled = true
            binding.btnStart.text = "开始转换"

            val success = logAdapter.getAllLogs().lines().count { it.startsWith("✅") }
            val failed = logAdapter.getAllLogs().lines().count { it.startsWith("❌") }
            Toast.makeText(this@MainActivity,
                "成功 $success / 失败 $failed", Toast.LENGTH_LONG).show()
        }
    }

    private fun getFileName(uri: android.net.Uri): String? {
        var name: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = cursor.getString(idx)
            }
        }
        return name
    }
}
