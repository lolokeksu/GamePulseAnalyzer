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

data class RootDiagnosticsState(
    val checked: Boolean,
    val suBinaryFound: Boolean,
    val suExecutionAllowed: Boolean,
    val cpuPoliciesReadable: Boolean,
    val thermalZonesReadable: Boolean,
    val metricsEnabled: Boolean,
    val statusMessage: String
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
    val completedAtMs: Long,
    val samplesJson: String? = null,
    val startCharging: Boolean? = null,
    val endCharging: Boolean? = null,
    val startPluggedState: String? = null,
    val endPluggedState: String? = null,
    val rootSamplerEnabled: Boolean? = null,
    val rootSamplesJson: String? = null
) {
    val batteryDeltaPercent: Int?
        get() {
            val start = startBatteryPercent ?: return null
            val end = endBatteryPercent ?: return null
            return end - start
        }

    val batteryChargingInvolved: Boolean
        get() = startCharging == true || endCharging == true ||
            startPluggedState?.let { it != "NONE" && it != "UNKNOWN" } == true ||
            endPluggedState?.let { it != "NONE" && it != "UNKNOWN" } == true

    val batteryDrainPercent: Int?
        get() {
            val start = startBatteryPercent ?: return null
            val end = endBatteryPercent ?: return null

            if (batteryChargingInvolved || end > start) {
                return null
            }

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
    var rootState by remember { mutableStateOf(loadRootDiagnosticsState(context)) }
    var manualPackageInput by remember { mutableStateOf("") }
    var manualStatus by remember { mutableStateOf<String?>(null) }
    var exportStatus by remember { mutableStateOf<String?>(null) }
    var currentTab by remember { mutableStateOf(GamePulseTab.DASHBOARD) }

    LaunchedEffect(Unit) {
        games = loadLaunchableGames(context, packageManager)
        selectedGame = games.firstOrNull()
        lastReport = loadLastReport(context)
        reportHistory = loadReportHistory(context)
        rootState = loadRootDiagnosticsState(context)
    }

    LaunchedEffect(refreshTick.value) {
        finishActiveSessionIfNeeded(context)?.let { report ->
            lastReport = report
            reportHistory = loadReportHistory(context)
            exportStatus = null
        }

        rootState = loadRootDiagnosticsState(context)
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

            NxiTabBar(
                currentTab = currentTab,
                onTabSelected = { currentTab = it }
            )

            when (currentTab) {
                GamePulseTab.DASHBOARD -> {
                    NxiDashboardWorkspace(
                        gamesCount = games.size,
                        reportsCount = reportHistory.size,
                        selectedGame = selectedGame,
                        lastReport = lastReport,
                        rootState = rootState,
                        onStartAnalyze = {
                            selectedGame?.let { game ->
                                startGameSession(
                                    context = context,
                                    game = game,
                                    rootState = rootState
                                )
                            }
                        }
                    )
                }

                GamePulseTab.GAMES -> {
                    NxiGamesWorkspace(
                        games = games,
                        selectedGame = selectedGame,
                        packageInput = manualPackageInput,
                        manualStatus = manualStatus,
                        onGameSelected = { selectedGame = it },
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
                }

                GamePulseTab.REPORTS -> {
                    NxiReportsWorkspace(
                        lastReport = lastReport,
                        history = reportHistory,
                        exportStatus = exportStatus,
                        onExportTxt = {
                            lastReport?.let { report ->
                                exportStatus = exportReport(
                                    context = context,
                                    report = report,
                                    format = ExportFormat.TXT
                                )
                            }
                        },
                        onExportJson = {
                            lastReport?.let { report ->
                                exportStatus = exportReport(
                                    context = context,
                                    report = report,
                                    format = ExportFormat.JSON
                                )
                            }
                        }
                    )
                }

                GamePulseTab.ROOT -> {
                    NxiRootWorkspace(
                        rootState = rootState,
                        onCheckRoot = {
                            rootState = runRootDiagnostics(context)
                        },
                        onDisableRoot = {
                            rootState = saveRootDiagnosticsState(
                                context = context,
                                state = RootDiagnosticsState(
                                    checked = false,
                                    suBinaryFound = false,
                                    suExecutionAllowed = false,
                                    cpuPoliciesReadable = false,
                                    thermalZonesReadable = false,
                                    metricsEnabled = false,
                                    statusMessage = "Root metrics disabled"
                                )
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

private enum class GamePulseTab(val title: String) {
    DASHBOARD("HOME"),
    GAMES("GAMES"),
    REPORTS("REPORTS"),
    ROOT("ROOT")
}

@Composable
private fun NxiTabBar(
    currentTab: GamePulseTab,
    onTabSelected: (GamePulseTab) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        GamePulseTab.values().forEach { tab ->
            NxiTabButton(
                modifier = Modifier.weight(1f),
                text = tab.title,
                selected = currentTab == tab,
                onClick = { onTabSelected(tab) }
            )
        }
    }
}

@Composable
private fun NxiTabButton(
    modifier: Modifier,
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (selected) {
        MaterialTheme.colorScheme.surface
    } else {
        MaterialTheme.colorScheme.background
    }

    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline
    }

    val textColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(backgroundColor)
            .border(
                width = 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun NxiDashboardWorkspace(
    gamesCount: Int,
    reportsCount: Int,
    selectedGame: GameApp?,
    lastReport: GameSessionReport?,
    rootState: RootDiagnosticsState,
    onStartAnalyze: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        NxiSessionCard(
            selectedGame = selectedGame,
            lastReport = lastReport,
            rootState = rootState
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            NxiMetricCard(
                modifier = Modifier.weight(1f),
                title = "GAMES",
                value = gamesCount.toString(),
                caption = "detected"
            )

            NxiMetricCard(
                modifier = Modifier.weight(1f),
                title = "ROOT",
                value = if (rootState.metricsEnabled) "ON" else "OFF",
                caption = if (rootState.checked) rootState.statusMessage else "not checked"
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            NxiMetricCard(
                modifier = Modifier.weight(1f),
                title = "REPORTS",
                value = reportsCount.toString(),
                caption = "saved"
            )

            NxiMetricCard(
                modifier = Modifier.weight(1f),
                title = "COST",
                value = lastReport?.let { formatBatteryCost(it) } ?: "--",
                caption = "battery/min"
            )
        }

        if (lastReport != null) {
            NxiVerdictBlock(text = buildSessionVerdict(lastReport))
        } else {
            NxiWorkspaceNotice(
                title = "NO SESSION YET",
                text = "Select a game in GAMES tab, then return here and press Start Analyze."
            )
        }

        NxiPrimaryButton(
            text = "Start Analyze",
            modifier = Modifier.fillMaxWidth(),
            enabled = selectedGame != null,
            onClick = onStartAnalyze
        )
    }
}

@Composable
private fun NxiGamesWorkspace(
    games: List<GameApp>,
    selectedGame: GameApp?,
    packageInput: String,
    manualStatus: String?,
    onGameSelected: (GameApp) -> Unit,
    onPackageInputChanged: (String) -> Unit,
    onAddClicked: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        NxiWorkspaceNotice(
            title = "GAME LIBRARY",
            text = "Choose detected games or add missing games manually by package name."
        )

        NxiManualGameCard(
            packageInput = packageInput,
            status = manualStatus,
            onPackageInputChanged = onPackageInputChanged,
            onAddClicked = onAddClicked
        )

        NxiGamesCard(
            games = games,
            selectedGame = selectedGame,
            onGameSelected = onGameSelected
        )
    }
}

@Composable
private fun NxiReportsWorkspace(
    lastReport: GameSessionReport?,
    history: List<GameSessionReport>,
    exportStatus: String?,
    onExportTxt: () -> Unit,
    onExportJson: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (lastReport != null) {
            NxiReportCard(
                report = lastReport,
                exportStatus = exportStatus,
                onExportTxt = onExportTxt,
                onExportJson = onExportJson
            )
        } else {
            NxiWorkspaceNotice(
                title = "NO REPORT",
                text = "Run a session first. Reports, history and export will appear here."
            )
        }

        NxiHistoryCard(history = history)
    }
}

@Composable
private fun NxiRootWorkspace(
    rootState: RootDiagnosticsState,
    onCheckRoot: () -> Unit,
    onDisableRoot: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        NxiWorkspaceNotice(
            title = "ROOT CONTROL",
            text = "Root metrics are disabled by default. Check Root explicitly before collecting CPU snapshots."
        )

        NxiRootDiagnosticsCard(
            state = rootState,
            onCheckRoot = onCheckRoot,
            onDisableRoot = onDisableRoot
        )
    }
}

@Composable
private fun NxiWorkspaceNotice(
    title: String,
    text: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp)
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private enum class ExportFormat {
    TXT,
    JSON
}

private data class BatteryState(
    val tempC: Float?,
    val percent: Int?,
    val charging: Boolean,
    val pluggedState: String
)

private data class SamplerReliability(
    val expectedSamples: Int,
    val capturedSamples: Int,
    val missedSamples: Int,
    val reliabilityPercent: Float,
    val maxGapMs: Long
)

private data class RefreshAnalysis(
    val minHz: Float?,
    val maxHz: Float?,
    val avgHz: Float?,
    val dominantHz: Float?
)

private data class ThermalAnalysis(
    val minTempC: Float?,
    val maxTempC: Float?,
    val deltaTempC: Float?,
    val growthCPerMinute: Float?
)

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

private fun startGameSession(
    context: Context,
    game: GameApp,
    rootState: RootDiagnosticsState
) {
    val packageManager = context.packageManager
    val launchIntent = packageManager.getLaunchIntentForPackage(game.packageName) ?: return

    val rootMetricsEnabled = rootState.metricsEnabled
    val rootSamplerStarted = if (rootMetricsEnabled) {
        RootSessionSampler.start()
    } else {
        false
    }

    val batteryState = readBatteryState(context)
    val cpuSnapshot = if (rootMetricsEnabled) {
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
        .putFloat("start_battery_temp_c", batteryState.tempC ?: -1f)
        .putInt("start_battery_percent", batteryState.percent ?: -1)
        .putBoolean("start_charging", batteryState.charging)
        .putString("start_plugged_state", batteryState.pluggedState)
        .putFloat("refresh_rate_hz", readRefreshRateHz(context) ?: -1f)
        .putBoolean("start_root_available", rootMetricsEnabled)
        .putString("start_cpu_snapshot", cpuSnapshot)
        .putBoolean("root_sampler_enabled", rootSamplerStarted)
        .putString("active_samples_json", "[]")
        .putLong("active_last_sample_elapsed_ms", 0L)
        .putInt("active_sample_count", 0)
        .apply()

    val samplerIntent = Intent(context, GamePulseSessionService::class.java).apply {
        putExtra(GamePulseSessionService.EXTRA_ROOT_METRICS_ENABLED, rootMetricsEnabled)
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(samplerIntent)
    } else {
        context.startService(samplerIntent)
    }

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

    val rootState = loadRootDiagnosticsState(context)
    val rootMetricsEnabled = rootState.metricsEnabled
    val endCpuSnapshot = if (rootMetricsEnabled) {
        readCpuFreqSnapshotWithRoot()
    } else {
        null
    }

    val samplesJson = prefs.getString("active_samples_json", "[]") ?: "[]"
    val rootSamplerEnabled = prefs.getBoolean("root_sampler_enabled", false)
    val rootSamplesJson = if (rootSamplerEnabled) {
        RootSessionSampler.stopAndRead()
    } else {
        "[]"
    }

    val endBatteryState = readBatteryState(context)

    val report = GameSessionReport(
        gameLabel = prefs.getString("game_label", "Unknown game") ?: "Unknown game",
        packageName = prefs.getString("package_name", "unknown.package") ?: "unknown.package",
        durationMs = durationMs,
        startBatteryTempC = prefs.getFloat("start_battery_temp_c", -1f).takeIf { it >= 0f },
        endBatteryTempC = endBatteryState.tempC,
        startBatteryPercent = prefs.getInt("start_battery_percent", -1).takeIf { it >= 0 },
        endBatteryPercent = endBatteryState.percent,
        refreshRateHz = prefs.getFloat("refresh_rate_hz", -1f).takeIf { it >= 0f },
        startRootAvailable = prefs.getBoolean("start_root_available", false),
        endRootAvailable = rootMetricsEnabled,
        startCpuSnapshot = prefs.getString("start_cpu_snapshot", null),
        endCpuSnapshot = endCpuSnapshot,
        completedAtMs = System.currentTimeMillis(),
        samplesJson = samplesJson,
        startCharging = prefs.getBoolean("start_charging", false),
        endCharging = endBatteryState.charging,
        startPluggedState = prefs.getString("start_plugged_state", "UNKNOWN"),
        endPluggedState = endBatteryState.pluggedState,
        rootSamplerEnabled = rootSamplerEnabled,
        rootSamplesJson = rootSamplesJson
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
        .putString("last_samples_json", report.samplesJson)
        .putBoolean("last_start_charging", report.startCharging ?: false)
        .putBoolean("last_end_charging", report.endCharging ?: false)
        .putString("last_start_plugged_state", report.startPluggedState)
        .putString("last_end_plugged_state", report.endPluggedState)
        .putBoolean("last_root_sampler_enabled", report.rootSamplerEnabled ?: false)
        .putString("last_root_samples_json", report.rootSamplesJson)
        .putLong("last_completed_at_ms", report.completedAtMs)
        .apply()

    appendReportHistory(context, report)

    context.stopService(Intent(context, GamePulseSessionService::class.java))

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
        completedAtMs = prefs.getLong("last_completed_at_ms", 0L),
        samplesJson = prefs.getString("last_samples_json", null),
        startCharging = prefs.getBoolean("last_start_charging", false),
        endCharging = prefs.getBoolean("last_end_charging", false),
        startPluggedState = prefs.getString("last_start_plugged_state", "UNKNOWN"),
        endPluggedState = prefs.getString("last_end_plugged_state", "UNKNOWN"),
        rootSamplerEnabled = prefs.getBoolean("last_root_sampler_enabled", false),
        rootSamplesJson = prefs.getString("last_root_samples_json", null)
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
                .put("startCharging", item.startCharging ?: JSONObject.NULL)
                .put("endCharging", item.endCharging ?: JSONObject.NULL)
                .put("startPluggedState", item.startPluggedState ?: JSONObject.NULL)
                .put("endPluggedState", item.endPluggedState ?: JSONObject.NULL)
                .put("rootSamplerEnabled", item.rootSamplerEnabled ?: JSONObject.NULL)
                .put("rootSamplesJson", item.rootSamplesJson ?: JSONObject.NULL)
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
                        completedAtMs = completedAtMs,
                        startCharging = item.optNullableBoolean("startCharging"),
                        endCharging = item.optNullableBoolean("endCharging"),
                        startPluggedState = item.optNullableString("startPluggedState"),
                        endPluggedState = item.optNullableString("endPluggedState"),
                        rootSamplerEnabled = item.optNullableBoolean("rootSamplerEnabled"),
                        rootSamplesJson = item.optNullableString("rootSamplesJson")
                    )
                )
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun loadRootDiagnosticsState(context: Context): RootDiagnosticsState {
    val prefs = context.getSharedPreferences("gamepulse_root", Context.MODE_PRIVATE)

    return RootDiagnosticsState(
        checked = prefs.getBoolean("checked", false),
        suBinaryFound = prefs.getBoolean("su_binary_found", false),
        suExecutionAllowed = prefs.getBoolean("su_execution_allowed", false),
        cpuPoliciesReadable = prefs.getBoolean("cpu_policies_readable", false),
        thermalZonesReadable = prefs.getBoolean("thermal_zones_readable", false),
        metricsEnabled = prefs.getBoolean("metrics_enabled", false),
        statusMessage = prefs.getString("status_message", "Not checked") ?: "Not checked"
    )
}

private fun saveRootDiagnosticsState(
    context: Context,
    state: RootDiagnosticsState
): RootDiagnosticsState {
    val prefs = context.getSharedPreferences("gamepulse_root", Context.MODE_PRIVATE)

    prefs.edit()
        .putBoolean("checked", state.checked)
        .putBoolean("su_binary_found", state.suBinaryFound)
        .putBoolean("su_execution_allowed", state.suExecutionAllowed)
        .putBoolean("cpu_policies_readable", state.cpuPoliciesReadable)
        .putBoolean("thermal_zones_readable", state.thermalZonesReadable)
        .putBoolean("metrics_enabled", state.metricsEnabled)
        .putString("status_message", state.statusMessage)
        .apply()

    return state
}

private fun runRootDiagnostics(context: Context): RootDiagnosticsState {
    val suBinaryFound = isSuBinaryFound()
    val suOutput = runRootCommand("echo gamepulse_root_ok", 4_000L)
    val suExecutionAllowed = suOutput?.contains("gamepulse_root_ok") == true

    val cpuPoliciesReadable = if (suExecutionAllowed) {
        runRootCommand(
            command = "test -r /sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq && echo cpu_ok || echo cpu_blocked",
            timeoutMs = 2_000L
        )?.contains("cpu_ok") == true
    } else {
        false
    }

    val thermalZonesReadable = if (suExecutionAllowed) {
        runRootCommand(
            command = "ls /sys/class/thermal/thermal_zone* >/dev/null 2>&1 && echo thermal_ok || echo thermal_blocked",
            timeoutMs = 2_000L
        )?.contains("thermal_ok") == true
    } else {
        false
    }

    val metricsEnabled = suExecutionAllowed && cpuPoliciesReadable

    val statusMessage = when {
        !suBinaryFound -> "su binary not found"
        !suExecutionAllowed -> "root denied or timeout"
        !cpuPoliciesReadable -> "root ok, CPU policies blocked"
        metricsEnabled -> "root metrics ready"
        else -> "root checked"
    }

    return saveRootDiagnosticsState(
        context = context,
        state = RootDiagnosticsState(
            checked = true,
            suBinaryFound = suBinaryFound,
            suExecutionAllowed = suExecutionAllowed,
            cpuPoliciesReadable = cpuPoliciesReadable,
            thermalZonesReadable = thermalZonesReadable,
            metricsEnabled = metricsEnabled,
            statusMessage = statusMessage
        )
    )
}

private fun isSuBinaryFound(): Boolean {
    return try {
        val process = ProcessBuilder("sh", "-c", "command -v su || which su || ls /system/bin/su /system/xbin/su 2>/dev/null")
            .redirectErrorStream(true)
            .start()

        val finished = process.waitFor(1_500L, TimeUnit.MILLISECONDS)

        if (!finished) {
            process.destroyForcibly()
            return false
        }

        process.inputStream.bufferedReader().use { it.readText() }.trim().isNotBlank()
    } catch (_: Exception) {
        false
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

private fun readBatteryState(context: Context): BatteryState {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    if (intent == null) {
        return BatteryState(
            tempC = null,
            percent = null,
            charging = false,
            pluggedState = "UNKNOWN"
        )
    }

    val rawTemp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
    val tempC = if (rawTemp == Int.MIN_VALUE) null else rawTemp / 10f

    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    val percent = if (level >= 0 && scale > 0) {
        ((level * 100f) / scale).roundToInt().coerceIn(0, 100)
    } else {
        null
    }

    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
        status == BatteryManager.BATTERY_STATUS_FULL

    val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
    val pluggedState = when {
        plugged and BatteryManager.BATTERY_PLUGGED_AC != 0 -> "AC"
        plugged and BatteryManager.BATTERY_PLUGGED_USB != 0 -> "USB"
        plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> "WIRELESS"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            plugged and BatteryManager.BATTERY_PLUGGED_DOCK != 0 -> "DOCK"
        else -> "NONE"
    }

    return BatteryState(
        tempC = tempC,
        percent = percent,
        charging = charging || pluggedState != "NONE",
        pluggedState = pluggedState
    )
}

private fun readBatteryTemperatureC(context: Context): Float? {
    return readBatteryState(context).tempC
}

private fun readBatteryPercent(context: Context): Int? {
    return readBatteryState(context).percent
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
    if (batteryCostUnavailableReason(report) != null) {
        return null
    }

    val rate = batteryCostPercentPerMinute(report) ?: return null

    if (rate <= 0f) {
        return null
    }

    return (100f / rate).roundToInt().coerceAtLeast(1)
}

private fun samplerReliability(report: GameSessionReport): SamplerReliability {
    val samples = parseSamplesJsonArray(report.samplesJson)
    val captured = samples.length()
    val expected = ((report.durationMs / 2_000L) + 1L).toInt().coerceAtLeast(1)
    val missed = (expected - captured).coerceAtLeast(0)

    var lastElapsed: Long? = null
    var maxGap = 0L

    for (index in 0 until samples.length()) {
        val item = samples.optJSONObject(index) ?: continue
        val elapsed = item.optLong("elapsedMs", -1L)

        if (elapsed < 0L) {
            continue
        }

        val previous = lastElapsed
        if (previous != null) {
            maxGap = maxGap.coerceAtLeast(elapsed - previous)
        }

        lastElapsed = elapsed
    }

    val reliability = if (expected <= 0) {
        0f
    } else {
        ((captured * 100f) / expected).coerceIn(0f, 100f)
    }

    return SamplerReliability(
        expectedSamples = expected,
        capturedSamples = captured,
        missedSamples = missed,
        reliabilityPercent = reliability,
        maxGapMs = maxGap
    )
}

private fun samplerReliabilityVerdict(report: GameSessionReport): String {
    val reliability = samplerReliability(report).reliabilityPercent

    return when {
        reliability >= 80f -> "GOOD"
        reliability >= 40f -> "PARTIAL"
        else -> "POOR"
    }
}

private fun rootSamplerReliability(report: GameSessionReport): SamplerReliability {
    val samples = parseSamplesJsonArray(report.rootSamplesJson)
    val captured = samples.length()
    val expected = ((report.durationMs / 2_000L) + 1L).toInt().coerceAtLeast(1)
    val missed = (expected - captured).coerceAtLeast(0)

    var lastElapsed: Long? = null
    var maxGap = 0L

    for (index in 0 until samples.length()) {
        val item = samples.optJSONObject(index) ?: continue
        val elapsed = item.optLong("elapsedMs", -1L)

        if (elapsed < 0L) {
            continue
        }

        val previous = lastElapsed
        if (previous != null) {
            maxGap = maxGap.coerceAtLeast(elapsed - previous)
        }

        lastElapsed = elapsed
    }

    val reliability = if (expected <= 0) {
        0f
    } else {
        ((captured * 100f) / expected).coerceIn(0f, 100f)
    }

    return SamplerReliability(
        expectedSamples = expected,
        capturedSamples = captured,
        missedSamples = missed,
        reliabilityPercent = reliability,
        maxGapMs = maxGap
    )
}

private fun rootSamplerReliabilityVerdict(report: GameSessionReport): String {
    if (report.rootSamplerEnabled != true) {
        return "DISABLED"
    }

    val reliability = rootSamplerReliability(report).reliabilityPercent

    return when {
        reliability >= 80f -> "GOOD"
        reliability >= 40f -> "PARTIAL"
        else -> "POOR"
    }
}

private fun rootSamplerWarning(report: GameSessionReport): String? {
    if (report.rootSamplerEnabled != true) {
        return "Root-backed sampler disabled. Enable ROOT tab diagnostics before session."
    }

    val reliability = rootSamplerReliability(report)

    if (reliability.reliabilityPercent >= 80f) {
        return null
    }

    return "Root sampler captured ${reliability.capturedSamples} of expected ~${reliability.expectedSamples} samples. Reliability ${formatRate(reliability.reliabilityPercent)}%, max gap ${formatDurationLong(reliability.maxGapMs)}."
}

private fun refreshAnalysis(report: GameSessionReport): RefreshAnalysis {
    val samples = parseSamplesJsonArray(report.samplesJson)
    val values = mutableListOf<Float>()

    for (index in 0 until samples.length()) {
        val item = samples.optJSONObject(index) ?: continue

        if (!item.has("refreshRateHz") || item.isNull("refreshRateHz")) {
            continue
        }

        val value = item.optDouble("refreshRateHz", Double.NaN)
        if (!value.isNaN() && value > 0.0) {
            values.add(value.toFloat())
        }
    }

    if (values.isEmpty()) {
        return RefreshAnalysis(
            minHz = report.refreshRateHz,
            maxHz = report.refreshRateHz,
            avgHz = report.refreshRateHz,
            dominantHz = report.refreshRateHz
        )
    }

    val roundedBuckets = values
        .map { it.roundToInt() }
        .groupingBy { it }
        .eachCount()

    val dominant = roundedBuckets.maxByOrNull { it.value }?.key?.toFloat()

    return RefreshAnalysis(
        minHz = values.minOrNull(),
        maxHz = values.maxOrNull(),
        avgHz = values.average().toFloat(),
        dominantHz = dominant
    )
}

private fun thermalAnalysis(report: GameSessionReport): ThermalAnalysis {
    val samples = parseSamplesJsonArray(report.samplesJson)
    val values = mutableListOf<Pair<Long, Float>>()

    for (index in 0 until samples.length()) {
        val item = samples.optJSONObject(index) ?: continue

        if (!item.has("batteryTempC") || item.isNull("batteryTempC")) {
            continue
        }

        val value = item.optDouble("batteryTempC", Double.NaN)
        val elapsed = item.optLong("elapsedMs", -1L)

        if (!value.isNaN() && elapsed >= 0L) {
            values.add(elapsed to value.toFloat())
        }
    }

    if (values.isEmpty()) {
        val start = report.startBatteryTempC
        val end = report.endBatteryTempC
        val delta = if (start != null && end != null) end - start else null
        val minutes = report.durationMs / 60_000f
        val growth = if (delta != null && minutes > 0f) delta / minutes else null

        return ThermalAnalysis(
            minTempC = listOfNotNull(start, end).minOrNull(),
            maxTempC = listOfNotNull(start, end).maxOrNull(),
            deltaTempC = delta,
            growthCPerMinute = growth
        )
    }

    val temps = values.map { it.second }
    val first = values.first().second
    val last = values.last().second
    val delta = last - first
    val minutes = report.durationMs / 60_000f

    return ThermalAnalysis(
        minTempC = temps.minOrNull(),
        maxTempC = temps.maxOrNull(),
        deltaTempC = delta,
        growthCPerMinute = if (minutes > 0f) delta / minutes else null
    )
}

private fun batteryCostUnavailableReason(report: GameSessionReport): String? {
    val start = report.startBatteryPercent
    val end = report.endBatteryPercent

    return when {
        report.batteryChargingInvolved -> "CHARGING"
        start != null && end != null && end > start -> "BATTERY_INCREASED"
        start == null || end == null -> "NO_BATTERY_DATA"
        else -> null
    }
}

private fun samplerWarning(report: GameSessionReport): String? {
    val reliability = samplerReliability(report)

    if (reliability.reliabilityPercent >= 80f) {
        return null
    }

    return "Sampler captured only ${reliability.capturedSamples} of expected ~${reliability.expectedSamples} samples. Reliability ${formatRate(reliability.reliabilityPercent)}%, max gap ${formatDurationLong(reliability.maxGapMs)}."
}

private fun thermalVerdict(report: GameSessionReport): String {
    val analysis = thermalAnalysis(report)
    val maxTemp = analysis.maxTempC ?: return "UNKNOWN"
    val delta = analysis.deltaTempC ?: 0f

    return when {
        maxTemp >= 45f || delta >= 6f -> "HOT"
        maxTemp >= 42f || delta >= 4f -> "HIGH"
        maxTemp >= 39f || delta >= 2f -> "MODERATE"
        else -> "NORMAL"
    }
}

private fun refreshVerdict(report: GameSessionReport): String {
    val hz = refreshAnalysis(report).dominantHz
        ?: refreshAnalysis(report).maxHz
        ?: report.refreshRateHz
        ?: return "UNKNOWN"

    return when {
        hz >= 120f -> "HIGH"
        hz >= 90f -> "GOOD"
        hz >= 60f -> "STANDARD"
        else -> "LOW"
    }
}

private fun batteryCostVerdict(report: GameSessionReport): String {
    batteryCostUnavailableReason(report)?.let { return it }

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
        "CHARGING" -> issues.add("battery cost unavailable while charging")
        "BATTERY_INCREASED" -> issues.add("battery increased during session")
    }

    if (refreshVerdict(report) == "LOW") {
        issues.add("low refresh rate")
    }

    if (samplerReliabilityVerdict(report) == "POOR") {
        if (report.rootSamplerEnabled == true && rootSamplerReliabilityVerdict(report) != "POOR") {
            issues.add("Android sampler poor, root sampler available")
        } else {
            issues.add("sampler reliability is poor")
        }
    }

    if (report.endRootAvailable == false) {
        issues.add("root metrics disabled")
    }

    return if (issues.isEmpty()) {
        "Stable session. No strong thermal, battery, refresh, or sampler reliability issue detected."
    } else {
        "Detected: ${issues.joinToString(", ")}. Root metrics are collected only after explicit Check Root."
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
        val thermal = thermalAnalysis(report)
        val refresh = refreshAnalysis(report)
        val reliability = samplerReliability(report)

        appendLine("Thermal:")
        appendLine("- Start temp: ${formatTemp(report.startBatteryTempC)}")
        appendLine("- End temp: ${formatTemp(report.endBatteryTempC)}")
        appendLine("- Min temp: ${formatTemp(thermal.minTempC)}")
        appendLine("- Max temp: ${formatTemp(thermal.maxTempC)}")
        appendLine("- Delta: ${formatSignedTempDelta(thermal.deltaTempC)}")
        appendLine("- Growth: ${formatTempGrowth(thermal.growthCPerMinute)}")
        appendLine("- Verdict: ${thermalVerdict(report)}")
        appendLine()
        appendLine("Battery:")
        appendLine("- Start battery: ${formatBatteryPercent(report.startBatteryPercent)}")
        appendLine("- End battery: ${formatBatteryPercent(report.endBatteryPercent)}")
        appendLine("- Start charging: ${formatBoolean(report.startCharging)} / ${report.startPluggedState ?: "--"}")
        appendLine("- End charging: ${formatBoolean(report.endCharging)} / ${report.endPluggedState ?: "--"}")
        appendLine("- Drain: ${formatBatteryDrainForReport(report)}")
        appendLine("- Cost: ${formatBatteryCost(report)}")
        appendLine("- Estimated full battery session: ${formatEstimatedFullBattery(report)}")
        appendLine("- Verdict: ${batteryCostVerdict(report)}")
        appendLine("- Unavailable reason: ${batteryCostUnavailableReason(report) ?: "--"}")
        appendLine()
        appendLine("Display:")
        appendLine("- Refresh rate start/end field: ${formatHz(report.refreshRateHz)}")
        appendLine("- Min refresh: ${formatHz(refresh.minHz)}")
        appendLine("- Max refresh: ${formatHz(refresh.maxHz)}")
        appendLine("- Avg refresh: ${formatHz(refresh.avgHz)}")
        appendLine("- Dominant refresh: ${formatHz(refresh.dominantHz)}")
        appendLine("- Verdict: ${refreshVerdict(report)}")
        appendLine()
        appendLine("Root:")
        appendLine("- Start root metrics: ${formatRootState(report.startRootAvailable)}")
        appendLine("- End root metrics: ${formatRootState(report.endRootAvailable)}")
        appendLine("- Note: root metrics are used only after explicit Check Root in the app.")
        appendLine()
        appendLine("Sampler:")
        appendLine("- Samples count: ${reliability.capturedSamples}")
        appendLine("- Expected samples: ~${reliability.expectedSamples}")
        appendLine("- Missed samples: ${reliability.missedSamples}")
        appendLine("- Reliability: ${formatRate(reliability.reliabilityPercent)}%")
        appendLine("- Max sample gap: ${formatDurationLong(reliability.maxGapMs)}")
        appendLine("- Verdict: ${samplerReliabilityVerdict(report)}")
        samplerWarning(report)?.let { appendLine("- Warning: $it") }
        appendLine("- Interval: 2s")
        appendLine()

        val rootReliability = rootSamplerReliability(report)
        appendLine("Root-backed sampler:")
        appendLine("- Enabled: ${formatBoolean(report.rootSamplerEnabled)}")
        appendLine("- Samples count: ${rootReliability.capturedSamples}")
        appendLine("- Expected samples: ~${rootReliability.expectedSamples}")
        appendLine("- Missed samples: ${rootReliability.missedSamples}")
        appendLine("- Reliability: ${formatRate(rootReliability.reliabilityPercent)}%")
        appendLine("- Max sample gap: ${formatDurationLong(rootReliability.maxGapMs)}")
        appendLine("- Verdict: ${rootSamplerReliabilityVerdict(report)}")
        rootSamplerWarning(report)?.let { appendLine("- Warning: $it") }
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
        .put("thermalAnalysis", buildThermalAnalysisJson(report))
        .put("startBatteryPercent", report.startBatteryPercent ?: JSONObject.NULL)
        .put("endBatteryPercent", report.endBatteryPercent ?: JSONObject.NULL)
        .put("startCharging", report.startCharging ?: JSONObject.NULL)
        .put("endCharging", report.endCharging ?: JSONObject.NULL)
        .put("startPluggedState", report.startPluggedState ?: JSONObject.NULL)
        .put("endPluggedState", report.endPluggedState ?: JSONObject.NULL)
        .put("batteryChargingInvolved", report.batteryChargingInvolved)
        .put("batteryDeltaPercent", report.batteryDeltaPercent ?: JSONObject.NULL)
        .put("batteryDrainPercent", report.batteryDrainPercent ?: JSONObject.NULL)
        .put("batteryCostPercentPerMinute", batteryCostPercentPerMinute(report) ?: JSONObject.NULL)
        .put("estimatedFullBatteryMinutes", estimatedFullBatteryMinutes(report) ?: JSONObject.NULL)
        .put("batteryCostVerdict", batteryCostVerdict(report))
        .put("batteryCostUnavailableReason", batteryCostUnavailableReason(report) ?: JSONObject.NULL)
        .put("refreshRateHz", report.refreshRateHz ?: JSONObject.NULL)
        .put("refreshAnalysis", buildRefreshAnalysisJson(report))
        .put("refreshVerdict", refreshVerdict(report))
        .put("startRootAvailable", report.startRootAvailable ?: JSONObject.NULL)
        .put("endRootAvailable", report.endRootAvailable ?: JSONObject.NULL)
        .put("startCpuSnapshot", report.startCpuSnapshot ?: JSONObject.NULL)
        .put("endCpuSnapshot", report.endCpuSnapshot ?: JSONObject.NULL)
        .put("rootNote", "Root metrics are used only after explicit Check Root in the app.")
        .put("samplesIntervalMs", 2000)
        .put("samplesCount", samplerReliability(report).capturedSamples)
        .put("samplesExpected", samplerReliability(report).expectedSamples)
        .put("samplesMissed", samplerReliability(report).missedSamples)
        .put("samplerReliabilityPercent", samplerReliability(report).reliabilityPercent)
        .put("samplerMaxGapMs", samplerReliability(report).maxGapMs)
        .put("samplerVerdict", samplerReliabilityVerdict(report))
        .put("samplerWarning", samplerWarning(report) ?: JSONObject.NULL)
        .put("samples", parseSamplesJsonArray(report.samplesJson))
        .put("rootSamplerEnabled", report.rootSamplerEnabled ?: false)
        .put("rootSamplesCount", rootSamplerReliability(report).capturedSamples)
        .put("rootSamplesExpected", rootSamplerReliability(report).expectedSamples)
        .put("rootSamplesMissed", rootSamplerReliability(report).missedSamples)
        .put("rootSamplerReliabilityPercent", rootSamplerReliability(report).reliabilityPercent)
        .put("rootSamplerMaxGapMs", rootSamplerReliability(report).maxGapMs)
        .put("rootSamplerVerdict", rootSamplerReliabilityVerdict(report))
        .put("rootSamplerWarning", rootSamplerWarning(report) ?: JSONObject.NULL)
        .put("rootSamples", parseSamplesJsonArray(report.rootSamplesJson))
        .put("sessionVerdict", buildSessionVerdict(report))
        .put("completedAtMs", report.completedAtMs)
}

private fun buildThermalAnalysisJson(report: GameSessionReport): JSONObject {
    val analysis = thermalAnalysis(report)

    return JSONObject()
        .put("minTempC", analysis.minTempC ?: JSONObject.NULL)
        .put("maxTempC", analysis.maxTempC ?: JSONObject.NULL)
        .put("deltaTempC", analysis.deltaTempC ?: JSONObject.NULL)
        .put("growthCPerMinute", analysis.growthCPerMinute ?: JSONObject.NULL)
        .put("verdict", thermalVerdict(report))
}

private fun buildRefreshAnalysisJson(report: GameSessionReport): JSONObject {
    val analysis = refreshAnalysis(report)

    return JSONObject()
        .put("minRefreshHz", analysis.minHz ?: JSONObject.NULL)
        .put("maxRefreshHz", analysis.maxHz ?: JSONObject.NULL)
        .put("avgRefreshHz", analysis.avgHz ?: JSONObject.NULL)
        .put("dominantRefreshHz", analysis.dominantHz ?: JSONObject.NULL)
        .put("verdict", refreshVerdict(report))
}

private fun parseSamplesJsonArray(raw: String?): JSONArray {
    if (raw.isNullOrBlank()) {
        return JSONArray()
    }

    return try {
        JSONArray(raw)
    } catch (_: Exception) {
        JSONArray()
    }
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
    lastReport: GameSessionReport?,
    rootState: RootDiagnosticsState
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

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = if (rootState.metricsEnabled) {
                "Root metrics: enabled"
            } else {
                "Root metrics: disabled, use Check Root manually"
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NxiRootDiagnosticsCard(
    state: RootDiagnosticsState,
    onCheckRoot: () -> Unit,
    onDisableRoot: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp)
    ) {
        Text(
            text = "ROOT DIAGNOSTICS",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Root is never requested during normal session start. Use Check Root explicitly to enable CPU metrics.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(modifier = Modifier.height(12.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            NxiReportPill("STATUS", if (state.metricsEnabled) "READY" else if (state.checked) "BLOCKED" else "NOT CHECKED")
            NxiReportPill("SU", if (state.suBinaryFound) "FOUND" else "--")
            NxiReportPill("EXEC", if (state.suExecutionAllowed) "ALLOWED" else "--")
            NxiReportPill("CPU", if (state.cpuPoliciesReadable) "READABLE" else "--")
            NxiReportPill("THERMAL", if (state.thermalZonesReadable) "READABLE" else "--")
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = state.statusMessage,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            NxiPrimaryButton(
                text = "Check Root",
                modifier = Modifier.weight(1f),
                enabled = true,
                onClick = onCheckRoot
            )

            NxiPrimaryButton(
                text = "Disable",
                modifier = Modifier.weight(1f),
                enabled = state.checked || state.metricsEnabled,
                onClick = onDisableRoot
            )
        }
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
            NxiReportPill("THERMAL", "${thermalVerdict(report)} / max ${formatTemp(thermalAnalysis(report).maxTempC)}")
            NxiReportPill("TEMP DELTA", formatSignedTempDelta(thermalAnalysis(report).deltaTempC))
            NxiReportPill("BATTERY", batteryCostVerdict(report))
            NxiReportPill("DRAIN", formatBatteryDrainForReport(report))
            NxiReportPill("COST", formatBatteryCost(report))
            NxiReportPill("ESTIMATE", formatEstimatedFullBattery(report))
            NxiReportPill("REFRESH", "${formatHz(refreshAnalysis(report).dominantHz)} / ${refreshVerdict(report)}")
            NxiReportPill("SAMPLER", "${samplerReliabilityVerdict(report)} / ${formatRate(samplerReliability(report).reliabilityPercent)}%")
            NxiReportPill("ROOT SAMPLER", "${rootSamplerReliabilityVerdict(report)} / ${formatRate(rootSamplerReliability(report).reliabilityPercent)}%")
            NxiReportPill("ROOT", formatRootState(report.endRootAvailable))
        }

        Spacer(modifier = Modifier.height(14.dp))

        NxiVerdictBlock(text = buildSessionVerdict(report))

        samplerWarning(report)?.let { warning ->
            Spacer(modifier = Modifier.height(12.dp))
            NxiCodeBlock(
                title = "SAMPLER WARNING",
                value = warning
            )
        }

        rootSamplerWarning(report)?.let { warning ->
            Spacer(modifier = Modifier.height(12.dp))
            NxiCodeBlock(
                title = "ROOT SAMPLER",
                value = warning
            )
        }

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
            text = "${thermalVerdict(report)} / ${formatBatteryCost(report)} / ${formatHz(refreshAnalysis(report).dominantHz)} / ${samplerReliabilityVerdict(report)} / root ${rootSamplerReliabilityVerdict(report)}",
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

private fun formatBatteryDrainForReport(report: GameSessionReport): String {
    batteryCostUnavailableReason(report)?.let { reason ->
        return reason
    }

    return formatBatteryDrain(report.batteryDrainPercent)
}

private fun formatBoolean(value: Boolean?): String {
    return when (value) {
        true -> "yes"
        false -> "no"
        null -> "--"
    }
}

private fun formatSignedTempDelta(value: Float?): String {
    return value?.let {
        val sign = if (it >= 0f) "+" else ""
        "$sign${formatRate(it)}°C"
    } ?: "--"
}

private fun formatTempGrowth(value: Float?): String {
    return value?.let { "${formatRate(it)}°C/min" } ?: "--"
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
        true -> "enabled"
        false -> "disabled"
        null -> "--"
    }
}
