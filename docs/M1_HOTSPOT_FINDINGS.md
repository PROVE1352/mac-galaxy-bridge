# M1 hotspot — validation findings (2026-07-05)

On-device testing (Galaxy S25, One UI / Android 16, adb) to validate the
one-click-hotspot approach. Two candidate no-code paths were tested; **both are
blocked**. The working path folds into the M2 companion app.

## Setup verified working
- Mac→phone Bluetooth control via `blueutil` is solid: disconnect/connect/reconnect
  cycles succeed and the link stays up. The menu-bar "connect" action is viable.
- On every Mac reconnect, the phone **reliably** broadcasts
  `android.bluetooth.device.action.ACL_CONNECTED` for the Mac
  (`A0:9A:8E:78:7C:8B`). Confirmed in logcat.

## Path A — programmatic hotspot toggle from shell: BLOCKED
`TetheringManager.startTethering(TETHERING_WIFI, ...)` invoked from an
`app_process` running as shell uid (which shows `TETHER_PRIVILEGED granted=true`):

```
callback: onTetheringFailed arg=14   // TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION
RESULT: FAILED
```

Even shell uid can't flip the hotspot: the `WRITE_SETTINGS` app-op check fails for
a package-less process. So Shizuku/ADB-grant approaches won't rescue this either —
tethering is gated below the permission we can reach.

## Path B — Samsung Modes & Routines "Bluetooth connected" trigger: BLOCKED
Routine correctly configured to watch the Mac. Routine engine
(`com.samsung.android.app.routines`) log on Mac reconnect:

```
BluetoothEventRouterImpl: route: action=...ACL_CONNECTED -> notification-only path
BluetoothConnectionService: isConnected: false address: A0:9A:8E:78:7C:8B
SpecificBluetoothConditionHandler: isSatisfied: isConnected=false, isBluetoothOn=true
```

Samsung's "connected" means a **profile-level** connection (A2DP/HFP/PAN), not a raw
ACL link. The Mac has no active profile with the phone:
- macOS dropped Bluetooth-PAN client support — turning on "Bluetooth tethering" on the
  phone does nothing because the Mac never binds PAN. (No Bluetooth hardware port in
  `networksetup -listallhardwareports`.)
- The Mac isn't an active audio device for the phone.

So the routine's condition never becomes satisfied → hotspot never turns on.

## Decision: hotspot moves into the companion app (M2)
A normal app *can* receive the `ACL_CONNECTED` broadcast for the Mac (with
`BLUETOOTH_CONNECT`), bypassing Samsung's profile-level gate entirely. Since the app
can't call the tethering API either (Path A), it toggles the hotspot via an
**AccessibilityService** (tap the Quick Settings hotspot tile) — the standard no-root
method used by MacroDroid/Tasker.

Resulting one-click flow:
```
Mac menu-bar button
  -> blueutil --connect <phone>
     -> phone fires ACL_CONNECTED (reliable)
        -> companion app catches it, accessibility-toggles hotspot
           -> Mac auto-joins the known SSID
```

## Immediate no-build option (optional)
MacroDroid trigger "Device Connected (Bluetooth) = MacBook" (it keys off ACL, not
Samsung's profile gate) + a hotspot-enable action can give a working setup today,
while our own app is the clean long-term path.
