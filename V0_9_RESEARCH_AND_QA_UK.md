# HomePod Music v0.9 — Samsung Fold design and QA
## Scope
One-task experience: choose saved HomePod mini, grant Android capture permission, route audio from YouTube Music via AirPlay 2, stop/reconnect, adjust volume, optional Quick Settings.
## Research comparison (2024–2026)
- AirMusic: proprietary Android audio-to-AirPlay sender; functioning comparison on Samsung, cannot embed its proprietary engine.
- centuryplay v2.1.2: open-source AirPlay 2 sender with mDNS, MediaRoute2, pairing, HomePod mini SETUP 403 fixes, multiroom and Android 17 work. Fixed version chosen for reproducible source builds.
- centuryplay 2.2 beta2 (2026-11-10): additional Sonos PTP and external-control improvements. Not blindly adopted because changes may regress HomePod.
- Airvia: another open-source experimental Android AirPlay sender; fallback to examine only if current engine fails on hardware.
## New in v0.9
- Modern dark UI with responsive capped card width for Samsung Fold external and inner screens.
- Clear status states: permissions → selection → connecting → streaming.
- Selecting a device is now a selection, not an unexpected immediate connect.
- Explicit connect button, stop button, volume, refresh and clear diagnostics.
- Disabled Play Protect-triggering notification access since 0.6.1, removed unused privileged Shizuku provider since 0.8.
- No insecure debug log server reachable from LAN; logs on device only.
## Checks possible via GitHub Actions
- Python patch tests; immutable v2.1.2 source verification
- Gradle build and existing crypto/protocol/volume JVM tests
- APK ZIP integrity and merged manifest audit
- APK signature inspection
- Pixel 6 Android 15 emulator UI smoke tests (no physical HomePod)
## Checks NOT possible without user's hardware
- Google Play Protect acceptance on Galaxy Fold 8 / Android 17
- Physical HomePod discovery and HAP pairing
- Remote audio playback, media capture policy for YouTube Music, audio dropouts
- Bluetooth vs Wi-Fi path and OS output-switcher differences
- Compatibility with Samsung Auto Blocker, device restrictions
## Samsung first install plan
1. Do not uninstall anything: user confirms no prior HomePod Music install; signature mismatch is not the initial-install cause.
2. Download only built APK from user's GitHub Actions artifact; verify source and checksum.
3. Attempt install with Google Play Protect enabled. If Play Protect refuses, do not bypass; exact screen and package/app warning should be recorded.
4. If Samsung Auto Blocker refuses, consult Samsung support and choose trusted distribution, not disabling protections without assessing risks.
5. If installed, first screen should show explicit permissions button. After permissions granted and same Wi-Fi confirmed, choose HomePod and press Connect.
6. Confirm Android's MediaProjection consent; YouTube Music opens when speaker session is ready. Test playback.
7. Copy in-app diagnostics if any step fails, inspect for private details.
## Future stable distribution
Use private long-lived signing key via GitHub Secrets and Google's developer verification/distribution processes. Do not put keys in repository. Debug APK from ephemeral GitHub runner may use a different signing certificate on each run.
