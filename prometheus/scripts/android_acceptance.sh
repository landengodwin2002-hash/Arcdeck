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
python3 termux/prometheus-sync.py --model-only --model "$model" > test-results/model-install.txt 2>&1 &
sender=$!
adb shell am start -W -n ai.prometheus.offline.nova/ai.prometheus.android.MainActivity
wait "$sender"
adb reverse --remove tcp:18765
adb shell settings put global airplane_mode_on 1
adb shell svc wifi disable
adb shell svc data disable
adb logcat -c
adb shell am instrument -w -r ai.prometheus.offline.nova.test/androidx.test.runner.AndroidJUnitRunner | tee test-results/instrumentation.txt
adb logcat -d > test-results/logcat.txt
grep -q 'OK (1 test)' test-results/instrumentation.txt
grep -q 'REAL_MODEL_STREAM_BACKGROUND_RECREATE_FOLLOWUP_STOP_PASS' test-results/logcat.txt
# A full process restart must still expose the committed conversation and original data.
adb shell am force-stop ai.prometheus.offline.nova
adb shell am start -W -n ai.prometheus.offline.nova/ai.prometheus.android.MainActivity
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
