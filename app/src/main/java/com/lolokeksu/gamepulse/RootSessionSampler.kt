package com.lolokeksu.gamepulse

import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

object RootSessionSampler {
    private const val DIR = "/data/local/tmp/gamepulse_analyzer"
    private const val SCRIPT_FILE = "$DIR/root_sampler.sh"
    private const val PID_FILE = "$DIR/root_sampler.pid"
    private const val STOP_FILE = "$DIR/root_sampler.stop"
    private const val OUT_FILE = "$DIR/root_samples.jsonl"

    fun start(): Boolean {
        val command = """
            DIR='$DIR'
            SCRIPT_FILE='$SCRIPT_FILE'
            PID_FILE='$PID_FILE'
            STOP_FILE='$STOP_FILE'
            OUT_FILE='$OUT_FILE'

            mkdir -p "${'$'}DIR"
            rm -f "${'$'}STOP_FILE" "${'$'}OUT_FILE" "${'$'}PID_FILE"

            cat > "${'$'}SCRIPT_FILE" <<'GPSH'
#!/system/bin/sh

DIR="/data/local/tmp/gamepulse_analyzer"
PID_FILE="${'$'}DIR/root_sampler.pid"
STOP_FILE="${'$'}DIR/root_sampler.stop"
OUT_FILE="${'$'}DIR/root_samples.jsonl"

trap '' HUP

mkdir -p "${'$'}DIR"

sanitize_line() {
  tr -cd 'A-Za-z0-9:;._=+,-'
}

index=0

while [ ! -f "${'$'}STOP_FILE" ] && [ "${'$'}index" -lt 10800 ]; do
  elapsed_ms=${'$'}((index * 2000))

  temp_raw=${'$'}(cat /sys/class/power_supply/battery/temp 2>/dev/null || echo "")
  case "${'$'}temp_raw" in
    ''|*[!0-9-]*) temp_raw=null ;;
  esac

  capacity=${'$'}(cat /sys/class/power_supply/battery/capacity 2>/dev/null || echo "")
  case "${'$'}capacity" in
    ''|*[!0-9]*) capacity=null ;;
  esac

  status=${'$'}(cat /sys/class/power_supply/battery/status 2>/dev/null || echo UNKNOWN)
  charging=false
  case "${'$'}status" in
    Charging|Full) charging=true ;;
  esac

  cpu_policies=${'$'}(
    for p in /sys/devices/system/cpu/cpufreq/policy*; do
      [ -d "${'$'}p" ] || continue
      name=${'$'}(basename "${'$'}p")
      cur=${'$'}(cat "${'$'}p/scaling_cur_freq" 2>/dev/null || echo -)
      min=${'$'}(cat "${'$'}p/scaling_min_freq" 2>/dev/null || echo -)
      max=${'$'}(cat "${'$'}p/scaling_max_freq" 2>/dev/null || echo -)
      printf "%s:%s:%s:%s;" "${'$'}name" "${'$'}cur" "${'$'}min" "${'$'}max"
    done | sanitize_line
  )

  thermal_zones=${'$'}(
    for z in /sys/class/thermal/thermal_zone*; do
      [ -d "${'$'}z" ] || continue
      type=${'$'}(cat "${'$'}z/type" 2>/dev/null || echo unknown | sanitize_line)
      temp=${'$'}(cat "${'$'}z/temp" 2>/dev/null || echo -)
      printf "%s:%s;" "${'$'}type" "${'$'}temp"
    done | sanitize_line
  )

  printf '{"elapsedMs":%s,"batteryTempRaw":%s,"batteryPercent":%s,"charging":%s,"batteryStatus":"%s","cpuPolicies":"%s","thermalZones":"%s"}\n' \
    "${'$'}elapsed_ms" \
    "${'$'}temp_raw" \
    "${'$'}capacity" \
    "${'$'}charging" \
    "${'$'}status" \
    "${'$'}cpu_policies" \
    "${'$'}thermal_zones" >> "${'$'}OUT_FILE"

  sync "${'$'}OUT_FILE" 2>/dev/null || true

  index=${'$'}((index + 1))
  sleep 2
done
GPSH

            chmod 700 "${'$'}SCRIPT_FILE"

            if command -v nohup >/dev/null 2>&1; then
              nohup /system/bin/sh "${'$'}SCRIPT_FILE" >/dev/null 2>&1 &
            elif command -v setsid >/dev/null 2>&1; then
              setsid /system/bin/sh "${'$'}SCRIPT_FILE" >/dev/null 2>&1 &
            else
              /system/bin/sh "${'$'}SCRIPT_FILE" >/dev/null 2>&1 &
            fi

            echo ${'$'}! > "${'$'}PID_FILE"

            sleep 1

            if [ -s "${'$'}OUT_FILE" ]; then
              echo root_sampler_started
            else
              echo root_sampler_started_pending
            fi
        """.trimIndent()

        val output = runRootCommand(command, 5_000L)
        return output?.contains("root_sampler_started") == true
    }

    fun stopAndRead(): String {
        val command = """
            DIR='$DIR'
            PID_FILE='$PID_FILE'
            STOP_FILE='$STOP_FILE'
            OUT_FILE='$OUT_FILE'

            mkdir -p "${'$'}DIR"
            touch "${'$'}STOP_FILE"

            if [ -f "${'$'}PID_FILE" ]; then
              pid=${'$'}(cat "${'$'}PID_FILE" 2>/dev/null)
              if [ -n "${'$'}pid" ]; then
                kill "${'$'}pid" 2>/dev/null || true
              fi
            fi

            sleep 1

            cat "${'$'}OUT_FILE" 2>/dev/null || true

            rm -f "${'$'}PID_FILE" "${'$'}STOP_FILE" "${'$'}SCRIPT_FILE"
        """.trimIndent()

        val raw = runRootCommand(command, 6_000L)
        return jsonLinesToArray(raw).toString()
    }

    private fun jsonLinesToArray(raw: String?): JSONArray {
        val array = JSONArray()

        if (raw.isNullOrBlank()) {
            return array
        }

        raw.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("{") && it.endsWith("}") }
            .forEach { line ->
                try {
                    array.put(JSONObject(line))
                } catch (_: Exception) {
                }
            }

        return array
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
}
