package com.lolokeksu.gamepulse

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.WindowManager
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

class GamePulseSessionService : Service() {
    private var workerThread: Thread? = null

    @Volatile
    private var running = false

    private var rootMetricsEnabled = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        rootMetricsEnabled = intent?.getBooleanExtra(EXTRA_ROOT_METRICS_ENABLED, false) ?: false

        startForegroundCompat()
        startSamplerWorker()

        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        workerThread?.interrupt()
        workerThread = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("GamePulse Analyzer")
            .setContentText("Collecting game session samples")
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel = NotificationChannel(
            CHANNEL_ID,
            "GamePulse Session",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "GamePulse foreground session sampler"
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun startSamplerWorker() {
        if (workerThread?.isAlive == true) {
            return
        }

        running = true

        workerThread = Thread {
            while (running) {
                try {
                    collectSample()

                    Thread.sleep(SAMPLE_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    running = false
                } catch (_: Exception) {
                    Thread.sleep(SAMPLE_INTERVAL_MS)
                }
            }
        }.apply {
            name = "GamePulseSessionSampler"
            start()
        }
    }

    private fun collectSample() {
        val prefs = getSharedPreferences("gamepulse_sessions", Context.MODE_PRIVATE)

        if (!prefs.getBoolean("active", false)) {
            running = false
            stopSelf()
            return
        }

        val startElapsedMs = prefs.getLong("start_elapsed_ms", 0L)
        if (startElapsedMs <= 0L) {
            return
        }

        val elapsedMs = SystemClock.elapsedRealtime() - startElapsedMs

        if (elapsedMs > MAX_SESSION_MS) {
            running = false
            stopSelf()
            return
        }

        val raw = prefs.getString("active_samples_json", "[]") ?: "[]"
        val array = try {
            JSONArray(raw)
        } catch (_: Exception) {
            JSONArray()
        }

        val cpuSnapshot = if (rootMetricsEnabled && array.length() % CPU_SAMPLE_EVERY_N_SAMPLES == 0) {
            readCpuFreqSnapshotWithRoot()
        } else {
            null
        }

        val sample = JSONObject()
            .put("elapsedMs", elapsedMs)
            .put("batteryTempC", readBatteryTemperatureC() ?: JSONObject.NULL)
            .put("batteryPercent", readBatteryPercent() ?: JSONObject.NULL)
            .put("refreshRateHz", readRefreshRateHz() ?: JSONObject.NULL)
            .put("rootMetricsEnabled", rootMetricsEnabled)
            .put("cpuSnapshot", cpuSnapshot ?: JSONObject.NULL)

        array.put(sample)

        val trimmed = if (array.length() <= MAX_SAMPLES) {
            array
        } else {
            val result = JSONArray()
            for (index in array.length() - MAX_SAMPLES until array.length()) {
                result.put(array.get(index))
            }
            result
        }

        prefs.edit()
            .putString("active_samples_json", trimmed.toString())
            .putLong("active_last_sample_elapsed_ms", elapsedMs)
            .putInt("active_sample_count", trimmed.length())
            .apply()
    }

    private fun readBatteryTemperatureC(): Float? {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null

        val rawTemp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (rawTemp == Int.MIN_VALUE) {
            return null
        }

        return rawTemp / 10f
    }

    private fun readBatteryPercent(): Int? {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)

        if (level < 0 || scale <= 0) {
            return null
        }

        return ((level * 100f) / scale).roundToInt().coerceIn(0, 100)
    }

    private fun readRefreshRateHz(): Float? {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return null

        return windowManager.defaultDisplay?.refreshRate
    }

    private fun readCpuFreqSnapshotWithRoot(): String? {
        val command = """
            for p in /sys/devices/system/cpu/cpufreq/policy*; do
              [ -d "${'$'}p" ] || continue
              name=${'$'}(basename "${'$'}p")
              cur=${'$'}(cat "${'$'}p/scaling_cur_freq" 2>/dev/null || echo -)
              min=${'$'}(cat "${'$'}p/scaling_min_freq" 2>/dev/null || echo -)
              max=${'$'}(cat "${'$'}p/scaling_max_freq" 2>/dev/null || echo -)
              echo "${'$'}name cur=${'$'}cur min=${'$'}min max=${'$'}max"
            done
        """.trimIndent()

        return runRootCommand(command, 2_000L)
            ?.lines()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.joinToString("\n")
            ?.takeIf { it.isNotBlank() }
    }

    private fun runRootCommand(
        command: String,
        timeoutMs: Long
    ): String? {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)

            if (!finished) {
                process.destroyForcibly()
                return null
            }

            process.inputStream.bufferedReader().use { it.readText() }.trim()
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val EXTRA_ROOT_METRICS_ENABLED = "gamepulse.extra.ROOT_METRICS_ENABLED"

        private const val CHANNEL_ID = "gamepulse_session_sampler"
        private const val NOTIFICATION_ID = 42
        private const val SAMPLE_INTERVAL_MS = 2_000L
        private const val MAX_SESSION_MS = 6L * 60L * 60L * 1_000L
        private const val MAX_SAMPLES = 1800
        private const val CPU_SAMPLE_EVERY_N_SAMPLES = 5
    }
}
