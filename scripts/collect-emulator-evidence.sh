#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Best-effort collection, including when framework preparation or Gradle fails.
set -u
serial=${1:?Usage: collect-emulator-evidence.sh SERIAL OUTPUT_DIRECTORY}
output=${2:?Missing output directory}
mkdir -p "$output"
capture() {
    local name=$1
    shift
    timeout 20s adb -s "$serial" "$@" > "$output/$name.txt" 2>&1 || true
}
capture logcat logcat -b all -d
capture properties shell getprop
capture kernel shell uname -a
capture boot-command-line shell cat /proc/cmdline
capture activity shell dumpsys activity
capture window shell dumpsys window
capture dropbox shell dumpsys dropbox --print
capture package shell dumpsys package dev.nijiframe.app
capture screenshot shell screencap -p /sdcard/nijiframe-ci.png
timeout 20s adb -s "$serial" pull /sdcard/nijiframe-ci.png "$output/screen.png" > "$output/screen-pull.txt" 2>&1 || true
timeout 30s adb -s "$serial" pull /data/tombstones "$output/tombstones" > "$output/tombstones-pull.txt" 2>&1 || true
timeout 30s adb -s "$serial" pull /data/anr "$output/anr" > "$output/anr-pull.txt" 2>&1 || true
timeout 30s adb -s "$serial" pull /sdcard/Download/nijiframe-test-evidence "$output/ui" > "$output/ui-pull.txt" 2>&1 || true
