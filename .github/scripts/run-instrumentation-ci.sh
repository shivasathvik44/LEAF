#!/usr/bin/env bash
set -euo pipefail

RESULTS_DIR="app/build/outputs/androidTest-results"
DIAG_DIR="$RESULTS_DIR/ci-watchdog-diagnostics"
STALL_TIMEOUT_SECONDS="${LEAF_CI_STALL_TIMEOUT_SECONDS:-240}"
GRADLE_TIMEOUT_SECONDS="${LEAF_CI_GRADLE_TIMEOUT_SECONDS:-1680}"

set +e
for _ in $(seq 1 30); do
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 2
done
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell input keyevent 82 || true
adb shell settings put secure immersive_mode_confirmations confirmed || true
adb shell settings put secure anr_show_background 0 || true
adb shell settings put secure show_ime_with_hard_keyboard 0 || true
adb shell dumpsys window policy 2>/dev/null | grep -E "mShowingLockscreen|isStatusBarKeyguard|screenState" | head -n 5 || true
set -e

rm -rf "$DIAG_DIR"
if [ -d "$RESULTS_DIR" ]; then
  find "$RESULTS_DIR" -name "test-results.log" -delete 2>/dev/null || true
fi

GRADLE_PID=""
cleanup_gradle() {
  if [ -n "$GRADLE_PID" ] && kill -0 "$GRADLE_PID" 2>/dev/null; then
    pkill -TERM -P "$GRADLE_PID" 2>/dev/null || true
    kill -TERM "$GRADLE_PID" 2>/dev/null || true
    for _ in $(seq 1 10); do
      kill -0 "$GRADLE_PID" 2>/dev/null || break
      sleep 1
    done
    if kill -0 "$GRADLE_PID" 2>/dev/null; then
      pkill -KILL -P "$GRADLE_PID" 2>/dev/null || true
      kill -KILL "$GRADLE_PID" 2>/dev/null || true
    fi
    wait "$GRADLE_PID" 2>/dev/null || true
  fi
  GRADLE_PID=""
}
trap cleanup_gradle EXIT

capture_diagnostics() {
  local reason="$1"
  set +e
  mkdir -p "$DIAG_DIR"
  local ps_out pids latest_logcat
  ps_out=$(timeout 10 adb shell 'ps -A 2>/dev/null || ps' < /dev/null 2>/dev/null | tr -d '\r' || true)
  printf "%s\n" "$ps_out" > "$DIAG_DIR/ps.txt"
  pids=$(printf "%s\n" "$ps_out" | awk '/com\.leaf\.reader|androidx\.test\.orchestrator/ {print $2}' | tr '\n' ' ')
  if [ -n "$pids" ]; then
    echo "=== Signalling stalled PIDs ($reason): $pids ==="
    for pid in $pids; do
      timeout 5 adb shell kill -3 "$pid" < /dev/null 2>/dev/null || true
    done
    sleep 1
    timeout 10 adb shell 'cat /data/anr/traces.txt 2>/dev/null | tail -n 200' < /dev/null > "$DIAG_DIR/anr-traces.txt" 2>/dev/null || true
  fi

  timeout 15 adb shell dumpsys meminfo com.leaf.reader < /dev/null > "$DIAG_DIR/meminfo.txt" 2>/dev/null || true
  timeout 15 adb shell dumpsys window windows < /dev/null > "$DIAG_DIR/window-windows.txt" 2>/dev/null || true
  timeout 15 adb shell dumpsys activity top < /dev/null > "$DIAG_DIR/activity-top.txt" 2>/dev/null || true
  timeout 15 adb shell dumpsys input_method < /dev/null > "$DIAG_DIR/input-method.txt" 2>/dev/null || true
  timeout 15 adb logcat -d -v threadtime -t 400 < /dev/null > "$DIAG_DIR/logcat-recent.txt" 2>/dev/null || true

  if [ -d "$RESULTS_DIR" ]; then
    find "$RESULTS_DIR" -name "test-results.log" \
      -exec sh -c 'echo "=== Tail of $1 ==="; tail -n 80 "$1"' _ {} \; || true
    latest_logcat=$(find "$RESULTS_DIR" -name "logcat-*.txt" -printf "%T@ %p\n" 2>/dev/null \
      | sort -n | tail -n 1 | cut -d" " -f2- || true)
    if [ -n "$latest_logcat" ] && [ -f "$latest_logcat" ]; then
      echo "=== Tail of latest test logcat: $latest_logcat ==="
      tail -n 120 "$latest_logcat" || true
    fi
  fi
  echo "=== Meminfo summary ($reason) ==="
  head -n 45 "$DIAG_DIR/meminfo.txt" || true
  echo "=== Focused window ($reason) ==="
  grep -E "Window #|mCurrentFocus|mFocusedApp" "$DIAG_DIR/window-windows.txt" || true
  echo "=== Top activity ($reason) ==="
  head -n 60 "$DIAG_DIR/activity-top.txt" || true
  echo "=== Recent device logcat ($reason) ==="
  tail -n 150 "$DIAG_DIR/logcat-recent.txt" || true
}

flush_progress() {
  local log_file="$1"
  local cur_printed="$2"
  awk -F= '
    /^INSTRUMENTATION_STATUS: test=/ { t=$2 }
    /^INSTRUMENTATION_STATUS: class=/ { c=$2 }
    /^INSTRUMENTATION_STATUS: current=/ { n=$2 }
    /^INSTRUMENTATION_STATUS: numtests=/ { m=$2 }
    /^INSTRUMENTATION_STATUS_CODE: / {
      code=$0; sub(/^INSTRUMENTATION_STATUS_CODE: /, "", code)
      gsub(/\r/, "", t); gsub(/\r/, "", c); gsub(/\r/, "", n); gsub(/\r/, "", m); gsub(/\r/, "", code)
      if (c != "" && t != "") {
        if (n == "") n = "?"
        if (m == "") m = "?"
        if (code == "1") printf "[%s/%s] START %s#%s\n", n, m, c, t
        else if (code == "0") printf "[%s/%s] PASS  %s#%s\n", n, m, c, t
        else printf "[%s/%s] FAIL(%s) %s#%s\n", n, m, code, c, t
      }
      t=""; c=""; n=""; m=""
    }
  ' "$log_file" | tail -n +"$((cur_printed + 1))"
}

run_gradle() {
  exec ./gradlew --no-daemon --stacktrace \
    -Pandroid.testInstrumentationRunnerArguments.timeout_msec=120000 \
    -Pandroid.testInstrumentationRunnerArguments.clearPackageData=true \
    :app:connectedDebugAndroidTest < /dev/null
}

start_ts=$(date +%s)
last_progress_ts="$start_ts"
last_bytes=0
printed=0
saw_log=0

if [ "${LEAF_CI_EXEC_CMD:-}" != "" ]; then
  bash -c "$LEAF_CI_EXEC_CMD" < /dev/null &
else
  run_gradle &
fi
GRADLE_PID=$!

set +e
while kill -0 "$GRADLE_PID" 2>/dev/null; do
  now=$(date +%s)
  elapsed=$((now - start_ts))
  log_file=$(find "$RESULTS_DIR" -name "test-results.log" 2>/dev/null | head -n 1 || true)
  if [ -n "$log_file" ] && [ -f "$log_file" ]; then
    if [ "$saw_log" -eq 0 ]; then
      saw_log=1
      last_progress_ts="$now"
    fi
    cur_bytes=$(wc -c < "$log_file" 2>/dev/null | tr -d ' ' || echo 0)
    if [ "$cur_bytes" -lt "$last_bytes" ]; then
      printed=0
      last_progress_ts="$now"
    fi
    if [ "$cur_bytes" -ne "$last_bytes" ]; then
      last_bytes="$cur_bytes"
      last_progress_ts="$now"
    fi
    out=$(flush_progress "$log_file" "$printed")
    if [ -n "$out" ]; then
      printf "%s\n" "$out"
      add=$(printf "%s\n" "$out" | wc -l)
      printed=$((printed + add))
      last_progress_ts="$now"
    fi
  fi

  if [ -n "$log_file" ] && [ -f "$log_file" ] && [ "$((now - last_progress_ts))" -ge "$STALL_TIMEOUT_SECONDS" ]; then
    echo "::error::connectedDebugAndroidTest stalled with no test-results.log activity for ${STALL_TIMEOUT_SECONDS}s"
    capture_diagnostics "watchdog-stall"
    cleanup_gradle
    exit 124
  fi

  if [ "$elapsed" -ge "$GRADLE_TIMEOUT_SECONDS" ]; then
    echo "::error::connectedDebugAndroidTest exceeded ${GRADLE_TIMEOUT_SECONDS}s wall-clock ceiling"
    capture_diagnostics "wallclock-timeout"
    cleanup_gradle
    exit 124
  fi
  sleep 5
done

status=0
wait "$GRADLE_PID" || status=$?
GRADLE_PID=""

log_file=$(find "$RESULTS_DIR" -name "test-results.log" 2>/dev/null | head -n 1 || true)
if [ -n "$log_file" ] && [ -f "$log_file" ]; then
  out=$(flush_progress "$log_file" "$printed")
  if [ -n "$out" ]; then
    printf "%s\n" "$out"
  fi
fi

if [ "$status" -ne 0 ]; then
  echo "::error::connectedDebugAndroidTest failed or timed out with exit code $status"
  capture_diagnostics "gradle-failure"
  exit "$status"
fi
