# Dexcom BG Announcer

Android app that polls Dexcom Share on a schedule, announces new glucose readings via text-to-speech, and briefly flashes a generated blood glucose image as Bluetooth cover art on connected head units (e.g. Rivian R1S).

**Informational use only. Not for treatment decisions.**

## Requirements

- Dexcom G7 with the official Dexcom app uploading to Dexcom Share
- At least one Share follower configured on the publisher account
- Android 8.0+ (API 26)

## Rivian / Bluetooth cover art setup

1. Enable **Developer options** on your phone
2. Set **Bluetooth AVRCP version** to **1.6**
3. Reboot the phone and re-pair with the vehicle if artwork does not appear
4. Grant **Notification access** to this app (Settings → Notification access) so it can detect the active music player

## App features

- Scheduled Dexcom Share polling (1–30 minutes, default 5)
- TTS announcements for new readings
- Brief Bluetooth cover-art flash with BG value + trend, then release back to the music app
- **Test Connection** (auth only)
- **Run Ad-Hoc Test** (fetch → announce → BT flash immediately, bypasses dedup)

## Build

```bash
./gradlew assembleDebug
./gradlew test
```

## Manual Rivian test checklist

1. Save valid Dexcom Share credentials and tap **Test Connection**
2. Pair phone to Rivian with AVRCP 1.6 enabled
3. Start YouTube Music (or another player) over Bluetooth
4. Tap **Run Ad-Hoc Test** while parked
5. Confirm TTS speaks the reading and Rivian briefly shows the BG image, then song art returns
6. Tap **Start Monitoring** and confirm a new CGM reading is announced once (not repeated)
