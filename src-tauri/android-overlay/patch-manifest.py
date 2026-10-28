import sys
p = sys.argv[1]; s = open(p).read()
if "MediaPlaybackService" not in s:
    s = s.replace('''    <uses-permission android:name="android.permission.INTERNET" />''', '''    <uses-permission android:name="android.permission.INTERNET" />
    <!-- Background playback (0.1.4): foreground media service, notification, CPU/Wi-Fi locks -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />''', 1)
    s = s.replace('''        <provider''', '''        <service
            android:name=".MediaPlaybackService"
            android:exported="false"
            android:foregroundServiceType="mediaPlayback" />

        <provider''', 1)
    assert s.count("MediaPlaybackService") == 1 and "WAKE_LOCK" in s
    open(p + ".tmp", "w").write(s)
    import os; os.replace(p + ".tmp", p)
if "REQUEST_INSTALL_PACKAGES" not in s:
    # In-app updates (0.1.9): the shell hands the downloaded APK to the system installer.
    s = s.replace('''    <uses-permission android:name="android.permission.INTERNET" />''', '''    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />''', 1)
    assert "REQUEST_INSTALL_PACKAGES" in s
    open(p + ".tmp", "w").write(s)
    import os; os.replace(p + ".tmp", p)
# Android Auto (bridge 7). Works both on a fresh Tauri manifest and on an already patched project.
# The descriptor is generated here because release-all copies the Kotlin overlay and invokes this script.
from pathlib import Path
import os
if "com.google.android.projection.gearhead" not in s:
    s = s.replace("    <application", '''    <queries>
        <package android:name="com.google.android.projection.gearhead" />
        <package android:name="com.google.android.googlequicksearchbox" />
        <package android:name="com.google.android.gms" />
    </queries>

    <application''', 1)
if "AutoBrowserService" not in s:
    s = s.replace('''        <provider''', '''        <service
            android:name=".AutoBrowserService"
            android:exported="true">
            <intent-filter>
                <action android:name="android.media.browse.MediaBrowserService" />
            </intent-filter>
        </service>
        <meta-data android:name="com.google.android.gms.car.application"
            android:resource="@xml/automotive_app_desc" />

        <provider''', 1)
    assert 'android:name=".AutoBrowserService"' in s
# Share sheet: Decibel appears when sharing a link (e.g. from YouTube); MainActivity opens it in the search.
if "android.intent.action.SEND" not in s:
    s = s.replace('''        </activity>''', '''            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="text/plain" />
            </intent-filter>
        </activity>''', 1)
    assert "android.intent.action.SEND" in s
Path(p + ".tmp").write_text(s)
os.replace(p + ".tmp", p)
resource = Path(p).parent / "res" / "xml" / "automotive_app_desc.xml"
resource.parent.mkdir(parents=True, exist_ok=True)
resource.write_text('''<?xml version="1.0" encoding="utf-8"?>
<automotiveApp>
    <uses name="media" />
</automotiveApp>
''')
print("manifest ok (Android Auto)")
