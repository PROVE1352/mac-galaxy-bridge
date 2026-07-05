#!/bin/bash
# Assembles MGBridge.app from the SwiftPM release binary — no Xcode project needed.
# The bundle (not a bare binary) is what makes Local Network / Bluetooth / Downloads
# TCC prompts and Bonjour work on modern macOS.
#
# Usage:  ./scripts/build-app.sh   (from macos/, then copy MGBridge.app wherever)
# NOTE: re-running re-signs ad hoc, which may re-trigger TCC/firewall prompts.
set -euo pipefail
cd "$(dirname "$0")/.."

swift build -c release

APP=MGBridge.app
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp .build/release/mgbridge "$APP/Contents/MacOS/mgbridge"

cat > "$APP/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>CFBundleIdentifier</key>
	<string>com.mgbridge.mac</string>
	<key>CFBundleName</key>
	<string>MGBridge</string>
	<key>CFBundleDisplayName</key>
	<string>MGBridge</string>
	<key>CFBundleExecutable</key>
	<string>mgbridge</string>
	<key>CFBundlePackageType</key>
	<string>APPL</string>
	<key>CFBundleShortVersionString</key>
	<string>0.2.0</string>
	<key>CFBundleVersion</key>
	<string>1</string>
	<key>LSMinimumSystemVersion</key>
	<string>13.0</string>
	<key>LSUIElement</key>
	<true/>
	<key>NSLocalNetworkUsageDescription</key>
	<string>MGBridge discovers and talks to your paired Galaxy devices on the local network.</string>
	<key>NSBonjourServices</key>
	<array>
		<string>_mgbridge._tcp</string>
	</array>
	<key>NSBluetoothAlwaysUsageDescription</key>
	<string>MGBridge connects to your phone over Bluetooth to wake its hotspot.</string>
	<key>NSDownloadsFolderUsageDescription</key>
	<string>Received files are saved to your Downloads folder.</string>
</dict>
</plist>
PLIST

codesign --force --sign - "$APP"
echo "Built $APP"
echo "Install:  cp -R $APP ~/Applications/  &&  open ~/Applications/$APP"
