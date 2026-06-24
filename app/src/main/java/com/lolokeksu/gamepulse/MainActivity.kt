package com.lolokeksu.gamepulse

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lolokeksu.gamepulse.ui.theme.NxiTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

data class GameApp(
    val label: String,
    val packageName: String,
    val isGameCategory: Boolean,
    val isManual: Boolean
)

data class GameSessionReport(
    val gameLabel: String,
    val packageName: String,
    val durationMs: Long,
    val startBatteryTempC: Float?,
    val endBatteryTempC: Float?,
    val startBatteryPercent: Int?,
    val endBatteryPercent: Int?,
    val refreshRateHz: Float?,
    val startRootAvailable: Boolean?,
    val endRootAvailable: Boolean?,
    val startCpuSnapshot: String?,
    val endCpuSnapshot: String?,
    val completedAtMs: Long
) {
    val batteryDrainPercent: Int?
        get() {
            val start = startBatteryPercent ?: return null
            val end = endBatteryPercent ?: return null
            return (start - end).coerceAtLeast(0)
        }
}

class MainActivity : ComponentActivity() {
    private val refreshTick = mutableStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            NxiTheme {
                GamePulseApp(refreshTick = refreshTick)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTick.value = SystemClock.elapsedRealtime()
    }
}

@Composable
private fun GamePulseApp(refreshTick: MutableState<Long>) {
    val context = LocalContext.current
    val packageManager = context.packageManager

    var games by remember { mutableStateOf<List<GameApp>>(emptyList()) }
    var selectedGame by remember { mutableStateOf<GameApp?>(null) }
    var lastReport by remember { mutableStateOf<GameSessionReport?>(null) }
    var reportHistory by remember { mutableStateOf<List<GameSessionReport>>(emptyList()) }
    var manualPackageInput by remember { mutableStateOf("") }
    var manualStatus by remember { mutableStateOf<String?>(null) }
    var exportStatus by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        games = loadLaunchableGames(context, packageManager)
        selectedGame = games.firstOrNull()
        lastReport = loadLastReport(context)
        reportHistory = loadReportHistory(context)
    }

    LaunchedEffect(refreshTick.value) {
        finishActiveSessionIfNeeded(context)?.let { report ->
            lastReport = report
            reportHistory = loadReportHistory(context)
            exportStatus = null
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            NxiHeader()

            NxiSessionCard(
                selectedGame = selectedGame,
                lastReport = lastReport
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                NxiMetricCard(
                    modifier = Modifier.weight(1f),
                    title = "GAMES",
                    value = games.size.toString(),
                    caption = "detected"
                )

                NxiMetricCard(
                    modifier = Modifier.weight(1f),
                    title = "COST",
                    value = lastReport?.let { formatBatteryCost(it) } ?: "--",
                    caption = "battery/min"
                )
            }

            NxiManualGameCard(
                packageInput = manualPackageInput,
                status = manualStatus,
                onPackageInputChanged = {
                    manualPackageInput = it
                    manualStatus = null
                },
                onAddClicked = {
                    val addedGame = addManualGame(
                        context = context,
                        packageManager = packageManager,
                        rawPackageName = manualPackageInput
                    )

                    if (addedGame == null) {
                        manualStatus = "Package not found or app has no launcher activity."
                    } else {
                        games = loadLaunchableGames(context, packageManager)
                        selectedGame = games.firstOrNull { it.packageName == addedGame.packageName }
                        manualPackageInput = ""
                        manualStatus = "Added: ${addedGame.label}"
                    }
                }
            )

            NxiGamesCard(
                games = games,
                selectedGame = selectedGame,
                onGameSelected = { selectedGame = it }
            )

            lastReport?.let { report ->
                NxiReportCard(
                    report = report,
                    exportStatus = exportStatus,
                    onExportTxt = {
                        exportStatus = exportReport(
                            context = context,
                            report = report,
                            format = ExportFormat.TXT
                        )
                    },
                    onExportJson = {
                        exportStatus = exportReport(
                            context = context,
                            report = report,
                            format = ExportFormat.JSON
                        )
                    }
                )
            }

            NxiHistoryCard(history = reportHistory)

            NxiPrimaryButton(
                text = "Start Analyze",
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedGame != null,
                onClick = {
                    selectedGame?.let { game ->
                        startGameSession(context, game)
                    }
                }
            )

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

private enum class ExportFormat {
    TXT,
    JSON
}

private fun addManualGame(
    context: Context,
    packageManager: PackageManager,
    rawPackageName: String
): GameApp? {
    val packageName = rawPackageName.trim()

    if (packageName.isBlank()) {
        return null
    }

    val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        ?: return null

    val applicationInfo = try {
        packageManager.getApplicationInfo(packageName, 0)
    } catch (_: PackageManager.NameNotFoundException) {
        return null
    }

    val label = applicationInfo.loadLabel(packageManager)?.toString()?.trim().orEmpty()
        .ifBlank { packageName }

    val prefs = context.getSharedPreferences("gamepulse_manual_games", Context.MODE_PRIVATE)
    val current = prefs.getStringSet("manual_packages", emptySet()).orEmpty().toMutableSet()
    current.add(packageName)

    prefs.edit()
        .putStringSet("manual_packages", current)
        .apply()

    return GameApp(
        label = label,
        packageName = packageName,
        isGameCategory = applicationInfo.category == ApplicationInfo.CATEGORY_GAME,
        isManual = true
    )
}

private fun loadManualGames(
    context: Context,
    packageManager: PackageManager
): List<GameApp> {
    val prefs = context.getSharedPreferences("gamepulse_manual_games", Context.MODE_PRIVATE)
    val packages = prefs.getStringSet("manual_packages", emptySet()).orEmpty()

    return packages.mapNotNull { packageName ->
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent == null) {
            return@mapNotNull null
        }

        val applicationInfo = try {
            packageManager.getApplicationInfo(packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return@mapNotNull null
        }

        val label = applicationInfo.loadLabel(packageManager)?.toString()?.trim().orEmpty()
            .ifBlank { packageName }

        GameApp(
            label = label,
            packageName = packageName,
            isGameCategory = applicationInfo.category == ApplicationInfo.CATEGORY_GAME,
            isManual = true
        )
    }
}

private fun startGameSession(context: Context, game: GameApp) {
    val packageManager = context.packageManager
    val launchIntent = packageManager.getLaunchIntentForPackage(game.packageName) ?: return

    val rootAvailable = isRootAvailable()
    val cpuSnapshot = if (rootAvailable) {
        readCpuFreqSnapshotWithRoot()
    } else {
        null
    }

    val prefs = context.getSharedPreferences("gamepulse_sessions", Context.MODE_PRIVATE)

    prefs.edit()
        .putBoolean("active", true)
        .putString("game_label", game.label)
        .putString("package_name", game.packageName)
        .putLong("start_elapsed_ms", SystemClock.elapsedRealtime())
        .putFloat("start_battery_temp_c", readBatteryTemperatureC(context) ?: -1f)
        .putInt("start_battery_percent", readBatteryPercent(context) ?: -1)
        .putFloat("refresh_rate_hz", readRefreshRateHz(context) ?: -1f)
        .putBoolean("start_root_available", rootAvailable)
        .putString("start_cpu_snapshot", cpuSnapshot)
        .apply()

    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(launchIntent)
}

private fun finishActiveSessionIfNeeded(context: Context): GameSessionReport? {
    val prefs = context.getSharedPreferences("gamepulse_sessions", Context.MODE_PRIVATE)

    if (!prefs.getBoolean("active", false)) {
        return null
    }

    val startElapsedMs = prefs.getLong("start_elapsed_ms", 0L)
    if (startElapsedMs <= 0L) {
        prefs.edit().clear().apply()
        return null
    }

    val nowElapsedMs = SystemClock.elapsedRealtime()
    val durationMs = nowElapsedMs - startElapsedMs

    if (durationMs < 3_000L) {
        return null
    }

    val endRootAvailable = isRootAvailable()
    val endCpuSnapshot = if (endRootAvailable) {
        readCpuFreqSnapshotWithRoot()
    } else {
        null
    }

    val report = GameSessionReport(
        gameLabel = prefs.getString("game_label", "Unknown game") ?: "Unknown game",
        packageName = prefs.getString("package_name", "unknown.package") ?: "unknown.package",
        durationMs = durationMs,
        startBatteryTempC = prefs.getFloat("start_battery_temp_c", -1f).takeIf { it >= 0f },
        endBatteryTempC = readBatteryTemperatureC(context),
        startBatteryPercent = prefs.getInt("start_battery_percent", -1).takeIf { it >= 0 },
        endBatteryPercent = readBatteryPercent(context),
        refreshRateHz = prefs.getFloat("refresh_rate_hz", -1f).takeIf { it >= 0f },
        startRootAvailable = prefs.getBoolean("start_root_available", false),
        endRootAvailable = endRootAvailable,
        startCpuSnapshot = prefs.getString("start_cpu_snapshot", null),
        endCpuSnapshot = endCpuSnapshot,
        completedAtMs = System.currentTimeMillis()
    )

    prefs.edit()
        .putBoolean("active", false)
        .putString("last_game_label", report.gameLabel)
        .putString("last_package_name", report.packageName)
        .putLong("last_duration_ms", report.durationMs)
        .putFloat("last_start_battery_temp_c", report.startBatteryTempC ?: -1f)
        .putFloat("last_end_battery_temp_c", report.endBatteryTempC ?: -1f)
        .putInt("last_start_battery_percent", report.startBatteryPercent ?: -1)
        .putInt("last_end_battery_percent", report.endBatteryPercent ?: -1)
        .putFloat("last_refresh_rate_hz", report.refreshRateHz ?: -1f)
        .putBoolean("last_start_root_available", report.startRootAvailable ?: false)
        .putBoolean("last_end_root_available", report.endRootAvailable ?: false)
        .putString("last_start_cpu_snapshot", report.startCpuSnapshot)
        .putString("last_end_cpu_snapshot", report.endCpuSnapshot)
        .putLong("last_completed_at_ms", report.completedAtMs)
        .apply()

    appendReportHistory(context, report)

    return report
}

private fun loadLastReport(context: Context): GameSessionReport? {
    val prefs = context.getSharedPreferences("gamepulse_sessions", Context.MODE_PRIVATE)
    val durationMs = prefs.getLong("last_duration_ms", 0L)

    if (durationMs <= 0L) {
        return null
    }

    return GameSessionReport(
        gameLabel = prefs.getString("last_game_label", "Unknown game") ?: "Unknown game",
        packageName = prefs.getString("last_package_name", "unknown.package") ?: "unknown.package",
        durationMs = durationMs,
        startBatteryTempC = prefs.getFloat("last_start_battery_temp_c", -1f).takeIf { it >= 0f },
        endBatteryTempC = prefs.getFloat("last_end_battery_temp_c", -1f).takeIf { it >= 0f },
        startBatteryPercent = prefs.getInt("last_start_battery_percent", -1).takeIf { it >= 0 },
        endBatteryPercent = prefs.getInt("last_end_battery_percent", -1).takeIf { it >= 0 },
        refreshRateHz = prefs.getFloat("last_refresh_rate_hz", -1f).takeIf { it >= 0f },
        startRootAvailable = prefs.getBoolean("last_start_root_available", false),
        endRootAvailable = prefs.getBoolean("last_end_root_available", false),
        startCpuSnapshot = prefs.getString("last_start_cpu_snapshot", null),
        endCpuSnapshot = prefs.getString("last_end_cpu_snapshot", null),
        completedAtMs = prefs.getLong("last_completed_at_ms", 0L)
    )
}

private fun appendReportHistory(
    context: Context,
    report: GameSessionReport
) {
    val prefs = context.getSharedPreferences("gamepulse_sessions", Context.MODE_PRIVATE)
    val currentHistory = loadReportHistory(context).toMutableList()

    currentHistory.removeAll {
        it.completedAtMs == report.completedAtMs && it.packageName == report.packageName
    }

    currentHistory.add(0, report)

    val array = JSONArray()

    currentHistory
        .take(10)
        .forEach { item ->
            val objectValue = JSONObject()
                .put("gameLabel", item.gameLabel)
                .put("packageName", item.packageName)
                .put("durationMs", item.durationMs)
                .put("startBatteryTempC", item.startBatteryTempC ?: JSONObject.NULL)
                .put("endBatteryTempC", item.endBatteryTempC ?: JSONObject.NULL)
                .put("startBatteryPercent", item.startBatteryPercent ?: JSONObject.NULL)
                .put("endBatteryPercent", item.endBatteryPercent ?: JSONObject.NULL)
                .put("refreshRateHz", item.refreshRateHz ?: JSONObject.NULL)
                .put("startRootAvailable", item.startRootAvailable ?: JSONObject.NULL)
                .put("endRootAvailable", item.endRootAvailable ?: JSONObject.NULL)
                .put("startCpuSnapshot", item.startCpuSnapshot ?: JSONObject.NULL)
                .put("endCpuSnapshot", item.endCpuSnapshot ?: JSONObject.NULL)
                .put("completedAtMs", item.completedAtMs)

            array.put(objectValue)
        }

    prefs.edit()
        .putString("history_json", array.toString())
        .apply()
}

private fun loadReportHistory(context: Context): List<GameSessionReport> {
    val prefs = context.getSharedPreferences("gamepulse_sessions", Context.MODE_PRIVATE)
    val raw = prefs.getString("history_json", null) ?: return emptyList()

    return try {
        val array = JSONArray(raw)

        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue

                val durationMs = item.optLong("durationMs", 0L)
                val completedAtMs = item.optLong("completedAtMs", 0L)

                if (durationMs <= 0L || completedAtMs <= 0L) {
                    continue
                }

                add(
                    GameSessionReport(
                        gameLabel = item.optString("gameLabel", "Unknown game"),
                        packageName = item.optString("packageName", "unknown.package"),
                        durationMs = durationMs,
                        startBatteryTempC = item.optNullableFloat("startBatteryTempC"),
                        endBatteryTempC = item.optNullableFloat("endBatteryTempC"),
                        startBatteryPercent = item.optNullableInt("startBatteryPercent"),
                        endBatteryPercent = item.optNullableInt("endBatteryPercent"),
                        refreshRateHz = item.optNullableFloat("refreshRateHz"),
                        startRootAvailable = item.optNullableBoolean("startRootAvailable"),
                        endRootAvailable = item.optNullableBoolean("endRootAvailable"),
                        startCpuSnapshot = item.optNullableString("startCpuSnapshot"),
                        endCpuSnapshot = item.optNullableString("endCpuSnapshot"),
                        completedAtMs = completedAtMs
                    )
                )
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun JSONObject.optNullableFloat(name: String): Float? {
    if (!has(name) || isNull(name)) {
        return null
    }

    val value = optDouble(name, Double.NaN)

    return if (value.isNaN()) {
        null
    } else {
        value.toFloat()
    }
}

private fun JSONObject.optNullableInt(name: String): Int? {
    if (!has(name) || isNull(name)) {
        return null
    }

    val value = optInt(name, -1)

    return if (value < 0) {
        null
    } else {
        value
    }
}

private fun JSONObject.optNullableBoolean(name: String): Boolean? {
    if (!has(name) || isNull(name)) {
        return null
    }

    return optBoolean(name)
}

private fun JSONObject.optNullableString(name: String): String? {
    if (!has(name) || isNull(name)) {
        return null
    }

    return optString(name).takeIf { it.isNotBlank() }
}

private fun readBatteryTemperatureC(context: Context): Float? {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?: return null

    val rawTemp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
    if (rawTemp == Int.MIN_VALUE) {
        return null
    }

    return rawTemp / 10f
}

private fun readBatteryPercent(context: Context): Int? {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?: return null

    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)

    if (level < 0 || scale <= 0) {
        return null
    }

    return ((level * 100f) / scale).roundToInt().coerceIn(0, 100)
}

private fun readRefreshRateHz(context: Context): Float? {
    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        ?: return null

    return windowManager.defaultDisplay?.refreshRate
}

private fun loadLaunchableGames(
    context: Context,
    packageManager: PackageManager
): List<GameApp> {
    val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
    }

    val resolvedApps = packageManager.queryIntentActivities(launcherIntent, 0)

    val detectedGames = resolvedApps
        .mapNotNull { resolveInfo ->
            val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
            val appInfo = activityInfo.applicationInfo ?: return@mapNotNull null
            val packageName = activityInfo.packageName
            val label = resolveInfo.loadLabel(packageManager)?.toString()?.trim().orEmpty()

            if (packageName == "com.lolokeksu.gamepulse" || packageName == "com.lolokeksu.gamepulse.debug") {
                return@mapNotNull null
            }

            GameApp(
                label = label.ifBlank { packageName },
                packageName = packageName,
                isGameCategory = appInfo.category == ApplicationInfo.CATEGORY_GAME,
                isManual = false
            )
        }
        .filter { it.isGameCategory }

    val manualGames = loadManualGames(context, packageManager)

    return (detectedGames + manualGames)
        .distinctBy { it.packageName }
        .sortedWith(
            compareByDescending<GameApp> { it.isGameCategory }
                .thenByDescending { it.isManual }
                .thenBy { it.label.lowercase() }
        )
}

private fun isRootAvailable(): Boolean {
    val output = runRootCommand("echo gamepulse_root_ok", 1_500L)
    return output?.contains("gamepulse_root_ok") == true
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

private fun batteryCostPercentPerMinute(report: GameSessionReport): Float? {
    val drain = report.batteryDrainPercent ?: return null
    val minutes = report.durationMs / 60_000f

    if (minutes <= 0f) {
        return null
    }

    return drain / minutes
}

private fun estimatedFullBatteryMinutes(report: GameSessionReport): Int? {
    val rate = batteryCostPercentPerMinute(report) ?: return null

    if (rate <= 0f) {
        return null
    }

    return (100f / rate).roundToInt().coerceAtLeast(1)
}

private fun thermalVerdict(report: GameSessionReport): String {
    val start = report.startBatteryTempC
    val end = report.endBatteryTempC

    if (end == null) {
        return "UNKNOWN"
    }

    val delta = if (start != null) end - start else 0f

    return when {
        end >= 45f || delta >= 6f -> "HOT"
        end >= 42f || delta >= 4f -> "HIGH"
        end >= 39f || delta >= 2f -> "MODERATE"
        else -> "NORMAL"
    }
}

private fun refreshVerdict(report: GameSessionReport): String {
    val hz = report.refreshRateHz ?: return "UNKNOWN"

    return when {
        hz >= 120f -> "HIGH"
        hz >= 90f -> "GOOD"
        hz >= 60f -> "STANDARD"
        else -> "LOW"
    }
}

private fun batteryCostVerdict(report: GameSessionReport): String {
    val rate = batteryCostPercentPerMinute(report) ?: return "UNKNOWN"

    return when {
        rate >= 2f -> "HIGH"
        rate >= 1f -> "MODERATE"
        rate > 0f -> "NORMAL"
        else -> "LOW"
    }
}

private fun buildSessionVerdict(report: GameSessionReport): String {
    val issues = mutableListOf<String>()

    when (thermalVerdict(report)) {
        "HOT" -> issues.add("very high temperature")
        "HIGH" -> issues.add("thermal pressure")
        "MODERATE" -> issues.add("moderate heating")
    }

    when (batteryCostVerdict(report)) {
        "HIGH" -> issues.add("high battery cost")
        "MODERATE" -> issues.add("moderate battery cost")
    }

    if (refreshVerdict(report) == "LOW") {
        issues.add("low refresh rate")
    }

    if (report.endRootAvailable == false) {
        issues.add("root metrics unavailable")
    }

    return if (issues.isEmpty()) {
        "Stable basic session. No strong thermal, battery, or refresh issue detected in this MVP report."
    } else {
        "Detected: ${issues.joinToString(", ")}. Check temperature, battery cost, refresh rate and root CPU snapshot."
    }
}

private fun exportReport(
    context: Context,
    report: GameSessionReport,
    format: ExportFormat
): String {
    return try {
        val timestamp = reportFileTimestamp(report.completedAtMs)
        val safeGame = sanitizeFileName(report.gameLabel)
        val extension = if (format == ExportFormat.TXT) "txt" else "json"
        val mimeType = if (format == ExportFormat.TXT) "text/plain" else "application/json"
        val fileName = "GamePulse_${safeGame}_${timestamp}.${extension}"
        val content = if (format == ExportFormat.TXT) {
            buildTextReport(report)
        } else {
            buildJsonReport(report).toString(2)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/GamePulseAnalyzer")
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return "Export failed: MediaStore insert returned null."

            resolver.openOutputStream(uri)?.use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
            } ?: return "Export failed: output stream is null."

            "Exported: Download/GamePulseAnalyzer/$fileName"
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "GamePulseAnalyzer")
            dir.mkdirs()

            val file = File(dir, fileName)
            file.writeText(content)

            "Exported: ${file.absolutePath}"
        }
    } catch (error: Exception) {
        "Export failed: ${error.message ?: "unknown error"}"
    }
}

private fun buildTextReport(report: GameSessionReport): String {
    return buildString {
        appendLine("GamePulse Analyzer Report")
        appendLine()
        appendLine("Game: ${report.gameLabel}")
        appendLine("Package: ${report.packageName}")
        appendLine("Duration: ${formatDurationLong(report.durationMs)}")
        appendLine()
        appendLine("Thermal:")
        appendLine("- Start temp: ${formatTemp(report.startBatteryTempC)}")
        appendLine("- End temp: ${formatTemp(report.endBatteryTempC)}")
        appendLine("- Verdict: ${thermalVerdict(report)}")
        appendLine()
        appendLine("Battery:")
        appendLine("- Start battery: ${formatBatteryPercent(report.startBatteryPercent)}")
        appendLine("- End battery: ${formatBatteryPercent(report.endBatteryPercent)}")
        appendLine("- Drain: ${formatBatteryDrain(report.batteryDrainPercent)}")
        appendLine("- Cost: ${formatBatteryCost(report)}")
        appendLine("- Estimated full battery session: ${formatEstimatedFullBattery(report)}")
        appendLine("- Verdict: ${batteryCostVerdict(report)}")
        appendLine()
        appendLine("Display:")
        appendLine("- Refresh rate: ${formatHz(report.refreshRateHz)}")
        appendLine("- Verdict: ${refreshVerdict(report)}")
        appendLine()
        appendLine("Root:")
        appendLine("- Start root: ${formatRootState(report.startRootAvailable)}")
        appendLine("- End root: ${formatRootState(report.endRootAvailable)}")
        appendLine()
        appendLine("Start CPU snapshot:")
        appendLine(report.startCpuSnapshot ?: "--")
        appendLine()
        appendLine("End CPU snapshot:")
        appendLine(report.endCpuSnapshot ?: "--")
        appendLine()
        appendLine("Session verdict:")
        appendLine(buildSessionVerdict(report))
    }
}

private fun buildJsonReport(report: GameSessionReport): JSONObject {
    return JSONObject()
        .put("app", "GamePulse Analyzer")
        .put("gameLabel", report.gameLabel)
        .put("packageName", report.packageName)
        .put("durationMs", report.durationMs)
        .put("durationFormatted", formatDurationLong(report.durationMs))
        .put("startBatteryTempC", report.startBatteryTempC ?: JSONObject.NULL)
        .put("endBatteryTempC", report.endBatteryTempC ?: JSONObject.NULL)
        .put("thermalVerdict", thermalVerdict(report))
        .put("startBatteryPercent", report.startBatteryPercent ?: JSONObject.NULL)
        .put("endBatteryPercent", report.endBatteryPercent ?: JSONObject.NULL)
        .put("batteryDrainPercent", report.batteryDrainPercent ?: JSONObject.NULL)
        .put("batteryCostPercentPerMinute", batteryCostPercentPerMinute(report) ?: JSONObject.NULL)
        .put("estimatedFullBatteryMinutes", estimatedFullBatteryMinutes(report) ?: JSONObject.NULL)
        .put("batteryCostVerdict", batteryCostVerdict(report))
        .put("refreshRateHz", report.refreshRateHz ?: JSONObject.NULL)
        .put("refreshVerdict", refreshVerdict(report))
        .put("startRootAvailable", report.startRootAvailable ?: JSONObject.NULL)
        .put("endRootAvailable", report.endRootAvailable ?: JSONObject.NULL)
        .put("startCpuSnapshot", report.startCpuSnapshot ?: JSONObject.NULL)
        .put("endCpuSnapshot", report.endCpuSnapshot ?: JSONObject.NULL)
        .put("sessionVerdict", buildSessionVerdict(report))
        .put("completedAtMs", report.completedAtMs)
}

private fun sanitizeFileName(value: String): String {
    return value
        .trim()
        .ifBlank { "unknown_game" }
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .trim('_')
        .ifBlank { "unknown_game" }
}

private fun reportFileTimestamp(timeMs: Long): String {
    val formatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    return formatter.format(Date(timeMs))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NxiHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(18.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            NxiTrafficDots()

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = "GAME SESSION CORE",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        Text(
            text = "GamePulse Analyzer",
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Select a game, start analysis and build a real session report.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(14.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            NxiChip("THERMAL")
            NxiChip("BATTERY")
            NxiChip("REFRESH")
            NxiChip("ROOT")
            NxiChip("EXPORT")
        }
    }
}

@Composable
private fun NxiTrafficDots() {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.error)
        )
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.tertiary)
        )
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondary)
        )
    }
}

@Composable
private fun NxiSessionCard(
    selectedGame: GameApp?,
    lastReport: GameSessionReport?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp)
    ) {
        Text(
            text = "SESSION STATUS",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = selectedGame?.label ?: "No game selected",
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = selectedGame?.packageName
                ?: "Choose a game below. Start Analyze will launch the game and create a session report after return.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(modifier = Modifier.height(14.dp))

        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(99.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.outline
        )

        if (lastReport != null) {
            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Last report: ${formatDurationShort(lastReport.durationMs)} / ${thermalVerdict(lastReport)} / ${formatBatteryCost(lastReport)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun NxiMetricCard(
    modifier: Modifier,
    title: String,
    value: String,
    caption: String
) {
    Column(
        modifier = modifier
            .defaultMinSize(minHeight = 112.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold
        )

        Text(
            text = value,
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun NxiManualGameCard(
    packageInput: String,
    status: String?,
    onPackageInputChanged: (String) -> Unit,
    onAddClicked: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp)
    ) {
        Text(
            text = "ADD MISSING GAME",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Use package name if Android did not mark the game automatically.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(modifier = Modifier.height(12.dp))

        BasicTextField(
            value = packageInput,
            onValueChange = onPackageInputChanged,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onBackground,
                fontFamily = FontFamily.Monospace
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.background)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(16.dp)
                )
                .padding(horizontal = 14.dp, vertical = 14.dp),
            decorationBox = { innerTextField ->
                if (packageInput.isBlank()) {
                    Text(
                        text = "com.example.game",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace
                    )
                }

                innerTextField()
            }
        )

        Spacer(modifier = Modifier.height(10.dp))

        NxiPrimaryButton(
            text = "Add Game",
            modifier = Modifier.fillMaxWidth(),
            enabled = packageInput.isNotBlank(),
            onClick = onAddClicked
        )

        if (status != null) {
            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = status,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun NxiGamesCard(
    games: List<GameApp>,
    selectedGame: GameApp?,
    onGameSelected: (GameApp) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp)
    ) {
        Text(
            text = "INSTALLED GAMES",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (games.isEmpty()) {
            Text(
                text = "No games detected. Add game manually by package name.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(games) { game ->
                    NxiGameRow(
                        game = game,
                        selected = selectedGame?.packageName == game.packageName,
                        onClick = { onGameSelected(game) }
                    )
                }
            }
        }
    }
}

@Composable
private fun NxiGameRow(
    game: GameApp,
    selected: Boolean,
    onClick: () -> Unit
) {
    val statusColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.background)
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (game.isManual && !game.isGameCategory) "MANUAL" else "GAME",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = if (selected) "SELECTED" else "TAP TO SELECT",
                color = statusColor,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = game.label,
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = game.packageName,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NxiReportCard(
    report: GameSessionReport,
    exportStatus: String?,
    onExportTxt: () -> Unit,
    onExportJson: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp)
    ) {
        Text(
            text = "SESSION REPORT",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = report.gameLabel,
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = report.packageName,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )

        Spacer(modifier = Modifier.height(12.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            NxiReportPill("DURATION", formatDurationLong(report.durationMs))
            NxiReportPill("THERMAL", thermalVerdict(report))
            NxiReportPill("START TEMP", formatTemp(report.startBatteryTempC))
            NxiReportPill("END TEMP", formatTemp(report.endBatteryTempC))
            NxiReportPill("DRAIN", formatBatteryDrain(report.batteryDrainPercent))
            NxiReportPill("COST", formatBatteryCost(report))
            NxiReportPill("ESTIMATE", formatEstimatedFullBattery(report))
            NxiReportPill("REFRESH", "${formatHz(report.refreshRateHz)} / ${refreshVerdict(report)}")
            NxiReportPill("ROOT", formatRootState(report.endRootAvailable))
        }

        Spacer(modifier = Modifier.height(14.dp))

        NxiVerdictBlock(text = buildSessionVerdict(report))

        val cpuSnapshot = report.endCpuSnapshot ?: report.startCpuSnapshot

        if (!cpuSnapshot.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            NxiCodeBlock(
                title = "CPU SNAPSHOT",
                value = cpuSnapshot
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            NxiPrimaryButton(
                text = "Export TXT",
                modifier = Modifier.weight(1f),
                enabled = true,
                onClick = onExportTxt
            )

            NxiPrimaryButton(
                text = "Export JSON",
                modifier = Modifier.weight(1f),
                enabled = true,
                onClick = onExportJson
            )
        }

        if (exportStatus != null) {
            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = exportStatus,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun NxiVerdictBlock(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp)
    ) {
        Text(
            text = "VERDICT",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = text,
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun NxiCodeBlock(
    title: String,
    value: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp)
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun NxiHistoryCard(history: List<GameSessionReport>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp)
    ) {
        Text(
            text = "SESSION HISTORY",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (history.isEmpty()) {
            Text(
                text = "No saved sessions yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            history.take(5).forEachIndexed { index, report ->
                if (index > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                }

                NxiHistoryRow(report = report)
            }

            if (history.size > 5) {
                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "+${history.size - 5} more saved sessions",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun NxiHistoryRow(report: GameSessionReport) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "SESSION",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = formatDurationLong(report.durationMs),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = report.gameLabel,
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "${thermalVerdict(report)} / ${formatBatteryCost(report)} / ${formatHz(report.refreshRateHz)}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun NxiReportPill(
    label: String,
    value: String
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = value,
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun NxiChip(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(99.dp))
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun NxiPrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(54.dp),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
            disabledContainerColor = MaterialTheme.colorScheme.surface,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun formatDurationShort(durationMs: Long): String {
    val totalSeconds = durationMs / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L

    return if (minutes > 0L) {
        "${minutes}m"
    } else {
        "${seconds}s"
    }
}

private fun formatDurationLong(durationMs: Long): String {
    val totalSeconds = durationMs / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L

    return if (minutes > 0L) {
        "${minutes}m ${seconds}s"
    } else {
        "${seconds}s"
    }
}

private fun formatTemp(value: Float?): String {
    return value?.let { "${((it * 10f).roundToInt() / 10f)}°C" } ?: "--"
}

private fun formatHz(value: Float?): String {
    return value?.let { "${it.roundToInt()} Hz" } ?: "--"
}

private fun formatBatteryPercent(value: Int?): String {
    return value?.let { "$it%" } ?: "--"
}

private fun formatBatteryDrain(value: Int?): String {
    return value?.let { "$it%" } ?: "--"
}

private fun formatBatteryCost(report: GameSessionReport): String {
    val rate = batteryCostPercentPerMinute(report) ?: return "--"

    return if (rate <= 0f) {
        "0%/min"
    } else {
        "${formatRate(rate)}%/min"
    }
}

private fun formatEstimatedFullBattery(report: GameSessionReport): String {
    val minutes = estimatedFullBatteryMinutes(report) ?: return "--"

    val hours = minutes / 60
    val leftMinutes = minutes % 60

    return if (hours > 0) {
        "${hours}h ${leftMinutes}m"
    } else {
        "${leftMinutes}m"
    }
}

private fun formatRate(value: Float): String {
    return if (value >= 10f) {
        String.format(Locale.US, "%.1f", value)
    } else {
        String.format(Locale.US, "%.2f", value)
    }
}

private fun formatRootState(value: Boolean?): String {
    return when (value) {
        true -> "available"
        false -> "blocked"
        null -> "--"
    }
}
