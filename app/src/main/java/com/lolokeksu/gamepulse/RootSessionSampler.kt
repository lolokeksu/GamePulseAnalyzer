package com.lolokeksu.gamepulse

import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

object RootSessionSampler {
    private const val DIR = "/data/local/tmp/gamepulse_analyzer"
    private const val PID_FILE = "$DIR/root_sampler.pid"
    private const val STOP_FILE = "$DIR/root_sampler.stop"
    private const val OUT_FILE = "$DIR/root_samples.jsonl"

    fun start(): Boolean {
        val command = """
            DIR='$DIR'
            PID_FILE='$PID_FILE'
            STOP_FILE='$STOP_FILE'
            OUT_FILE='$OUT_FILE'

            mkdir -p "${'$'}DIR"
            rm -f "${'$'}STOP_FILE" "${'$'}OUT_FILE"

            (
              now_ms() {
                awk '{printf "%d", ${'$'}1 * 1000}' /proc/uptime 2>/dev/null || echo 0
              }

              json_string_safe() {
                tr '"' '_' | tr '\\' '_' | tr '\n' ' '
              }

              start_ms=${'$'}(now_ms)
              index=0

              while [ ! -f "${'$'}STOP_FILE" ] && [ "${'$'}index" -lt 10800 ]; do
                current_ms=${'$'}(now_ms)
                elapsed_ms=${'$'}((current_ms - start_ms))

                temp_raw=${'$'}(cat /sys/class/power_supply/battery/temp 2>/dev/null || echo "")
                temp_c=${'$'}(
                  awk -v t="${'$'}temp_raw" 'BEGIN {
                    if (t == "" || t == "-") {
                      printf "null"
                    } else if (t > 1000) {
                      printf "%.1f", t / 1000
                    } else {
                      printf "%.1f", t / 10
                    }
                  }'
                )

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
                  done | json_string_safe
                )

                thermal_zones=${'$'}(
                  for z in /sys/class/thermal/thermal_zone*; do
                    [ -d "${'$'}z" ] || continue
                    type=${'$'}(cat "${'$'}z/type" 2>/dev/null || echo unknown)
                    temp=${'$'}(cat "${'$'}z/temp" 2>/dev/null || echo -)
                    printf "%s:%s;" "${'$'}type" "${'$'}temp"
                  done | json_string_safe
                )

                printf '{"elapsedMs":%s,"batteryTempC":%s,"batteryPercent":%s,"charging":%s,"batteryStatus":"%s","cpuPolicies":"%s","thermalZones":"%s"}\n' \
                  "${'$'}elapsed_ms" \
                  "${'$'}temp_c" \
                  "${'$'}capacity" \
                  "${'$'}charging" \
                  "${'$'}status" \
                  "${'$'}cpu_policies" \
                  "${'$'}thermal_zones" >> "${'$'}OUT_FILE"

                index=${'$'}((index + 1))
                sleep 2
              done
            ) >/dev/null 2>&1 &

            echo ${'$'}! > "${'$'}PID_FILE"
            echo root_sampler_started
        """.trimIndent()

        val output = runRootCommand(command, 4_000L)
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
              [ -n "${'$'}pid" ] && kill "${'$'}pid" 2>/dev/null || true
            fi

            sleep 1

            cat "${'$'}OUT_FILE" 2>/dev/null || true

            rm -f "${'$'}PID_FILE" "${'$'}STOP_FILE"
        """.trimIndent()

        val raw = runRootCommand(command, 4_000L)
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
