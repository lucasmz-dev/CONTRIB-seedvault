#!/usr/bin/env bash
#
# SPDX-FileCopyrightText: 2023 The Calyx Institute
# SPDX-License-Identifier: Apache-2.0
#

# assert ANDROID_HOME is set
if [ -z "$ANDROID_HOME" ]; then
  echo "ANDROID_HOME is not set"
  exit 1
fi

# assert 2 parameters are provided
if [ $# -ne 2 ]; then
  echo "Usage: $0 <emulator_name> <system_image>"
  exit 1
fi

EMULATOR_NAME=$1
SYSTEM_IMAGE=$2

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &>/dev/null && pwd)

echo "Downloading system image..."
yes | "$ANDROID_HOME"/cmdline-tools/latest/bin/sdkmanager --install "$SYSTEM_IMAGE"

# create AVD if it doesn't exist
if "$ANDROID_HOME"/cmdline-tools/latest/bin/avdmanager list avd | grep -q "$EMULATOR_NAME"; then
  echo "AVD already exists. Skipping creation."
else
  echo "Creating AVD..."
  echo 'no' | "$ANDROID_HOME"/cmdline-tools/latest/bin/avdmanager create avd -n "$EMULATOR_NAME" -k "$SYSTEM_IMAGE"
  sleep 1
fi

EMULATOR_DEVICE_NAME=$("$ANDROID_HOME"/platform-tools/adb devices | grep emulator | cut -f1)

if [ -z "$EMULATOR_DEVICE_NAME" ]; then
    "$SCRIPT_DIR"/start_emulator.sh "$EMULATOR_NAME"
fi

# wait for emulator device to appear with 180 second timeout
echo "Waiting for emulator device..."

for i in {1..180}; do
  if [ -z "$EMULATOR_DEVICE_NAME" ]; then
    sleep 1
    EMULATOR_DEVICE_NAME=$("$ANDROID_HOME"/platform-tools/adb devices | grep emulator | cut -f1)
  else
    break
  fi
done

if [ -z "$EMULATOR_DEVICE_NAME" ]; then
  echo "Emulator device name not found"
  exit 1
fi

ADB="$ANDROID_HOME/platform-tools/adb -s $EMULATOR_DEVICE_NAME"

echo "Waiting for emulator to boot..."
$ADB wait-for-device shell "while [[ -z $(getprop sys.boot_completed) ]]; do sleep 1; done;"

echo "Provisioning emulator for write access to '/system'..."
$ADB root
sleep 3      # wait for adb to restart
$ADB remount # remount /system as writable

echo "Rebooting emulator..."
$ADB reboot # need to reboot first time we remount
$ADB wait-for-device shell "while [[ -z $(getprop sys.boot_completed) ]]; do sleep 1; done;"

echo "Provisioning emulator for Seedvault..."
"$SCRIPT_DIR"/install_app.sh

echo "Rebooting emulator..."
$ADB reboot
$ADB wait-for-device shell "while [[ -z $(getprop sys.boot_completed) ]]; do sleep 1; done;"

echo "Disabling backup..."
$ADB shell bmgr enable false

echo "Downloading and extracting test backup to '/sdcard/seedvault_baseline'..."

if [ ! -f backup.tar.gz ]; then
  echo "Downloading test backup..."
  wget --quiet https://github.com/seedvault-app/seedvault-test-data/releases/download/3/backup.tar.gz
fi

$ADB root
sleep 3      # wait for adb to restart
$ADB push backup.tar.gz /sdcard
rm backup.tar.gz

$ADB wait-for-device
$ADB shell mkdir -p /sdcard/seedvault_baseline
$ADB shell tar xzf /sdcard/backup.tar.gz --directory=/sdcard/seedvault_baseline
$ADB shell rm /sdcard/backup.tar.gz

# sometimes a system dialog (e.g. launcher stopped) is showing and taking focus
$ADB shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS

echo "Emulator '$EMULATOR_NAME' has been provisioned with Seedvault!"
