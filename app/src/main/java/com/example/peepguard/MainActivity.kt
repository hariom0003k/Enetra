package com.example.peepguard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.peepguard.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences(AppWatcherService.PREFS_NAME, MODE_PRIVATE) }
    private val selectedApps = mutableSetOf<String>()

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.title = getString(R.string.app_name)

        selectedApps.addAll(
            prefs.getStringSet(AppWatcherService.PREF_KEY_APPS, emptySet()) ?: emptySet()
        )

        setupButtons()
        populateAppList()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatuses()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            updatePermissionStatuses()
        }
    }

    private fun setupButtons() {
        binding.btnPermissions.setOnClickListener {
            val perms = mutableListOf(Manifest.permission.CAMERA).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), REQUEST_CODE_PERMISSIONS)
        }

        binding.btnOverlay.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }

        binding.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnStart.setOnClickListener {
            ContextCompat.startForegroundService(this, Intent(this, FaceWatchService::class.java))
            binding.tvStatus.text = getString(R.string.status_running)
        }

        binding.btnStop.setOnClickListener {
            stopService(Intent(this, FaceWatchService::class.java))
            binding.tvStatus.text = getString(R.string.status_stopped)
        }
    }

    private fun updatePermissionStatuses() {
        // Camera + Notifications
        val cameraOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        val notifOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        } else true

        val allGranted = cameraOk && notifOk
        binding.tvPermStatus.text = getString(
            if (allGranted) R.string.permission_granted else R.string.permission_not_granted
        )
        binding.tvPermStatus.setTextColor(statusColor(allGranted))

        // Overlay
        val overlayOk = Settings.canDrawOverlays(this)
        binding.tvOverlayStatus.text = getString(
            if (overlayOk) R.string.permission_granted else R.string.permission_not_granted
        )
        binding.tvOverlayStatus.setTextColor(statusColor(overlayOk))

        // Accessibility service
        val accessibilityOk = isAccessibilityEnabled()
        binding.tvAccessibilityStatus.text = getString(
            if (accessibilityOk) R.string.permission_granted else R.string.permission_not_granted
        )
        binding.tvAccessibilityStatus.setTextColor(statusColor(accessibilityOk))
    }

    private fun statusColor(granted: Boolean): Int = ContextCompat.getColor(
        this,
        if (granted) android.R.color.holo_green_dark else android.R.color.holo_red_dark
    )

    private fun isAccessibilityEnabled(): Boolean {
        val expectedComponent = "$packageName/${AppWatcherService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(":").any { it.equals(expectedComponent, ignoreCase = true) }
    }

    private fun populateAppList() {
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val installedApps = packageManager.queryIntentActivities(launchIntent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .filter { (pkg, _) -> pkg != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }

        val container = binding.appListContainer
        container.removeAllViews()

        for ((pkg, label) in installedApps) {
            val cb = CheckBox(this).apply {
                text = label
                isChecked = pkg in selectedApps
                setPadding(0, 8, 0, 8)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedApps.add(pkg) else selectedApps.remove(pkg)
                    prefs.edit()
                        .putStringSet(AppWatcherService.PREF_KEY_APPS, HashSet(selectedApps))
                        .apply()
                }
            }
            container.addView(
                cb,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }
}
