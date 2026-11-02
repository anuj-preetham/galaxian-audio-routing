# Nothing Phone (3a) Lite Dual Output Probe

An Android diagnostic app for investigating whether ordinary app playback can
send audio to the Nothing Phone (3a) Lite earpiece and bottom speaker at the
same time. It generates quiet 440 Hz and 880 Hz tones, lets you test either
output alone, and reports the route Android actually assigns to each
`AudioTrack`.

**Current result: simultaneous earpiece + bottom-speaker routing has not been
achieved.** On the tested stock phone, Android routed both tracks to the same
output. This app is a probe; it does not unlock stereo, modify the audio HAL,
change mixer controls, or write vendor settings.

## Test matrix and observed result

Tested on a Nothing Phone (3a) Lite (`A001T`, codename `Galaxian`, MediaTek
MT6878), using its stock production build. The phone exposed these built-in
outputs to the app:

| Test | Observed route |
| --- | --- |
| Earpiece only | Earpiece (type 1, id 2); independently usable |
| Bottom speaker only | Bottom speaker (type 2, id 3) |
| Both, earpiece track created/started first | Both tracks reported earpiece (type 1, id 2) |
| Both, speaker track created/started first | Both tracks reported bottom speaker (type 2, id 3) |

In both simultaneous tests, `setPreferredDevice()` returned `true` for each
track. That return value means the preference request was accepted; it does
not mean the requested route was honored. The observed routed devices were
the same for both tracks in each run, and the user reported hearing no bottom
speaker output in the first simultaneous run.

The order tests change track creation and playback order together, so they do
not isolate which ordering detail selects the final route. They do show that
neither tested order produced two distinct routes. These results establish the
behavior of this app on the tested stock build; they do not prove whether a
vendor-level change or a different build could enable concurrent output.

## What the app does

- Lists built-in output devices exposed by Android.
- Plays one quiet sine tone on the earpiece or bottom speaker at a time.
- Creates two media `AudioTrack`s and requests their preferred devices in
  either order.
- Polls `AudioTrack.getRoutedDevice()` while playback is active.
- Logs route observations under the `DualOutputProbe` tag.
- Stops and releases playback when the user taps **Stop tones** or closes the
  activity.

Use a low media volume. The generated tones are intentionally quiet, but
volume and audibility vary by device.

## Build

Requirements:

- Android Studio with Android SDK Platform 35
- JDK 11 or newer
- A network connection for the initial Gradle dependency download

Open this project directory in Android Studio, allow Gradle sync, then build
the `app` debug variant. From a terminal, with `ANDROID_HOME` set to your SDK:

```bash
./gradlew assembleDebug
```

The APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Install and run

Connect an Android device with USB debugging enabled, then run:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open **Dual Output Probe**, lower media volume, and run these tests separately:

1. **Test earpiece only**
2. **Test bottom speaker only**
3. **Test both (earpiece starts first)**
4. **Test both (speaker starts first)**

For simultaneous output, both tracks must report distinct actual routes, and
both tones should be audible. An accepted preference request alone is not a
successful result.

To collect app logs:

```bash
adb logcat -d -s DualOutputProbe:I
```

To uninstall:

```bash
adb uninstall org.codex.dualoutputprobe
```

## Scope and next research step

The probe uses public Android app APIs and does not require root. It can test
whether the stock app-facing audio policy honors two output preferences. It
cannot enable a missing route in the system audio policy or MediaTek HAL, and
it does not test system-wide stereo playback in other apps.

Further work would require inspecting device-specific audio policy, vendor
configuration, and HAL behavior, then validating any proposed change on the
device. The current evidence is not enough to claim that stereo can be
unlocked safely or that a custom ROM already supports it.

## License

MIT. See [LICENSE](LICENSE).
