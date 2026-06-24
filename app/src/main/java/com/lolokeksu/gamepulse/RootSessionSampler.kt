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
    private const val LOG_FILE = "$DIR/root_sampler.log"

    fun start(): Boolean {
        val command = """
            DIR='$DIR'
            SCRIPT_FILE='$SCRIPT_FILE'
            PID_FILE='$PID_FILE'
            STOP_FILE='$STOP_FILE'
            OUT_FILE='$OUT_FILE'
            LOG_FILE='$LOG_FILE'

            mkdir -p "${'$'}DIR"
            rm -f "${'$'}STOP_FILE" "${'$'}OUT_FILE" "${'$'}PID_FILE" "${'$'}LOG_FILE"

            cat > "${'$'}SCRIPT_FILE" <<'GPSH'
#!/system/bin/sh

DIR="/data/local/tmp/gamepulse_analyzer"
PID_FILE="${'$'}DIR/root_sampler.pid"
STOP_FILE="${'$'}DIR/root_sampler.stop"
OUT_FILE="${'$'}DIR/root_samples.jsonl"
LOG_FILE="${'$'}DIR/root_sampler.log"

echo "${'$'}$" > "${'$'}PID_FILE"
echo "started" > "${'$'}LOG_FILE"

safe_value() {
  tr -cd 'A-Za-z0-9:;._=+,-'
}

read_battery_temp_c() {
  raw=${'$'}(cat /sys/class/power_supply/battery/temp 2>/dev/null || echo "")
  case "${'$'}raw" in
    ''|*[!0-9-]*) echo "null"; return ;;
  esac

  awk -v t="${'$'}raw" 'BEGIN {
    if (t > 1000) {
      printf "%.1f", t / 1000
    } else {
      printf "%.1f", t / 10
    }
  }'
}

read_battery_percent() {
  value=${'$'}(cat /sys/class/power_supply/battery/capacity 2>/dev/null || echo "")
  case "${'$'}value" in
    ''|*[!0-9]*) echo "null" ;;
    *) echo "${'$'}value" ;;
  esac
}

read_battery_status() {
  cat /sys/class/power_supply/battery/status 2>/dev/null | safe_value
}

read_cpu_policies() {
  for p in /sys/devices/system/cpu/cpufreq/policy*; do
    [ -d "${'$'}p" ] || continue
    name=${'$'}(basename "${'$'}p")
    cur=${'$'}(cat "${'$'}p/scaling_cur_freq" 2>/dev/null || echo -)
    min=${'$'}(cat "${'$'}p/scaling_min_freq" 2>/dev/null || echo -)
    max=${'$'}(cat "${'$'}p/scaling_max_freq" 2>/dev/null || echo -)
    printf "%s:%s:%s:%s;" "${'$'}name" "${'$'}cur" "${'$'}min" "${'$'}max"
  done | safe_value
}

read_thermal_zones() {
  for z in /sys/class/thermal/thermal_zone*; do
    [ -d "${'$'}z" ] || continue
    type=${'$'}(cat "${'$'}z/type" 2>/dev/null | safe_value)
    temp=${'$'}(cat "${'$'}z/temp" 2>/dev/null || echo -)
    printf "%s:%s;" "${'$'}type" "${'$'}temp"
  done | safe_value
}

write_sample() {
  elapsed_ms="${'$'}1"

  temp_c=${'$'}(read_battery_temp_c)
  capacity=${'$'}(read_battery_percent)
  status=${'$'}(read_battery_status)

  [ -z "${'$'}status" ] && status="UNKNOWN"

  charging=false
  case "${'$'}status" in
    Charging|Full) charging=true ;;
  esac

  cpu_policies=${'$'}(read_cpu_policies)
  thermal_zones=${'$'}(read_thermal_zones)

  printf '{"elapsedMs":%s,"batteryTempC":%s,"batteryPercent":%s,"charging":%s,"batteryStatus":"%s","cpuPolicies":"%s","thermalZones":"%s"}\n' \
    "${'$'}elapsed_ms" \
    "${'$'}temp_c" \
    "${'$'}capacity" \
    "${'$'}charging" \
    "${'$'}status" \
    "${'$'}cpu_policies" \
    "${'$'}thermal_zones" >> "${'$'}OUT_FILE"
}

index=0

while [ ! -f "${'$'}STOP_FILE" ] && [ "${'$'}index" -lt 10800 ]; do
  elapsed_ms=${'$'}((index * 2000))

  write_sample "${'$'}elapsed_ms"

  index=${'$'}((index + 1))

  sleep 2
done

echo "stopped index=${'$'}index" >> "${'$'}LOG_FILE"
GPSH

            chmod 700 "${'$'}SCRIPT_FILE"

            if command -v setsid >/dev/null 2>&1; then
              setsid /system/bin/sh "${'$'}SCRIPT_FILE" >/dev/null 2>&1 &
            elif command -v nohup >/dev/null 2>&1; then
              nohup /system/bin/sh "${'$'}SCRIPT_FILE" >/dev/null 2>&1 &
            else
              /system/bin/sh "${'$'}SCRIPT_FILE" >/dev/null 2>&1 &
            fi

            tries=0
            while [ "${'$'}tries" -lt 10 ]; do
              if [ -s "${'$'}OUT_FILE" ]; then
                echo root_sampler_started
                exit 0
              fi

              tries=${'$'}((tries + 1))
              sleep 1
            done

            echo root_sampler_failed
            echo "status:"
            ls -la "${'$'}DIR" 2>/dev/null || true
            echo "log:"
            cat "${'$'}LOG_FILE" 2>/dev/null || true
        """.trimIndent()

        val output = runRootCommand(command, 15_000L)

        return output
            ?.lineSequence()
            ?.map { it.trim() }
            ?.any { it == "root_sampler_started" } == true
    }

    fun stopAndRead(): String {
        val command = """
            DIR='$DIR'
            SCRIPT_FILE='$SCRIPT_FILE'
            PID_FILE='$PID_FILE'
            STOP_FILE='$STOP_FILE'
            OUT_FILE='$OUT_FILE'
            LOG_FILE='$LOG_FILE'

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

        val raw = runRootCommand(command, 10_000L)
        return jsonLinesToArray(raw).toString()
    }

    fun debugStatus(): String {
        val command = """
            DIR='$DIR'
            PID_FILE='$PID_FILE'
            OUT_FILE='$OUT_FILE'
            LOG_FILE='$LOG_FILE'
            SCRIPT_FILE='$SCRIPT_FILE'

            echo "DIR:"
            ls -la "${'$'}DIR" 2>/dev/null || true

            echo "PID:"
            cat "${'$'}PID_FILE" 2>/dev/null || true

            echo "PS:"
            pid=${'$'}(cat "${'$'}PID_FILE" 2>/dev/null || true)
            [ -n "${'$'}pid" ] && ps -A 2>/dev/null | grep "${'$'}pid" || true

            echo "OUT_HEAD:"
            head -5 "${'$'}OUT_FILE" 2>/dev/null || true

            echo "LOG:"
            cat "${'$'}LOG_FILE" 2>/dev/null || true

            echo "SCRIPT_HEAD:"
            head -20 "${'$'}SCRIPT_FILE" 2>/dev/null || true
        """.trimIndent()

        return runRootCommand(command, 5_000L) ?: "debug failed"
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
