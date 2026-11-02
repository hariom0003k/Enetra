package com.example.peepguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class FaceWatchService : LifecycleService() {

    companion object {
        private const val TAG = "FaceWatchService"
        private const val ACTION_FRIEND = "com.example.peepguard.ACTION_FRIEND"
        private const val ACTION_IGNORE = "com.example.peepguard.ACTION_IGNORE"
        private const val CHANNEL_MAIN = "peepguard"
        private const val CHANNEL_ALERT = "peepguard_alert"
        private const val NOTIF_MAIN = 1
        private const val NOTIF_ALERT = 2
        private const val WATCHDOG_INTERVAL_MS = 2_000L
        private const val NO_FRAME_TIMEOUT_MS = 4_000L
        private const val ANALYSIS_INTERVAL_MS = 400L   // ~2-3 FPS to save battery
        private const val MULTI_FACE_THRESHOLD = 2       // consecutive frames before alert

        private val TIMER_OPTIONS = listOf(
            "30 seconds" to 30_000L,
            "1 minute"   to 60_000L,
            "2 minutes"  to 120_000L,
            "5 minutes"  to 300_000L,
            "10 minutes" to 600_000L
        )
    }

    private enum class OverlayMode { ALERT, TIMER }

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var cameraProvider: ProcessCameraProvider? = null

    private var lastAnalysisTime = 0L
    @Volatile private var lastFrameTime = 0L
    private var multiFaceCount = 0

    private var isCameraWanted = false
    private var isAlertPending = false
    private var suppressUntil = 0L

    private var overlayRoot: LinearLayout? = null

    private val faceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.15f)
                .build()
        )
    }

    /** Restarts camera if no frames received within timeout (handles camera stalls). */
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (isCameraWanted &&
                System.currentTimeMillis() - lastFrameTime > NO_FRAME_TIMEOUT_MS
            ) {
                Log.w(TAG, "Watchdog: no frames received, restarting camera")
                startCamera()
            }
            mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    // ─────────────────────────────────────────── Lifecycle

    override fun onCreate() {
        super.onCreate()
        setupNotificationChannels()
        startForegroundWithNotification()

        GuardState.reset()

        // Collect active state using lifecycleScope (automatically cancelled on destroy)
        lifecycleScope.launch {
            GuardState.isActive.collectLatest { active ->
                if (active) startCamera() else stopCamera()
            }
        }

        mainHandler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_FRIEND -> if (isAlertPending) renderOverlay(OverlayMode.TIMER, "Friend")
            ACTION_IGNORE -> if (isAlertPending) renderOverlay(OverlayMode.TIMER, "Ignore")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        GuardState.reset()
        mainHandler.removeCallbacksAndMessages(null)
        stopCamera()
        faceDetector.close()
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    // ─────────────────────────────────────────── Camera

    private fun startCamera() {
        isCameraWanted = true
        lastFrameTime = System.currentTimeMillis()

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!isCameraWanted) return@addListener
            try {
                val provider = future.get()
                cameraProvider = provider

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, ::analyzeFrame) }

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    analysis
                )
                Log.d(TAG, "Camera started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start camera: ${e.message}", e)
                // Watchdog will retry automatically
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        isCameraWanted = false
        cameraProvider?.unbindAll()
        dismissAlert()
        Log.d(TAG, "Camera stopped")
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun analyzeFrame(proxy: ImageProxy) {
        val now = System.currentTimeMillis()
        lastFrameTime = now
        val mediaImage = proxy.image

        // Throttle analysis to save CPU / battery
        if (now - lastAnalysisTime < ANALYSIS_INTERVAL_MS || mediaImage == null) {
            proxy.close()
            return
        }
        lastAnalysisTime = now

        val input = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
        faceDetector.process(input)
            .addOnSuccessListener { faces -> onFacesDetected(faces.size) }
            .addOnFailureListener { e -> Log.w(TAG, "Face detection failed: ${e.message}") }
            .addOnCompleteListener { proxy.close() }
    }

    // ─────────────────────────────────────────── Face logic

    private fun onFacesDetected(count: Int) {
        multiFaceCount = if (count >= 2) multiFaceCount + 1 else 0
        if (multiFaceCount >= MULTI_FACE_THRESHOLD &&
            isCameraWanted &&
            !isAlertPending &&
            System.currentTimeMillis() >= suppressUntil
        ) {
            mainHandler.post { triggerAlert() }
        }
    }

    // ─────────────────────────────────────────── Alert

    private fun triggerAlert() {
        isAlertPending = true
        showOverlay()
        renderOverlay(OverlayMode.ALERT)
        postAlertNotification()
    }

    private fun applyChoice(label: String, durationMs: Long) {
        suppressUntil = System.currentTimeMillis() + durationMs
        dismissAlert()
        Toast.makeText(this, getString(R.string.toast_suppressed, label), Toast.LENGTH_SHORT).show()
    }

    private fun dismissAlert() {
        isAlertPending = false
        multiFaceCount = 0
        removeOverlay()
        getSystemService(NotificationManager::class.java).cancel(NOTIF_ALERT)
    }

    private fun makeActionIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode,
            Intent(this, FaceWatchService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun postAlertNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(getString(R.string.notif_alert_title))
            .setContentText(getString(R.string.notif_alert_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .addAction(0, getString(R.string.btn_friend), makeActionIntent(ACTION_FRIEND, 10))
            .addAction(0, getString(R.string.btn_ignore), makeActionIntent(ACTION_IGNORE, 11))
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ALERT, notification)
    }

    // ─────────────────────────────────────────── Overlay

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun showOverlay() {
        if (overlayRoot != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val isBlurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                wm.isCrossWindowBlurEnabled

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
            setBackgroundColor(
                if (isBlurSupported) Color.argb(120, 0, 0, 0)
                else Color.argb(245, 15, 15, 15)
            )
        }

        // Use FLAG_NOT_TOUCH_MODAL so touches outside the overlay pass through,
        // but remove FLAG_NOT_FOCUSABLE so buttons remain clickable.
        val flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (isBlurSupported) {
                this.flags = this.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                blurBehindRadius = 80
            }
        }

        try {
            wm.addView(root, lp)
            overlayRoot = root
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show overlay: ${e.message}", e)
        }
    }

    private fun removeOverlay() {
        overlayRoot?.let { view ->
            try {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "removeOverlay: ${e.message}")
            }
        }
        overlayRoot = null
    }

    private fun addLabel(root: LinearLayout, text: String, sizeSp: Float, bold: Boolean = false) {
        root.addView(TextView(this).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(12))
        })
    }

    private fun addOverlayButton(root: LinearLayout, label: String, onClick: () -> Unit) {
        root.addView(
            Button(this).apply {
                text = label
                setOnClickListener { onClick() }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )
    }

    private fun renderOverlay(mode: OverlayMode, who: String = "") {
        val root = overlayRoot ?: return
        root.removeAllViews()
        when (mode) {
            OverlayMode.ALERT -> {
                addLabel(root, getString(R.string.overlay_alert_title), 24f, bold = true)
                addLabel(root, getString(R.string.overlay_alert_body), 16f)
                addOverlayButton(root, getString(R.string.btn_friend)) {
                    renderOverlay(OverlayMode.TIMER, "Friend")
                }
                addOverlayButton(root, getString(R.string.btn_ignore)) {
                    renderOverlay(OverlayMode.TIMER, "Ignore")
                }
            }
            OverlayMode.TIMER -> {
                addLabel(root, "$who ${getString(R.string.overlay_timer_suffix)}", 22f, bold = true)
                for ((label, ms) in TIMER_OPTIONS) {
                    addOverlayButton(root, label) { applyChoice(label, ms) }
                }
                addOverlayButton(root, getString(R.string.btn_back)) {
                    renderOverlay(OverlayMode.ALERT)
                }
            }
        }
    }

    // ─────────────────────────────────────────── Notifications

    private fun setupNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MAIN,
                getString(R.string.notif_channel_main),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERT,
                getString(R.string.notif_channel_alert),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }

    private fun startForegroundWithNotification() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_MAIN)
            .setContentTitle(getString(R.string.notif_main_title))
            .setContentText(getString(R.string.notif_main_text))
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .build()

        ServiceCompat.startForeground(
            this, NOTIF_MAIN, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        )
    }
}
