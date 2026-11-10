#!/usr/bin/env bash
set -euo pipefail

set +e
for _ in $(seq 1 30); do
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 2
done
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell input keyevent 82 || true
adb shell settings put secure immersive_mode_confirmations confirmed || true
adb shell settings put secure anr_show_background 0 || true
adb shell dumpsys window policy 2>/dev/null | grep -E "mShowingLockscreen|isStatusBarKeyguard|screenState" | head -n 5 || true
set -e

PROGRESS_PID=""
cleanup_progress() {
  if [ -n "$PROGRESS_PID" ]; then
    kill "$PROGRESS_PID" 2>/dev/null || true
    wait "$PROGRESS_PID" 2>/dev/null || true
    PROGRESS_PID=""
  fi
}
trap cleanup_progress EXIT

(
  set +e
  printed=0
  while true; do
    log_file=$(find app/build/outputs/androidTest-results -name "test-results.log" 2>/dev/null | head -n 1 || true)
    if [ -n "$log_file" ] && [ -f "$log_file" ]; then
      out=$(awk -F= "
        /^INSTRUMENTATION_STATUS: test=/ { t=\$2 }
        /^INSTRUMENTATION_STATUS: class=/ { c=\$2 }
        /^INSTRUMENTATION_STATUS: current=/ { n=\$2 }
        /^INSTRUMENTATION_STATUS: numtests=/ { m=\$2 }
        /^INSTRUMENTATION_STATUS_CODE: / {
          code=\$0; sub(/^INSTRUMENTATION_STATUS_CODE: /, \"\", code)
          gsub(/\r/, \"\", t); gsub(/\r/, \"\", c); gsub(/\r/, \"\", n); gsub(/\r/, \"\", m); gsub(/\r/, \"\", code)
          if (code == \"1\") printf \"[%s/%s] START %s#%s\n\", n, m, c, t
          else if (code == \"0\") printf \"[%s/%s] PASS  %s#%s\n\", n, m, c, t
          else printf \"[%s/%s] FAIL(%s) %s#%s\n\", n, m, code, c, t
        }
      " "$log_file" | tail -n +"$((printed + 1))")
      if [ -n "$out" ]; then
        printf "%s\n" "$out"
        add=$(printf "%s\n" "$out" | wc -l)
        printed=$((printed + add))
      fi
    fi
    sleep 10
  done
) < /dev/null &
PROGRESS_PID=$!

status=0
timeout -k 30s 22m ./gradlew --no-daemon --stacktrace \
  -Pandroid.testInstrumentationRunnerArguments.timeout_msec=180000 \
  :app:connectedDebugAndroidTest < /dev/null || status=$?

cleanup_progress
if [ "$status" -ne 0 ]; then
  echo "::error::connectedDebugAndroidTest failed or timed out with exit code $status"
  if [ -d app/build/outputs/androidTest-results ]; then
    find app/build/outputs/androidTest-results -name "test-results.log" \
      -exec sh -c "echo \"=== Tail of \$1 ===\"; tail -n 80 \"\$1\"" _ {} \; || true
    latest_logcat=$(find app/build/outputs/androidTest-results -name "logcat-*.txt" -printf "%T@ %p\n" 2>/dev/null \
      | sort -n | tail -n 1 | cut -d" " -f2- || true)
    if [ -n "$latest_logcat" ] && [ -f "$latest_logcat" ]; then
      echo "=== Tail of latest test logcat: $latest_logcat ==="
      tail -n 120 "$latest_logcat" || true
    fi
  fi
  echo "=== Focused window ==="
  timeout 15 adb shell dumpsys window windows < /dev/null 2>/dev/null | grep -E "Window #|mCurrentFocus|mFocusedApp" || true
  echo "=== Top activity ==="
  timeout 15 adb shell dumpsys activity top < /dev/null 2>/dev/null | head -n 60 || true
  echo "=== Recent device logcat ==="
  timeout 15 adb logcat -d -t 150 < /dev/null || true
  exit "$status"
fi
