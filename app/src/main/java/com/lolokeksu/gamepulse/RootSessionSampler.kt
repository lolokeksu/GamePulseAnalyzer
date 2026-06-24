package com.lolokeksu.gamepulse

import java.util.TreeMap
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

            # Stop stale sampler before starting a new session.
            touch "${'$'}STOP_FILE"
            if [ -f "${'$'}PID_FILE" ]; then
              old_pid=${'$'}(cat "${'$'}PID_FILE" 2>/dev/null)
              [ -n "${'$'}old_pid" ] && kill "${'$'}old_pid" 2>/dev/null || true
            fi
            sleep 1

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

uptime_ms() {
  up=${'$'}(cat /proc/uptime 2>/dev/null | cut -d ' ' -f 1)
  sec=${'$'}{up%.*}
  frac=${'$'}{up#*.}
  ms=${'$'}(echo "${'$'}{frac}000" | cut -c 1-3)

  case "${'$'}sec" in
    ''|*[!0-9]*) sec=0 ;;
  esac

  case "${'$'}ms" in
    ''|*[!0-9]*) ms=0 ;;
  esac

  echo ${'$'}((sec * 1000 + ms))
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

read_zone_by_type() {
  target="${'$'}1"

  for z in /sys/class/thermal/thermal_zone*; do
    [ -d "${'$'}z" ] || continue

    type=${'$'}(cat "${'$'}z/type" 2>/dev/null || echo "")
    if [ "${'$'}type" = "${'$'}target" ]; then
      cat "${'$'}z/temp" 2>/dev/null | safe_value
      return
    fi
  done

  echo "-"
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

read_thermal_compact() {
  battery=${'$'}(read_zone_by_type battery)
  shell_front=${'$'}(read_zone_by_type shell_front)
  shell_back=${'$'}(read_zone_by_type shell_back)
  shell_frame=${'$'}(read_zone_by_type shell_frame)
  skin=${'$'}(read_zone_by_type skin-msm-therm)
  gpu0=${'$'}(read_zone_by_type gpuss-0)
  gpu1=${'$'}(read_zone_by_type gpuss-1)
  cpu0=${'$'}(read_zone_by_type cpuss-0)
  cpu1=${'$'}(read_zone_by_type cpuss-1)

  printf "battery:%s;shell_front:%s;shell_back:%s;shell_frame:%s;skin:%s;gpu0:%s;gpu1:%s;cpu0:%s;cpu1:%s;" \
    "${'$'}battery" \
    "${'$'}shell_front" \
    "${'$'}shell_back" \
    "${'$'}shell_frame" \
    "${'$'}skin" \
    "${'$'}gpu0" \
    "${'$'}gpu1" \
    "${'$'}cpu0" \
    "${'$'}cpu1" | safe_value
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
  thermal_compact=${'$'}(read_thermal_compact)

  printf '{"elapsedMs":%s,"batteryTempC":%s,"batteryPercent":%s,"charging":%s,"batteryStatus":"%s","cpuPolicies":"%s","thermalCompact":"%s"}\n' \
    "${'$'}elapsed_ms" \
    "${'$'}temp_c" \
    "${'$'}capacity" \
    "${'$'}charging" \
    "${'$'}status" \
    "${'$'}cpu_policies" \
    "${'$'}thermal_compact" >> "${'$'}OUT_FILE"
}

start_ms=${'$'}(uptime_ms)
index=0

while [ ! -f "${'$'}STOP_FILE" ] && [ "${'$'}index" -lt 10800 ]; do
  now_ms=${'$'}(uptime_ms)
  elapsed_ms=${'$'}((now_ms - start_ms))

  if [ "${'$'}elapsed_ms" -lt 0 ]; then
    elapsed_ms=${'$'}((index * 2000))
  fi

  write_sample "${'$'}elapsed_ms"

  index=${'$'}((index + 1))

  sleep 2
done

echo "stopped index=${'$'}index" >> "${'$'}LOG_FILE"
GPSH

            chmod 700 "${'$'}SCRIPT_FILE"

            if command -v setsid >/dev/null 2>&1; then
              setsid /system/bin/sh "${'$'}SCRIPT_FILE" >/dev/null 2>&1 < /dev/null &
            elif command -v nohup >/dev/null 2>&1; then
              nohup /system/bin/sh "${'$'}SCRIPT_FILE" >/dev/null 2>&1 < /dev/null &
            else
              /system/bin/sh "${'$'}SCRIPT_FILE" >/dev/null 2>&1 < /dev/null &
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

            mkdir -p "${'$'}DIR"
            touch "${'$'}STOP_FILE"

            if [ -f "${'$'}PID_FILE" ]; then
              pid=${'$'}(cat "${'$'}PID_FILE" 2>/dev/null)
              if [ -n "${'$'}pid" ]; then
                kill "${'$'}pid" 2>/dev/null || true
              fi
            fi

            sleep 1

            # Compact payload, safe to read fully.
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

            echo "OUT_LINES:"
            wc -l "${'$'}OUT_FILE" 2>/dev/null || true

            echo "OUT_HEAD:"
            head -5 "${'$'}OUT_FILE" 2>/dev/null || true

            echo "LOG:"
            cat "${'$'}LOG_FILE" 2>/dev/null || true
        """.trimIndent()

        return runRootCommand(command, 5_000L) ?: "debug failed"
    }

    private fun jsonLinesToArray(raw: String?): JSONArray {
        val byElapsed = TreeMap<Long, JSONObject>()
        val noElapsed = mutableListOf<JSONObject>()

        if (raw.isNullOrBlank()) {
            return JSONArray()
        }

        raw.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("{") && it.endsWith("}") }
            .forEach { line ->
                try {
                    val item = JSONObject(line)
                    val elapsed = item.optLong("elapsedMs", Long.MIN_VALUE)

                    if (elapsed >= 0L) {
                        byElapsed[elapsed] = item
                    } else {
                        noElapsed.add(item)
                    }
                } catch (_: Exception) {
                }
            }

        val result = JSONArray()
        byElapsed.values.forEach { result.put(it) }
        noElapsed.forEach { result.put(it) }

        return result
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
