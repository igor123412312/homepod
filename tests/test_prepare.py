"""Offline patch regressions against SOURCE-SHAPE fixtures (not an Android device)."""
import pathlib
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from prepare import APP_ID, SOURCE_REF, SOURCE_COMMIT, patch_capture, patch_gradle, patch_manifest, patch_tile, patch_app_and_tile_strings, patch_permissions, patch_consent, prepare

SAMPLE_MANIFEST = '''<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
<uses-permission android:name="android.permission.INTERNET"/>
<application android:icon="@mipmap/ic_launcher"
 android:roundIcon="@mipmap/ic_launcher_round">
<activity android:name=".MainActivity" android:exported="true">
<intent-filter>
<action android:name="android.intent.action.MAIN" />
<category android:name="android.intent.category.LAUNCHER" />
</intent-filter>
</activity>
<service android:name=".service.AirPlayTileService" android:exported="true" />
</application></manifest>'''
SAMPLE_CAPTURE = '''class AudioCaptureService {
    suspend fun openSource() {
        if (prefs.useShizukuCapture && Build.VERSION.SDK_INT >= 33) {
          useShizuku()
        }
        val projection = mediaProjection ?: return null
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .apply { prefs.captureUsages.forEach { addMatchingUsage(it) } }
            .build()
    }
}'''
SAMPLE_TILE = '''package com.airplay.streamer.service
import com.airplay.streamer.TileDeviceActivity
fun onClick() {
  val last = Prefs(this).lastSpeakers
  val devices = DiscoveryRepository.getInstance(this).devices.value.filter { it.identity in last }
  if (devices.isNotEmpty() && StreamController.canConnectSilently(this)) {
    devices.forEach { StreamController.connect(this, it) }
  } else {
    openPicker()
  }
}
fun openPicker() {
  val intent = Intent(this, TileDeviceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
}'''
SAMPLE_STRINGS = '<resources><string name="app_name">centuryplay</string><string name="tile_label">airplay</string></resources>'

class PatchTests(unittest.TestCase):
    def test_fixed_release(self):
        self.assertEqual(SOURCE_REF, 'v2.1.2')
        self.assertEqual(SOURCE_COMMIT, 'e241344a61008936186718ee0b2feec40ab81e79')

    def test_manifest_launcher_and_package_visibility(self):
        patched = patch_manifest(SAMPLE_MANIFEST)
        root = ET.fromstring(patched)
        self.assertEqual(patched.count('android.intent.category.LAUNCHER'), 1)
        self.assertIn('android:name=".HomePodMusicLauncherActivity"', patched)
        self.assertEqual(root.find('queries/package').attrib['{http://schemas.android.com/apk/res/android}name'],
                         'com.google.android.apps.youtube.music')
        self.assertIn('ic_homepod_music', patched)
        self.assertNotIn('@mipmap/ic_launcher', patched)

    def test_uid_filter_and_shizuku_disabled(self):
        patched = patch_capture(SAMPLE_CAPTURE)
        self.assertIn('addMatchingUid(ytmUid)', patched)
        self.assertIn('com.google.android.apps.youtube.music', patched)
        self.assertIn('false && prefs.useShizukuCapture', patched)
        self.assertIn('getOrNull() ?: return null', patched)
        self.assertIn('prefs.captureUsages.forEach { addMatchingUsage(it) }', patched)

    def test_tile_user_consent_flow(self):
        patched = patch_tile(SAMPLE_TILE)
        self.assertIn('HomePodMusicLauncherActivity::class.java', patched)
        self.assertIn('EXTRA_QUICK_CONNECT, true', patched)
        self.assertNotIn('devices.forEach { StreamController.connect(this, it) }', patched)
        self.assertNotIn('TileDeviceActivity::class.java', patched)
        self.assertIn('openPicker()', patched)

    def test_id_and_labels(self):
        self.assertIn(APP_ID, patch_gradle('applicationId = "com.airplay.streamer"\nversionName = "2.1.2"'))
        self.assertIn('HomePod Music', patch_app_and_tile_strings(SAMPLE_STRINGS))
        self.assertIn('<string name="tile_label">HomePod Music</string>', patch_app_and_tile_strings(SAMPLE_STRINGS))

    def test_instrumentation_runner_and_deps(self):
        from prepare import patch_instrumentation_gradle
        output = patch_instrumentation_gradle('targetSdk = 35\ntestImplementation("junit:junit:4.13.2")')
        self.assertIn('testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"', output)
        self.assertIn('androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")', output)

    def test_optional_notifications_do_not_block_stream(self):
        controller = 'if (Build.VERSION.SDK_INT >= 33) { add(Manifest.permission.POST_NOTIFICATIONS); add(Manifest.permission.NEARBY_WIFI_DEVICES) }'
        patched = patch_permissions(controller)
        self.assertNotIn('POST_NOTIFICATIONS', patched)
        self.assertIn('NEARBY_WIFI_DEVICES', patched)
        consent = 'add(Manifest.permission.RECORD_AUDIO)\nif (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)'
        self.assertEqual(patch_consent(consent).count('POST_NOTIFICATIONS'), 0)
        self.assertIn('RECORD_AUDIO', patch_consent(consent))

    def test_click_handlers_present_and_safe(self):
        code = (ROOT/'overlay/HomePodMusicLauncherActivity.kt').read_text()
        for action in [
            'StreamController.stopAll(this)',
            'StreamController.connect(this, device)',
            'StreamController.setSpeakerVolume(id, value)',
            'repository.refresh()',
            'openYoutubeMusic()',
            'MainActivity::class.java',
            'QuickConnectPolicy.connectAction(',
        ]:
            self.assertIn(action, code, action)
        self.assertIn('if (fromUser) updateVolume(progress)', code)
        self.assertIn('SpeakerStatus.NEEDS_PIN', code)
        self.assertIn('SpeakerStatus.NEEDS_ACCEPT', code)
        self.assertIn('SpeakerStatus.FAILED', code)
        self.assertIn('CONNECT_TIMEOUT_MS', code)
        self.assertNotIn('BOOT_COMPLETED', code)

    def test_fail_closed_on_changed_upstream(self):
        for fn, text in [(patch_capture,'outdated engine'), (patch_manifest,'<manifest></manifest>'),
                         (patch_tile, 'outdated tile'), (patch_app_and_tile_strings, '<resources/>'), (patch_permissions, 'outdated permissions'), (patch_consent, 'outdated consent')]:
            with self.assertRaises(RuntimeError): fn(text)

    def test_prepare_checkout_and_reject_double_patch(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            inputs = {
                'app/build.gradle.kts': 'applicationId = "com.airplay.streamer"\nversionName = "2.1.2"\ntargetSdk = 35\ntestImplementation("junit:junit:4.13.2")',
                'app/src/main/res/values/strings.xml': SAMPLE_STRINGS,
                'app/src/main/AndroidManifest.xml': SAMPLE_MANIFEST,
                'app/src/main/java/com/airplay/streamer/service/AudioCaptureService.kt': SAMPLE_CAPTURE,
                'app/src/main/java/com/airplay/streamer/service/AirPlayTileService.kt': SAMPLE_TILE,
                'app/src/main/java/com/airplay/streamer/service/StreamController.kt': 'if (Build.VERSION.SDK_INT >= 33) { add(Manifest.permission.POST_NOTIFICATIONS); add(Manifest.permission.NEARBY_WIFI_DEVICES) }',
                'app/src/main/java/com/airplay/streamer/router/MediaProjectionConsentActivity.kt': 'if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)',
                'LICENSE': 'GNU AFFERO GENERAL PUBLIC LICENSE Version 3',
            }
            for rel, content in inputs.items():
                f = root / rel
                f.parent.mkdir(parents=True, exist_ok=True)
                f.write_text(content, encoding='utf-8')
            prepare(src=root, download=False)
            self.assertTrue((root / 'app/src/main/java/com/airplay/streamer/HomePodMusicLauncherActivity.kt').is_file())
            self.assertTrue((root / 'app/src/main/java/com/airplay/streamer/QuickConnectPolicy.kt').is_file())
            self.assertEqual((root / 'LICENSE').read_text(), inputs['LICENSE'])
            self.assertIn('addMatchingUid(ytmUid)', (root / 'app/src/main/java/com/airplay/streamer/service/AudioCaptureService.kt').read_text())
            self.assertIn('HomePodMusicLauncherActivity', (root / 'app/src/main/java/com/airplay/streamer/service/AirPlayTileService.kt').read_text())
            with self.assertRaises(RuntimeError): prepare(src=root, download=False)

    def test_rejects_wrong_git_commit_before_patching(self):
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            (root / '.git').mkdir()
            with patch('prepare.subprocess.check_output', return_value='deadbeef\n'):
                with self.assertRaisesRegex(RuntimeError, 'Unexpected centuryplay commit'):
                    prepare(src=root, download=False)

    def test_upstream_precheck_is_transactional(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            f = root / 'app/build.gradle.kts'
            f.parent.mkdir(parents=True)
            f.write_text('applicationId = "com.airplay.streamer"\nversionName = "2.1.2"\ntargetSdk = 35\ntestImplementation("junit:junit:4.13.2")')
            with self.assertRaises(FileNotFoundError): prepare(src=root, download=False)
            self.assertEqual(f.read_text(), 'applicationId = "com.airplay.streamer"\nversionName = "2.1.2"\ntargetSdk = 35\ntestImplementation("junit:junit:4.13.2")')

    def test_launcher_has_no_automatic_background_capture(self):
        text = (ROOT/'overlay/HomePodMusicLauncherActivity.kt').read_text()
        self.assertIn('EXTRA_QUICK_CONNECT', text)
        self.assertIn('SpeakerStatus.NEEDS_ACCEPT', text)
        self.assertIn('QuickConnectPolicy.choose', text)
        self.assertIn('registerForActivityResult', text)
        self.assertIn('StreamController.connect(this, device)', text)
        self.assertIn('QuickConnectPolicy.readyToLaunch(session.capturing, true)', text)
        self.assertNotIn('BOOT_COMPLETED', text)
        self.assertIn('putString(KEY_FAVORITE, device.identity)', text)
        self.assertNotIn('prefs.lastSpeakers =', text)

if __name__ == '__main__': unittest.main()
