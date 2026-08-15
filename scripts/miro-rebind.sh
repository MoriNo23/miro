#!/usr/bin/env bash
# miro-rebind.sh — auto-rebind miro AccessibilityService after tablet reboot
#
# Runs on the host PC. Watches the tablet via ADB. When the tablet reboots
# (ADB device disconnects), waits for it to come back, then launches the
# miro launcher activity which forces the a11y service re-bind.
#
# Usage:
#   miro-rebind.sh <device-serial>
#   miro-rebind.sh 6c000c6d480109622dd   # USB
#   miro-rebind.sh 10.42.1.63:5555       # WiFi (if IP stable)
#
# Requirements: adb, jq (optional), USB or WiFi ADB connection to tablet.

set -euo pipefail

DEV="${1:-}"
if [ -z "$DEV" ]; then
    echo "Usage: $0 <device-serial>"
    echo "Example: $0 6c000c6d480109622dd"
    exit 1
fi

VERSION="1.0.0"
echo "miro-rebind v$VERSION — watching $DEV"
echo "Press Ctrl+C to stop"

check_and_rebind() {
    # Check if miro is bound
    local bound
    bound=$(adb -s "$DEV" shell dumpsys accessibility 2>/dev/null | grep "Bound services" | grep -c "miro" || true)

    if [ "$bound" -eq 0 ]; then
        echo "[$(date '+%H:%M:%S')] miro not bound — launching activity to re-bind..."

        # Make sure app is not stopped
        adb -s "$DEV" shell am start -n com.miro.a11y/.MiroLauncherActivity 2>/dev/null || true
        sleep 6

        # Verify
        bound=$(adb -s "$DEV" shell dumpsys accessibility 2>/dev/null | grep "Bound services" | grep -c "miro" || true)
        if [ "$bound" -gt 0 ]; then
            echo "[$(date '+%H:%M:%S')] miro BOUND — service connected"
            return 0
        else
            echo "[$(date '+%H:%M:%S')] re-bind failed — retrying in 10s..."
            return 1
        fi
    else
        echo "[$(date '+%H:%M:%S')] miro already bound"
        return 0
    fi
}

while true; do
    # Check if device is online
    if adb -s "$DEV" shell getprop sys.boot_completed 2>/dev/null | grep -q 1; then
        check_and_rebind && {
            # After successful bind, poll every 60s to detect if service drops
            while true; do
                sleep 60
                if ! adb -s "$DEV" shell echo ok 2>/dev/null | grep -q ok; then
                    echo "[$(date '+%H:%M:%S')] device disconnected — waiting for reboot..."
                    break
                fi
                bound=$(adb -s "$DEV" shell dumpsys accessibility 2>/dev/null | grep "Bound services" | grep -c "miro" || true)
                if [ "$bound" -eq 0 ]; then
                    echo "[$(date '+%H:%M:%S')] service dropped during runtime — re-binding..."
                    adb -s "$DEV" shell am start -n com.miro.a11y/.MiroLauncherActivity 2>/dev/null || true
                    sleep 6
                fi
            done
        }
    fi

    # Device not ready — wait and retry
    sleep 5
done
