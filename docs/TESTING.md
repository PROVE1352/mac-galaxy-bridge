# On-device test checklist

What this repo's CI-less, two-device reality needs verified by hand. Run top to
bottom after building both sides; every line should pass before calling a milestone
done. (Automated coverage — framing, codecs, pinning, pairing HMAC — runs with
`gradlew testDebugUnitTest` and `swift test`; identical vectors on both sides.)

## Build

- [ ] Android: `cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest`,
      install `app/build/outputs/apk/debug/app-debug.apk` on S25 (and Tab S11)
- [ ] Mac: `cd macos && swift test && ./scripts/build-app.sh`,
      `cp -R MGBridge.app ~/Applications/ && open ~/Applications/MGBridge.app`
- [ ] Mac Quick Action: `cp -R "macos/quickaction/Send to Galaxy.workflow" ~/Library/Services/`
      (if Finder doesn't show it under Quick Actions, open the .workflow in Automator
      once and save — the shell one-liner is `"$HOME/Applications/MGBridge.app/Contents/MacOS/mgbridge" send "$@"`,
      input "as arguments")
- [ ] First Mac launch: expect Local Network prompt, notification prompt, and (on
      first receive) a Downloads-folder prompt; firewall prompt on first listen —
      allow all. Re-running `build-app.sh` re-signs ad hoc and may re-prompt.

## 1. Hotspot (M1/M2 regression)

- [ ] Phone: app installed, bridge service running, accessibility service enabled,
      Mac BT address saved
- [ ] Mac: `config.json` filled (`phoneBtAddr`, `hotspotSSID`, `wifiInterface`);
      `brew install blueutil` done
- [ ] Menu → *Connect Phone Hotspot* → phone flips hotspot ON by itself (screen may
      briefly show Settings) → Mac joins the SSID. Works with the phone locked,
      in Korean UI
- [ ] Menu → *Disconnect Hotspot* → phone flips hotspot OFF
- [ ] Repeat connect/disconnect 3× in a row — no stuck state

## 2. Pairing

- [ ] Mac → *Pair New Device…* shows a code; phone → *Pair with a Mac* lists the Mac
- [ ] Wrong code → phone shows "Pairing refused: wrong code"; Mac stays unpaired
- [ ] Right code → both sides show the peer; survives app restarts on both ends
- [ ] Wait >2 min after arming, then try → "pairing not armed"

## 3. Transfers

- [ ] Phone → Mac: *Send test file* lands in `~/Downloads` with zero taps on the Mac
- [ ] Mac → phone: *Send Files…* with a 1 KB text file → appears in phone Downloads
      with zero taps on the phone
- [ ] Mac → phone: batch of ~10 photos (~50 MB) → all arrive, names sensible,
      duplicates suffixed not overwritten
- [ ] A ≥2 GB video in both directions — hash-verified receipt, no OOM, progress
      visible in the Mac status line
- [ ] Kill Wi-Fi mid-transfer → sender reports failure, receiver leaves no partial
      files in Downloads (no `.mgbridge-partial-*` remnants) → re-send succeeds
- [ ] Transfer over the *hotspot* link (Mac joined via §1, no shared Wi-Fi): phone →
      Mac and Mac → phone both work
- [ ] Unpaired third device (or `openssl s_client -connect mac:port`) → handshake
      refused; nothing written

## 4. OS integration (M3)

- [ ] Phone: Photos → Share → "Mac-Galaxy Bridge" → single photo lands on Mac
- [ ] Phone: share 5 photos at once → all land
- [ ] Mac: Finder → right-click file(s) → Quick Actions → *Send to Galaxy* → lands
      on the phone (menu-bar app running)
- [ ] Tablet: pair Tab S11, send phone-sized batch both directions; Mac picks the
      right target when both devices are online (last-used peer wins)

## 5. Extras (M4)

- [ ] Copy text on Mac → *Send Clipboard to Phone* → phone toast/notification;
      paste works (or notification "Copy" action on One UI builds that block
      background clipboard writes)
- [ ] History: Android app → History screen lists recent transfers; Mac menu →
      *History* submenu shows the last 5
- [ ] Reboot phone and Mac → service auto-resumes on launch, menu-bar app relaunches
      (add to Login Items), a transfer works with zero re-setup
- [ ] Battery optimization: exempt the companion app (Settings → Battery) and
      confirm the bridge still reacts to BT connect after a night of doze
