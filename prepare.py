#!/usr/bin/env python3
"""Assemble reproducible personal Android UI + YouTube Music-only capture patch.

The sender itself is g8row/centuryplay v2.1.2 (AGPL-3.0).
No binaries or third-party source are bundled with this patch distribution.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
SOURCE_URL = 'https://github.com/g8row/centuryplay.git'
SOURCE_REF = 'v2.1.2'
SOURCE_COMMIT = 'e241344a61008936186718ee0b2feec40ab81e79'  # GitHub-verified release tag
SOURCE_DIR = ROOT / '_centuryplay'
APP_ID = 'ua.homepodmusic.personalcast'
YTM_PACKAGE = 'com.google.android.apps.youtube.music'


def substitute_exactly_once(source: str, pattern: str, replace: str, description: str, flags=0) -> str:
    result, changes = re.subn(pattern, replace, source, count=1, flags=flags)
    if changes != 1:
        raise RuntimeError(f'{description}: source layout changed, expected 1 match; got {changes}')
    return result


def patch_gradle(source: str) -> str:
    source = substitute_exactly_once(source,
        r'applicationId\s*=\s*"com\.airplay\.streamer"',
        f'applicationId = "{APP_ID}"', 'applicationId')
    return substitute_exactly_once(source,
        r'versionName\s*=\s*"2\.1\.2"',
        'versionName = "0.6.0"', 'product versionName')


def patch_strings(source: str) -> str:
    return substitute_exactly_once(source,
        r'<string name="app_name">[^<]*</string>',
        '<string name="app_name">HomePod Music</string>', 'app_name')


def patch_manifest(source: str) -> str:
    if '<queries>' in source:
        raise RuntimeError('Upstream has a <queries> element; merge it explicitly before updating')
    source = substitute_exactly_once(source,
        r'<intent-filter>\s*<action\s+android:name="android\.intent\.action\.MAIN"\s*/>\s*'
        r'<category\s+android:name="android\.intent\.category\.LAUNCHER"\s*/>\s*</intent-filter>',
        '', 'upstream launcher intent', flags=re.S)
    new_launcher = '''<activity
            android:name=".HomePodMusicLauncherActivity"
            android:exported="true"
            android:theme="@style/Theme.AirPlayStreamer">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        '''
    source = substitute_exactly_once(source,
        r'<activity\s+android:name="\.MainActivity"',
        new_launcher + '<activity android:name=".MainActivity"', 'MainActivity')
    # Android package visibility is needed for PackageManager.getLaunchIntentForPackage.
    source = substitute_exactly_once(source,
        r'<application\b',
        f'<queries><package android:name="{YTM_PACKAGE}" /></queries>\n    <application',
        'manifest application')
    source = substitute_exactly_once(source,
        r'android:icon="@mipmap/ic_launcher"',
        'android:icon="@drawable/ic_homepod_music"', 'app icon')
    source = substitute_exactly_once(source,
        r'android:roundIcon="@mipmap/ic_launcher_round"',
        'android:roundIcon="@drawable/ic_homepod_music"', 'round icon')
    # Fail closed if malformed manifest or exported launcher incorrectly configured.
    root = ET.fromstring(source)
    android_ns = '{http://schemas.android.com/apk/res/android}'
    launchers = []
    for activity in root.findall('./application/activity'):
        for inf in activity.findall('./intent-filter'):
            actions = [a.get(android_ns + 'name') for a in inf.findall('./action')]
            cats = [c.get(android_ns + 'name') for c in inf.findall('./category')]
            if 'android.intent.action.MAIN' in actions and 'android.intent.category.LAUNCHER' in cats:
                launchers.append(activity.get(android_ns + 'name'))
    if launchers != ['.HomePodMusicLauncherActivity']:
        raise RuntimeError(f'Expected one personal launcher, found: {launchers}')
    return source


def patch_capture(source: str) -> str:
    # Our product MUST capture *only* YTM; privileged Shizuku branch bypasses
    # Android's per-UID AudioPlaybackCapture configuration. Disable this path.
    source = substitute_exactly_once(source,
        r'if\s*\(prefs\.useShizukuCapture\s*&&\s*Build\.VERSION\.SDK_INT\s*>=\s*33\)\s*\{',
        'if (false && prefs.useShizukuCapture && Build.VERSION.SDK_INT >= 33) {',
        'optional privileged audio source')
    # Keep the upstream usage filters and additionally restrict to YTM UID.
    # Android's public Builder supports matchingUsage AND matchingUid together.
    source = substitute_exactly_once(source,
        r'(val config\s*=\s*AudioPlaybackCaptureConfiguration\.Builder\(projection\)\s*'
        r'\.apply\s*\{\s*prefs\.captureUsages\.forEach\s*\{\s*addMatchingUsage\(it\)\s*\}\s*\})',
        r'''\g<1>
                .apply {
                    // MediaProjection respects the source app's capture policy.
                    val ytmUid = runCatching {
                        packageManager.getApplicationInfo(
                            "com.google.android.apps.youtube.music", 0
                        ).uid
                    }.getOrNull() ?: return null
                    addMatchingUid(ytmUid)
                }''',
        'MediaProjection UID filter', flags=re.S)
    if source.count('addMatchingUid(ytmUid)') != 1:
        raise RuntimeError('Unexpected capture filter count')
    return source



def patch_permissions(source: str) -> str:
    # POST_NOTIFICATIONS is optional: foreground mediaProjection can start even if
    # notifications are denied. Keeping it in runtimePermissions blocked all connection.
    return substitute_exactly_once(source,
        r'add\(Manifest\.permission\.POST_NOTIFICATIONS\)\s*',
        '', 'notification must not block playback')


def patch_instrumentation_gradle(source: str) -> str:
    source = substitute_exactly_once(source,
        r'(testImplementation\("junit:junit:4\.13\.2"\))',
        lambda m: m.group(1) + '\n    androidTestImplementation("androidx.test.ext:junit:1.2.1")'
                 + '\n    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")'
                 + '\n    androidTestImplementation("androidx.test:rules:1.6.1")',
        'Android instrumentation dependencies')
    source = substitute_exactly_once(source,
        r'(targetSdk\s*=\s*35)',
        lambda m: m.group(1) + '\n        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"',
        'Instrumentation runner')
    return source


def patch_consent(source: str) -> str:
    # The upstream consent trampoline's result callback checks RECORD_AUDIO even
    # when only POST_NOTIFICATIONS is requested, incorrectly rejecting permission.
    # Remove optional notification request and rely on record-audio permission only.
    return substitute_exactly_once(source,
        r'if \(Build\.VERSION\.SDK_INT >= 33\) add\(Manifest\.permission\.POST_NOTIFICATIONS\)\s*',
        '', 'optional notification permission from consent flow')



def patch_app_and_tile_strings(source: str) -> str:
    source = patch_strings(source)
    return substitute_exactly_once(source,
        r'<string name="tile_label">[^<]*</string>',
        '<string name="tile_label">HomePod Music</string>', 'tile_label')


def patch_tile(source: str) -> str:
    # Upstream already registers AirPlayTileService. Route a QS tap to the same
    # on-screen flow so Android's per-session MediaProjection consent is preserved.
    source = substitute_exactly_once(source,
        r'import com\.airplay\.streamer\.TileDeviceActivity',
        'import com.airplay.streamer.HomePodMusicLauncherActivity', 'QS tile import')
    source = substitute_exactly_once(source,
        r'val last = Prefs\(this\)\.lastSpeakers\s*'
        r'val devices = DiscoveryRepository\.getInstance\(this\)\.devices\.value\.filter \{ it\.identity in last \}\s*'
        r'if \(devices\.isNotEmpty\(\) && StreamController\.canConnectSilently\(this\)\) \{\s*'
        r'devices\.forEach \{ StreamController\.connect\(this, it\) \}\s*'
        r'\} else \{\s*openPicker\(\)\s*\}',
        'openPicker()', 'QS tile idle click', flags=re.S)
    source = substitute_exactly_once(source,
        r'Intent\(this, TileDeviceActivity::class\.java\)\.addFlags\(',
        'Intent(this, HomePodMusicLauncherActivity::class.java)'
        '.putExtra(HomePodMusicLauncherActivity.EXTRA_QUICK_CONNECT, true).addFlags(',
        'QS tile launches personal quick flow')
    return source


def prepare(src: Path = SOURCE_DIR, download: bool = True) -> Path:
    if download and not src.exists():
        print(f'Fetching immutable release tag {SOURCE_REF} from {SOURCE_URL}...')
        subprocess.run(['git', 'clone', '--depth', '1', '--branch', SOURCE_REF,
                        '--single-branch', SOURCE_URL, str(src)], check=True)
    if not src.is_dir():
        raise FileNotFoundError(f'Source checkout missing: {src}')
    # Tag names can be moved upstream. Assert the exact inspected source commit.
    if (src / '.git').exists():
        commit = subprocess.check_output(['git', '-C', str(src), 'rev-parse', 'HEAD'], text=True).strip()
        if commit != SOURCE_COMMIT:
            raise RuntimeError(f'Unexpected centuryplay commit {commit}; expected {SOURCE_COMMIT}')
    targets = {
        'app/build.gradle.kts': lambda text: patch_instrumentation_gradle(patch_gradle(text)),
        'app/src/main/AndroidManifest.xml': patch_manifest,
        'app/src/main/java/com/airplay/streamer/service/AudioCaptureService.kt': patch_capture,
        'app/src/main/java/com/airplay/streamer/service/AirPlayTileService.kt': patch_tile,
        'app/src/main/res/values/strings.xml': patch_app_and_tile_strings,
        'app/src/main/java/com/airplay/streamer/service/StreamController.kt': patch_permissions,
        'app/src/main/java/com/airplay/streamer/router/MediaProjectionConsentActivity.kt': patch_consent,
    }
    output_files = {}
    for relative_path, patch in targets.items():
        file = src / relative_path
        if not file.is_file():
            raise FileNotFoundError(f'Missing upstream file {file}')
        # Generate all modified files before writing any, to avoid partial patching.
        output_files[file] = patch(file.read_text(encoding='utf-8'))
    if not (src / 'LICENSE').is_file():
        raise RuntimeError('Missing AGPL license: refusing to prepare checkout')
    launcher = src / 'app/src/main/java/com/airplay/streamer/HomePodMusicLauncherActivity.kt'
    icon = src / 'app/src/main/res/drawable/ic_homepod_music.xml'
    policy = src / 'app/src/main/java/com/airplay/streamer/QuickConnectPolicy.kt'
    testpath = src / 'app/src/test/java/com/airplay/streamer'
    androidtest = src / 'app/src/androidTest/java/com/airplay/streamer'
    if launcher.exists() or icon.exists() or policy.exists() or (testpath / 'PolicyJUnitTest.kt').exists() or (androidtest / 'LauncherSmokeTest.kt').exists():
        raise RuntimeError('Checkout is already modified; provide a clean v2.1.2 checkout')
    for file, content in output_files.items():
        file.write_text(content, encoding='utf-8')
    launcher.parent.mkdir(parents=True, exist_ok=True)
    icon.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(ROOT / 'overlay/HomePodMusicLauncherActivity.kt', launcher)
    shutil.copyfile(ROOT / 'overlay/ic_homepod_music.xml', icon)
    shutil.copyfile(ROOT / 'overlay/QuickConnectPolicy.kt', policy)
    testpath.mkdir(parents=True, exist_ok=True)
    androidtest.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(ROOT / 'overlay/PolicyJUnitTest.kt', testpath / 'PolicyJUnitTest.kt')
    shutil.copyfile(ROOT / 'tests/PolicyChecks.kt', testpath / 'PolicyChecks.kt')
    shutil.copyfile(ROOT / 'overlay/androidTest/LauncherSmokeTest.kt', androidtest / 'LauncherSmokeTest.kt')
    print(f'Source prepared in {src}. Compile and hardware-test before installing.')
    return src


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--no-clone', action='store_true', help='Use existing clean _centuryplay checkout')
    arguments = parser.parse_args()
    prepare(download=not arguments.no_clone)
