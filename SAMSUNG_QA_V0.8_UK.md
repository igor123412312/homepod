# HomePod Music v0.8 — Samsung installation and signing

## What v0.8 fixes
- Removes the exported Shizuku provider and its provider dependency from the personal APK. Privileged Shizuku audio is not part of this product.
- NotificationListener was already removed in v0.6.1. Local network permission is not requested with targetSdk35.
- Debug HTTP log service is now bound to 127.0.0.1 only, rather than all Wi-Fi interfaces.
- Adds a one-tap "Скопіювати діагностику" button (logs remain on the phone until copied; inspect before sending).
- CI verifies the merged manifest and cryptographic signing, Android 15 smoke tests, Gradle build and Kotlin/Python tests.

## First test installation on Samsung
1. Use the new APK artifact from GitHub Actions (not v0.6/v0.7), making sure GitHub Actions is successful.
2. If you previously installed v0.6/v0.7 signed with a different debug key and Android says "App not installed", uninstall the old **HomePod Music** first. This deletes stored selections and must be done only if you're comfortable losing that app's local data.
3. Install from Samsung My Files. Leave Google Play Protect on. If it blocks the APK, do not override it: capture the exact warning screen and share it.
4. Launch HomePod Music. Press "Надати дозволи та знайти HomePod". You may need to grant Nearby devices and microphone permission because Android Playback Capture uses RECORD_AUDIO.
5. Connect Samsung and HomePod to the same regular home Wi-Fi network (not an isolated guest Wi-Fi). Pick HomePod and press "Підключити й відкрити YouTube Music". Confirm Android's MediaProjection prompt.
6. When the HomePod state reaches PLAYING and audio capture is ready, the app should open YouTube Music. Start a track.
7. Test stop, reconnect, screen lock, output switcher, volume and Quick Settings tile.
8. If no speaker or no sound, tap "Скопіювати діагностику", inspect the clipboard for device names / IP addresses before sending and share the relevant failure.

## Permanent APK signing (recommended before the next upgrade)
GitHub-hosted CI runners generate their own Android debug key by default. The v0.6 and v0.7 builds were signed with **different certificates**; Android refuses to update an already-installed package with a different certificate.

For future consistent debug signing, create a private signing keystore on your own trusted computer. **Never commit a keystore or signing passwords to GitHub.**

For this debug-only workflow, generate an Android debug-compatible signing key with the standard alias/password using the Java keytool installed with Android Studio or JDK:

```bash
keytool -genkeypair -v -keystore debug.keystore -alias androiddebugkey -keyalg RSA -keysize 3072 -validity 10000 -storepass android -keypass android -dname "CN=Android Debug,O=Android,C=US"
```

On Windows PowerShell encode it:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("debug.keystore")) | Set-Clipboard
```

In the private GitHub repository open Settings → Secrets and variables → Actions → New repository secret; create `HOMEPOD_DEBUG_KEYSTORE_BASE64` and paste the encoded text. **Before using secrets, switch the GitHub repo to Private** if it is public.

The workflow will place the keystore at `~/.android/debug.keystore` before building. The encrypted GitHub Secret is never printed and the private key should stay under your control. Save a protected backup of your keystore; losing it means you cannot update an existing installation using that key.

NOTE: Because a different key is now used, the **first** install with your persistent key requires removal of older debug builds (unless the phone has no HomePod Music installed). Later builds will update in place.

## Boundaries of automated tests
- Android 15 emulator can test launch, buttons, permissions and absence of crashes. It has no physical HomePod or Google Play Protect.
- CI validates APK manifests, archives and signatures but cannot certify acceptance by Play Protect on a real Samsung.
- The Android AudioPlaybackCapture API cannot capture audio from apps that explicitly disallow it. If YouTube Music changes its policy, the HomePod might connect but play silence.
- The YouTube Music Cast device picker and Android system output switcher are not the same thing; the advertised MediaRoute2 integration needs real-device validation.
- For a detailed physical test, collect **only with your consent** an adb logcat around a failed connection from your own PC:
  `adb logcat -d -s AndroidRuntime:E LogServer:D > homepod-debug.txt`
  Inspect for sensitive strings before sharing.

## Next stable build checklist
- Verify that Play Protect does not issue an install block.
- Verify Android 17 first-run permission dialogs on Galaxy Fold.
- Verify HomePod AirPlay 2 PAIR, SETUP, audio packets, remote volume, recovery after Wi-Fi disconnect, screen-off behavior.
- Verify YouTube Music capture with non-protected content permitted by Android and media routing.
