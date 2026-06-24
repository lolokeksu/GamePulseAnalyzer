package com.lolokeksu.gamepulse

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.SystemClock
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
    val refreshRateHz: Float?,
    val completedAtMs: Long
)

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
                    title = "LAST",
                    value = lastReport?.let { formatDurationShort(it.durationMs) } ?: "--",
                    caption = "session"
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
                NxiReportCard(report = report)
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

    val prefs = context.getSharedPreferences("gamepulse_sessions", Context.MODE_PRIVATE)

    prefs.edit()
        .putBoolean("active", true)
        .putString("game_label", game.label)
        .putString("package_name", game.packageName)
        .putLong("start_elapsed_ms", SystemClock.elapsedRealtime())
        .putFloat("start_battery_temp_c", readBatteryTemperatureC(context) ?: -1f)
        .putFloat("refresh_rate_hz", readRefreshRateHz(context) ?: -1f)
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

    val report = GameSessionReport(
        gameLabel = prefs.getString("game_label", "Unknown game") ?: "Unknown game",
        packageName = prefs.getString("package_name", "unknown.package") ?: "unknown.package",
        durationMs = durationMs,
        startBatteryTempC = prefs.getFloat("start_battery_temp_c", -1f).takeIf { it >= 0f },
        endBatteryTempC = readBatteryTemperatureC(context),
        refreshRateHz = prefs.getFloat("refresh_rate_hz", -1f).takeIf { it >= 0f },
        completedAtMs = System.currentTimeMillis()
    )

    prefs.edit()
        .putBoolean("active", false)
        .putString("last_game_label", report.gameLabel)
        .putString("last_package_name", report.packageName)
        .putLong("last_duration_ms", report.durationMs)
        .putFloat("last_start_battery_temp_c", report.startBatteryTempC ?: -1f)
        .putFloat("last_end_battery_temp_c", report.endBatteryTempC ?: -1f)
        .putFloat("last_refresh_rate_hz", report.refreshRateHz ?: -1f)
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
        refreshRateHz = prefs.getFloat("last_refresh_rate_hz", -1f).takeIf { it >= 0f },
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
                .put("refreshRateHz", item.refreshRateHz ?: JSONObject.NULL)
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
                        refreshRateHz = item.optNullableFloat("refreshRateHz"),
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


private fun readBatteryTemperatureC(context: Context): Float? {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?: return null

    val rawTemp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
    if (rawTemp == Int.MIN_VALUE) {
        return null
    }

    return rawTemp / 10f
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
            NxiChip("GAME SCAN")
            NxiChip("SESSION")
            NxiChip("REPORT")
            NxiChip("MANUAL ADD")
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
                text = "Last report: ${formatDurationShort(lastReport.durationMs)}",
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
private fun NxiReportCard(report: GameSessionReport) {
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
            NxiReportPill("START TEMP", formatTemp(report.startBatteryTempC))
            NxiReportPill("END TEMP", formatTemp(report.endBatteryTempC))
            NxiReportPill("REFRESH", formatHz(report.refreshRateHz))
        }
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
            text = "${formatTemp(report.startBatteryTempC)} → ${formatTemp(report.endBatteryTempC)}  /  ${formatHz(report.refreshRateHz)}",
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
