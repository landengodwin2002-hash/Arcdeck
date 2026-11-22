#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p test-results
apk=app/build/outputs/apk/debug/app-debug.apk
tests=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
model="${PROMETHEUS_TEST_MODEL:?Set PROMETHEUS_TEST_MODEL to the existing 4B GGUF}"
adb install -r "$apk"
adb install -r "$tests"
# Exercise the real Termux maintenance protocol, not a file injection shortcut.
adb reverse tcp:18765 tcp:18765
transfer() {
    adb shell input keyevent KEYCODE_HOME
    timeout 600 python3 termux/prometheus-sync.py "$@" > "test-results/transfer-$1.txt" 2>&1 &
    sender=$!
    python3 - <<'WAIT'
import json,time,urllib.request
for _ in range(300):
    try:
        with urllib.request.urlopen('http://127.0.0.1:18765/manifest',timeout=1) as response:
            if json.load(response).get('protocol')=='prometheus-local-transfer-v1':break
    except Exception:time.sleep(1)
else:raise SystemExit('Termux transfer sender did not become ready')
WAIT
    adb shell am start -W -n ai.prometheus.offline.nova/ai.prometheus.android.MainActivity
    while kill -0 "$sender" 2>/dev/null; do
        if ! adb shell pidof ai.prometheus.offline.nova >/dev/null; then
            echo 'Nova exited during model maintenance; inspect captured native and Android logs.' >&2
            kill "$sender" 2>/dev/null || true
            wait "$sender" || true
            return 1
        fi
        sleep 1
    done
    wait "$sender"
}
capture_diagnostics() {
    local status=$?
    adb logcat -d > test-results/logcat.txt || true
    adb exec-out run-as ai.prometheus.offline.nova cat files/llama-runtime.log > test-results/native-runtime.txt || true
    adb shell dumpsys meminfo ai.prometheus.offline.nova > test-results/memory.txt || true
    adb reverse --remove tcp:18765 >/dev/null 2>&1 || true
    return "$status"
}
trap capture_diagnostics EXIT
transfer --model-only --model "$model"
python3 - <<'BOOK'
from pathlib import Path
Path('test-results/maintenance-original.txt').write_text('Prometheus maintenance fixture: original book bytes are preserved. '*12)
BOOK
transfer --books-only --include "$PWD/test-results/maintenance-original.txt"
transfer --probe
adb reverse --remove tcp:18765
adb shell settings put global airplane_mode_on 1
adb shell svc wifi disable
adb shell svc data disable
adb shell settings put secure show_ime_with_hard_keyboard 1
adb shell cat /proc/cpuinfo > test-results/cpuinfo.txt
adb shell cat /proc/meminfo > test-results/system-memory.txt
adb logcat -c
adb shell am instrument -w -r ai.prometheus.offline.nova.test/androidx.test.runner.AndroidJUnitRunner | tee test-results/instrumentation.txt
adb logcat -d > test-results/logcat.txt
grep -q 'OK (1 test)' test-results/instrumentation.txt
grep -q 'REAL_MODEL_STREAM_BACKGROUND_RECREATE_FOLLOWUP_STOP_PASS' test-results/logcat.txt
# A full process restart must still expose the committed conversation and original data.
adb shell am force-stop ai.prometheus.offline.nova
adb shell am start -W -n ai.prometheus.offline.nova/ai.prometheus.android.MainActivity
adb shell am force-stop ai.prometheus.offline.nova
adb exec-out run-as ai.prometheus.offline.nova cat databases/prometheus-native.db > test-results/restarted.db
adb exec-out run-as ai.prometheus.offline.nova cat databases/prometheus-native.db-wal > test-results/restarted.db-wal || true
adb shell am force-stop ai.prometheus.offline.nova
python3 - <<'PY'
import sqlite3
db=sqlite3.connect('test-results/restarted.db')
assert db.execute("select count(*) from messages where role='assistant'").fetchone()[0]>=3
assert db.execute("select count(*) from book_files where sha256='test-upgrade-sentinel'").fetchone()[0]==1
assert db.execute("select count(*) from memory where kind='test' and body='migration sentinel'").fetchone()[0]>=1
print('PROCESS_RESTART_DATABASE_PASS')
PY
