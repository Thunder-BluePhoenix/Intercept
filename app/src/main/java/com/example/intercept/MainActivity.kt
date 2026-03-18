package com.example.intercept

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.intercept.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isRecording = false
    private lateinit var adapter: RecordingAdapter

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            isRecording = intent?.getBooleanExtra("isRecording", false) ?: false
            updateUI()
            adapter.updateData(getRecordings())
        }
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (recordAudioGranted) {
            startMediaProjection()
        } else {
            Toast.makeText(this, "Audio permission is required", Toast.LENGTH_SHORT).show()
        }
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            RecordingService.setLastResult(result.resultCode, result.data)
            val intent = Intent(this, RecordingService::class.java).apply {
                action = RecordingService.ACTION_START
                putExtra(RecordingService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(RecordingService.EXTRA_RESULT_DATA, result.data)
            }
            try {
                ContextCompat.startForegroundService(this, intent)
                isRecording = true
                updateUI()
            } catch (e: Exception) {
                Log.e("MainActivity", "Error starting recording service", e)
                Toast.makeText(this, "Failed to start recording", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            startFloatingService()
        } else {
            Toast.makeText(this, "Overlay permission is required", Toast.LENGTH_SHORT).show()
            binding.swFloating.isChecked = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        
        binding.btnRecord.setOnClickListener {
            if (isRecording) {
                stopRecordingService()
            } else {
                requestPermissions()
            }
        }

        binding.swFloating.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (Settings.canDrawOverlays(this)) {
                    startFloatingService()
                } else {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    overlayPermissionLauncher.launch(intent)
                }
            } else {
                stopService(Intent(this, FloatingControlService::class.java))
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(statusReceiver, IntentFilter("com.example.intercept.RECORDING_STATUS"), Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(statusReceiver, IntentFilter("com.example.intercept.RECORDING_STATUS"))
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error registering receiver", e)
        }

        isRecording = RecordingService.isRecording
        updateUI()

        binding.swFloating.isChecked = FloatingControlService.isRunning
    }

    private fun setupRecyclerView() {
        adapter = RecordingAdapter(getRecordings()) { file ->
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, "audio/x-wav")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "No app found to play audio", Toast.LENGTH_SHORT).show()
            }
        }
        binding.rvRecordings.layoutManager = LinearLayoutManager(this)
        binding.rvRecordings.adapter = adapter
    }

    private fun getRecordings(): List<File> {
        val folder = File(getExternalFilesDir(null), "Intercept")
        return folder.listFiles()?.filter { it.extension == "wav" }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabledServices?.contains(packageName + "/" + CallAccessibilityService::class.java.name) == true
    }

    private fun requestPermissions() {
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "Please enable Intercept in Accessibility Settings to allow call recording", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val notGranted = permissions.filter { 
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED 
        }

        if (notGranted.isEmpty()) {
            startMediaProjection()
        } else {
            requestPermissionsLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun startFloatingService() {
        if (Settings.canDrawOverlays(this)) {
            startService(Intent(this, FloatingControlService::class.java))
        }
    }

    private fun startMediaProjection() {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun stopRecordingService() {
        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }
        startService(intent)
        isRecording = false
        updateUI()
    }

    private fun updateUI() {
        if (isRecording) {
            binding.btnRecord.text = getString(R.string.stop_recording)
            binding.tvStatus.text = "Recording active..."
        } else {
            binding.btnRecord.text = getString(R.string.start_recording)
            binding.tvStatus.text = "Ready to intercept"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(statusReceiver)
        } catch (e: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        adapter.updateData(getRecordings())
        binding.swFloating.isChecked = FloatingControlService.isRunning
    }
}
