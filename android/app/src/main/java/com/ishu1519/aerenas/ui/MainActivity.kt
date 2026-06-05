package com.ishu1519.aerenas.ui

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.ishu1519.aerenas.R
import com.ishu1519.aerenas.databinding.ActivityMainBinding
import com.ishu1519.aerenas.service.WebDavService
import com.ishu1519.aerenas.utils.Prefs
import com.ishu1519.aerenas.utils.QrUtils
import java.io.File
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private val logEntries = mutableListOf<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            intent.extras?.let { extras ->
                if (extras.containsKey(WebDavService.EXTRA_RUNNING)) {
                    updateRunningState(extras.getBoolean(WebDavService.EXTRA_RUNNING))
                }
                extras.getString(WebDavService.EXTRA_IP)?.let { ip ->
                    updateIpDisplay(ip)
                }
                if (extras.containsKey(WebDavService.EXTRA_SPEED)) {
                    updateSpeed(extras.getLong(WebDavService.EXTRA_SPEED))
                    updateTotalBytes(extras.getLong(WebDavService.EXTRA_TOTAL_BYTES))
                }
                extras.getString(WebDavService.EXTRA_LOG_EVENT)?.let { event ->
                    addLogEntry(event)
                }
            }
        }
    }

    private val manageStoragePermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                ensureAereNASFolder()
                checkBatteryOptimization()
            } else {
                showPermissionDeniedDialog()
            }
        }
    }

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not, continue */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        setupUI()
        requestPermissions()
        syncUIWithServiceState()
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(statusReceiver, IntentFilter(WebDavService.BROADCAST_STATUS),
            RECEIVER_NOT_EXPORTED)
        syncUIWithServiceState()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statusReceiver)
    }

    // ── UI Setup ──────────────────────────────────────────────────────────────

    private fun setupUI() {
        // Power button
        binding.btnPower.setOnClickListener {
            if (WebDavService.isRunning) stopServer() else startServer()
        }

        // QR code
        binding.btnQr.setOnClickListener { showQrDialog() }

        // Copy address
        binding.btnCopyAddress.setOnClickListener {
            val address = binding.tvAddress.text.toString()
            if (address.isNotBlank() && address != getString(R.string.waiting_for_ip)) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("AereNAS Address", address))
                Snackbar.make(binding.root, "Address copied", Snackbar.LENGTH_SHORT).show()
            }
        }

        // Regenerate password
        binding.btnRegenPassword.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Regenerate Password")
                .setMessage("This will create a new password. You'll need to reconnect all clients.")
                .setPositiveButton("Regenerate") { _, _ ->
                    val newPass = prefs.regeneratePassword()
                    binding.tvPassword.text = newPass
                    if (WebDavService.isRunning) {
                        stopServer()
                        startServer()
                    }
                    Snackbar.make(binding.root, "Password regenerated", Snackbar.LENGTH_LONG).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Auto-start toggle
        binding.switchAutoStart.isChecked = prefs.autoStartOnBoot
        binding.switchAutoStart.setOnCheckedChangeListener { _, isChecked ->
            prefs.autoStartOnBoot = isChecked
        }

        // Clear log
        binding.btnClearLog.setOnClickListener {
            logEntries.clear()
            binding.tvLog.text = ""
        }

        // Credentials display
        binding.tvUsername.text = prefs.username
        binding.tvPassword.text = prefs.password
        binding.tvPort.text = prefs.port.toString()
    }

    private fun syncUIWithServiceState() {
        updateRunningState(WebDavService.isRunning)
    }

    private fun updateRunningState(running: Boolean) {
        binding.btnPower.isActivated = running
        binding.statusIndicator.setBackgroundResource(
            if (running) R.drawable.indicator_active else R.drawable.indicator_inactive
        )
        binding.tvStatus.text = if (running) "RUNNING" else "STOPPED"
        binding.tvStatus.setTextColor(
            ContextCompat.getColor(this,
                if (running) R.color.green_active else R.color.text_secondary)
        )
        binding.cardConnectionInfo.visibility = if (running) View.VISIBLE else View.GONE
        binding.btnQr.isEnabled = running
    }

    private fun updateIpDisplay(ip: String) {
        val address = "http://$ip:${prefs.port}"
        binding.tvAddress.text = address
        binding.tvFullPath.text = "$address  •  user: ${prefs.username}"
    }

    private fun updateSpeed(bps: Long) {
        binding.tvSpeed.text = formatSpeed(bps)
    }

    private fun updateTotalBytes(bytes: Long) {
        binding.tvTotalTransfer.text = formatBytes(bytes)
    }

    private fun addLogEntry(event: String) {
        val timestamp = timeFormat.format(Date())
        val entry = "[$timestamp] $event"
        logEntries.add(0, entry)
        if (logEntries.size > 100) logEntries.removeLastOrNull()
        binding.tvLog.text = logEntries.joinToString("\n")
    }

    // ── Server Control ────────────────────────────────────────────────────────

    private fun startServer() {
        val intent = Intent(this, WebDavService::class.java).apply {
            action = WebDavService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopServer() {
        val intent = Intent(this, WebDavService::class.java).apply {
            action = WebDavService.ACTION_STOP
        }
        startService(intent)
    }

    // ── QR Dialog ─────────────────────────────────────────────────────────────

    private fun showQrDialog() {
        val ip = binding.tvAddress.text.toString()
            .removePrefix("http://").substringBefore(":")
        val qrBitmap = QrUtils.generateQr(ip, prefs.port, prefs.username, prefs.password)

        val dialogView = layoutInflater.inflate(R.layout.dialog_qr, null)
        dialogView.findViewById<android.widget.ImageView>(R.id.ivQr).setImageBitmap(qrBitmap)
        dialogView.findViewById<android.widget.TextView>(R.id.tvQrHint).text =
            "Scan with AereNAS Windows client to auto-configure"

        AlertDialog.Builder(this)
            .setTitle("Connect Windows Client")
            .setView(dialogView)
            .setPositiveButton("Close", null)
            .show()
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private fun requestPermissions() {
        // Notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Storage permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                AlertDialog.Builder(this)
                    .setTitle("Storage Permission Required")
                    .setMessage("AereNAS needs full storage access to serve your files over the network.")
                    .setPositiveButton("Grant") { _, _ ->
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName"))
                        manageStoragePermission.launch(intent)
                    }
                    .setCancelable(false)
                    .show()
            } else {
                ensureAereNASFolder()
                checkBatteryOptimization()
            }
        }
    }

    private fun ensureAereNASFolder() {
        val folder = File(prefs.rootPath)
        if (!folder.exists()) {
            folder.mkdirs()
            // Create a README so folder isn't empty
            File(folder, "README.txt").writeText(
                "This folder is served by AereNAS.\n" +
                "Place files here to access them from your Windows PC.\n" +
                "GitHub: https://github.com/Ishu1519/AereNAS\n"
            )
        }
    }

    private fun checkBatteryOptimization() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            AlertDialog.Builder(this)
                .setTitle("Battery Optimization")
                .setMessage("To keep AereNAS running in background on Realme UI, disable battery optimization for this app. Tap 'Fix Now' and select 'No restrictions'.")
                .setPositiveButton("Fix Now") { _, _ ->
                    try {
                        // Direct to Realme/OPPO battery manager
                        val intent = Intent().apply {
                            component = ComponentName(
                                "com.coloros.oppoguardelf",
                                "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"
                            )
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        // Fallback to standard battery optimization settings
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }
                .setNegativeButton("Later", null)
                .show()
        }
    }

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage("AereNAS cannot function without storage access. Please grant the permission.")
            .setPositiveButton("Try Again") { _, _ -> requestPermissions() }
            .setNegativeButton("Exit") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun formatSpeed(bps: Long): String {
        val df = DecimalFormat("#.##")
        return when {
            bps >= 1_000_000 -> "${df.format(bps / 1_000_000.0)} MB/s"
            bps >= 1_000     -> "${df.format(bps / 1_000.0)} KB/s"
            else             -> "$bps B/s"
        }
    }

    private fun formatBytes(bytes: Long): String {
        val df = DecimalFormat("#.##")
        return when {
            bytes >= 1_073_741_824 -> "${df.format(bytes / 1_073_741_824.0)} GB"
            bytes >= 1_048_576     -> "${df.format(bytes / 1_048_576.0)} MB"
            bytes >= 1_024         -> "${df.format(bytes / 1_024.0)} KB"
            else                   -> "$bytes B"
        }
    }
}
